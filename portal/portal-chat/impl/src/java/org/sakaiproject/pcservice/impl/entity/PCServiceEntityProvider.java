package org.sakaiproject.pcservice.impl.entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.email.api.EmailService;
import org.sakaiproject.entitybroker.EntityReference;
import org.sakaiproject.entitybroker.EntityView;
import org.sakaiproject.entitybroker.entityprovider.EntityProvider;
import org.sakaiproject.entitybroker.entityprovider.annotations.EntityCustomAction;
import org.sakaiproject.entitybroker.entityprovider.capabilities.ActionsExecutable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.AutoRegisterEntityProvider;
import org.sakaiproject.entitybroker.entityprovider.capabilities.Createable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.Inputable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.Outputable;
import org.sakaiproject.entitybroker.entityprovider.extension.Formats;
import org.sakaiproject.entitybroker.exception.EntityException;
import org.sakaiproject.presence.api.PresenceService;
import org.sakaiproject.profile2.model.Person;
import org.sakaiproject.profile2.service.ProfileService;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;

import org.jgroups.Address;
import org.jgroups.Channel;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ReceiverAdapter;

/**
 * Provides all the RESTful targets for the portal chat code in chat.js. Clustering
 * is catered for using a JGroups channel.
 *
 * @author Adrian Fish (a.fish@lancaster.ac.uk)
 */
public class PCServiceEntityProvider extends ReceiverAdapter implements EntityProvider, Createable, Inputable, Outputable, ActionsExecutable, AutoRegisterEntityProvider {

	protected final Logger logger = Logger.getLogger(getClass());

	public final static String ENTITY_PREFIX = "portal-chat";

    /* JGROUPS MESSAGE PREFIXES */

    /* Heartbeat messages start with this */
    private final String HEARTBEAT_PREAMBLE = "heartbeat:";
    /* Message messages start with this */
    private final String MESSAGE_PREAMBLE = "message:";
    /* Clear messages start with this */
    private final String CLEAR_PREAMBLE = "clear:";
	
	private UserDirectoryService userDirectoryService;
	public void setUserDirectoryService(UserDirectoryService userDirectoryService) {
		this.userDirectoryService = userDirectoryService;
	}
	
	private EmailService emailService;
	public void setEmailService(EmailService emailService) {
		this.emailService = emailService;
	}

	private PresenceService presenceService;
	public void setPresenceService(PresenceService presenceService) {
		this.presenceService = presenceService;
	}
	
	private ServerConfigurationService serverConfigurationService;
	public void setServerConfigurationService(ServerConfigurationService serverConfigurationService) {
		this.serverConfigurationService = serverConfigurationService;
	}
	
	private ProfileService profileService;
	public void setProfileService(ProfileService profileService) {
		this.profileService = profileService;
	}

    /* A mapping of a list of messages onto the user id they are intended for */
	private Map<String, List<UserMessage>> messageMap = new HashMap<String,List<UserMessage>>();
	
    /* A mapping of timestamps onto the user id that sent the heartbeat */
	private Map<String,Date> heartbeatMap = new ConcurrentHashMap<String,Date>(500,0.75F,32);

    /* JGroups channel for keeping the above maps in sync across nodes in a Sakai cluster */
    private Channel clusterChannel = null;

    public void init() {
        try {
            clusterChannel = new JChannel();
            clusterChannel.setReceiver(this);
            clusterChannel.connect(serverConfigurationService.getString("portalchat.cluster.channel", "SAKAI-PORTALCHAT-CLUSTER-CHANNEL"));
            // We don't want a copy of JGroups messages sent
            clusterChannel.setOpt(Channel.LOCAL,false);
        } catch (Exception e) {
            logger.error("Error creating JGroups channel. Chat messages will now NOT BE KEPT IN SYNC", e);
        }
    }
	
	public String getEntityPrefix() {
		return ENTITY_PREFIX;
	}
	
	public String[] getHandledOutputFormats() {
	    return new String[] { Formats.TXT ,Formats.JSON};
	}

	public Object getSampleEntity() {
		return new UserMessage();
	}

