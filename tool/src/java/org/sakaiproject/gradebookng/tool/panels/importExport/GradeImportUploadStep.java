package org.sakaiproject.gradebookng.tool.panels.importExport;

import au.com.bytecode.opencsv.CSVWriter;
import org.apache.commons.lang.ArrayUtils;
import org.apache.log4j.Logger;
import org.apache.wicket.Component;
import org.apache.wicket.markup.html.form.Button;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.upload.FileUpload;
import org.apache.wicket.markup.html.form.upload.FileUploadField;
import org.apache.wicket.markup.html.link.DownloadLink;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.apache.wicket.util.lang.Bytes;
import org.apache.wicket.util.time.Duration;
import org.sakaiproject.gradebookng.business.GradebookNgBusinessService;
import org.sakaiproject.gradebookng.business.helpers.ImportGradesHelper;
import org.sakaiproject.gradebookng.business.model.ImportedGradeWrapper;
import org.sakaiproject.gradebookng.business.model.ProcessedGradeItem;
import org.sakaiproject.gradebookng.tool.model.GradeInfo;
import org.sakaiproject.gradebookng.tool.model.ImportWizardModel;
import org.sakaiproject.gradebookng.tool.model.StudentGradeInfo;
import org.sakaiproject.gradebookng.tool.pages.GradebookPage;
import org.sakaiproject.service.gradebook.shared.Assignment;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by chmaurer on 1/22/15.
 */
public class GradeImportUploadStep extends Panel {

    private static final Logger log = Logger.getLogger(GradeImportUploadStep.class);

    //list of mimetypes for each category. Must be compatible with the parser
    private static final String[] XLS_MIME_TYPES={"application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"};
    private static final String[] CSV_MIME_TYPES={"text/csv"};

    private String panelId;
    private List<Assignment> assignments;
    private List<StudentGradeInfo> grades;

    @SpringBean(name="org.sakaiproject.gradebookng.business.GradebookNgBusinessService")
    protected GradebookNgBusinessService businessService;

    public GradeImportUploadStep(String id) {
        super(id);
        this.panelId = id;
    }

    @Override
    public void onInitialize() {
        super.onInitialize();

        //get list of assignments. this allows us to build the columns and then fetch the grades for each student for each assignment from the map
        assignments = businessService.getGradebookAssignments();

        //get the grade matrix
        grades = businessService.buildGradeMatrix(assignments);

        add(new DownloadLink("downloadBlankTemplate", new LoadableDetachableModel<File>() {

            @Override
            protected File load() {
                return buildFile(false);
            }
        }).setCacheDuration(Duration.NONE).setDeleteAfterDownload(true));

        add(new DownloadLink("downloadFullGradebook", new LoadableDetachableModel<File>() {

            @Override
            protected File load() {
                return buildFile(true);

            }
        }).setCacheDuration(Duration.NONE).setDeleteAfterDownload(true));


        add(new UploadForm("form"));
    }

    private File buildFile(boolean includeGrades) {
        File tempFile;
        try {
            //TODO - add the site name to the file?
            tempFile = File.createTempFile("gradebookTemplate", ".csv");
            FileWriter fw = new FileWriter(tempFile);
            CSVWriter csvWriter = new CSVWriter(fw);

            //Create csv header
            List<String> header = new ArrayList<String>();
            header.add("Student ID");
            header.add("Student Name");

            for (Assignment assignment : assignments) {
                header.add(assignment.getName() + " [" + assignment.getPoints() + "]");
                header.add("*/ " + assignment.getName() + " Comments */");
            }

            csvWriter.writeNext(header.toArray(new String[]{}));

            for (StudentGradeInfo studentGradeInfo : grades) {
                List<String> line = new ArrayList<String>();
                line.add(studentGradeInfo.getStudentEid());
                line.add(studentGradeInfo.getStudentDisplayName());
                if (includeGrades) {
                    for (Assignment assignment : assignments) {
                        GradeInfo gradeInfo = studentGradeInfo.getGrades().get(assignment.getId());
                        if (gradeInfo != null) {
                            line.add(gradeInfo.getGrade());
                            line.add(gradeInfo.getGradeComment());
                        } else {
                            // Need to account for no grades
                            line.add(null);
                            line.add(null);
                        }
                    }
                }
                csvWriter.writeNext(line.toArray(new String[]{}));
            }

            csvWriter.close();
            fw.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return tempFile;

    }

    /*
     * Upload form
     */
    private class UploadForm extends Form<Void> {

        FileUploadField fileUploadField;

        public UploadForm(String id) {
            super(id);

            setMultiPart(true);
            setMaxSize(Bytes.megabytes(2));

            fileUploadField = new FileUploadField("upload");
            add(fileUploadField);

            add(new Button("continuebutton"));

            Button cancel = new Button("cancelbutton"){
                public void onSubmit() {
//                    info("Cancel was pressed!");
                    setResponsePage(new GradebookPage());
                }
            };
            cancel.setDefaultFormProcessing(false);
            add(cancel);
        }

        @Override
        public void onSubmit() {

            FileUpload upload = fileUploadField.getFileUpload();
            if (upload != null) {

                try {
                    log.debug("file upload success");
                    //turn file into list
                    ImportedGradeWrapper importedGradeWrapper = parseImportedGradeFile(upload.getInputStream(), upload.getContentType());

                    List<ProcessedGradeItem> processedGradeItems = ImportGradesHelper.processImportedGrades(importedGradeWrapper, assignments, grades);

                    //if null, the file was of the incorrect type
                    //if empty there are no users
                    if(processedGradeItems == null || processedGradeItems.isEmpty()) {
                        error(getString("error.parse.upload"));
                    } else {
                        //GO TO NEXT PAGE
                        log.debug(processedGradeItems.size());

						//repaint panel
                        ImportWizardModel importWizardModel = new ImportWizardModel();
                        importWizardModel.setProcessedGradeItems(processedGradeItems);
						Component newPanel = new GradeItemImportSelectionStep(panelId, Model.of(importWizardModel));
						newPanel.setOutputMarkupId(true);
						GradeImportUploadStep.this.replaceWith(newPanel);

                    }

                } catch (IOException e) {
                    e.printStackTrace();
                }

            }

        }
    }


    public ImportedGradeWrapper parseImportedGradeFile(InputStream is, String mimetype){

        //determine file type and delegate
        if(ArrayUtils.contains(CSV_MIME_TYPES, mimetype)) {
            return ImportGradesHelper.parseCsv(is);
        } else if (ArrayUtils.contains(XLS_MIME_TYPES, mimetype)) {
            return ImportGradesHelper.parseXls(is);
        } else {
            log.error("Invalid file type for grade import: " + mimetype);
        }
        return null;
    }
}
