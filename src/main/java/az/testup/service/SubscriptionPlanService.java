package az.testup.service;

import az.testup.dto.request.SubscriptionPlanRequest;
import az.testup.dto.response.SubscriptionPlanResponse;
import az.testup.entity.SubscriptionPlan;
import az.testup.enums.AuditAction;
import az.testup.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubscriptionPlanService {

    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final AuditLogService auditLogService;

    /**
     * Public-facing list: only plans the admin has flagged as visible.
     * Used by the pricing page and any teacher-facing plan switcher.
     */
    public List<SubscriptionPlanResponse> getVisiblePlans() {
        return subscriptionPlanRepository.findAll()
                .stream()
                .filter(SubscriptionPlan::isVisible)
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Admin list: every plan, regardless of visibility, so admins can flip
     * hidden plans back on and still manually assign them to users.
     */
    public List<SubscriptionPlanResponse> getAllPlans() {
        return subscriptionPlanRepository.findAll()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public SubscriptionPlanResponse getPlanById(Long id) {
        SubscriptionPlan plan = subscriptionPlanRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Subscription plan not found: " + id));
        return toResponse(plan);
    }

    @Transactional
    public SubscriptionPlanResponse createPlan(SubscriptionPlanRequest request) {
        if (subscriptionPlanRepository.findByName(request.getName()).isPresent()) {
            throw new RuntimeException("Plan with name '" + request.getName() + "' already exists");
        }
        SubscriptionPlan plan = toEntity(request);
        SubscriptionPlan savedPlan = subscriptionPlanRepository.save(plan);
        auditLogService.logCurrent(AuditAction.PLAN_CREATED, "PLAN", savedPlan.getName(),
                "Qiymət: " + savedPlan.getPrice() + " AZN, Səviyyə: " + savedPlan.getLevel());
        return toResponse(savedPlan);
    }

    @Transactional
    public SubscriptionPlanResponse updatePlan(Long id, SubscriptionPlanRequest request) {
        SubscriptionPlan plan = subscriptionPlanRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Subscription plan not found: " + id));

        updateEntity(request, plan);
        SubscriptionPlan updatedPlan = subscriptionPlanRepository.save(plan);
        auditLogService.logCurrent(AuditAction.PLAN_UPDATED, "PLAN", updatedPlan.getName(),
                "Qiymət: " + updatedPlan.getPrice() + " AZN");
        return toResponse(updatedPlan);
    }

    @Transactional
    public void deletePlan(Long id) {
        SubscriptionPlan plan = subscriptionPlanRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Subscription plan not found: " + id));
        String name = plan.getName();
        subscriptionPlanRepository.deleteById(id);
        auditLogService.logCurrent(AuditAction.PLAN_DELETED, "PLAN", name, null);
    }

    // ── Manual mapping ──

    private SubscriptionPlanResponse toResponse(SubscriptionPlan e) {
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
        r.setDurationMonths(e.getDurationMonths() != null ? e.getDurationMonths() : 1);
        r.setVisible(e.isVisible());
        return r;
    }

    private SubscriptionPlan toEntity(SubscriptionPlanRequest req) {
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
                .durationMonths(req.getDurationMonths() != null ? req.getDurationMonths() : 1)
                .visible(req.isVisible())
                .build();
    }

    private void updateEntity(SubscriptionPlanRequest req, SubscriptionPlan e) {
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
        if (req.getDurationMonths() != null) e.setDurationMonths(req.getDurationMonths());
        e.setVisible(req.isVisible());
    }
}
