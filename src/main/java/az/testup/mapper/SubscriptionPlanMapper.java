package az.testup.mapper;

import az.testup.dto.request.SubscriptionPlanRequest;
import az.testup.dto.response.SubscriptionPlanResponse;
import az.testup.entity.SubscriptionPlan;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionPlanMapper {

    public SubscriptionPlanResponse toResponse(SubscriptionPlan e) {
        if (e == null) return null;
        SubscriptionPlanResponse r = new SubscriptionPlanResponse();
        r.setId(e.getId());
        r.setName(e.getName());
        r.setPrice(e.getPrice());
        r.setLevel(e.getLevel());
        r.setDescription(e.getDescription());
        r.setMonthlyExamLimit(e.getMonthlyExamLimit());
        r.setMaxQuestionsPerExam(e.getMaxQuestionsPerExam());
        r.setMaxSavedExamsLimit(e.getMaxSavedExamsLimit());
        r.setMaxParticipantsPerExam(e.getMaxParticipantsPerExam());
        r.setStudentResultAnalysis(e.isStudentResultAnalysis());
        r.setExamEditing(e.isExamEditing());
        r.setAddImage(e.isAddImage());
        r.setAddPassageQuestion(e.isAddPassageQuestion());
        r.setDownloadPastExams(e.isDownloadPastExams());
        r.setDownloadAsPdf(e.isDownloadAsPdf());
        r.setMultipleSubjects(e.isMultipleSubjects());
        r.setUseTemplateExams(e.isUseTemplateExams());
        r.setManualChecking(e.isManualChecking());
        r.setSelectExamDuration(e.isSelectExamDuration());
        r.setUseQuestionBank(e.isUseQuestionBank());
        r.setCreateQuestionBank(e.isCreateQuestionBank());
        r.setImportQuestionsFromPdf(e.isImportQuestionsFromPdf());
        r.setMonthlyAiQuestionLimit(e.getMonthlyAiQuestionLimit());
        r.setUseAiExamGeneration(e.isUseAiExamGeneration());
        return r;
    }

    public SubscriptionPlan toEntity(SubscriptionPlanRequest req) {
        if (req == null) return null;
        return SubscriptionPlan.builder()
                .name(req.getName())
                .price(req.getPrice())
                .level(req.getLevel())
                .description(req.getDescription())
                .monthlyExamLimit(req.getMonthlyExamLimit())
                .maxQuestionsPerExam(req.getMaxQuestionsPerExam())
                .maxSavedExamsLimit(req.getMaxSavedExamsLimit())
                .maxParticipantsPerExam(req.getMaxParticipantsPerExam())
                .studentResultAnalysis(req.isStudentResultAnalysis())
                .examEditing(req.isExamEditing())
                .addImage(req.isAddImage())
                .addPassageQuestion(req.isAddPassageQuestion())
                .downloadPastExams(req.isDownloadPastExams())
                .downloadAsPdf(req.isDownloadAsPdf())
                .multipleSubjects(req.isMultipleSubjects())
                .useTemplateExams(req.isUseTemplateExams())
                .manualChecking(req.isManualChecking())
                .selectExamDuration(req.isSelectExamDuration())
                .useQuestionBank(req.isUseQuestionBank())
                .createQuestionBank(req.isCreateQuestionBank())
                .importQuestionsFromPdf(req.isImportQuestionsFromPdf())
                .monthlyAiQuestionLimit(req.getMonthlyAiQuestionLimit())
                .useAiExamGeneration(req.isUseAiExamGeneration())
                .build();
    }

    public void updateEntityFromRequest(SubscriptionPlanRequest req, SubscriptionPlan e) {
        if (req == null) return;
        e.setName(req.getName());
        e.setPrice(req.getPrice());
        e.setLevel(req.getLevel());
        e.setDescription(req.getDescription());
        e.setMonthlyExamLimit(req.getMonthlyExamLimit());
        e.setMaxQuestionsPerExam(req.getMaxQuestionsPerExam());
        e.setMaxSavedExamsLimit(req.getMaxSavedExamsLimit());
        e.setMaxParticipantsPerExam(req.getMaxParticipantsPerExam());
        e.setStudentResultAnalysis(req.isStudentResultAnalysis());
        e.setExamEditing(req.isExamEditing());
        e.setAddImage(req.isAddImage());
        e.setAddPassageQuestion(req.isAddPassageQuestion());
        e.setDownloadPastExams(req.isDownloadPastExams());
        e.setDownloadAsPdf(req.isDownloadAsPdf());
        e.setMultipleSubjects(req.isMultipleSubjects());
        e.setUseTemplateExams(req.isUseTemplateExams());
        e.setManualChecking(req.isManualChecking());
        e.setSelectExamDuration(req.isSelectExamDuration());
        e.setUseQuestionBank(req.isUseQuestionBank());
        e.setCreateQuestionBank(req.isCreateQuestionBank());
        e.setImportQuestionsFromPdf(req.isImportQuestionsFromPdf());
        e.setMonthlyAiQuestionLimit(req.getMonthlyAiQuestionLimit());
        e.setUseAiExamGeneration(req.isUseAiExamGeneration());
    }
}
