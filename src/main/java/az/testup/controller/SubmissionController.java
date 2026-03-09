package az.testup.controller;

import az.testup.dto.request.StartSubmissionRequest;
import az.testup.dto.request.SubmitExamRequest;
import az.testup.dto.response.SubmissionResponse;
import az.testup.entity.User;
import az.testup.exception.UnauthorizedException;
import az.testup.repository.UserRepository;
import az.testup.service.SubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/submissions")
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionService submissionService;
    private final UserRepository userRepository;

    @PostMapping("/start/{shareLink}")
    public ResponseEntity<SubmissionResponse> startSubmission(
            @PathVariable String shareLink,
            @Valid @RequestBody StartSubmissionRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getCurrentUserOrNull(userDetails);
        return ResponseEntity.ok(submissionService.startSubmission(shareLink, request, student));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<SubmissionResponse> submitExam(
            @PathVariable Long id,
            @Valid @RequestBody SubmitExamRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getCurrentUserOrNull(userDetails);
        return ResponseEntity.ok(submissionService.submitExam(id, request, student));
    }

    @GetMapping("/{id}/session")
    public ResponseEntity<az.testup.dto.response.SessionDetailsResponse> getSessionDetails(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getCurrentUserOrNull(userDetails);
        return ResponseEntity.ok(submissionService.getSessionDetails(id, student));
    }

    @PostMapping("/{id}/rate")
    public ResponseEntity<Void> rateSubmission(
            @PathVariable Long id,
            @RequestParam Integer rating,
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getCurrentUserOrNull(userDetails);
        submissionService.rateSubmission(id, rating, student);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/my-results")
    public ResponseEntity<List<SubmissionResponse>> getMySubmissions(
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getCurrentUser(userDetails);
        return ResponseEntity.ok(submissionService.getMySubmissions(student));
    }

    @GetMapping("/exam/{examId}")
    public ResponseEntity<List<SubmissionResponse>> getExamSubmissions(
            @PathVariable Long examId,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        return ResponseEntity.ok(submissionService.getExamSubmissions(examId, teacher));
    }

    @GetMapping("/exam/{examId}/statistics")
    public ResponseEntity<az.testup.dto.response.ExamStatisticsResponse> getExamStatistics(
            @PathVariable Long examId,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        return ResponseEntity.ok(submissionService.getExamStatistics(examId, teacher));
    }

    private User getCurrentUserOrNull(UserDetails userDetails) {
        if (userDetails == null) return null;
        return userRepository.findByEmail(userDetails.getUsername()).orElse(null);
    }

    private User getCurrentUser(UserDetails userDetails) {
        if (userDetails == null) {
            throw new UnauthorizedException("İstifadəçi tapılmadı");
        }
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new UnauthorizedException("İstifadəçi tapılmadı"));
    }
}
