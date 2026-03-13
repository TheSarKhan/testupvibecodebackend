package az.testup.mapper;

import az.testup.dto.request.SubscriptionPlanRequest;
import az.testup.dto.response.SubscriptionPlanResponse;
import az.testup.entity.SubscriptionPlan;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-13T19:33:01+0400",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.8 (JetBrains s.r.o.)"
)
@Component
public class SubscriptionPlanMapperImpl implements SubscriptionPlanMapper {

    @Override
    public SubscriptionPlan toEntity(SubscriptionPlanRequest request) {
        if ( request == null ) {
            return null;
        }

        SubscriptionPlan.SubscriptionPlanBuilder subscriptionPlan = SubscriptionPlan.builder();

        subscriptionPlan.name( request.getName() );
        subscriptionPlan.price( request.getPrice() );
        subscriptionPlan.description( request.getDescription() );
        subscriptionPlan.monthlyExamLimit( request.getMonthlyExamLimit() );
        subscriptionPlan.maxQuestionsPerExam( request.getMaxQuestionsPerExam() );
        subscriptionPlan.maxSavedExamsLimit( request.getMaxSavedExamsLimit() );
        subscriptionPlan.maxParticipantsPerExam( request.getMaxParticipantsPerExam() );
        subscriptionPlan.studentResultAnalysis( request.isStudentResultAnalysis() );
        subscriptionPlan.examEditing( request.isExamEditing() );
        subscriptionPlan.downloadPastExams( request.isDownloadPastExams() );
        subscriptionPlan.downloadAsPdf( request.isDownloadAsPdf() );
        subscriptionPlan.multipleSubjects( request.isMultipleSubjects() );
        subscriptionPlan.useTemplateExams( request.isUseTemplateExams() );
        subscriptionPlan.manualChecking( request.isManualChecking() );
        subscriptionPlan.selectExamDuration( request.isSelectExamDuration() );
        subscriptionPlan.useQuestionBank( request.isUseQuestionBank() );
        subscriptionPlan.createQuestionBank( request.isCreateQuestionBank() );
        subscriptionPlan.importQuestionsFromPdf( request.isImportQuestionsFromPdf() );

        return subscriptionPlan.build();
    }

    @Override
    public SubscriptionPlanResponse toResponse(SubscriptionPlan entity) {
        if ( entity == null ) {
            return null;
        }

        SubscriptionPlanResponse subscriptionPlanResponse = new SubscriptionPlanResponse();

        subscriptionPlanResponse.setId( entity.getId() );
        subscriptionPlanResponse.setName( entity.getName() );
        subscriptionPlanResponse.setPrice( entity.getPrice() );
        subscriptionPlanResponse.setDescription( entity.getDescription() );
        subscriptionPlanResponse.setMonthlyExamLimit( entity.getMonthlyExamLimit() );
        subscriptionPlanResponse.setMaxQuestionsPerExam( entity.getMaxQuestionsPerExam() );
        subscriptionPlanResponse.setMaxSavedExamsLimit( entity.getMaxSavedExamsLimit() );
        subscriptionPlanResponse.setMaxParticipantsPerExam( entity.getMaxParticipantsPerExam() );
        subscriptionPlanResponse.setStudentResultAnalysis( entity.isStudentResultAnalysis() );
        subscriptionPlanResponse.setExamEditing( entity.isExamEditing() );
        subscriptionPlanResponse.setAddImage( entity.isAddImage() );
        subscriptionPlanResponse.setAddPassageQuestion( entity.isAddPassageQuestion() );
        subscriptionPlanResponse.setDownloadPastExams( entity.isDownloadPastExams() );
        subscriptionPlanResponse.setDownloadAsPdf( entity.isDownloadAsPdf() );
        subscriptionPlanResponse.setMultipleSubjects( entity.isMultipleSubjects() );
        subscriptionPlanResponse.setUseTemplateExams( entity.isUseTemplateExams() );
        subscriptionPlanResponse.setManualChecking( entity.isManualChecking() );
        subscriptionPlanResponse.setSelectExamDuration( entity.isSelectExamDuration() );
        subscriptionPlanResponse.setUseQuestionBank( entity.isUseQuestionBank() );
        subscriptionPlanResponse.setCreateQuestionBank( entity.isCreateQuestionBank() );
        subscriptionPlanResponse.setImportQuestionsFromPdf( entity.isImportQuestionsFromPdf() );

        return subscriptionPlanResponse;
    }

    @Override
    public void updateEntityFromRequest(SubscriptionPlanRequest request, SubscriptionPlan entity) {
        if ( request == null ) {
            return;
        }

        entity.setName( request.getName() );
        entity.setPrice( request.getPrice() );
        entity.setDescription( request.getDescription() );
        entity.setMonthlyExamLimit( request.getMonthlyExamLimit() );
        entity.setMaxQuestionsPerExam( request.getMaxQuestionsPerExam() );
        entity.setMaxSavedExamsLimit( request.getMaxSavedExamsLimit() );
        entity.setMaxParticipantsPerExam( request.getMaxParticipantsPerExam() );
        entity.setStudentResultAnalysis( request.isStudentResultAnalysis() );
        entity.setExamEditing( request.isExamEditing() );
        entity.setAddImage( request.isAddImage() );
        entity.setAddPassageQuestion( request.isAddPassageQuestion() );
        entity.setDownloadPastExams( request.isDownloadPastExams() );
        entity.setDownloadAsPdf( request.isDownloadAsPdf() );
        entity.setMultipleSubjects( request.isMultipleSubjects() );
        entity.setUseTemplateExams( request.isUseTemplateExams() );
        entity.setManualChecking( request.isManualChecking() );
        entity.setSelectExamDuration( request.isSelectExamDuration() );
        entity.setUseQuestionBank( request.isUseQuestionBank() );
        entity.setCreateQuestionBank( request.isCreateQuestionBank() );
        entity.setImportQuestionsFromPdf( request.isImportQuestionsFromPdf() );
    }
}
