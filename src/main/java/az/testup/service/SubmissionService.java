package az.testup.service;

import az.testup.dto.request.AnswerRequest;
import az.testup.dto.request.MatchingPairAnswerRequest;
import az.testup.dto.request.StartSubmissionRequest;
import az.testup.dto.request.SubmitExamRequest;
import az.testup.dto.response.*;
import az.testup.entity.*;
import az.testup.enums.ExamVisibility;
import az.testup.enums.QuestionType;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.AnswerRepository;
import az.testup.repository.ExamRepository;
import az.testup.repository.QuestionRepository;
import az.testup.repository.SubmissionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final ExamRepository examRepository;
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;
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

        // Check if there is already an ongoing submission for this exam and student
        if (student != null) {
            Optional<Submission> ongoing = submissionRepository.findByStudentIdAndExamIdAndSubmittedAtIsNull(student.getId(), exam.getId());
            if (ongoing.isPresent()) {
                return mapToResponse(ongoing.get());
            }
        }

        Submission submission = Submission.builder()
                .exam(exam)
                .student(student)
                .guestName(student == null ? request.getGuestName() : null)
                .startedAt(LocalDateTime.now())
                .isFullyGraded(false)
                .answers(new ArrayList<>())
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

        // Clear existing answers to avoid duplicates
        submission.getAnswers().clear();
        submissionRepository.saveAndFlush(submission);

        for (AnswerRequest answerReq : request.getAnswers()) {
            Question question = questionRepository.findById(answerReq.getQuestionId())
                    .orElseThrow(() -> new ResourceNotFoundException("Sual tapılmadı: " + answerReq.getQuestionId()));
            
            Answer answer = Answer.builder()
                    .submission(submission)
                    .question(question)
                    .build();
            
            updateAnswerData(answer, answerReq);
            gradeAnswer(answer);
            submission.getAnswers().add(answer);
        }

        return finalizeAndSave(submission);
    }

    @Transactional
    public void saveAnswer(Long submissionId, AnswerRequest request, User student) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Cəhd tapılmadı"));

        if (submission.getSubmittedAt() != null) {
            throw new BadRequestException("İmtahan artıq bitib");
        }

        Answer answer = submission.getAnswers().stream()
                .filter(a -> a.getQuestion().getId().equals(request.getQuestionId()))
                .findFirst()
                .orElseGet(() -> {
                    Question question = questionRepository.findById(request.getQuestionId())
                            .orElseThrow(() -> new ResourceNotFoundException("Sual tapılmadı"));
                    Answer newAns = Answer.builder()
                            .submission(submission)
                            .question(question)
                            .build();
                    submission.getAnswers().add(newAns);
                    return newAns;
                });

        updateAnswerData(answer, request);
        answerRepository.save(answer); // Explicitly save the answer
    }

    @Transactional
    public SubmissionResponse finalizeSubmission(Long submissionId, User student) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Cəhd tapılmadı"));

        if (submission.getSubmittedAt() != null) {
            return mapToResponse(submission);
        }

        // Grade all current answers
        for (Question question : submission.getExam().getQuestions()) {
            Answer answer = submission.getAnswers().stream()
                    .filter(a -> a.getQuestion().getId().equals(question.getId()))
                    .findFirst()
                    .orElseGet(() -> {
                        Answer newAns = Answer.builder()
                                .submission(submission)
                                .question(question)
                                .score(0.0)
                                .isGraded(true)
                                .build();
                        submission.getAnswers().add(newAns);
                        return newAns;
                    });
            gradeAnswer(answer);
        }

        return finalizeAndSave(submission);
    }

    private void updateAnswerData(Answer answer, AnswerRequest request) {
        Question question = answer.getQuestion();
        if (question.getQuestionType() == QuestionType.MCQ || question.getQuestionType() == QuestionType.TRUE_FALSE) {
            if (request.getOptionIds() != null && !request.getOptionIds().isEmpty()) {
                answer.setSelectedOptionId(request.getOptionIds().get(0));
            } else {
                answer.setSelectedOptionId(null);
            }
        } else if (question.getQuestionType() == QuestionType.OPEN_AUTO || question.getQuestionType() == QuestionType.OPEN_MANUAL) {
            answer.setAnswerText(request.getTextAnswer());
        } else if (question.getQuestionType() == QuestionType.MATCHING) {
            try {
                answer.setMatchingAnswerJson(objectMapper.writeValueAsString(request.getMatchingPairs()));
            } catch (JsonProcessingException e) {
                answer.setMatchingAnswerJson("[]");
            }
        }
    }

    private void gradeAnswer(Answer answer) {
        Question question = answer.getQuestion();
        if (question.getQuestionType() == QuestionType.MCQ || question.getQuestionType() == QuestionType.TRUE_FALSE) {
            if (answer.getSelectedOptionId() != null) {
                Option correctOption = question.getOptions().stream()
                        .filter(Option::getIsCorrect)
                        .findFirst()
                        .orElse(null);
                if (correctOption != null && correctOption.getId().equals(answer.getSelectedOptionId())) {
                    answer.setScore(question.getPoints());
                } else {
                    answer.setScore(0.0);
                }
                answer.setIsGraded(true);
            } else {
                answer.setScore(0.0);
                answer.setIsGraded(true);
            }
        } else if (question.getQuestionType() == QuestionType.OPEN_AUTO) {
            String correctText = question.getCorrectAnswer();
            if (correctText != null && answer.getAnswerText() != null &&
                    correctText.trim().equalsIgnoreCase(answer.getAnswerText().trim())) {
                answer.setScore(question.getPoints());
            } else {
                answer.setScore(0.0);
            }
            answer.setIsGraded(true);
        } else if (question.getQuestionType() == QuestionType.OPEN_MANUAL) {
            answer.setScore(0.0);
            answer.setIsGraded(false);
        } else if (question.getQuestionType() == QuestionType.MATCHING) {
            if (answer.getMatchingAnswerJson() != null) {
                try {
                    List<MatchingPairAnswerRequest> pairs = objectMapper.readValue(
                        answer.getMatchingAnswerJson(), 
                        objectMapper.getTypeFactory().constructCollectionType(List.class, MatchingPairAnswerRequest.class)
                    );
                    long correctCount = 0;
                    List<MatchingPair> actualPairs = question.getMatchingPairs();
                    if (!actualPairs.isEmpty()) {
                        for (MatchingPairAnswerRequest reqPair : pairs) {
                            if (reqPair.getLeftItemId() != null && reqPair.getLeftItemId().equals(reqPair.getRightItemId())) {
                                correctCount++;
                            }
                        }
                        double pointsPerMatch = question.getPoints() / actualPairs.size();
                        answer.setScore(correctCount * pointsPerMatch);
                    } else {
                        answer.setScore(0.0);
                    }
                } catch (Exception e) {
                    answer.setScore(0.0);
                }
            } else {
                answer.setScore(0.0);
            }
            answer.setIsGraded(true);
        }
    }

    private SubmissionResponse finalizeAndSave(Submission submission) {
        double totalScore = submission.getAnswers().stream()
                .filter(a -> a.getScore() != null)
                .mapToDouble(Answer::getScore)
                .sum();
        
        long questionCount = submission.getExam().getQuestions().size();
        long gradedAnswerCount = submission.getAnswers().stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsGraded()))
                .count();
        
        boolean allGraded = (gradedAnswerCount == questionCount);

        double examMaxScore = submission.getExam().getQuestions().stream()
                .mapToDouble(Question::getPoints)
                .sum();

        submission.setSubmittedAt(LocalDateTime.now());
        submission.setTotalScore(totalScore);
        submission.setMaxScore(examMaxScore);
        submission.setIsFullyGraded(allGraded);

        submission = submissionRepository.save(submission);
        return mapToResponse(submission);
    }

    @Transactional(readOnly = true)
    public List<SubmissionResponse> getMySubmissions(User student) {
        return submissionRepository.findByStudentId(student.getId()).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SubmissionResponse> getOngoingSubmissions(User student) {
        return submissionRepository.findByStudentIdAndSubmittedAtIsNull(student.getId()).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SubmissionResponse> getExamSubmissions(Long examId, User teacher) {
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

    public ExamStatisticsResponse getExamStatistics(Long examId, User teacher) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        List<Submission> submissions = submissionRepository.findByExamId(examId);

        double totalScoreSum = 0;
        double totalRatingSum = 0;
        int ratingCount = 0;
        long totalDurationSeconds = 0;
        int completedSubmissions = 0;

        List<ExamStatisticsResponse.TopStudentDTO> topList = new java.util.ArrayList<>();

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
                
                topList.add(ExamStatisticsResponse.TopStudentDTO.builder()
                        .name(studentName)
                        .score(sub.getTotalScore())
                        .timeSpent(durationFormatted)
                        .build());
            }
        }

        double avgScore = completedSubmissions > 0 ? totalScoreSum / completedSubmissions : 0.0;
        double avgRating = ratingCount > 0 ? totalRatingSum / ratingCount : 0.0;
        int avgDurationMins = completedSubmissions > 0 ? (int) ((totalDurationSeconds / completedSubmissions) / 60) : 0;
        
        double examMaxScore = exam.getQuestions().stream().mapToDouble(Question::getPoints).sum();

        topList.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        if (topList.size() > 5) {
            topList = topList.subList(0, 5);
        }

        return ExamStatisticsResponse.builder()
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
                .durationMinutes(submission.getExam().getDurationMinutes())
                .build();
    }

    @Transactional(readOnly = true)
    public SessionDetailsResponse getSessionDetails(Long submissionId, User student) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Cəhd tapılmadı"));

        if (submission.getStudent() != null) {
            if (student == null || !submission.getStudent().getId().equals(student.getId())) {
                throw new BadRequestException("Bu imtahana daxil olmaq hüququnuz yoxdur");
            }
        }

        Exam exam = submission.getExam();
        
        List<ClientQuestionResponse> clientQuestions = exam.getQuestions().stream().map(q -> 
            ClientQuestionResponse.builder()
                    .id(q.getId())
                    .content(q.getContent())
                    .attachedImage(q.getAttachedImage())
                    .questionType(q.getQuestionType())
                    .points(q.getPoints())
                    .orderIndex(q.getOrderIndex())
                    .options(q.getOptions().stream().map(o -> 
                        ClientOptionResponse.builder()
                            .id(o.getId())
                            .content(o.getContent())
                            .attachedImage(o.getAttachedImage())
                            .build()
                    ).collect(Collectors.toList()))
                    .matchingPairs(q.getMatchingPairs().stream().map(m -> 
                        ClientMatchingPairResponse.builder()
                            .id(m.getId())
                            .leftItem(m.getLeftItem())
                            .attachedImageLeft(m.getAttachedImageLeft())
                            .rightItem(m.getRightItem())
                            .attachedImageRight(m.getAttachedImageRight())
                            .build()
                    ).collect(Collectors.toList()))
                    .build()
        ).collect(Collectors.toList());

        List<AnswerRequest> savedAnswers = submission.getAnswers().stream().map(a -> {
            List<MatchingPairAnswerRequest> mps = null;
            if (a.getQuestion().getQuestionType() == QuestionType.MATCHING && a.getMatchingAnswerJson() != null) {
                try {
                    mps = objectMapper.readValue(a.getMatchingAnswerJson(), 
                        objectMapper.getTypeFactory().constructCollectionType(List.class, MatchingPairAnswerRequest.class));
                } catch (Exception e) {}
            }
            return AnswerRequest.builder()
                    .questionId(a.getQuestion().getId())
                    .optionIds(a.getSelectedOptionId() != null ? List.of(a.getSelectedOptionId()) : List.of())
                    .textAnswer(a.getAnswerText())
                    .matchingPairs(mps)
                    .build();
        }).collect(Collectors.toList());

        return SessionDetailsResponse.builder()
                .submissionId(submission.getId())
                .examTitle(exam.getTitle())
                .durationMinutes(exam.getDurationMinutes())
                .startedAt(submission.getStartedAt())
                .questions(clientQuestions)
                .savedAnswers(savedAnswers)
                .build();
    }

    @Transactional(readOnly = true)
    public SubmissionReviewResponse getSubmissionReview(Long id, User student) {
        Submission submission = submissionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cəhd tapılmadı"));

        if (submission.getStudent() != null) {
            if (student == null || !submission.getStudent().getId().equals(student.getId())) {
                throw new BadRequestException("Bu imtahana daxil olmaq hüququnuz yoxdur");
            }
        }

        Exam exam = submission.getExam();
        
        List<QuestionReviewResponse> reviewQuestions = exam.getQuestions().stream().map(q -> {
            Answer studentAnswer = submission.getAnswers().stream()
                    .filter(a -> a.getQuestion().getId().equals(q.getId()))
                    .findFirst()
                    .orElse(null);

            return QuestionReviewResponse.builder()
                    .id(q.getId())
                    .content(q.getContent())
                    .attachedImage(q.getAttachedImage())
                    .questionType(q.getQuestionType())
                    .points(q.getPoints())
                    .orderIndex(q.getOrderIndex())
                    .studentAnswerText(studentAnswer != null ? studentAnswer.getAnswerText() : null)
                    .studentSelectedOptionId(studentAnswer != null ? studentAnswer.getSelectedOptionId() : null)
                    .studentMatchingAnswerJson(studentAnswer != null ? studentAnswer.getMatchingAnswerJson() : null)
                    .awardedScore(studentAnswer != null && studentAnswer.getScore() != null ? studentAnswer.getScore() : 0.0)
                    .isGraded(studentAnswer != null && studentAnswer.getIsGraded() != null ? studentAnswer.getIsGraded() : true)
                    .feedback(studentAnswer != null ? studentAnswer.getFeedback() : null)
                    .correctAnswer(q.getCorrectAnswer())
                    .options(q.getOptions().stream().map(o -> 
                        OptionReviewResponse.builder()
                            .id(o.getId())
                            .content(o.getContent())
                            .isCorrect(o.getIsCorrect())
                            .orderIndex(o.getOrderIndex())
                            .attachedImage(o.getAttachedImage())
                            .build()
                    ).collect(Collectors.toList()))
                    .matchingPairs(q.getMatchingPairs().stream().map(m -> 
                        ClientMatchingPairResponse.builder()
                            .id(m.getId())
                            .leftItem(m.getLeftItem())
                            .attachedImageLeft(m.getAttachedImageLeft())
                            .rightItem(m.getRightItem())
                            .attachedImageRight(m.getAttachedImageRight())
                            .build()
                    ).collect(Collectors.toList()))
                    .build();
        }).collect(Collectors.toList());

        return SubmissionReviewResponse.builder()
                .id(submission.getId())
                .examId(exam.getId())
                .examTitle(exam.getTitle())
                .totalScore(submission.getTotalScore())
                .maxScore(submission.getMaxScore())
                .startedAt(submission.getStartedAt())
                .submittedAt(submission.getSubmittedAt())
                .isFullyGraded(submission.getIsFullyGraded())
                .rating(submission.getRating())
                .questions(reviewQuestions)
                .build();
    }
}
