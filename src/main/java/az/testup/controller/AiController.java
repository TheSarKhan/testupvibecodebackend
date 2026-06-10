package az.testup.controller;

import az.testup.dto.request.BankQuestionRequest;
import az.testup.dto.request.GenerateExamRequest;
import az.testup.dto.request.GenerateQuestionsRequest;
import az.testup.entity.User;
import az.testup.enums.AuditAction;
import az.testup.exception.SubscriptionLimitExceededException;
import az.testup.repository.UserRepository;
import az.testup.service.AiAsyncExamService;
import az.testup.service.AuditLogService;
import az.testup.service.GeminiService;
import az.testup.service.SubscriptionValidatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final GeminiService geminiService;
    private final AiAsyncExamService aiAsyncExamService;
    private final SubscriptionValidatorService subscriptionValidatorService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    /**
     * GET /api/ai/usage
     * Returns AI question usage info for the current month: { limit, used, remaining }
     */
    @GetMapping("/usage")
    public ResponseEntity<?> getAiUsage(@AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getUser(userDetails);
            return ResponseEntity.ok(subscriptionValidatorService.getAiUsageInfo(user.getId()));
        } catch (SubscriptionLimitExceededException e) {
            return ResponseEntity.ok(Map.of("limit", 0, "used", 0, "remaining", 0));
        }
    }

    /**
     * POST /api/ai/generate-questions
     * Generates questions via Gemini API and returns them as BankQuestionRequest list.
     */
    @PostMapping("/generate-questions")
    public ResponseEntity<?> generateQuestions(
            @RequestBody GenerateQuestionsRequest req,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (req.getCount() < 1)  req.setCount(1);
        if (req.getCount() > 10) req.setCount(10);

        try {
            User user = getUser(userDetails);
            subscriptionValidatorService.validateAiQuestions(user.getId(), req.getCount());

            List<BankQuestionRequest> questions = geminiService.generateQuestions(req);

            subscriptionValidatorService.recordAiQuestions(user.getId(), questions.size());
            auditLogService.log(AuditAction.AI_QUESTIONS_GENERATED, user.getEmail(), user.getFullName(),
                    "AI", "Sual generasiyası",
                    "Say: " + questions.size() + ", Fənn: " + req.getSubjectName()
                            + (req.getTopicName() != null ? ", Mövzu: " + req.getTopicName() : ""));
            return ResponseEntity.ok(questions);
        } catch (SubscriptionLimitExceededException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Sual generasiyası zamanı xəta: " + e.getMessage()));
        }
    }

    /**
     * POST /api/ai/refine-question (BUG-22)
     * Polishes ONE already-generated question: REGENERATE | EASIER | HARDER |
     * REWORD. Counts as 1 AI question against the monthly limit. Errors flow
     * through the global handler so the teacher sees a readable 400/403/503
     * message instead of a generic 500.
     */
    @PostMapping("/refine-question")
    public ResponseEntity<?> refineQuestion(
            @RequestBody az.testup.dto.request.RefineQuestionRequest req,
            @AuthenticationPrincipal UserDetails userDetails) {
        User user = getUser(userDetails);
        subscriptionValidatorService.validateAiQuestions(user.getId(), 1);

        BankQuestionRequest refined = geminiService.refineQuestion(req);

        subscriptionValidatorService.recordAiQuestions(user.getId(), 1);
        auditLogService.log(AuditAction.AI_QUESTIONS_GENERATED, user.getEmail(), user.getFullName(),
                "AI", "Sual cilalama",
                "Əməliyyat: " + req.getAction() + ", Fənn: " + req.getSubjectName()
                        + (req.getTopicName() != null ? ", Mövzu: " + req.getTopicName() : ""));
        return ResponseEntity.ok(refined);
    }

    @PostMapping("/generate-exam")
    public ResponseEntity<?> generateExam(
            @RequestBody GenerateExamRequest req,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getUser(userDetails);
            subscriptionValidatorService.validateAiExamGeneration(user.getId());

            int totalQuestions = req.getTypeCounts() == null ? 5
                    : req.getTypeCounts().values().stream().mapToInt(Integer::intValue).sum();
            subscriptionValidatorService.validateAiQuestions(user.getId(), totalQuestions);

            List<BankQuestionRequest> questions = geminiService.generateExam(req);

            subscriptionValidatorService.recordAiQuestions(user.getId(), questions.size());
            auditLogService.log(AuditAction.AI_EXAM_GENERATED, user.getEmail(), user.getFullName(),
                    "AI", "İmtahan generasiyası",
                    "Sual sayı: " + questions.size() + ", Fənn: " + req.getSubjectName());
            return ResponseEntity.ok(questions);
        } catch (SubscriptionLimitExceededException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "İmtahan generasiyası zamanı xəta: " + e.getMessage()));
        }
    }

    /**
     * POST /api/ai/generate-exam-async
     * Validates limits synchronously, then generates the exam in a background
     * thread and saves it as a DRAFT. Returns 202 immediately so a large
     * (50–60+ question) exam never blocks the request past Cloudflare's proxy
     * timeout. When done, the teacher gets a notification linking to the draft.
     */
    @PostMapping("/generate-exam-async")
    public ResponseEntity<?> generateExamAsync(
            @RequestBody GenerateExamRequest req,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getUser(userDetails);

            // Validate up front so the teacher gets immediate feedback if they're
            // over a limit — the background job won't be able to surface a 403.
            subscriptionValidatorService.validateAiExamGeneration(user.getId());
            int totalQuestions = req.getTypeCounts() == null ? 5
                    : req.getTypeCounts().values().stream().mapToInt(Integer::intValue).sum();
            subscriptionValidatorService.validateAiQuestions(user.getId(), totalQuestions);
            subscriptionValidatorService.validateMonthlyExamCreation(user.getId());
            subscriptionValidatorService.validateTotalSavedExams(user.getId());

            // Fail fast on an inconsistent difficulty mix — the background job
            // can only surface a generic failure notification, not a 400.
            geminiService.normalizedDifficultyMix(req);

            aiAsyncExamService.generateExamInBackground(user.getId(), req, req.getTitle());

            return ResponseEntity.accepted().body(Map.of(
                    "message", "İmtahan arxa fonda yaradılır. Hazır olanda bildiriş alacaqsınız.",
                    "questionCount", totalQuestions));
        } catch (SubscriptionLimitExceededException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (az.testup.exception.BadRequestException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Generasiya başladıla bilmədi: " + e.getMessage()));
        }
    }

    private User getUser(UserDetails userDetails) {
        if (userDetails == null) throw new SubscriptionLimitExceededException("Bu xüsusiyyətdən istifadə üçün hesabınıza daxil olun.");
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new SubscriptionLimitExceededException("İstifadəçi tapılmadı"));
    }
}
