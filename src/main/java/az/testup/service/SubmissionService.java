package az.testup.service;

import az.testup.dto.request.AnswerRequest;
import az.testup.dto.request.MatchingPairAnswerRequest;
import az.testup.dto.request.StartSubmissionRequest;
import az.testup.dto.request.SubmitExamRequest;
import az.testup.dto.response.*;
import az.testup.entity.*;
import az.testup.enums.ExamStatus;
import az.testup.enums.Role;
import az.testup.util.FormulaEvaluator;
import az.testup.enums.ExamVisibility;
import az.testup.enums.QuestionType;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.AnswerRepository;
import az.testup.repository.ExamPurchaseRepository;
import az.testup.repository.ExamRepository;
import az.testup.repository.PayriffOrderRepository;
import az.testup.repository.QuestionRepository;
import az.testup.repository.StudentSavedExamRepository;
import az.testup.repository.SubmissionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final ExamPurchaseRepository examPurchaseRepository;
    private final StudentSavedExamRepository studentSavedExamRepository;
    private final PayriffOrderRepository payriffOrderRepository;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    @Transactional
    public SubmissionResponse startSubmission(String shareLink, StartSubmissionRequest request, User student) {
        Exam exam = examRepository.findByShareLinkAndDeletedFalse(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        if (exam.getStatus() == ExamStatus.CANCELLED || exam.getStatus() == ExamStatus.DRAFT) {
            throw new BadRequestException("Bu imtahan hazırda bağlıdır. Müəllimlə əlaqə saxlayın.");
        }

        // Teachers and admins cannot take exams
        if (student != null && (student.getRole() == Role.TEACHER || student.getRole() == Role.ADMIN)) {
            throw new BadRequestException("Müəllimlər və adminlər imtahan işləyə bilməz.");
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

        // Payment check for paid exams: paidOrders must exceed completedSubmissions
        if (student != null && exam.getPrice() != null && exam.getPrice().compareTo(java.math.BigDecimal.ZERO) > 0) {
            long paid = payriffOrderRepository.countByUserIdAndExamIdAndStatus(student.getId(), exam.getId(), "PAID");
            long submitted = submissionRepository.countByExamIdAndStudentIdAndSubmittedAtIsNotNull(exam.getId(), student.getId());
            if (paid <= submitted) {
                throw new BadRequestException("Bu imtahanı başlamaq üçün ödəniş tələb olunur");
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
        boolean isTemplateExam = question.getExam().getTemplateSection() != null;
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
                    } else if (isTemplateExam) {
                        answer.setScore(0.0);
                    } else {
                        long correctlySelected = studentOptionIds.stream()
                            .filter(correctOptionIds::contains)
                            .count();
                        double raw = correctOptionIds.isEmpty() ? 0.0
                            : ((double) correctlySelected / correctOptionIds.size()) * question.getPoints();
                        answer.setScore(Math.round(raw * 100.0) / 100.0);
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
                    if (isTemplateExam) {
                        answer.setScore(correct == correctAnswers.size() ? question.getPoints() : 0.0);
                    } else {
                        double raw = correctAnswers.isEmpty() ? 0.0
                            : ((double) correct / correctAnswers.size()) * question.getPoints();
                        answer.setScore(Math.round(raw * 100.0) / 100.0);
                    }
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
                    List<MatchingPairAnswerRequest> studentPairs = objectMapper.readValue(
                        answer.getMatchingAnswerJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, MatchingPairAnswerRequest.class)
                    );
                    List<MatchingPair> allPairs = question.getMatchingPairs();
                    // Linked pairs = both sides non-empty; these are the expected correct connections
                    List<MatchingPair> linkedPairs = allPairs.stream()
                        .filter(p -> p.getLeftItem() != null && !p.getLeftItem().isBlank()
                                  && p.getRightItem() != null && !p.getRightItem().isBlank())
                        .collect(Collectors.toList());
                    if (!linkedPairs.isEmpty()) {
                        // Text lookup maps: pairId → text content
                        Map<Long, String> leftTextById = new HashMap<>();
                        Map<Long, String> rightTextById = new HashMap<>();
                        for (MatchingPair p : allPairs) {
                            if (p.getLeftItem() != null) leftTextById.put(p.getId(), p.getLeftItem());
                            if (p.getRightItem() != null) rightTextById.put(p.getId(), p.getRightItem());
                        }
                        // Set of valid correct connections by text: "leftText|||rightText"
                        java.util.Set<String> correctConnections = new java.util.HashSet<>();
                        for (MatchingPair lp : linkedPairs) {
                            correctConnections.add(lp.getLeftItem() + "|||" + lp.getRightItem());
                        }
                        // Count unique correct student connections using text lookup
                        java.util.Set<String> counted = new java.util.HashSet<>();
                        for (MatchingPairAnswerRequest req : studentPairs) {
                            if (req.getLeftItemId() == null || req.getRightItemId() == null) continue;
                            String leftText = leftTextById.get(req.getLeftItemId());
                            String rightText = rightTextById.get(req.getRightItemId());
                            if (leftText == null || rightText == null) continue;
                            String key = leftText + "|||" + rightText;
                            if (correctConnections.contains(key)) counted.add(key);
                        }
                        long correctCount = counted.size();
                        if (isTemplateExam) {
                            answer.setScore(correctCount == linkedPairs.size() ? question.getPoints() : 0.0);
                        } else {
                            double score = (correctCount == linkedPairs.size())
                                ? question.getPoints()
                                : correctCount * (question.getPoints() / linkedPairs.size());
                            answer.setScore(Math.min(question.getPoints(), score));
                        }
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

        // Formula-based scoring for template exams
        if (submission.getExam().getTemplateSection() != null) {
            String formula = submission.getExam().getTemplateSection().getFormula();
            int templateQuestionCount = submission.getExam().getTemplateSection().getQuestionCount();
            Map<String, Double> actualVars = buildFormulaVariables(submission.getAnswers(), templateQuestionCount);
            Map<String, Double> maxVars = buildMaxFormulaVariables(submission.getAnswers(), templateQuestionCount);
            double rawScore = FormulaEvaluator.evaluate(formula, actualVars);
            double maxRaw = FormulaEvaluator.evaluate(formula, maxVars);
            double percent = maxRaw > 0 ? Math.max(0.0, rawScore / maxRaw * 100.0) : 0.0;
            submission.setTemplateScorePercent(Math.round(percent * 100.0) / 100.0);
        }

        submission = submissionRepository.save(submission);

        // Remove exam from student's depot after completion
        if (submission.getStudent() != null) {
            studentSavedExamRepository.deleteByStudentIdAndExamId(
                    submission.getStudent().getId(),
                    submission.getExam().getId());
        }

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
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        boolean isPaidExam = exam.getPrice() != null && exam.getPrice().compareTo(java.math.BigDecimal.ZERO) > 0;

        // Build a purchase map: userId -> amountPaid for quick lookup
        Map<Long, java.math.BigDecimal> purchaseMap = isPaidExam
                ? examPurchaseRepository.findByExamId(examId).stream()
                    .filter(p -> p.getUser() != null)
                    .collect(Collectors.toMap(
                        p -> p.getUser().getId(),
                        az.testup.entity.ExamPurchase::getAmountPaid,
                        (a, b) -> a
                    ))
                : Map.of();

        return submissionRepository.findByExamId(examId).stream()
                .map(sub -> {
                    SubmissionResponse resp = mapToResponse(sub);
                    if (isPaidExam) {
                        Long studentId = sub.getStudent() != null ? sub.getStudent().getId() : null;
                        if (studentId != null && purchaseMap.containsKey(studentId)) {
                            resp.setHasPaid(true);
                            resp.setAmountPaid(purchaseMap.get(studentId));
                        } else {
                            resp.setHasPaid(false);
                            resp.setAmountPaid(null);
                        }
                    }
                    return resp;
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SubmissionResponse> getTeacherPendingGradings(User teacher) {
        List<Long> examIds = examRepository.findByTeacherAndDeletedFalse(teacher).stream()
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

        // Recalculate formula score for template exams
        if (submission.getExam().getTemplateSection() != null) {
            String formula = submission.getExam().getTemplateSection().getFormula();
            int templateQuestionCount = submission.getExam().getTemplateSection().getQuestionCount();
            Map<String, Double> actualVars = buildFormulaVariables(submission.getAnswers(), templateQuestionCount);
            Map<String, Double> maxVars = buildMaxFormulaVariables(submission.getAnswers(), templateQuestionCount);
            double formulaRawScore = FormulaEvaluator.evaluate(formula, actualVars);
            double formulaMaxRaw = FormulaEvaluator.evaluate(formula, maxVars);
            double percent = formulaMaxRaw > 0 ? Math.max(0.0, formulaRawScore / formulaMaxRaw * 100.0) : 0.0;
            submission.setTemplateScorePercent(Math.round(percent * 100.0) / 100.0);
        }

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
                .examPrice(exam.getPrice())
                .totalParticipants(submissions.size())
                .averageScore(avgScore)
                .maximumScore(examMaxScore)
                .averageRating(avgRating)
                .averageDurationMinutes(avgDurationMins)
                .topStudents(topList)
                .build();
    }

    /** Counts answer outcomes per question type for formula evaluation. */
    private Map<String, Double> buildFormulaVariables(List<Answer> answers, int totalQuestionCount) {
        long mcq_correct = 0, mcq_wrong = 0, mcq_blank = 0;
        long multi_correct = 0, multi_wrong = 0;
        long open_correct = 0, open_wrong = 0;
        double manual_correct = 0.0, manual_wrong = 0.0; // fractional for partial credit
        long fill_correct = 0, fill_wrong = 0;
        long match_correct = 0, match_wrong = 0;
        for (Answer a : answers) {
            QuestionType type = a.getQuestion().getQuestionType();
            double pts = a.getQuestion().getPoints();
            double score = a.getScore() != null ? a.getScore() : 0.0;
            if (type == QuestionType.MCQ || type == QuestionType.TRUE_FALSE) {
                if (a.getSelectedOptionId() == null) mcq_blank++;
                else if (score >= pts) mcq_correct++;
                else mcq_wrong++;
            } else if (type == QuestionType.MULTI_SELECT) {
                if (score >= pts) multi_correct++; else multi_wrong++;
            } else if (type == QuestionType.OPEN_AUTO) {
                if (score >= pts) open_correct++; else open_wrong++;
            } else if (type == QuestionType.OPEN_MANUAL) {
                // Use fractional counting so 1/3 and 2/3 partial credit is reflected in formula
                double effectivePts = pts > 0 ? pts : 1.0;
                double frac = Math.min(1.0, Math.max(0.0, score / effectivePts));
                manual_correct += frac;
                manual_wrong += (1.0 - frac);
            } else if (type == QuestionType.FILL_IN_THE_BLANK) {
                if (score >= pts) fill_correct++; else fill_wrong++;
            } else if (type == QuestionType.MATCHING) {
                if (score >= pts) match_correct++; else match_wrong++;
            }
        }
        Map<String, Double> v = new HashMap<>();
        v.put("a", (double) mcq_correct);    v.put("b", (double) mcq_wrong);    v.put("c", (double) mcq_blank);
        v.put("d", (double) multi_correct);  v.put("e", (double) multi_wrong);
        v.put("f", (double) open_correct);   v.put("g", (double) open_wrong);
        v.put("l", manual_correct);          v.put("m", manual_wrong);
        v.put("h", (double) fill_correct);   v.put("i", (double) fill_wrong);
        v.put("j", (double) match_correct);  v.put("k", (double) match_wrong);
        v.put("n", (double) totalQuestionCount);
        return v;
    }

    /** Builds variable map for max-score scenario (all correct, none wrong/blank). */
    private Map<String, Double> buildMaxFormulaVariables(List<Answer> answers, int totalQuestionCount) {
        long mcq = answers.stream().filter(a -> a.getQuestion().getQuestionType() == QuestionType.MCQ || a.getQuestion().getQuestionType() == QuestionType.TRUE_FALSE).count();
        long multi = answers.stream().filter(a -> a.getQuestion().getQuestionType() == QuestionType.MULTI_SELECT).count();
        long open = answers.stream().filter(a -> a.getQuestion().getQuestionType() == QuestionType.OPEN_AUTO).count();
        long manual = answers.stream().filter(a -> a.getQuestion().getQuestionType() == QuestionType.OPEN_MANUAL).count();
        long fill = answers.stream().filter(a -> a.getQuestion().getQuestionType() == QuestionType.FILL_IN_THE_BLANK).count();
        long match = answers.stream().filter(a -> a.getQuestion().getQuestionType() == QuestionType.MATCHING).count();
        Map<String, Double> v = new HashMap<>();
        v.put("a", (double) mcq);    v.put("b", 0.0); v.put("c", 0.0);
        v.put("d", (double) multi);  v.put("e", 0.0);
        v.put("f", (double) open);   v.put("g", 0.0);
        v.put("l", (double) manual); v.put("m", 0.0);
        v.put("h", (double) fill);   v.put("i", 0.0);
        v.put("j", (double) match);  v.put("k", 0.0);
        v.put("n", (double) totalQuestionCount);
        return v;
    }

    private SubmissionResponse mapToResponse(Submission submission) {
        int totalQuestions = submission.getExam().getQuestions().size();

        int correct = (int) submission.getAnswers().stream()
                .filter(a -> a.getScore() != null && a.getScore() > 0.0)
                .count();

        int pending = (int) submission.getAnswers().stream()
                .filter(a -> a.getQuestion() != null
                        && a.getQuestion().getQuestionType() == QuestionType.OPEN_MANUAL
                        && (a.getScore() == null || a.getScore() == 0.0))
                .count();

        int wrong = (int) submission.getAnswers().stream()
                .filter(a -> a.getQuestion() != null
                        && a.getQuestion().getQuestionType() != QuestionType.OPEN_MANUAL
                        && (a.getScore() == null || a.getScore() == 0.0))
                .count();

        int skipped = Math.max(0, totalQuestions - correct - pending - wrong);

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
                .templateScorePercent(submission.getTemplateScorePercent())
                .correctCount(correct)
                .wrongCount(wrong)
                .skippedCount(skipped)
                .pendingManualCount(pending)
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
                        .subjectGroup(p.getSubjectGroup())
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

        // Calculate remaining seconds server-side to avoid client timezone issues
        Long remainingSeconds = null;
        if (exam.getDurationMinutes() != null && exam.getDurationMinutes() > 0 && submission.getStartedAt() != null) {
            long elapsed = java.time.temporal.ChronoUnit.SECONDS.between(submission.getStartedAt(), LocalDateTime.now());
            remainingSeconds = exam.getDurationMinutes() * 60L - elapsed;
        }

        return SessionDetailsResponse.builder()
                .submissionId(submission.getId())
                .examTitle(exam.getTitle())
                .durationMinutes(exam.getDurationMinutes())
                .startedAt(submission.getStartedAt())
                .remainingSeconds(remainingSeconds)
                .subjects(exam.getSubjects())
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
                .subjectGroup(q.getSubjectGroup())
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
                .templateScorePercent(submission.getTemplateScorePercent())
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
