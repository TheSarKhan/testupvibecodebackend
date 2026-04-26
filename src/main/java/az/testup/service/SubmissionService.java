package az.testup.service;

import az.testup.dto.request.AnswerRequest;
import az.testup.dto.request.MatchingPairAnswerRequest;
import az.testup.dto.request.StartSubmissionRequest;
import az.testup.dto.request.SubmitExamRequest;
import az.testup.dto.response.*;
import az.testup.entity.*;
import az.testup.enums.AuditAction;
import az.testup.enums.ExamStatus;
import az.testup.enums.Role;
import az.testup.exception.UnauthorizedException;
import az.testup.util.FormulaEvaluator;
import az.testup.enums.ExamVisibility;
import az.testup.enums.QuestionType;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.AnswerRepository;
import az.testup.repository.ExamAccessCodeRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final AuditLogService auditLogService;
    private final ExamAccessCodeRepository examAccessCodeRepository;

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
            if (request.getAccessCode() == null || request.getAccessCode().isBlank()) {
                throw new BadRequestException("Keçid kodu tələb olunur");
            }
            ExamAccessCode accessCode = examAccessCodeRepository.findByCode(request.getAccessCode())
                    .orElseThrow(() -> new BadRequestException("Keçid kodu yanlışdır"));
            if (!accessCode.getExam().getId().equals(exam.getId())) {
                throw new BadRequestException("Keçid kodu yanlışdır");
            }
            if (accessCode.isUsed()) {
                throw new BadRequestException("Bu keçid kodu artıq istifadə edilib");
            }
            if (accessCode.getExpiresAt().isBefore(java.time.Instant.now())) {
                throw new BadRequestException("Keçid kodunun müddəti bitib. Müəllimdən yeni kod istəyin");
            }
            accessCode.setUsed(true);
            examAccessCodeRepository.save(accessCode);
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
                .startedAt(java.time.Instant.now())
                .isFullyGraded(false)
                .answers(new ArrayList<>())
                .build();

        if (student == null && (request.getGuestName() == null || request.getGuestName().trim().isEmpty())) {
            throw new BadRequestException("Qonaq adı mütləqdir");
        }

        submission = submissionRepository.save(submission);
        String actorEmail = student != null ? student.getEmail() : (request.getGuestName() + " (qonaq)");
        String actorName  = student != null ? student.getFullName() : request.getGuestName();
        auditLogService.log(AuditAction.EXAM_STARTED, actorEmail, actorName, "EXAM", exam.getTitle(), "Müəllim: " + exam.getTeacher().getFullName());
        return mapToResponse(submission);
    }

    @Transactional
    public SubmissionResponse submitExam(Long submissionId, SubmitExamRequest request, User student) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Cəhd tapılmadı"));

        if (student != null && submission.getStudent() != null
                && !submission.getStudent().getId().equals(student.getId())) {
            throw new az.testup.exception.UnauthorizedException("Bu cəhdə müdaxilə etmək hüququnuz yoxdur");
        }

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

        if (student != null && submission.getStudent() != null
                && !submission.getStudent().getId().equals(student.getId())) {
            throw new az.testup.exception.UnauthorizedException("Bu cəhdə müdaxilə etmək hüququnuz yoxdur");
        }

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

    /** Grades all answers (creating blank entries for unanswered questions) and snapshots each question. */
    private void gradeAndPrepareAnswers(Submission submission) {
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
            if (answer.getQuestionSnapshot() == null) {
                answer.setQuestionSnapshot(createQuestionSnapshot(question));
            }
        }
    }

    @Transactional
    public SubmissionResponse finalizeSubmission(Long submissionId, User student) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Cəhd tapılmadı"));

        if (submission.getSubmittedAt() != null) {
            return mapToResponse(submission);
        }

        gradeAndPrepareAnswers(submission);

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
        boolean isTemplateExam;
        try {
            isTemplateExam = question.getExam().getTemplateSection() != null;
        } catch (Exception e) {
            isTemplateExam = false;
        }
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
                    normalizeAnswer(correctText).equalsIgnoreCase(normalizeAnswer(answer.getAnswerText()))) {
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
            boolean blankManual = (answer.getAnswerText() == null || answer.getAnswerText().isBlank())
                    && (answer.getAnswerImage() == null || answer.getAnswerImage().isBlank());
            answer.setIsGraded(blankManual);
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
                                && normalizeAnswer(correctAnswers.get(i)).equalsIgnoreCase(normalizeAnswer(studentAnswers.get(i)))) {
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
                        // ID-based grading: a student answer is correct when leftItemId == rightItemId
                        // and that pair is a linked pair. This is independent of text content,
                        // so teacher edits to pair text do not affect past submission grades.
                        java.util.Set<Long> linkedPairIds = linkedPairs.stream()
                            .map(MatchingPair::getId)
                            .collect(Collectors.toSet());
                        java.util.Set<Long> counted = new java.util.HashSet<>();
                        for (MatchingPairAnswerRequest req : studentPairs) {
                            if (req.getLeftItemId() == null || req.getRightItemId() == null) continue;
                            if (req.getLeftItemId().equals(req.getRightItemId())
                                    && linkedPairIds.contains(req.getLeftItemId())) {
                                counted.add(req.getLeftItemId());
                            }
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

    /**
     * Called by scheduler every minute.
     * Auto-submits any in-progress submission whose exam duration has elapsed.
     */
    @Transactional
    public void autoSubmitExpiredExams() {
        List<Submission> active = submissionRepository.findActiveTimedSubmissions();
        if (active.isEmpty()) return;
        java.time.Instant now = java.time.Instant.now();
        int count = 0;
        for (Submission submission : active) {
            long durationSeconds = submission.getExam().getDurationMinutes() * 60L;
            long elapsed = ChronoUnit.SECONDS.between(submission.getStartedAt(), now);
            if (elapsed >= durationSeconds) {
                try {
                    // Set submittedAt to the exact moment the exam should have expired
                    submission.setSubmittedAt(submission.getStartedAt().plusSeconds(durationSeconds));
                    gradeAndPrepareAnswers(submission);
                    finalizeAndSave(submission);
                    count++;
                    log.info("Auto-submitted expired exam: submissionId={}, examId={}",
                            submission.getId(), submission.getExam().getId());
                } catch (Exception e) {
                    log.error("Auto-submit failed for submissionId={}: {}", submission.getId(), e.getMessage());
                }
            }
        }
        if (count > 0) {
            log.info("Auto-submitted {} expired submission(s)", count);
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

        if (submission.getSubmittedAt() == null) {
            submission.setSubmittedAt(java.time.Instant.now());
        }
        submission.setTotalScore(totalScore);
        submission.setMaxScore(examMaxScore);
        submission.setIsFullyGraded(allGraded);

        // Formula-based scoring for template exams
        List<TemplateSection> templateSections;
        try {
            templateSections = submission.getExam().getTemplateSections();
        } catch (Exception e) {
            log.warn("Template sections not available for exam {}, skipping formula score", submission.getExam().getId());
            templateSections = List.of();
        }
        if (templateSections != null && templateSections.size() >= 2) {
            // Multi-section: weighted average of per-section formula percentages
            double totalWeightedPercent = 0.0;
            int totalQCount = 0;
            for (TemplateSection section : templateSections) {
                List<Answer> sectionAnswers = submission.getAnswers().stream()
                        .filter(a -> a.getQuestion() != null
                                && section.getSubjectName().equals(a.getQuestion().getSubjectGroup()))
                        .collect(Collectors.toList());
                Map<String, Double> actualVars = buildFormulaVariables(sectionAnswers, section.getQuestionCount());
                Map<String, Double> maxVars = buildMaxFormulaVariables(sectionAnswers, section.getQuestionCount());
                double rawScore = FormulaEvaluator.evaluate(section.getFormula(), actualVars);
                double maxRaw = FormulaEvaluator.evaluate(section.getFormula(), maxVars);
                double pct = maxRaw > 0 ? Math.max(0.0, rawScore / maxRaw * 100.0) : 0.0;
                totalWeightedPercent += pct * section.getQuestionCount();
                totalQCount += section.getQuestionCount();
            }
            double overall = totalQCount > 0 ? totalWeightedPercent / totalQCount : 0.0;
            submission.setTemplateScorePercent(Math.round(overall * 100.0) / 100.0);
        } else {
            try {
                TemplateSection singleSection = submission.getExam().getTemplateSection();
                if (singleSection != null) {
                    String formula = singleSection.getFormula();
                    int templateQuestionCount = singleSection.getQuestionCount();
                    Map<String, Double> actualVars = buildFormulaVariables(submission.getAnswers(), templateQuestionCount);
                    Map<String, Double> maxVars = buildMaxFormulaVariables(submission.getAnswers(), templateQuestionCount);
                    double rawScore = FormulaEvaluator.evaluate(formula, actualVars);
                    double maxRaw = FormulaEvaluator.evaluate(formula, maxVars);
                    double percent = maxRaw > 0 ? Math.max(0.0, rawScore / maxRaw * 100.0) : 0.0;
                    submission.setTemplateScorePercent(Math.round(percent * 100.0) / 100.0);
                }
            } catch (Exception e) {
                log.warn("Template section not available for exam {}, skipping formula score", submission.getExam().getId());
            }
        }

        submission = submissionRepository.save(submission);

        String subEmail = submission.getStudent() != null ? submission.getStudent().getEmail() : (submission.getGuestName() + " (qonaq)");
        String subName  = submission.getStudent() != null ? submission.getStudent().getFullName() : submission.getGuestName();
        auditLogService.log(AuditAction.EXAM_SUBMITTED, subEmail, subName, "EXAM", submission.getExam().getTitle(),
                String.format("Bal: %.2f / %.2f", submission.getTotalScore(), submission.getMaxScore()));

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
    public SubmissionResponse getSubmissionById(Long submissionId, User student) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Nəticə tapılmadı"));

        // Verify that the student owns this submission or is viewing their own result
        if (student != null && submission.getStudent() != null && !submission.getStudent().getId().equals(student.getId())) {
            throw new UnauthorizedException("Bu nəticəni görməmə icazəniz yoxdur");
        }

        return mapToResponse(submission);
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
                .filter(sub -> !Boolean.TRUE.equals(sub.getHiddenFromTeacher()))
                .filter(sub -> sub.getSubmittedAt() != null)
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

        Exam exam = submission.getExam();
        boolean isAdmin = teacher.getRole() == az.testup.enums.Role.ADMIN;
        if (!isAdmin && (exam.getTeacher() == null || !exam.getTeacher().getId().equals(teacher.getId()))) {
            throw new az.testup.exception.UnauthorizedException("Bu imtahanı yoxlamaq hüququnuz yoxdur");
        }

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
        List<TemplateSection> gradeSections = submission.getExam().getTemplateSections();
        if (gradeSections != null && gradeSections.size() >= 2) {
            double totalWeightedPercent = 0.0;
            int totalQCount = 0;
            for (TemplateSection gradeSection : gradeSections) {
                List<Answer> sectionAnswers = submission.getAnswers().stream()
                        .filter(a -> a.getQuestion() != null
                                && gradeSection.getSubjectName().equals(a.getQuestion().getSubjectGroup()))
                        .collect(Collectors.toList());
                Map<String, Double> secActualVars = buildFormulaVariables(sectionAnswers, gradeSection.getQuestionCount());
                Map<String, Double> secMaxVars = buildMaxFormulaVariables(sectionAnswers, gradeSection.getQuestionCount());
                double secRaw = FormulaEvaluator.evaluate(gradeSection.getFormula(), secActualVars);
                double secMaxRaw = FormulaEvaluator.evaluate(gradeSection.getFormula(), secMaxVars);
                double pct = secMaxRaw > 0 ? Math.max(0.0, secRaw / secMaxRaw * 100.0) : 0.0;
                totalWeightedPercent += pct * gradeSection.getQuestionCount();
                totalQCount += gradeSection.getQuestionCount();
            }
            double overall = totalQCount > 0 ? totalWeightedPercent / totalQCount : 0.0;
            submission.setTemplateScorePercent(Math.round(overall * 100.0) / 100.0);
        } else if (submission.getExam().getTemplateSection() != null) {
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

        List<Submission> submissions = submissionRepository.findByExamId(examId).stream()
                .filter(sub -> !Boolean.TRUE.equals(sub.getHiddenFromTeacher()))
                .collect(Collectors.toList());

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
        double mcq_score_sum = 0.0;  // sum of points from correct MCQ answers
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
                else if (score >= pts) { mcq_correct++; mcq_score_sum += score; }
                else { mcq_wrong++; }
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
        v.put("s", mcq_score_sum);   // sum of points from correct MCQ answers
        v.put("w", (double) mcq_wrong);   // count of wrong MCQ answers (olympiad penalty: s - w/4.0)
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
        double mcq_score_max = answers.stream()
                .filter(a -> a.getQuestion().getQuestionType() == QuestionType.MCQ
                        || a.getQuestion().getQuestionType() == QuestionType.TRUE_FALSE)
                .mapToDouble(a -> a.getQuestion().getPoints() != null ? a.getQuestion().getPoints() : 1.0)
                .sum();
        Map<String, Double> v = new HashMap<>();
        v.put("a", (double) mcq);    v.put("b", 0.0); v.put("c", 0.0);
        v.put("s", mcq_score_max); // max possible MCQ score (all correct)
        v.put("w", 0.0);           // max scenario: no wrong answers
        v.put("d", (double) multi);  v.put("e", 0.0);
        v.put("f", (double) open);   v.put("g", 0.0);
        v.put("l", (double) manual); v.put("m", 0.0);
        v.put("h", (double) fill);   v.put("i", 0.0);
        v.put("j", (double) match);  v.put("k", 0.0);
        v.put("n", (double) totalQuestionCount);
        return v;
    }

    /** Returns true when the student left this answer completely blank (nothing selected/typed). */
    private boolean isBlankAnswer(Answer a) {
        if (a.getQuestion() == null) return false;
        return switch (a.getQuestion().getQuestionType()) {
            case MCQ, TRUE_FALSE        -> a.getSelectedOptionId() == null;
            case MULTI_SELECT           -> a.getSelectedOptionIdsJson() == null
                                           || a.getSelectedOptionIdsJson().isBlank()
                                           || a.getSelectedOptionIdsJson().equals("[]");
            case OPEN_AUTO, OPEN_MANUAL -> (a.getAnswerText() == null || a.getAnswerText().isBlank())
                                           && (a.getAnswerImage() == null || a.getAnswerImage().isBlank());
            case FILL_IN_THE_BLANK      -> a.getAnswerText() == null
                                           || a.getAnswerText().isBlank()
                                           || a.getAnswerText().equals("[]");
            case MATCHING               -> a.getMatchingAnswerJson() == null
                                           || a.getMatchingAnswerJson().isBlank()
                                           || a.getMatchingAnswerJson().equals("[]");
            default                     -> false;
        };
    }

    private SubmissionResponse mapToResponse(Submission submission) {
        int totalQuestions = submission.getExam().getQuestions().size();

        int correct = (int) submission.getAnswers().stream()
                .filter(a -> a.getScore() != null && a.getScore() > 0.0)
                .count();

        int pending = (int) submission.getAnswers().stream()
                .filter(a -> a.getQuestion() != null
                        && a.getQuestion().getQuestionType() == QuestionType.OPEN_MANUAL
                        && !isBlankAnswer(a)
                        && !Boolean.TRUE.equals(a.getIsGraded()))
                .count();

        int skipped = (int) submission.getAnswers().stream()
                .filter(a -> a.getQuestion() != null
                        && a.getQuestion().getQuestionType() != QuestionType.OPEN_MANUAL
                        && (a.getScore() == null || a.getScore() == 0.0)
                        && isBlankAnswer(a))
                .count();
        // Unanswered OPEN_MANUAL questions also count as skipped
        skipped += (int) submission.getAnswers().stream()
                .filter(a -> a.getQuestion() != null
                        && a.getQuestion().getQuestionType() == QuestionType.OPEN_MANUAL
                        && isBlankAnswer(a))
                .count();
        // Also count questions that have no Answer record at all
        skipped += Math.max(0, totalQuestions - submission.getAnswers().size());

        int wrong = (int) submission.getAnswers().stream()
                .filter(a -> a.getQuestion() != null
                        && a.getQuestion().getQuestionType() != QuestionType.OPEN_MANUAL
                        && (a.getScore() == null || a.getScore() == 0.0)
                        && !isBlankAnswer(a))
                .count();

        var exam = submission.getExam();
        SubmissionResponse response = SubmissionResponse.builder()
                .id(submission.getId())
                .examId(exam.getId())
                .examTitle(exam.getTitle())
                .studentName(submission.getStudent() != null ? submission.getStudent().getFullName() : submission.getGuestName())
                .totalScore(submission.getTotalScore())
                .maxScore(submission.getMaxScore())
                .isFullyGraded(submission.getIsFullyGraded())
                .rating(submission.getRating())
                .startedAt(submission.getStartedAt())
                .submittedAt(submission.getSubmittedAt())
                .durationMinutes(exam.getDurationMinutes())
                .templateScorePercent(submission.getTemplateScorePercent())
                .correctCount(correct)
                .wrongCount(wrong)
                .skippedCount(skipped)
                .pendingManualCount(pending)
                .subjects(exam.getSubjects())
                .tags(exam.getTags())
                .examType(exam.getExamType() != null ? exam.getExamType().name() : null)
                .questionCount(totalQuestions)
                .teacherName(exam.getTeacher() != null ? exam.getTeacher().getFullName() : null)
                .build();

        List<SubjectStatResponse> stats = buildSubjectStats(submission);
        response.setSubjectStats(stats);
        if (stats != null && stats.stream().anyMatch(s -> s.getSectionMaxScore() != null)) {
            response.setTemplateTotalScore(
                stats.stream().filter(s -> s.getSectionScore() != null).mapToDouble(SubjectStatResponse::getSectionScore).sum());
            response.setTemplateTotalMaxScore(
                stats.stream().filter(s -> s.getSectionMaxScore() != null).mapToDouble(SubjectStatResponse::getSectionMaxScore).sum());
        }
        return response;
    }

    @Transactional
    public void hideSubmission(Long submissionId, User teacher) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Nəticə tapılmadı"));
        Exam exam = submission.getExam();
        boolean isOwner = exam.getTeacher() != null && exam.getTeacher().getId().equals(teacher.getId());
        boolean isAdmin = teacher.getRole() == Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new UnauthorizedException("Bu əməliyyat üçün icazəniz yoxdur");
        }
        submission.setHiddenFromTeacher(true);
        submissionRepository.save(submission);
    }

    private List<SubjectStatResponse> buildSubjectStats(Submission submission) {
        Exam exam = submission.getExam();
        List<TemplateSection> sections;
        try {
            sections = exam.getTemplateSections();
        } catch (Exception e) {
            sections = List.of();
        }
        boolean isMultiSection = sections != null && sections.size() >= 2;

        // Determine subject list: either from template sections or from exam subjects
        List<String> subjects = isMultiSection
                ? sections.stream().map(TemplateSection::getSubjectName).collect(Collectors.toList())
                : exam.getSubjects();

        if (subjects == null || subjects.size() < 2) return List.of();

        // Build answer lookup: questionId -> Answer
        Map<Long, Answer> answerByQuestionId = submission.getAnswers().stream()
                .filter(a -> a.getQuestion() != null)
                .collect(Collectors.toMap(
                        a -> a.getQuestion().getId(),
                        a -> a,
                        (a, b) -> a));

        // Group questions by subjectGroup, preserving subject order
        Map<String, List<Question>> bySubject = new LinkedHashMap<>();
        for (String subject : subjects) {
            bySubject.put(subject, new ArrayList<>());
        }
        for (Question q : exam.getQuestions()) {
            String group = q.getSubjectGroup() != null ? q.getSubjectGroup() : "";
            bySubject.computeIfAbsent(group, k -> new ArrayList<>()).add(q);
        }

        // Build section lookup by subjectName (for formula access)
        Map<String, TemplateSection> sectionByName = isMultiSection
                ? sections.stream().collect(Collectors.toMap(TemplateSection::getSubjectName, s -> s, (a, b) -> a))
                : Map.of();

        List<SubjectStatResponse> stats = new ArrayList<>();
        for (Map.Entry<String, List<Question>> entry : bySubject.entrySet()) {
            String subjectName = entry.getKey();
            if (subjectName.isEmpty() && !subjects.isEmpty()) subjectName = subjects.get(0);
            List<Question> questions = entry.getValue();
            if (questions.isEmpty()) continue;

            int qCount   = questions.size();
            int correct  = 0, wrong = 0, skipped = 0, pending = 0;
            double totalScore = 0.0, maxScore = 0.0;
            List<Answer> sectionAnswers = new ArrayList<>();

            for (Question q : questions) {
                maxScore += q.getPoints() != null ? q.getPoints() : 0.0;
                Answer a = answerByQuestionId.get(q.getId());
                if (a == null) {
                    skipped++;
                    continue;
                }
                sectionAnswers.add(a);
                boolean blank = isBlankAnswer(a);
                if (q.getQuestionType() == QuestionType.OPEN_MANUAL) {
                    if (blank) skipped++;
                    else if (!Boolean.TRUE.equals(a.getIsGraded())) pending++;
                    else if (a.getScore() != null && a.getScore() > 0.0) {
                        correct++;
                        totalScore += a.getScore();
                    } else {
                        wrong++;
                    }
                } else if (blank || a.getScore() == null || a.getScore() == 0.0) {
                    if (blank) skipped++;
                    else wrong++;
                } else {
                    correct++;
                    totalScore += a.getScore();
                }
            }

            // Compute formulaPercent for multi-section template exams
            Double formulaPercent = null;
            if (isMultiSection) {
                TemplateSection section = sectionByName.get(subjectName);
                if (section != null) {
                    Map<String, Double> actualVars = buildFormulaVariables(sectionAnswers, section.getQuestionCount());
                    Map<String, Double> maxVars = buildMaxFormulaVariables(sectionAnswers, section.getQuestionCount());
                    double rawScore = FormulaEvaluator.evaluate(section.getFormula(), actualVars);
                    double maxRaw = FormulaEvaluator.evaluate(section.getFormula(), maxVars);
                    formulaPercent = maxRaw > 0 ? Math.max(0.0, Math.round(rawScore / maxRaw * 10000.0) / 100.0) : 0.0;
                }
            }

            // sectionScore = formulaPercent/100 * section.maxScore (if both available)
            Double sectionMaxScore = null;
            Double sectionScore = null;
            if (isMultiSection) {
                TemplateSection section = sectionByName.get(subjectName);
                if (section != null && section.getMaxScore() != null && formulaPercent != null) {
                    sectionMaxScore = section.getMaxScore();
                    sectionScore = formulaPercent / 100.0 * sectionMaxScore;
                }
            }

            stats.add(SubjectStatResponse.builder()
                    .subjectName(subjectName)
                    .questionCount(qCount)
                    .correctCount(correct)
                    .wrongCount(wrong)
                    .skippedCount(skipped)
                    .pendingManualCount(pending)
                    .totalScore(totalScore)
                    .maxScore(maxScore)
                    .formulaPercent(formulaPercent)
                    .sectionScore(sectionScore)
                    .sectionMaxScore(sectionMaxScore)
                    .build());
        }
        return stats;
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
        // Use Instant.now() which is timezone-independent (UTC)
        Long remainingSeconds = null;
        if (exam.getDurationMinutes() != null && exam.getDurationMinutes() > 0 && submission.getStartedAt() != null) {
            long elapsed = java.time.temporal.ChronoUnit.SECONDS.between(submission.getStartedAt(), java.time.Instant.now());
            remainingSeconds = exam.getDurationMinutes() * 60L - elapsed;
        }

        return SessionDetailsResponse.builder()
                .submissionId(submission.getId())
                .examTitle(exam.getTitle())
                .durationMinutes(exam.getDurationMinutes())
                .startedAt(submission.getStartedAt())
                .submittedAt(submission.getSubmittedAt())
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

        // Create a map of question ID to answer for quick lookup
        Map<Long, Answer> answerByQuestionId = submission.getAnswers().stream()
                .collect(Collectors.toMap(a -> a.getQuestion().getId(), a -> a));

        // Get all questions from exam (standalone + passage) and sort by orderIndex
        List<Question> allQuestions = exam.getQuestions().stream()
                .sorted(java.util.Comparator.comparing(q -> q.getOrderIndex() != null ? q.getOrderIndex() : 0))
                .collect(Collectors.toList());

        List<QuestionReviewResponse> reviewQuestions = allQuestions.stream()
                .map(q -> {
                    Answer studentAnswer = answerByQuestionId.get(q.getId());

                    QuestionReviewResponse.QuestionReviewResponseBuilder qBuilder = QuestionReviewResponse.builder()
                            .id(q.getId())
                            .passageId(q.getPassage() != null ? q.getPassage().getId() : null)
                            .subjectGroup(q.getSubjectGroup());

                    if (studentAnswer != null && studentAnswer.getQuestionSnapshot() != null) {
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

                    if (studentAnswer != null) {
                        List<Long> selectedOptionIds = new ArrayList<>();
                        if (q.getQuestionType() == QuestionType.MULTI_SELECT && studentAnswer.getSelectedOptionIdsJson() != null) {
                            try {
                                selectedOptionIds = objectMapper.readValue(studentAnswer.getSelectedOptionIdsJson(),
                                    objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
                            } catch (Exception e) {}
                        } else if (studentAnswer.getSelectedOptionId() != null) {
                            selectedOptionIds.add(studentAnswer.getSelectedOptionId());
                        }

                        qBuilder.studentAnswerText(studentAnswer.getAnswerText())
                                .studentAnswerImage(studentAnswer.getAnswerImage())
                                .studentSelectedOptionId(studentAnswer.getSelectedOptionId())
                                .studentSelectedOptionIds(selectedOptionIds)
                                .studentMatchingAnswerJson(studentAnswer.getMatchingAnswerJson())
                                .awardedScore(studentAnswer.getScore() != null ? studentAnswer.getScore() : 0.0)
                                .isGraded(studentAnswer.getIsGraded() != null ? studentAnswer.getIsGraded() : true)
                                .feedback(studentAnswer.getFeedback());
                    } else {
                        // Question was not answered - set default values
                        qBuilder.studentAnswerText(null)
                                .studentAnswerImage(null)
                                .studentSelectedOptionId(null)
                                .studentSelectedOptionIds(new ArrayList<>())
                                .studentMatchingAnswerJson(null)
                                .awardedScore(0.0)
                                .isGraded(false)
                                .feedback(null);
                    }

                    return qBuilder.build();
                }).collect(Collectors.toList());

        int ungradedCount = (int) submission.getAnswers().stream()
                .filter(a -> Boolean.FALSE.equals(a.getIsGraded()))
                .count();

        List<SubjectStatResponse> reviewStats = buildSubjectStats(submission);
        Double reviewTotalScore = null, reviewTotalMaxScore = null;
        if (reviewStats != null && reviewStats.stream().anyMatch(s -> s.getSectionMaxScore() != null)) {
            reviewTotalScore    = reviewStats.stream().filter(s -> s.getSectionScore()    != null).mapToDouble(SubjectStatResponse::getSectionScore).sum();
            reviewTotalMaxScore = reviewStats.stream().filter(s -> s.getSectionMaxScore() != null).mapToDouble(SubjectStatResponse::getSectionMaxScore).sum();
        }

        return SubmissionReviewResponse.builder()
                .id(submission.getId())
                .examId(exam.getId())
                .examTitle(exam.getTitle())
                .examSubject(exam.getSubjects() != null && !exam.getSubjects().isEmpty() ? exam.getSubjects().get(0) : null)
                .totalScore(submission.getTotalScore())
                .maxScore(submission.getMaxScore())
                .startedAt(submission.getStartedAt())
                .submittedAt(submission.getSubmittedAt())
                .isFullyGraded(submission.getIsFullyGraded())
                .ungradedCount(ungradedCount)
                .rating(submission.getRating())
                .questions(reviewQuestions)
                .templateScorePercent(submission.getTemplateScorePercent())
                .templateTotalScore(reviewTotalScore)
                .templateTotalMaxScore(reviewTotalMaxScore)
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

    /**
     * Strips LaTeX math delimiters ($$...$$, $...$) from an answer so that
     * a correct answer written with the math keyboard (e.g. "$$29$$") matches
     * a student's plain-text answer (e.g. "29").
     */
    private static String normalizeAnswer(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // Strip all $$...$$ and $...$ wrappers, keep only the inner content
        s = s.replaceAll("\\$\\$([^$]*)\\$\\$", "$1");
        s = s.replaceAll("\\$([^$]*)\\$", "$1");
        return s.trim();
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

    @Transactional(readOnly = true)
    public byte[] generateExamResultsExcel(Long examId, User teacher) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        boolean isOwner = exam.getTeacher() != null && exam.getTeacher().getId().equals(teacher.getId());
        boolean isAdminUser = teacher.getRole() == Role.ADMIN;
        if (!isOwner && !isAdminUser) {
            throw new UnauthorizedException("Bu imtahanın nəticələrini görməyə icazəniz yoxdur");
        }

        boolean isPaidExam = exam.getPrice() != null && exam.getPrice().compareTo(java.math.BigDecimal.ZERO) > 0;
        Map<Long, java.math.BigDecimal> purchaseMap = isPaidExam
                ? examPurchaseRepository.findByExamId(examId).stream()
                    .filter(p -> p.getUser() != null)
                    .collect(Collectors.toMap(p -> p.getUser().getId(), az.testup.entity.ExamPurchase::getAmountPaid, (a, b) -> a))
                : Map.of();

        // Load raw entities so we can compute subject scores directly from answers
        List<Submission> rawSubs = submissionRepository.findByExamId(examId).stream()
                .filter(sub -> !Boolean.TRUE.equals(sub.getHiddenFromTeacher()))
                .filter(sub -> sub.getSubmittedAt() != null)
                .collect(Collectors.toList());

        // Determine ordered subject list from exam (same logic as buildSubjectStats)
        List<TemplateSection> sections;
        try { sections = exam.getTemplateSections(); } catch (Exception e) { sections = List.of(); }
        boolean isMultiSection = sections != null && sections.size() >= 2;
        List<String> subjectNames = isMultiSection
                ? sections.stream().map(TemplateSection::getSubjectName).collect(Collectors.toList())
                : (exam.getSubjects() != null ? exam.getSubjects() : List.of());
        // Filter to subjects that have at least 2 entries
        boolean hasSubjects = subjectNames.size() >= 2;

        // Build per-question subject mapping: questionId -> subjectName
        // Use the same grouping as buildSubjectStats: subjectGroup, falling back to first subject for null
        Map<Long, String> questionSubject = new HashMap<>();
        if (hasSubjects) {
            for (Question q : exam.getQuestions()) {
                String grp = q.getSubjectGroup() != null && !q.getSubjectGroup().isEmpty()
                        ? q.getSubjectGroup() : subjectNames.get(0);
                questionSubject.put(q.getId(), grp);
            }
        }

        // Build per-subject maxScore from exam questions (constant across all submissions)
        Map<String, Double> subjectMaxScore = new LinkedHashMap<>();
        if (hasSubjects) {
            for (String name : subjectNames) subjectMaxScore.put(name, 0.0);
            for (Question q : exam.getQuestions()) {
                String sub = questionSubject.get(q.getId());
                if (sub != null) {
                    double pts = q.getPoints() != null ? q.getPoints() : 0.0;
                    subjectMaxScore.merge(sub, pts, Double::sum);
                }
            }
        }

        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter
                .ofPattern("dd.MM.yyyy HH:mm")
                .withZone(java.time.ZoneId.of("Asia/Baku"));

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.CellStyle boldStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font boldFont = wb.createFont();
            boldFont.setBold(true);
            boldStyle.setFont(boldFont);

            org.apache.poi.ss.usermodel.CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            org.apache.poi.ss.usermodel.Font hFont = wb.createFont();
            hFont.setBold(true);
            headerStyle.setFont(hFont);

            // Sheet 1: Statistika
            org.apache.poi.ss.usermodel.Sheet statsSheet = wb.createSheet("Statistika");
            statsSheet.setColumnWidth(0, 7000);
            statsSheet.setColumnWidth(1, 10000);

            long subCount = rawSubs.size();
            double avgScore = rawSubs.stream().mapToDouble(r -> r.getTotalScore() != null ? r.getTotalScore() : 0).average().orElse(0);
            double maxScore = rawSubs.stream().mapToDouble(r -> r.getMaxScore() != null ? r.getMaxScore() : 0).max().orElse(0);
            double avgRating = rawSubs.stream().filter(r -> r.getRating() != null).mapToDouble(r -> r.getRating()).average().orElse(0);

            Object[][] statsData = {
                {"İmtahan Adı", exam.getTitle()},
                {"İmtahan ID", String.valueOf(examId)},
                {"Ümumi İştirakçı", subCount},
                {"Orta Bal", Math.round(avgScore * 100.0) / 100.0},
                {"Maksimum Bal", maxScore},
                {"Orta Reytinq", avgRating > 0 ? Math.round(avgRating * 100.0) / 100.0 : "–"},
            };
            int sRow = 0;
            for (Object[] rowData : statsData) {
                org.apache.poi.ss.usermodel.Row row = statsSheet.createRow(sRow++);
                org.apache.poi.ss.usermodel.Cell lbl = row.createCell(0);
                lbl.setCellValue((String) rowData[0]);
                lbl.setCellStyle(boldStyle);
                org.apache.poi.ss.usermodel.Cell val = row.createCell(1);
                if (rowData[1] instanceof Number) val.setCellValue(((Number) rowData[1]).doubleValue());
                else val.setCellValue(String.valueOf(rowData[1]));
            }

            // Sheet 2: İştirakçılar
            org.apache.poi.ss.usermodel.Sheet partSheet = wb.createSheet("İştirakçılar");

            List<String> headers = new ArrayList<>(java.util.Arrays.asList("#", "İştirakçı", "Başlayıb", "Xərclənən Vaxt"));
            if (hasSubjects) {
                subjectNames.forEach(headers::add);
                headers.add("Toplam");
            } else {
                headers.add("Bal");
            }
            headers.add("Reytinq");
            if (isPaidExam) headers.add("Ödəniş");
            headers.add("Status");

            org.apache.poi.ss.usermodel.Row hRow = partSheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                org.apache.poi.ss.usermodel.Cell c = hRow.createCell(i);
                c.setCellValue(headers.get(i));
                c.setCellStyle(headerStyle);
            }
            partSheet.setColumnWidth(0, 1200);
            partSheet.setColumnWidth(1, 7000);
            partSheet.setColumnWidth(2, 5500);
            partSheet.setColumnWidth(3, 3800);
            int ci = 4;
            if (hasSubjects) {
                for (int i = 0; i < subjectNames.size(); i++) partSheet.setColumnWidth(ci++, 4500);
                partSheet.setColumnWidth(ci++, 4000);
            } else {
                partSheet.setColumnWidth(ci++, 3500);
            }
            partSheet.setColumnWidth(ci++, 2800);
            if (isPaidExam) partSheet.setColumnWidth(ci++, 5500);
            partSheet.setColumnWidth(ci, 4500);

            for (int idx = 0; idx < rawSubs.size(); idx++) {
                Submission sub = rawSubs.get(idx);
                org.apache.poi.ss.usermodel.Row row = partSheet.createRow(idx + 1);
                int col = 0;

                String studentName = sub.getStudent() != null ? sub.getStudent().getFullName() : sub.getGuestName();
                row.createCell(col++).setCellValue(idx + 1);
                row.createCell(col++).setCellValue(studentName != null ? studentName : "");
                row.createCell(col++).setCellValue(sub.getStartedAt() != null ? dtf.format(sub.getStartedAt()) : "–");

                String duration = "–";
                if (sub.getStartedAt() != null && sub.getSubmittedAt() != null) {
                    long sec = Math.abs(ChronoUnit.SECONDS.between(sub.getStartedAt(), sub.getSubmittedAt()));
                    duration = (sec / 60) + "dk " + (sec % 60) + "sn";
                }
                row.createCell(col++).setCellValue(duration);

                if (hasSubjects) {
                    // Compute per-subject totalScore directly from answers
                    Map<String, Double> subjectEarned = new LinkedHashMap<>();
                    for (String name : subjectNames) subjectEarned.put(name, 0.0);

                    for (Answer ans : sub.getAnswers()) {
                        if (ans.getQuestion() == null || ans.getScore() == null || ans.getScore() <= 0.0) continue;
                        String subj = questionSubject.get(ans.getQuestion().getId());
                        if (subj != null && subjectEarned.containsKey(subj)) {
                            subjectEarned.merge(subj, ans.getScore(), Double::sum);
                        }
                    }

                    double totalEarned = 0.0;
                    double totalMax = 0.0;
                    for (String name : subjectNames) {
                        double earned = Math.round(subjectEarned.getOrDefault(name, 0.0) * 100.0) / 100.0;
                        double maks = Math.round(subjectMaxScore.getOrDefault(name, 0.0) * 100.0) / 100.0;
                        row.createCell(col++).setCellValue(earned + "/" + maks);
                        totalEarned += earned;
                        totalMax += maks;
                    }
                    row.createCell(col++).setCellValue(
                            Math.round(totalEarned * 100.0) / 100.0 + "/" + Math.round(totalMax * 100.0) / 100.0);
                } else {
                    double sc = sub.getTotalScore() != null ? sub.getTotalScore() : 0;
                    double mx = sub.getMaxScore() != null ? sub.getMaxScore() : 0;
                    row.createCell(col++).setCellValue(sc + "/" + mx);
                }

                row.createCell(col++).setCellValue(sub.getRating() != null ? sub.getRating() + "/5" : "–");

                if (isPaidExam) {
                    Long sid = sub.getStudent() != null ? sub.getStudent().getId() : null;
                    boolean hasPaid = sid != null && purchaseMap.containsKey(sid);
                    java.math.BigDecimal paid = sid != null ? purchaseMap.get(sid) : null;
                    String pay = hasPaid
                            ? (paid != null ? "Ödənib (" + paid.setScale(2, java.math.RoundingMode.HALF_UP) + " ₼)" : "Ödənib")
                            : "Ödənilməyib";
                    row.createCell(col++).setCellValue(pay);
                }

                String status = Boolean.TRUE.equals(sub.getIsFullyGraded()) ? "Tam Yoxlanılıb" : "Yoxlanılır";
                row.createCell(col++).setCellValue(status);
            }

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Excel faylı yaradılarkən xəta baş verdi", e);
        }
    }
}
