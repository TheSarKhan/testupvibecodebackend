package az.testup.controller;

import az.testup.dto.request.CollaboratorAssignment;
import az.testup.dto.request.CreateCollaborativeExamRequest;
import az.testup.dto.request.RejectDraftRequest;
import az.testup.dto.response.CollaborativeExamResponse;
import az.testup.dto.response.CollaboratorResponse;
import az.testup.entity.User;
import az.testup.repository.UserRepository;
import az.testup.service.CollaborativeExamService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CollaborativeExamController {

    private final CollaborativeExamService collaborativeExamService;
    private final UserRepository userRepository;

    private User resolveUser(UserDetails ud) {
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new az.testup.exception.ResourceNotFoundException("İstifadəçi tapılmadı"));
    }

    // ─── Admin endpoints ─────────────────────────────────────────────────────

    @PostMapping("/api/admin/collaborative-exams")
    public ResponseEntity<CollaborativeExamResponse> createCollaborativeExam(
            @RequestBody CreateCollaborativeExamRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        User admin = resolveUser(userDetails);
        return ResponseEntity.ok(collaborativeExamService.createCollaborativeExam(request, admin));
    }

    @GetMapping("/api/admin/collaborative-exams")
    public ResponseEntity<Page<CollaborativeExamResponse>> getCollaborativeExams(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(collaborativeExamService.getCollaborativeExams(pageable));
    }

    @GetMapping("/api/admin/collaborative-exams/{id}")
    public ResponseEntity<CollaborativeExamResponse> getCollaborativeExamDetail(@PathVariable Long id) {
        return ResponseEntity.ok(collaborativeExamService.getCollaborativeExamDetail(id));
    }

    @PostMapping("/api/admin/collaborative-exams/{id}/collaborators")
    public ResponseEntity<CollaboratorResponse> addCollaborator(
            @PathVariable Long id,
            @RequestBody CollaboratorAssignment assignment) {
        return ResponseEntity.ok(collaborativeExamService.addCollaborator(id, assignment));
    }

    @PostMapping("/api/admin/collaborators/{id}/approve")
    public ResponseEntity<Void> approveDraft(@PathVariable Long id) {
        collaborativeExamService.approveDraft(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/admin/collaborators/{id}/reject")
    public ResponseEntity<Void> rejectDraft(
            @PathVariable Long id,
            @RequestBody RejectDraftRequest request) {
        collaborativeExamService.rejectDraft(id, request.comment());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/admin/collaborative-exams/pending-count")
    public ResponseEntity<Map<String, Long>> getPendingCount() {
        long count = collaborativeExamService.getPendingCount();
        return ResponseEntity.ok(Map.of("count", count));
    }

    // ─── Teacher endpoints ────────────────────────────────────────────────────

    @GetMapping("/api/collaborative-exams/my-assignments")
    public ResponseEntity<List<CollaboratorResponse>> getMyAssignments(
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = resolveUser(userDetails);
        return ResponseEntity.ok(collaborativeExamService.getMyCollaborativeAssignments(teacher));
    }

    @PostMapping("/api/collaborative-exams/{collaboratorId}/open-draft")
    public ResponseEntity<Map<String, Long>> openDraft(
            @PathVariable Long collaboratorId,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = resolveUser(userDetails);
        Long draftExamId = collaborativeExamService.getOrCreateDraftExam(collaboratorId, teacher);
        return ResponseEntity.ok(Map.of("draftExamId", draftExamId));
    }

    @PostMapping("/api/collaborative-exams/submit/{draftExamId}")
    public ResponseEntity<Void> submitDraft(
            @PathVariable Long draftExamId,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = resolveUser(userDetails);
        collaborativeExamService.submitDraft(draftExamId, teacher);
        return ResponseEntity.ok().build();
    }
}
