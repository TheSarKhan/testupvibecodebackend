package az.testup.controller;

import az.testup.dto.request.AnswerRequest;
import az.testup.dto.request.GradeManualAnswerRequest;
import az.testup.dto.request.StartSubmissionRequest;
import az.testup.dto.request.SubmitExamRequest;
import az.testup.dto.response.*;
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

    @PostMapping("/{id}/save-answer")
    public ResponseEntity<Void> saveAnswer(
            @PathVariable Long id,
            @Valid @RequestBody AnswerRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getCurrentUserOrNull(userDetails);
        submissionService.saveAnswer(id, request, student);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/finalize")
    public ResponseEntity<SubmissionResponse> finalizeSubmission(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getCurrentUserOrNull(userDetails);
        return ResponseEntity.ok(submissionService.finalizeSubmission(id, student));
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

    @GetMapping("/ongoing")
    public ResponseEntity<List<SubmissionResponse>> getOngoingSubmissions(
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getCurrentUser(userDetails);
        return ResponseEntity.ok(submissionService.getOngoingSubmissions(student));
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

    @GetMapping("/{id}")
    public ResponseEntity<SubmissionResponse> getSubmissionById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = getCurrentUserOrNull(userDetails);
        return ResponseEntity.ok(submissionService.getSubmissionById(id, student));
    }

    @PostMapping("/{id}/grade-answer")
    public ResponseEntity<SubmissionResponse> gradeManualAnswer(
            @PathVariable Long id,
            @RequestBody GradeManualAnswerRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        return ResponseEntity.ok(submissionService.gradeManualAnswer(id, request, teacher));
    }

    @GetMapping("/{id}/review")
    public ResponseEntity<SubmissionReviewResponse> getSubmissionReview(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User student = userDetails != null ? userRepository.findByEmail(userDetails.getUsername()).orElse(null) : null;
        return ResponseEntity.ok(submissionService.getSubmissionReview(id, student));
    }

    @DeleteMapping("/{id}/teacher-hide")
    public ResponseEntity<Void> hideSubmission(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        submissionService.hideSubmission(id, teacher);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/teacher/pending")
    public ResponseEntity<List<SubmissionResponse>> getTeacherPendingGradings(
            @AuthenticationPrincipal UserDetails userDetails) {
        User teacher = getCurrentUser(userDetails);
        return ResponseEntity.ok(submissionService.getTeacherPendingGradings(teacher));
    }
}
