package az.testup.controller.admin;

import az.testup.dto.request.ImportBankRequest;
import az.testup.dto.response.BankQuestionResponse;
import az.testup.dto.response.BankSubjectResponse;
import az.testup.entity.User;
import az.testup.exception.BadRequestException;
import az.testup.exception.UnauthorizedException;
import az.testup.repository.UserRepository;
import az.testup.service.BankService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin-only: pull a teacher's question bank into the site's central (global)
 * bank. Secured by the {@code /api/admin/**} → ROLE_ADMIN rule in SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin/bank")
@RequiredArgsConstructor
public class AdminBankController {

    private final BankService bankService;
    private final UserRepository userRepository;

    /** Every bank subject owned by the given teacher (the source side of an import). */
    @GetMapping("/teacher/{teacherId}/subjects")
    public ResponseEntity<List<BankSubjectResponse>> teacherSubjects(@PathVariable Long teacherId) {
        return ResponseEntity.ok(bankService.getSubjectsByOwner(teacherId));
    }

    /** Questions of any subject (admin preview, no owner guard). */
    @GetMapping("/subject/{subjectId}/questions")
    public ResponseEntity<List<BankQuestionResponse>> subjectQuestions(@PathVariable Long subjectId) {
        return ResponseEntity.ok(bankService.getSubjectQuestionsForAdmin(subjectId));
    }

    /** Deep-copy selected (or all) questions from a teacher's subject into the site bank. */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importFromTeacher(
            @RequestBody ImportBankRequest req,
            @AuthenticationPrincipal UserDetails ud) {
        if (req == null || req.sourceSubjectId() == null) {
            throw new BadRequestException("Mənbə fənn seçilməlidir");
        }
        User admin = userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new UnauthorizedException("İstifadəçi tapılmadı"));
        return ResponseEntity.ok(bankService.importFromTeacher(
                admin, req.sourceSubjectId(), req.targetSubjectId(),
                req.targetSubjectName(), req.questionIds()));
    }
}
