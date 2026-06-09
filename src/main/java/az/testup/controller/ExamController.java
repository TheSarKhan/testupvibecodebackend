package az.testup.controller;

import az.testup.dto.request.ExamRequest;
import az.testup.dto.response.ExamResponse;
import az.testup.dto.response.ExamSummaryResponse;
import az.testup.entity.Exam;
import az.testup.entity.PaymentOrder;
import az.testup.entity.User;
import az.testup.exception.UnauthorizedException;
import az.testup.enums.AuditAction;
import az.testup.enums.ExamStatus;
import az.testup.repository.PaymentOrderRepository;
import az.testup.repository.UserRepository;
import az.testup.service.AuditLogService;
import az.testup.service.ExamService;
import az.testup.service.PdfService;
import az.testup.service.SubscriptionValidatorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/exams")
@RequiredArgsConstructor
public class ExamController {

    private final ExamService examService;
    private final UserRepository userRepository;
    private final PdfService pdfService;
    private final SubscriptionValidatorService subscriptionValidatorService;
    private final PaymentOrderRepository paymentOrderRepository;
    private final AuditLogService auditLogService;


    @PostMapping
    public ResponseEntity<ExamResponse> createExam(
            @Valid @RequestBody ExamRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        return ResponseEntity.ok(examService.createExam(request, teacher));
    }

    /**
     * Teacher list endpoint — returns a slim summary (no nested
     * questions/options/matching pairs) so the "İmtahanlarım" page loads in a
     * handful of queries instead of N×M. The full {@link ExamResponse} is
     * still served by {@code GET /exams/{id}/details}.
     */
    @GetMapping
    public ResponseEntity<List<ExamSummaryResponse>> getMyExams(@AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        return ResponseEntity.ok(examService.getTeacherExamsSummary(teacher));
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

        auditLogService.log(AuditAction.EXAM_PDF_DOWNLOADED, user.getEmail(), user.getFullName(),
                "EXAM", exam.getTitle(),
                "Rol: " + user.getRole() + ", Ölçü: " + pdfBytes.length + " bayt");

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

    @GetMapping("/my-purchased-exam-details")
    public ResponseEntity<List<Map<String, Object>>> getMyPurchasedExamDetails(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) return ResponseEntity.ok(List.of());
        User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);
        if (user == null) return ResponseEntity.ok(List.of());
        List<PaymentOrder> orders = paymentOrderRepository.findPaidExamOrders(user.getId(), "PAID");
        List<Map<String, Object>> result = orders.stream()
            .filter(order -> {
                Exam ex = order.getExam();
                // Skip deleted / closed (CANCELLED) exams so they drop out of "Alınanlar".
                return ex != null && !ex.isDeleted() && ex.getStatus() != ExamStatus.CANCELLED
                        && examService.hasUnusedPurchase(ex, user);
            })
            .collect(Collectors.toMap(
                order -> order.getExam().getId(),
                order -> order,
                (existing, replacement) -> existing // keep first (earliest) order per exam
            ))
            .values().stream()
            .map(order -> {
                Exam exam = order.getExam();
                Map<String, Object> item = new HashMap<>();
                item.put("orderId", order.getOrderId());
                item.put("purchasedAt", order.getCreatedAt());
                item.put("amountPaid", order.getAmount());
                item.put("examId", exam.getId());
                item.put("title", exam.getTitle());
                item.put("shareLink", exam.getShareLink());
                item.put("durationMinutes", exam.getDurationMinutes());
                item.put("description", exam.getDescription());
                return item;
            }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
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
