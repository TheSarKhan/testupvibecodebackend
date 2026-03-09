package az.testup.service;

import az.testup.dto.request.AnswerRequest;
import az.testup.dto.request.StartSubmissionRequest;
import az.testup.dto.request.SubmitExamRequest;
import az.testup.dto.response.SubmissionResponse;
import az.testup.dto.response.ExamStatisticsResponse;
import az.testup.entity.*;
import az.testup.enums.ExamVisibility;
import az.testup.enums.QuestionType;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.ExamRepository;
import az.testup.repository.QuestionRepository;
import az.testup.repository.SubmissionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final ExamRepository examRepository;
    private final QuestionRepository questionRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SubmissionResponse startSubmission(String shareLink, StartSubmissionRequest request, User student) {
        Exam exam = examRepository.findByShareLink(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        if (exam.getVisibility() == ExamVisibility.PRIVATE) {
            if (request.getAccessCode() == null || !request.getAccessCode().equals(exam.getAccessCode())) {
                throw new BadRequestException("Keçid kodu yanlışdır");
            }
        }

        Submission submission = Submission.builder()
                .exam(exam)
                .student(student) // null if guest
                .guestName(student == null ? request.getGuestName() : null)
                .startedAt(LocalDateTime.now())
                .isFullyGraded(false)
                .build();

        if (student == null && (request.getGuestName() == null || request.getGuestName().trim().isEmpty())) {
            throw new BadRequestException("Qonaq adı mütləqdir");
        }

        submission = submissionRepository.save(submission);
        return mapToResponse(submission);
    }

    @Transactional
    public SubmissionResponse submitExam(Long submissionId, SubmitExamRequest request, User student) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Cəhd tapılmadı"));

        if (submission.getSubmittedAt() != null) {
            throw new BadRequestException("Bu imtahan artıq təhvil verilib");
        }

        Exam exam = submission.getExam();
        double totalScore = 0.0;
        double maxScore = 0.0;
        boolean allGraded = true;

        for (Question question : exam.getQuestions()) {
            maxScore += question.getPoints();

            AnswerRequest answerReq = request.getAnswers().stream()
                    .filter(a -> a.getQuestionId().equals(question.getId()))
                    .findFirst()
                    .orElse(null);

            Answer answer = Answer.builder()
                    .submission(submission)
                    .question(question)
                    .build();

            if (answerReq != null) {
                // Auto-grading logic based on QuestionType
                if (question.getQuestionType() == QuestionType.MCQ || question.getQuestionType() == QuestionType.TRUE_FALSE) {
                    if (answerReq.getOptionIds() != null && !answerReq.getOptionIds().isEmpty()) {
                        Long selectedId = answerReq.getOptionIds().get(0);
                        answer.setSelectedOptionId(selectedId);
                        
                        Option correctOption = question.getOptions().stream()
                                .filter(Option::getIsCorrect)
                                .findFirst()
                                .orElse(null);
                        
                        if (correctOption != null && correctOption.getId().equals(selectedId)) {
                            answer.setScore(question.getPoints());
                            totalScore += question.getPoints();
                        } else {
                            answer.setScore(0.0);
                        }
                        answer.setIsGraded(true);
                    }
                } else if (question.getQuestionType() == QuestionType.OPEN_AUTO) {
                    answer.setAnswerText(answerReq.getTextAnswer());
                    String correctText = question.getCorrectAnswer();
                    if (correctText != null && answerReq.getTextAnswer() != null &&
                            correctText.trim().equalsIgnoreCase(answerReq.getTextAnswer().trim())) {
                        answer.setScore(question.getPoints());
                        totalScore += question.getPoints();
                    } else {
                        answer.setScore(0.0);
                    }
                    answer.setIsGraded(true);
                } else if (question.getQuestionType() == QuestionType.OPEN_MANUAL) {
                    answer.setAnswerText(answerReq.getTextAnswer());
                    answer.setScore(0.0);
                    answer.setIsGraded(false);
                    allGraded = false;
                } else if (question.getQuestionType() == QuestionType.MATCHING) {
                    answer.setIsGraded(false); // Can be auto-graded if fully matched, leaving for manual/complex logic later
                    allGraded = false;
                    if (answerReq.getMatchingPairs() != null) {
                        try {
                            answer.setMatchingAnswerJson(objectMapper.writeValueAsString(answerReq.getMatchingPairs()));
                        } catch (JsonProcessingException e) {
                            // Ignore or log
                        }
                    }
                }
            } else {
                answer.setScore(0.0);
                answer.setIsGraded(true);
            }
            submission.getAnswers().add(answer);
        }

        submission.setSubmittedAt(LocalDateTime.now());
        submission.setTotalScore(totalScore);
        submission.setMaxScore(maxScore);
        submission.setIsFullyGraded(allGraded);

        submission = submissionRepository.save(submission);
        return mapToResponse(submission);
    }

    public List<SubmissionResponse> getMySubmissions(User student) {
        return submissionRepository.findByStudentId(student.getId()).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<SubmissionResponse> getExamSubmissions(Long examId, User teacher) {
        // Here we could check if exam belongs to teacher
        return submissionRepository.findByExamId(examId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void rateSubmission(Long submissionId, Integer rating, User student) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Cəhd tapılmadı"));

        if (rating == null || rating < 1 || rating > 5) {
            throw new BadRequestException("Reytinq 1 və 5 arasında olmalıdır");
        }

        if (submission.getStudent() != null) {
            if (student == null || !submission.getStudent().getId().equals(student.getId())) {
                throw new BadRequestException("Bu əməliyyat üçün icazəniz yoxdur");
            }
        }

        submission.setRating(rating);
        submissionRepository.save(submission);
    }

    public az.testup.dto.response.ExamStatisticsResponse getExamStatistics(Long examId, User teacher) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        // Optional: Verify teacher ownership
        // if (!exam.getTeacher().getId().equals(teacher.getId())) throw new UnauthorizedException();

        List<Submission> submissions = submissionRepository.findByExamId(examId);

        double totalScoreSum = 0;
        int maxScoreSum = 0;
        double totalRatingSum = 0;
        int ratingCount = 0;
        long totalDurationSeconds = 0;
        int completedSubmissions = 0;

        List<az.testup.dto.response.ExamStatisticsResponse.TopStudentDTO> topList = new java.util.ArrayList<>();

        for (Submission sub : submissions) {
            if (sub.getSubmittedAt() != null) {
                totalScoreSum += sub.getTotalScore();
                completedSubmissions++;
                
                long durationSec = java.time.Duration.between(sub.getStartedAt(), sub.getSubmittedAt()).getSeconds();
                totalDurationSeconds += durationSec;

                if (sub.getRating() != null) {
                    totalRatingSum += sub.getRating();
                    ratingCount++;
                }

                String durationFormatted = String.format("%02d:%02d", durationSec / 60, durationSec % 60);
                String studentName = sub.getStudent() != null ? sub.getStudent().getFullName() : (sub.getGuestName() != null ? sub.getGuestName() : "Qonaq");
                
                topList.add(az.testup.dto.response.ExamStatisticsResponse.TopStudentDTO.builder()
                        .name(studentName)
                        .score(sub.getTotalScore())
                        .timeSpent(durationFormatted)
                        .build());
            }
        }

        double avgScore = completedSubmissions > 0 ? totalScoreSum / completedSubmissions : 0.0;
        double avgRating = ratingCount > 0 ? totalRatingSum / ratingCount : 0.0;
        int avgDurationMins = completedSubmissions > 0 ? (int) ((totalDurationSeconds / completedSubmissions) / 60) : 0;
        
        // Max possible score for the exam
        double examMaxScore = exam.getQuestions().stream().mapToDouble(Question::getPoints).sum();

        // Sort top list
        topList.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        if (topList.size() > 5) {
            topList = topList.subList(0, 5);
        }

        return az.testup.dto.response.ExamStatisticsResponse.builder()
                .examId(exam.getId())
                .examTitle(exam.getTitle())
                .totalParticipants(submissions.size())
                .averageScore(avgScore)
                .maximumScore(examMaxScore)
                .averageRating(avgRating)
                .averageDurationMinutes(avgDurationMins)
                .topStudents(topList)
                .build();
    }

    private SubmissionResponse mapToResponse(Submission submission) {
        return SubmissionResponse.builder()
                .id(submission.getId())
                .examId(submission.getExam().getId())
                .examTitle(submission.getExam().getTitle())
                .studentName(submission.getStudent() != null ? submission.getStudent().getFullName() : submission.getGuestName())
                .totalScore(submission.getTotalScore())
                .maxScore(submission.getMaxScore())
                .isFullyGraded(submission.getIsFullyGraded())
                .rating(submission.getRating())
                .startedAt(submission.getStartedAt())
                .submittedAt(submission.getSubmittedAt())
                .build();
    }

    public az.testup.dto.response.SessionDetailsResponse getSessionDetails(Long submissionId, User student) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Cəhd tapılmadı"));

        // Basic security check to ensure the student accessing this is the one who created it (guest or logged in)
        if (submission.getStudent() != null) {
            if (student == null || !submission.getStudent().getId().equals(student.getId())) {
                throw new BadRequestException("Bu imtahana daxil olmaq hüququnuz yoxdur");
            }
        }

        Exam exam = submission.getExam();
        
        List<az.testup.dto.response.ClientQuestionResponse> clientQuestions = exam.getQuestions().stream().map(q -> {
            return az.testup.dto.response.ClientQuestionResponse.builder()
                    .id(q.getId())
                    .content(q.getContent())
                    .attachedImage(q.getAttachedImage())
                    .questionType(q.getQuestionType())
                    .points(q.getPoints())
                    .orderIndex(q.getOrderIndex())
                    .options(q.getOptions().stream().map(o -> 
                        az.testup.dto.response.ClientOptionResponse.builder()
                            .id(o.getId())
                            .content(o.getContent())
                            .attachedImage(o.getAttachedImage())
                            .build()
                    ).collect(Collectors.toList()))
                    .matchingPairs(q.getMatchingPairs().stream().map(m -> 
                        az.testup.dto.response.ClientMatchingPairResponse.builder()
                            .id(m.getId())
                            .leftItem(m.getLeftItem())
                            .rightItem(m.getRightItem())
                            .build()
                    ).collect(Collectors.toList()))
                    .build();
        }).collect(Collectors.toList());

        return az.testup.dto.response.SessionDetailsResponse.builder()
                .submissionId(submission.getId())
                .examTitle(exam.getTitle())
                .durationMinutes(exam.getDurationMinutes())
                .startedAt(submission.getStartedAt())
                .questions(clientQuestions)
                .build();
    }
}