    /**
     * New messages come in here. The recipient is indicated by the parameter 'to'.
     */
	public String createEntity(EntityReference ref, Object entity, Map<String, Object> params) {
		User currentUser = userDirectoryService.getCurrentUser();
		User anon = userDirectoryService.getAnonymousUser();
		
		if(anon.equals(currentUser)) {
			throw new SecurityException("You must be logged in to use this service");
		}
		
		String to = (String) params.get("to");
		if(to == null) throw new IllegalArgumentException("You must supply a recipient");
		
		if(to.equals(currentUser.getId())) {
			throw new IllegalArgumentException("You can't chat with yourself");
		}
		
		Date now = new Date();
		Date lastHeartbeat = null;
		
		lastHeartbeat = heartbeatMap.get(to);
		
		if(lastHeartbeat == null) return "OFFLINE";
			
		if((now.getTime() - lastHeartbeat.getTime()) >= 5000L)
			return "OFFLINE";
		
		String message = (String) params.get("message");
		if(message == null) throw new IllegalArgumentException("You must supply a message");

        addMessageToMap(new UserMessage(currentUser.getId(), to, message));
			
        try {
            Message msg = new Message(null, null, MESSAGE_PREAMBLE + currentUser.getId() + ":" + to + ":" + message);
            clusterChannel.send(msg);
        } catch (Exception e) {
            logger.error("Error sending JGroups message", e);
        }
		
		return "success";
	}

	public String[] getHandledInputFormats() {
	    return new String[] { Formats.HTML };
	}
	
	public class UserMessage {
		
		public String from;
        public String to;
		public String content;
		public long timestamp;
		
		private UserMessage() {
		}

        private UserMessage(String from, String to, String content) {
            this.to = to;
			this.from = from;
			this.content = content;
			this.timestamp = (new Date()).getTime();
		}
	}

    /**
     * The JS client calls this to grab the latest data in one call. Connections, latest messages, online users
     * and present users (in a site) are all returned in one lump of JSON. If the online parameter is supplied and
     * true, a heartbeat is stamped for the sender as well.
     */
	@EntityCustomAction(action = "latestData", viewKey = EntityView.VIEW_SHOW)
	public Map<String,Object> handleLatestData(EntityReference ref, Map<String,Object> params) {
		
		User currentUser = userDirectoryService.getCurrentUser();
		User anon = userDirectoryService.getAnonymousUser();
		
		if(anon.equals(currentUser))
			throw new SecurityException("You must be logged in to use this service");
		
		String online = (String) params.get("online");
		
		if("true".equals(online)) {
			heartbeatMap.put(currentUser.getId(),new Date());
            Message msg = new Message(null, null, HEARTBEAT_PREAMBLE + currentUser.getId());
            try {
                clusterChannel.send(msg);
            } catch (Exception e) {
                logger.error("Error sending JGroups heartbeat message", e);
            }
		}
		else {
			synchronized(messageMap) {
				messageMap.remove(currentUser.getId());
			}

            sendClearMessage(currentUser.getId());
			
			return new HashMap<String,Object>(0);
		}

        List<User> presentUsers = new ArrayList<User>();

		String siteId = (String) params.get("siteId");

        if(siteId != null && siteId.length() > 0) {
            // A site id has been specified, so we add the present users from the presence service
            presentUsers = presenceService.getPresentUsers(siteId + "-presence");
            presentUsers.remove(currentUser);
        }
		
		List<Person> connections =  profileService.getConnectionsForUser(currentUser.getId());
		
		List<String> onlineConnections = new ArrayList<String>(connections.size());
		
		Date now = new Date();
		
		for(Person person : connections) {
			
			Date lastHeartbeat = null;
			
			lastHeartbeat = heartbeatMap.get(person.getUuid());
			
			if(lastHeartbeat == null) continue;
			
			if((now.getTime() - lastHeartbeat.getTime()) < 5000L) {
				onlineConnections.add(person.getUuid());
			}
		}
		
		List<UserMessage> messages = new ArrayList<UserMessage>();
		
		String currentUserId = currentUser.getId();
		
		synchronized(messageMap) {
			if(messageMap.containsKey(currentUserId)) {
				messages = messageMap.get(currentUserId);
				messageMap.remove(currentUserId);
			}
            sendClearMessage(currentUserId);
		}

		
		Map<String,Object> data = new HashMap<String,Object>(4);
		
		data.put("connections", connections);
		data.put("messages", messages);
		data.put("online", onlineConnections);
		data.put("presentUsers", presentUsers);
		
		return data;
	}

