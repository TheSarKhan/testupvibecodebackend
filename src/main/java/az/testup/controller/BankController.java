package az.testup.controller;

import az.testup.dto.request.BankQuestionRequest;
import az.testup.dto.request.BankSubjectRequest;
import az.testup.dto.response.BankQuestionResponse;
import az.testup.dto.response.BankSubjectResponse;
import az.testup.entity.User;
import az.testup.enums.Difficulty;
import az.testup.enums.QuestionType;
import az.testup.exception.UnauthorizedException;
import az.testup.repository.UserRepository;
import az.testup.service.BankExcelExportService;
import az.testup.service.BankService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/bank")
@RequiredArgsConstructor
public class BankController {

    private final BankService bankService;
    private final BankExcelExportService bankExcelExportService;
    private final UserRepository userRepository;

    // ── Subjects ──────────────────────────────────────────────────────────────

    @GetMapping("/subjects")
    public ResponseEntity<List<BankSubjectResponse>> getSubjects(@AuthenticationPrincipal UserDetails ud) {
        return ResponseEntity.ok(bankService.getSubjectsForUser(getUser(ud)));
    }

    @GetMapping("/subjects/paged")
    public ResponseEntity<Page<BankSubjectResponse>> getSubjectsPaged(
            @AuthenticationPrincipal UserDetails ud,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        List<BankSubjectResponse> all = bankService.getSubjectsForUser(getUser(ud));
        var pageable = PageRequest.of(page, size);
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return ResponseEntity.ok(new PageImpl<>(all.subList(from, to), pageable, all.size()));
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

    /**
     * Paginated, filtered and sorted question list.
     * Query params (all optional):
     *   search, topic, difficulty (EASY/MEDIUM/HARD), type (QuestionType),
     *   gradeLevel, tags (comma-separated), sort, page, size
     */
    @GetMapping("/subjects/{id}/questions/paged")
    public ResponseEntity<Page<BankQuestionResponse>> getQuestionsPaged(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails ud,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) Difficulty difficulty,
            @RequestParam(required = false) QuestionType type,
            @RequestParam(required = false) String gradeLevel,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false, defaultValue = "order") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Set<String> tagSet = (tags == null || tags.isBlank())
                ? null
                : java.util.Arrays.stream(tags.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
        return ResponseEntity.ok(bankService.getQuestionsFiltered(
                id, getUser(ud), search, topic, difficulty, type, gradeLevel, tagSet, sort, page, size));
    }

    @GetMapping("/subjects/{id}/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails ud) {
        return ResponseEntity.ok(bankService.getStats(id, getUser(ud)));
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

    @PostMapping("/questions/bulk-delete")
    public ResponseEntity<Map<String, Integer>> bulkDelete(
            @RequestBody Map<String, List<Long>> body,
            @AuthenticationPrincipal UserDetails ud) {
        int n = bankService.bulkDelete(getUser(ud), body.getOrDefault("ids", List.of()));
        return ResponseEntity.ok(Map.of("deleted", n));
    }

    @PostMapping("/questions/{id}/clone")
    public ResponseEntity<BankQuestionResponse> cloneQuestion(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails ud) {
        return ResponseEntity.ok(bankService.cloneQuestion(id, getUser(ud)));
    }

    @PostMapping("/subjects/{id}/reorder")
    public ResponseEntity<Void> reorder(
            @PathVariable Long id,
            @RequestBody Map<String, List<Long>> body,
            @AuthenticationPrincipal UserDetails ud) {
        bankService.reorder(id, getUser(ud), body.getOrDefault("orderedIds", List.of()));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/subjects/{id}/export")
    public ResponseEntity<byte[]> exportExcel(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails ud) {
        User user = getUser(ud);
        List<BankQuestionResponse> qs = bankService.getQuestions(id, user);
        byte[] bytes = bankExcelExportService.export(qs);
        String fileName = "sual-bazasi-" + id + ".xlsx";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encoded);
        return new ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private User getUser(UserDetails ud) {
        if (ud == null) throw new UnauthorizedException("Giriş tələb olunur");
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new UnauthorizedException("İstifadəçi tapılmadı"));
    }
}
