package az.testup.controller;

import az.testup.dto.request.BankQuestionRequest;
import az.testup.dto.request.GenerateExamRequest;
import az.testup.dto.request.GenerateQuestionsRequest;
import az.testup.entity.User;
import az.testup.service.GeminiService;
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

    /**
     * POST /api/ai/generate-questions
     * Generates questions via Gemini API and returns them as BankQuestionRequest list.
     * The caller can then save them individually via the existing /bank/questions endpoint.
     */
    @PostMapping("/generate-questions")
    public ResponseEntity<?> generateQuestions(
            @RequestBody GenerateQuestionsRequest req,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (req.getCount() < 1)  req.setCount(1);
        if (req.getCount() > 10) req.setCount(10);

        try {
            List<BankQuestionRequest> questions = geminiService.generateQuestions(req);
            return ResponseEntity.ok(questions);
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
            List<BankQuestionRequest> questions = geminiService.generateExam(req);
            return ResponseEntity.ok(questions);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "İmtahan generasiyası zamanı xəta: " + e.getMessage()));
        }
    }
}
