package az.testup.controller;

import az.testup.dto.request.BankQuestionRequest;
import az.testup.dto.request.GenerateExamRequest;
import az.testup.dto.request.GenerateQuestionsRequest;
import az.testup.entity.User;
import az.testup.exception.SubscriptionLimitExceededException;
import az.testup.repository.UserRepository;
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
    private final SubscriptionValidatorService subscriptionValidatorService;
    private final UserRepository userRepository;

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
            return ResponseEntity.ok(questions);
        } catch (SubscriptionLimitExceededException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Sual generasiyası zamanı xəta: " + e.getMessage()));
        }
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
            return ResponseEntity.ok(questions);
        } catch (SubscriptionLimitExceededException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "İmtahan generasiyası zamanı xəta: " + e.getMessage()));
        }
    }

    private User getUser(UserDetails userDetails) {
        if (userDetails == null) throw new SubscriptionLimitExceededException("Bu xüsusiyyətdən istifadə üçün hesabınıza daxil olun.");
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new SubscriptionLimitExceededException("İstifadəçi tapılmadı"));
    }
}
