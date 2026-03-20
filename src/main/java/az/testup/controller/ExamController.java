package az.testup.controller;

import az.testup.dto.request.ExamRequest;
import az.testup.dto.response.ExamResponse;
import az.testup.entity.Exam;
import az.testup.entity.User;
import az.testup.exception.UnauthorizedException;
import az.testup.repository.UserRepository;
import az.testup.service.ExamService;
import az.testup.service.PdfService;
import az.testup.service.SubscriptionValidatorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/exams")
@RequiredArgsConstructor
public class ExamController {

    private final ExamService examService;
    private final UserRepository userRepository;
    private final PdfService pdfService;
    private final SubscriptionValidatorService subscriptionValidatorService;


    @PostMapping
    public ResponseEntity<ExamResponse> createExam(
            @Valid @RequestBody ExamRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        return ResponseEntity.ok(examService.createExam(request, teacher));
    }

    @GetMapping
    public ResponseEntity<List<ExamResponse>> getMyExams(@AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        return ResponseEntity.ok(examService.getTeacherExams(teacher));
    }

    @GetMapping("/public")
    public ResponseEntity<List<ExamResponse>> getPublicExams() {
        return ResponseEntity.ok(examService.getPublicExams());
    }

    @GetMapping("/{id}/details")
    public ResponseEntity<ExamResponse> getExamById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        return ResponseEntity.ok(examService.getExamById(id, teacher));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ExamResponse> updateExam(
            @PathVariable Long id,
            @Valid @RequestBody ExamRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        return ResponseEntity.ok(examService.updateExam(id, request, teacher));
    }

    @GetMapping("/{shareLink}")
    public ResponseEntity<ExamResponse> getExamByShareLink(@PathVariable String shareLink) {
        return ResponseEntity.ok(examService.getExamByShareLink(shareLink));
    }

    @PostMapping("/{id}/generate-code")
    public ResponseEntity<Map<String, Object>> generateAccessCode(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        return ResponseEntity.ok(examService.generateAccessCode(id, teacher));
    }

    @PatchMapping("/{id}/toggle-status")
    public ResponseEntity<ExamResponse> toggleStatus(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        return ResponseEntity.ok(examService.toggleStatus(id, teacher));
    }

    @PostMapping("/{shareLink}/purchase")
    public ResponseEntity<Map<String, Object>> purchaseExam(
            @PathVariable String shareLink,
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getCurrentUser(userDetails);
        examService.purchaseExam(shareLink, student);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<ExamResponse> cloneExam(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        return ResponseEntity.ok(examService.cloneExam(id, teacher));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteExam(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        examService.deleteExam(id, teacher);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> downloadExamPdf(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) throws java.io.IOException {
        User user = getCurrentUser(userDetails);
        
        // Admins can download any exam, teachers only their own + permission check
        Exam exam = examService.getExamEntityById(id);
        if (!user.getRole().name().equals("ADMIN")) {
            if (!exam.getTeacher().getId().equals(user.getId())) {
                throw new UnauthorizedException("Bu imtahanı yükləmək icazəniz yoxdur");
            }
            subscriptionValidatorService.validateDownloadAsPdf(user.getId());
        }

        byte[] pdfBytes = pdfService.generateExamPdf(exam);

        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "attachment; filename=\"exam_" + id + ".pdf\"")
                .body(pdfBytes);
    }


    @GetMapping("/my-purchased-exams")
    public ResponseEntity<List<String>> getMyPurchasedExams(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) return ResponseEntity.ok(List.of());
        User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);
        if (user == null) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(examService.getMyPurchasedExamShareLinks(user));
    }

    @GetMapping("/{shareLink}/my-status")
    public ResponseEntity<Map<String, Object>> getMyExamStatus(
            @PathVariable String shareLink,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) return ResponseEntity.ok(Map.of("hasUnusedPurchase", false));
        User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);
        if (user == null) return ResponseEntity.ok(Map.of("hasUnusedPurchase", false));
        boolean hasUnused = examService.hasPurchasedByShareLink(shareLink, user);
        return ResponseEntity.ok(Map.of("hasUnusedPurchase", hasUnused));
    }

    private User getCurrentUser(UserDetails userDetails) {
        if (userDetails == null) {
            throw new UnauthorizedException("İstifadəçi tapılmadı");
        }
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new UnauthorizedException("İstifadəçi tapılmadı"));
    }
}
