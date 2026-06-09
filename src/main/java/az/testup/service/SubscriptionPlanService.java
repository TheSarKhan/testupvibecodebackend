package az.testup.service;

import az.testup.dto.request.PlanPriceRequest;
import az.testup.dto.request.SubscriptionPlanRequest;
import az.testup.dto.response.PlanPriceResponse;
import az.testup.dto.response.SubscriptionPlanResponse;
import az.testup.entity.SubscriptionPlan;
import az.testup.entity.SubscriptionPlanPrice;
import az.testup.enums.AuditAction;
import az.testup.repository.SubscriptionPlanPriceRepository;
import az.testup.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubscriptionPlanService {

    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final SubscriptionPlanPriceRepository subscriptionPlanPriceRepository;
    private final AuditLogService auditLogService;

    /**
     * Public-facing list: only tiers the admin flagged visible, each carrying
     * only its visible price options. Used by the pricing page and any
     * teacher-facing plan switcher.
     */
    public List<SubscriptionPlanResponse> getVisiblePlans() {
        // Bulk-load visible prices once, group by tier, attach.
        Map<Long, List<SubscriptionPlanPrice>> byPlan = subscriptionPlanPriceRepository.findByVisibleTrue()
                .stream().filter(p -> p.getPlan() != null)
                .collect(Collectors.groupingBy(p -> p.getPlan().getId()));
        return subscriptionPlanRepository.findAll().stream()
                .filter(SubscriptionPlan::isVisible)
                .map(plan -> toResponse(plan, byPlan.getOrDefault(plan.getId(), List.of())))
                .collect(Collectors.toList());
    }

    /**
     * Admin list: every tier, regardless of visibility, each with ALL its price
     * options (hidden ones included) so admins can manage them.
     */
    public List<SubscriptionPlanResponse> getAllPlans() {
        Map<Long, List<SubscriptionPlanPrice>> byPlan = subscriptionPlanPriceRepository.findAll()
                .stream().filter(p -> p.getPlan() != null)
                .collect(Collectors.groupingBy(p -> p.getPlan().getId()));
        return subscriptionPlanRepository.findAll().stream()
                .map(plan -> toResponse(plan, byPlan.getOrDefault(plan.getId(), List.of())))
                .collect(Collectors.toList());
    }

    public SubscriptionPlanResponse getPlanById(Long id) {
        SubscriptionPlan plan = subscriptionPlanRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Subscription plan not found: " + id));
        return toResponse(plan, subscriptionPlanPriceRepository.findByPlanId(id));
    }

    @Transactional
    public SubscriptionPlanResponse createPlan(SubscriptionPlanRequest request) {
        if (subscriptionPlanRepository.findByName(request.getName()).isPresent()) {
            throw new RuntimeException("Plan with name '" + request.getName() + "' already exists");
        }
        SubscriptionPlan plan = toEntity(request);
        SubscriptionPlan savedPlan = subscriptionPlanRepository.save(plan);
        List<SubscriptionPlanPrice> prices = savePrices(savedPlan, request.getPrices());
        auditLogService.logCurrent(AuditAction.PLAN_CREATED, "PLAN", savedPlan.getName(),
                "Səviyyə: " + savedPlan.getLevel() + ", Qiymət variantları: " + prices.size());
        return toResponse(savedPlan, prices);
    }

    @Transactional
    public SubscriptionPlanResponse updatePlan(Long id, SubscriptionPlanRequest request) {
        SubscriptionPlan plan = subscriptionPlanRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Subscription plan not found: " + id));

        updateEntity(request, plan);
        SubscriptionPlan updatedPlan = subscriptionPlanRepository.save(plan);

        // Replace-all prices: only when the request actually carries a price
        // list (null = "leave prices untouched", e.g. a tier-only edit).
        List<SubscriptionPlanPrice> prices;
        if (request.getPrices() != null) {
            subscriptionPlanPriceRepository.deleteByPlanId(id);
            subscriptionPlanPriceRepository.flush();
            prices = savePrices(updatedPlan, request.getPrices());
        } else {
            prices = subscriptionPlanPriceRepository.findByPlanId(id);
        }

        auditLogService.logCurrent(AuditAction.PLAN_UPDATED, "PLAN", updatedPlan.getName(),
                "Səviyyə: " + updatedPlan.getLevel());
        return toResponse(updatedPlan, prices);
    }

    @Transactional
    public void deletePlan(Long id) {
        SubscriptionPlan plan = subscriptionPlanRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Subscription plan not found: " + id));
        String name = plan.getName();
        subscriptionPlanPriceRepository.deleteByPlanId(id); // FK ON DELETE CASCADE is the backstop
        subscriptionPlanRepository.deleteById(id);
        auditLogService.logCurrent(AuditAction.PLAN_DELETED, "PLAN", name, null);
    }

    // ── Helpers ──

    private List<SubscriptionPlanPrice> savePrices(SubscriptionPlan plan, List<PlanPriceRequest> priceRequests) {
        if (priceRequests == null || priceRequests.isEmpty()) return List.of();
        List<SubscriptionPlanPrice> saved = new ArrayList<>();
        for (PlanPriceRequest pr : priceRequests) {
            saved.add(subscriptionPlanPriceRepository.save(SubscriptionPlanPrice.builder()
                    .plan(plan)
                    .durationMonths(pr.getDurationMonths())
                    .price(pr.getPrice())
                    .visible(pr.isVisible())
                    .build()));
        }
        return saved;
    }

    // ── Manual mapping ──

    private SubscriptionPlanResponse toResponse(SubscriptionPlan e, List<SubscriptionPlanPrice> prices) {
        SubscriptionPlanResponse r = new SubscriptionPlanResponse();
        r.setId(e.getId());
        r.setName(e.getName());
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
        r.setVisible(e.isVisible());

        List<PlanPriceResponse> priceResponses = prices.stream()
                .sorted(Comparator.comparing(SubscriptionPlanPrice::getDurationMonths))
                .map(this::toPriceResponse)
                .collect(Collectors.toList());
        r.setPrices(priceResponses);

        // Deprecated frontend-compat shim: flat 1-month price + durationMonths=1.
        prices.stream()
                .filter(p -> Integer.valueOf(1).equals(p.getDurationMonths()))
                .findFirst()
                .ifPresentOrElse(
                        p -> r.setPrice(p.getPrice()),
                        () -> r.setPrice(prices.stream().findFirst()
                                .map(SubscriptionPlanPrice::getPrice).orElse(0.0)));
        r.setDurationMonths(1);
        return r;
    }

    private PlanPriceResponse toPriceResponse(SubscriptionPlanPrice p) {
        PlanPriceResponse pr = new PlanPriceResponse();
        pr.setId(p.getId());
        pr.setDurationMonths(p.getDurationMonths());
        pr.setPrice(p.getPrice());
        pr.setVisible(p.isVisible());
        return pr;
    }

    private SubscriptionPlan toEntity(SubscriptionPlanRequest req) {
        return SubscriptionPlan.builder()
                .name(req.getName())
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
                .visible(req.isVisible())
                .build();
    }

    private void updateEntity(SubscriptionPlanRequest req, SubscriptionPlan e) {
        e.setName(req.getName());
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
        e.setVisible(req.isVisible());
    }
}