    private void sendClearMessage(String userId) {
        try {
            Message msg = new Message(null, null, CLEAR_PREAMBLE + userId);
            clusterChannel.send(msg);
        } catch (Exception e) {
            logger.error("Error sending JGroups clear message", e);
        }
    }
	
	@EntityCustomAction(action = "ping", viewKey = EntityView.VIEW_SHOW)
	public String handlePing(EntityReference ref)
	{
		User currentUser = userDirectoryService.getCurrentUser();
		User anon = userDirectoryService.getAnonymousUser();
		
		if(anon.equals(currentUser)) {
			throw new SecurityException("You must be logged in to use this service");
		}
		
		String userId = ref.getId();
		
		try {
			String email = userDirectoryService.getUser(userId).getEmail();
			String portalUrl = serverConfigurationService.getServerUrl() + "/portal";
			new EmailSender(email,"[Sakai Chat] Chat Invitation",currentUser.getDisplayName() + " wants you to come and chat on <a href=\"" + portalUrl + "\">Sakai!</a>");
		}
		catch(Exception e) {
			throw new EntityException("Failed to send email",userId);
		}
		
		return "success";
	}

    /**
     * JGroups message listener. Overrides ReceiverAdapter.
     */
    public void receive(Message msg) {
        Object o = msg.getObject();
        if (o instanceof String) {
            String message = (String) o;
            if (message.startsWith(HEARTBEAT_PREAMBLE)) {
                String onlineUserId = message.substring(HEARTBEAT_PREAMBLE.length());
                heartbeatMap.put(onlineUserId, new Date());
            } else if (message.startsWith(MESSAGE_PREAMBLE)) {
                Address address = clusterChannel.getAddress();
                String[] parts = message.split(":");
                String from = parts[1];
                String to = parts[2];
                String m = parts[3];
                addMessageToMap(new UserMessage(from, to, m));
            } else if (message.startsWith(CLEAR_PREAMBLE)) {
                String userId = message.substring(CLEAR_PREAMBLE.length());
                synchronized (messageMap) {
                    messageMap.remove(userId);
                }
            }
        }
    }

    /**
     * Implements a threadsafe addition to the message map
     */
    private void addMessageToMap(UserMessage m) {
        synchronized (messageMap) {
            List<UserMessage> current = messageMap.get(m.to);

            if (current != null) {
                List<UserMessage> copy = new ArrayList<UserMessage>(current.size());
                copy.addAll(current);
                copy.add(m);
                messageMap.put(m.to, copy);
            } else {
                messageMap.put(m.to, Arrays.asList(m));
            }
        }   
    }

	private class EmailSender implements Runnable {
		private Thread runner;

		private String email;

		private String subject;

		private String message;

		public final String HTML_END = "\n  </body>\n</html>\n";

		public EmailSender(String email, String subject, String message)
		{
			this.email = email;
			this.subject = subject;
			this.message = message;
			runner = new Thread(this, "PC EmailSender thread");
			runner.start();
		}

		// do it!
		public synchronized void run()
		{
			try
			{
				final List<String> additionalHeaders = new ArrayList<String>();
				additionalHeaders.add("Content-Type: text/html; charset=ISO-8859-1");

				// do it
				final String emailFromAddress = "\""+serverConfigurationService.getString("ui.service") + "\" <no-reply@" + serverConfigurationService.getServerName()+">";
				emailService.send(emailFromAddress, email, subject, formatMessage(subject, message), email, null, additionalHeaders);
			}
			catch (Exception e)
			{
				logger.error("sendEmail() failed for emailuserId: " + email + " : " + e.getClass() + " : " + e.getMessage());
			}
		}

		/** helper methods for formatting the message */
		private String formatMessage(String subject, String message)
		{
			StringBuilder sb = new StringBuilder();
			sb.append(htmlPreamble(subject));
			sb.append(message);
			sb.append(HTML_END);
			return sb.toString();
		}

		private String htmlPreamble(String subject)
		{
			StringBuilder sb = new StringBuilder();
			sb.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\"\n");
			sb.append("\"http://www.w3.org/TR/html4/loose.dtd\">\n");
			sb.append("<html>\n");
			sb.append("<head><title>");
			sb.append(subject);
			sb.append("</title></head>\n");
			sb.append("<body>\n");

			return sb.toString();
		}
	}
}
