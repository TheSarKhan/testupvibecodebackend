package az.testup.service;

import az.testup.dto.request.AnswerRequest;
import az.testup.dto.request.MatchingPairAnswerRequest;
import az.testup.dto.request.StartSubmissionRequest;
import az.testup.dto.request.SubmitExamRequest;
import az.testup.dto.response.*;
import az.testup.entity.*;
import az.testup.enums.ExamStatus;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final ExamRepository examRepository;
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    @Transactional
    public SubmissionResponse startSubmission(String shareLink, StartSubmissionRequest request, User student) {
        Exam exam = examRepository.findByShareLink(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        if (exam.getStatus() == ExamStatus.CANCELLED || exam.getStatus() == ExamStatus.DRAFT) {
            throw new BadRequestException("Bu imtahan hazırda bağlıdır. Müəllimlə əlaqə saxlayın.");
        }

        if (exam.getVisibility() == ExamVisibility.PRIVATE) {
            if (request.getAccessCode() == null || !request.getAccessCode().equals(exam.getAccessCode())) {
                throw new BadRequestException("Keçid kodu yanlışdır");
            }
            if (exam.getAccessCodeExpiresAt() == null || exam.getAccessCodeExpiresAt().isBefore(LocalDateTime.now())) {
                throw new BadRequestException("Keçid kodunun müddəti bitib. Müəllimdən yeni kod istəyin");
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
            // Snapshot the question for versioning
            answer.setQuestionSnapshot(createQuestionSnapshot(question));
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

        // Grade all current answers — includes both standalone and passage questions
        for (Question question : getAllExamQuestions(submission.getExam())) {
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
            // Snapshot the question for versioning
            answer.setQuestionSnapshot(createQuestionSnapshot(question));
        }

        return finalizeAndSave(submission);
    }

    /** Returns ALL questions in an exam — both standalone and passage questions */
    private List<Question> getAllExamQuestions(Exam exam) {
        return exam.getQuestions(); // exam.questions includes ALL (standalone + passage) mapped by exam_id
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
            answer.setAnswerImage(request.getAnswerImage());
        } else if (question.getQuestionType() == QuestionType.FILL_IN_THE_BLANK) {
            answer.setAnswerText(request.getTextAnswer());
        } else if (question.getQuestionType() == QuestionType.MULTI_SELECT) {
            try {
                answer.setSelectedOptionIdsJson(objectMapper.writeValueAsString(request.getOptionIds()));
            } catch (JsonProcessingException e) {
                answer.setSelectedOptionIdsJson("[]");
            }
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
        } else if (question.getQuestionType() == QuestionType.MULTI_SELECT) {
            if (answer.getSelectedOptionIdsJson() != null) {
                try {
                    List<Long> studentOptionIds = objectMapper.readValue(
                        answer.getSelectedOptionIdsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class)
                    );
                    List<Long> correctOptionIds = question.getOptions().stream()
                        .filter(Option::getIsCorrect)
                        .map(Option::getId)
                        .collect(Collectors.toList());

                    if (studentOptionIds.size() == correctOptionIds.size() &&
                        studentOptionIds.containsAll(correctOptionIds)) {
                        answer.setScore(question.getPoints());
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
        } else if (question.getQuestionType() == QuestionType.OPEN_MANUAL) {
            answer.setScore(0.0);
            answer.setIsGraded(false);
        } else if (question.getQuestionType() == QuestionType.FILL_IN_THE_BLANK) {
            String correctJson = question.getCorrectAnswer();
            String studentJson = answer.getAnswerText();
            if (correctJson != null && !correctJson.isBlank() && studentJson != null && !studentJson.isBlank()) {
                try {
                    List<String> correctAnswers = objectMapper.readValue(correctJson,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    List<String> studentAnswers = objectMapper.readValue(studentJson,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    int correct = 0;
                    for (int i = 0; i < correctAnswers.size(); i++) {
                        if (i < studentAnswers.size()
                                && correctAnswers.get(i) != null
                                && studentAnswers.get(i) != null
                                && correctAnswers.get(i).trim().equalsIgnoreCase(studentAnswers.get(i).trim())) {
                            correct++;
                        }
                    }
                    double raw = correctAnswers.isEmpty() ? 0.0
                        : ((double) correct / correctAnswers.size()) * question.getPoints();
                    answer.setScore(Math.round(raw * 100.0) / 100.0);
                } catch (Exception e) {
                    answer.setScore(0.0);
                }
            } else {
                answer.setScore(0.0);
            }
            answer.setIsGraded(true);
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

        List<Question> allQuestions = getAllExamQuestions(submission.getExam());
        long questionCount = allQuestions.size();
        long gradedAnswerCount = submission.getAnswers().stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsGraded()))
                .count();

        boolean allGraded = (gradedAnswerCount == questionCount);

        double examMaxScore = allQuestions.stream()
                .mapToDouble(Question::getPoints)
                .sum();

        submission.setSubmittedAt(LocalDateTime.now());
        submission.setTotalScore(totalScore);
        submission.setMaxScore(examMaxScore);
        submission.setIsFullyGraded(allGraded);

        submission = submissionRepository.save(submission);

        // Notify teacher of new submission
        User teacher = submission.getExam().getTeacher();
        if (teacher != null) {
            String studentName = submission.getStudent() != null
                    ? submission.getStudent().getFullName() : submission.getGuestName();
            notificationService.send(teacher,
                    "Yeni göndəriş",
                    studentName + " \"" + submission.getExam().getTitle() + "\" imtahanını təhvil verdi.");
        }

        // Notify student if auto-graded fully
        if (allGraded && submission.getStudent() != null) {
            notificationService.send(submission.getStudent(),
                    "Nəticəniz hazırdır",
                    "\"" + submission.getExam().getTitle() + "\" imtahanı yoxlandı. Nəticənizə baxa bilərsiniz.");
        }

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

    @Transactional(readOnly = true)
    public List<SubmissionResponse> getTeacherPendingGradings(User teacher) {
        List<Long> examIds = examRepository.findByTeacher(teacher).stream()
                .map(exam -> exam.getId())
                .collect(Collectors.toList());
        if (examIds.isEmpty()) return List.of();
        return submissionRepository.findByExamIdInAndSubmittedAtIsNotNullAndIsFullyGradedFalse(examIds).stream()
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

    @Transactional
    public SubmissionResponse gradeManualAnswer(Long submissionId, az.testup.dto.request.GradeManualAnswerRequest request, User teacher) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Cəhd tapılmadı"));

        if (submission.getSubmittedAt() == null) {
            throw new BadRequestException("İmtahan hələ bitməyib");
        }

        Answer answer = submission.getAnswers().stream()
                .filter(a -> a.getQuestion().getId().equals(request.getQuestionId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Bu suala cavab tapılmadı"));

        if (answer.getQuestion().getQuestionType() != QuestionType.OPEN_MANUAL) {
            throw new BadRequestException("Bu sual tipi manuel yoxlama tələb etmir");
        }

        double fraction = request.getFraction() != null ? request.getFraction() : 0.0;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        double rawScore = fraction * answer.getQuestion().getPoints();
        answer.setScore(Math.round(rawScore * 100.0) / 100.0);
        answer.setIsGraded(true);
        if (request.getFeedback() != null) {
            answer.setFeedback(request.getFeedback());
        }

        // Recalculate total score (rounded to 2 dp)
        double totalScore = Math.round(submission.getAnswers().stream()
                .filter(a -> a.getScore() != null)
                .mapToDouble(Answer::getScore)
                .sum() * 100.0) / 100.0;
        submission.setTotalScore(totalScore);

        // Check if all answers are now graded
        long totalQuestions = getAllExamQuestions(submission.getExam()).size();
        long gradedCount = submission.getAnswers().stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsGraded()))
                .count();
        boolean wasFullyGraded = submission.getIsFullyGraded();
        boolean nowFullyGraded = gradedCount >= totalQuestions;
        submission.setIsFullyGraded(nowFullyGraded);

        submissionRepository.save(submission);

        // Notify student when all manual answers are graded
        if (!wasFullyGraded && nowFullyGraded && submission.getStudent() != null) {
            notificationService.send(submission.getStudent(),
                    "Nəticəniz hazırdır",
                    "\"" + submission.getExam().getTitle() + "\" imtahanı tam yoxlandı. Nəticənizə baxa bilərsiniz.");
        }

        return mapToResponse(submission);
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

        double examMaxScore = getAllExamQuestions(exam).stream().mapToDouble(Question::getPoints).sum();

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

        // Group passage questions by passage id
        Map<Long, List<ClientQuestionResponse>> questionsByPassage = exam.getQuestions().stream()
                .filter(q -> q.getPassage() != null)
                .collect(Collectors.groupingBy(
                        q -> q.getPassage().getId(),
                        Collectors.mapping(this::mapToClientQuestion, Collectors.toList())
                ));

        // Standalone questions (no passage)
        List<ClientQuestionResponse> standaloneQuestions = exam.getQuestions().stream()
                .filter(q -> q.getPassage() == null)
                .map(this::mapToClientQuestion)
                .collect(Collectors.toList());

        // Passage groups
        List<ClientPassageResponse> clientPassages = exam.getPassages().stream()
                .map(p -> ClientPassageResponse.builder()
                        .id(p.getId())
                        .passageType(p.getPassageType())
                        .title(p.getTitle())
                        .textContent(p.getTextContent())
                        .attachedImage(p.getAttachedImage())
                        .audioContent(p.getAudioContent())
                        .listenLimit(p.getListenLimit())
                        .orderIndex(p.getOrderIndex())
                        .questions(questionsByPassage.getOrDefault(p.getId(), new ArrayList<>()))
                        .build())
                .collect(Collectors.toList());

        // Build saved answers for all questions (standalone + passage)
        List<AnswerRequest> savedAnswers = submission.getAnswers().stream().map(a -> {
            List<MatchingPairAnswerRequest> mps = null;
            List<Long> optionIds = new ArrayList<>();

            if (a.getQuestion().getQuestionType() == QuestionType.MATCHING && a.getMatchingAnswerJson() != null) {
                try {
                    mps = objectMapper.readValue(a.getMatchingAnswerJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, MatchingPairAnswerRequest.class));
                } catch (Exception e) {}
            } else if (a.getQuestion().getQuestionType() == QuestionType.MULTI_SELECT && a.getSelectedOptionIdsJson() != null) {
                try {
                    optionIds = objectMapper.readValue(a.getSelectedOptionIdsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
                } catch (Exception e) {}
            } else if (a.getSelectedOptionId() != null) {
                optionIds.add(a.getSelectedOptionId());
            }

            return AnswerRequest.builder()
                    .questionId(a.getQuestion().getId())
                    .optionIds(optionIds)
                    .textAnswer(a.getAnswerText())
                    .matchingPairs(mps)
                    .build();
        }).collect(Collectors.toList());

        return SessionDetailsResponse.builder()
                .submissionId(submission.getId())
                .examTitle(exam.getTitle())
                .durationMinutes(exam.getDurationMinutes())
                .startedAt(submission.getStartedAt())
                .questions(standaloneQuestions)
                .passages(clientPassages)
                .savedAnswers(savedAnswers)
                .build();
    }

    private ClientQuestionResponse mapToClientQuestion(Question q) {
        return ClientQuestionResponse.builder()
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
                .build();
    }

    @Transactional(readOnly = true)
    public SubmissionReviewResponse getSubmissionReview(Long id, User student) {
        Submission submission = submissionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cəhd tapılmadı"));

        if (submission.getStudent() != null) {
            boolean isStudent = student != null && submission.getStudent().getId().equals(student.getId());
            boolean isTeacher = student != null && submission.getExam().getTeacher().getId().equals(student.getId());

            if (!isStudent && !isTeacher) {
                throw new BadRequestException("Bu imtahan nəticəsinə baxmaq hüququnuz yoxdur");
            }
        }

        Exam exam = submission.getExam();

        List<QuestionReviewResponse> reviewQuestions = submission.getAnswers().stream()
                .sorted(java.util.Comparator.comparing(a -> {
                    if (a.getQuestionSnapshot() != null) {
                        try {
                            QuestionSnapshot snapshot = objectMapper.readValue(a.getQuestionSnapshot(), QuestionSnapshot.class);
                            return snapshot.getOrderIndex() != null ? snapshot.getOrderIndex() : 0;
                        } catch (Exception e) {}
                    }
                    return a.getQuestion().getOrderIndex() != null ? a.getQuestion().getOrderIndex() : 0;
                }))
                .map(studentAnswer -> {
                    Question q = studentAnswer.getQuestion();
                    QuestionReviewResponse.QuestionReviewResponseBuilder qBuilder = QuestionReviewResponse.builder()
                            .id(q.getId())
                            .passageId(q.getPassage() != null ? q.getPassage().getId() : null);

                    if (studentAnswer.getQuestionSnapshot() != null) {
                        try {
                            QuestionSnapshot snapshot = objectMapper.readValue(studentAnswer.getQuestionSnapshot(), QuestionSnapshot.class);
                            qBuilder.content(snapshot.getContent())
                                    .attachedImage(snapshot.getAttachedImage())
                                    .questionType(snapshot.getQuestionType())
                                    .points(snapshot.getPoints())
                                    .orderIndex(snapshot.getOrderIndex())
                                    .correctAnswer(snapshot.getCorrectAnswer())
                                    .options(snapshot.getOptions().stream().map(o ->
                                        OptionReviewResponse.builder()
                                            .id(o.getId())
                                            .content(o.getContent())
                                            .isCorrect(o.getIsCorrect())
                                            .orderIndex(o.getOrderIndex())
                                            .attachedImage(o.getAttachedImage())
                                            .build()
                                    ).collect(Collectors.toList()))
                                    .matchingPairs(snapshot.getMatchingPairs());
                        } catch (Exception e) {
                            fillBuilderWithLiveData(qBuilder, q);
                        }
                    } else {
                        fillBuilderWithLiveData(qBuilder, q);
                    }

                    List<Long> selectedOptionIds = new ArrayList<>();
                    if (q.getQuestionType() == QuestionType.MULTI_SELECT && studentAnswer.getSelectedOptionIdsJson() != null) {
                        try {
                            selectedOptionIds = objectMapper.readValue(studentAnswer.getSelectedOptionIdsJson(),
                                objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
                        } catch (Exception e) {}
                    } else if (studentAnswer.getSelectedOptionId() != null) {
                        selectedOptionIds.add(studentAnswer.getSelectedOptionId());
                    }

                    return qBuilder
                            .studentAnswerText(studentAnswer.getAnswerText())
                            .studentAnswerImage(studentAnswer.getAnswerImage())
                            .studentSelectedOptionId(studentAnswer.getSelectedOptionId())
                            .studentSelectedOptionIds(selectedOptionIds)
                            .studentMatchingAnswerJson(studentAnswer.getMatchingAnswerJson())
                            .awardedScore(studentAnswer.getScore() != null ? studentAnswer.getScore() : 0.0)
                            .isGraded(studentAnswer.getIsGraded() != null ? studentAnswer.getIsGraded() : true)
                            .feedback(studentAnswer.getFeedback())
                            .build();
                }).collect(Collectors.toList());

        int ungradedCount = (int) submission.getAnswers().stream()
                .filter(a -> Boolean.FALSE.equals(a.getIsGraded()))
                .count();

        return SubmissionReviewResponse.builder()
                .id(submission.getId())
                .examId(exam.getId())
                .examTitle(exam.getTitle())
                .totalScore(submission.getTotalScore())
                .maxScore(submission.getMaxScore())
                .startedAt(submission.getStartedAt())
                .submittedAt(submission.getSubmittedAt())
                .isFullyGraded(submission.getIsFullyGraded())
                .ungradedCount(ungradedCount)
                .rating(submission.getRating())
                .questions(reviewQuestions)
                .build();
    }

    private String createQuestionSnapshot(Question q) {
        try {
            QuestionSnapshot snapshot = QuestionSnapshot.builder()
                    .id(q.getId())
                    .content(q.getContent())
                    .attachedImage(q.getAttachedImage())
                    .questionType(q.getQuestionType())
                    .points(q.getPoints())
                    .orderIndex(q.getOrderIndex())
                    .correctAnswer(q.getCorrectAnswer())
                    .options(q.getOptions().stream().map(o ->
                        OptionSnapshot.builder()
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
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.error("Failed to create question snapshot", e);
            return null;
        }
    }

    private void fillBuilderWithLiveData(QuestionReviewResponse.QuestionReviewResponseBuilder builder, Question q) {
        builder.content(q.getContent())
                .attachedImage(q.getAttachedImage())
                .questionType(q.getQuestionType())
                .points(q.getPoints())
                .orderIndex(q.getOrderIndex())
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
                ).collect(Collectors.toList()));
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class QuestionSnapshot {
        private Long id;
        private String content;
        private String attachedImage;
        private QuestionType questionType;
        private Double points;
        private Integer orderIndex;
        private String correctAnswer;
        private List<OptionSnapshot> options;
        private List<ClientMatchingPairResponse> matchingPairs;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OptionSnapshot {
        private Long id;
        private String content;
        private Boolean isCorrect;
        private Integer orderIndex;
        private String attachedImage;
    }
}
