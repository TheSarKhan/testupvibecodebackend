package az.testup.controller;

import az.testup.dto.request.BankQuestionRequest;
import az.testup.dto.request.BankSubjectRequest;
import az.testup.dto.response.BankQuestionResponse;
import az.testup.dto.response.BankSubjectResponse;
import az.testup.entity.User;
import az.testup.exception.UnauthorizedException;
import az.testup.repository.UserRepository;
import az.testup.service.BankService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bank")
@RequiredArgsConstructor
public class BankController {

    private final BankService bankService;
    private final UserRepository userRepository;

    // ── Subjects ──────────────────────────────────────────────────────────────

    @GetMapping("/subjects")
    public ResponseEntity<List<BankSubjectResponse>> getSubjects(@AuthenticationPrincipal UserDetails ud) {
        return ResponseEntity.ok(bankService.getSubjectsForUser(getUser(ud)));
    }

    @GetMapping("/subjects/paged")
    public ResponseEntity<org.springframework.data.domain.Page<BankSubjectResponse>> getSubjectsPaged(
            @AuthenticationPrincipal UserDetails ud,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        List<BankSubjectResponse> all = bankService.getSubjectsForUser(getUser(ud));
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return ResponseEntity.ok(new org.springframework.data.domain.PageImpl<>(
                all.subList(from, to), pageable, all.size()));
    }

    @PostMapping("/subjects")
    public ResponseEntity<BankSubjectResponse> createSubject(
            @RequestBody BankSubjectRequest req,
            @AuthenticationPrincipal UserDetails ud) {
        return ResponseEntity.ok(bankService.createSubject(getUser(ud), req));
    }

    @PutMapping("/subjects/{id}")
    public ResponseEntity<BankSubjectResponse> updateSubject(
            @PathVariable Long id,
            @RequestBody BankSubjectRequest req,
            @AuthenticationPrincipal UserDetails ud) {
        return ResponseEntity.ok(bankService.updateSubject(id, getUser(ud), req));
    }

    @DeleteMapping("/subjects/{id}")
    public ResponseEntity<Void> deleteSubject(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails ud) {
        bankService.deleteSubject(id, getUser(ud));
        return ResponseEntity.ok().build();
    }

    // ── Questions ─────────────────────────────────────────────────────────────

    @GetMapping("/subjects/{id}/questions")
    public ResponseEntity<List<BankQuestionResponse>> getQuestions(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails ud) {
        return ResponseEntity.ok(bankService.getQuestions(id, getUser(ud)));
    }

    @GetMapping("/subjects/{id}/questions/paged")
    public ResponseEntity<org.springframework.data.domain.Page<BankQuestionResponse>> getQuestionsPaged(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails ud,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<BankQuestionResponse> all = bankService.getQuestions(id, getUser(ud));
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return ResponseEntity.ok(new org.springframework.data.domain.PageImpl<>(
                all.subList(from, to), pageable, all.size()));
    }

    @PostMapping("/questions")
    public ResponseEntity<BankQuestionResponse> createQuestion(
            @RequestBody BankQuestionRequest req,
            @AuthenticationPrincipal UserDetails ud) {
        return ResponseEntity.ok(bankService.createQuestion(getUser(ud), req));
    }

    @PutMapping("/questions/{id}")
    public ResponseEntity<BankQuestionResponse> updateQuestion(
            @PathVariable Long id,
            @RequestBody BankQuestionRequest req,
            @AuthenticationPrincipal UserDetails ud) {
        return ResponseEntity.ok(bankService.updateQuestion(id, getUser(ud), req));
    }

    @DeleteMapping("/questions/{id}")
    public ResponseEntity<Void> deleteQuestion(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails ud) {
        bankService.deleteQuestion(id, getUser(ud));
        return ResponseEntity.ok().build();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private User getUser(UserDetails ud) {
        if (ud == null) throw new UnauthorizedException("Giriş tələb olunur");
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new UnauthorizedException("İstifadəçi tapılmadı"));
    }
}
