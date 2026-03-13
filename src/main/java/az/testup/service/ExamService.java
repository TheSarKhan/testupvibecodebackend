package az.testup.service;

import az.testup.dto.request.ExamRequest;
import az.testup.dto.request.MatchingPairRequest;
import az.testup.dto.request.OptionRequest;
import az.testup.dto.request.PassageRequest;
import az.testup.dto.request.QuestionRequest;
import az.testup.dto.response.*;
import az.testup.entity.*;
import az.testup.entity.TemplateSection;
import az.testup.entity.ExamPurchase;
import az.testup.entity.StudentSavedExam;
import az.testup.enums.ExamStatus;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.exception.UnauthorizedException;
import az.testup.repository.*;
import az.testup.util.CodeGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExamService {

    private final ExamRepository examRepository;
    private final TemplateRepository templateRepository;
    private final TemplateSectionRepository templateSectionRepository;
    private final ExamPurchaseRepository examPurchaseRepository;
    private final StudentSavedExamRepository studentSavedExamRepository;
    private final SubscriptionValidatorService subscriptionValidatorService;

    public Exam getExamEntityById(Long id) {
        return examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
    }


    @Transactional
    public ExamResponse createExam(ExamRequest request, User teacher) {
        // Validate limits
        subscriptionValidatorService.validateMonthlyExamCreation(teacher.getId());
        subscriptionValidatorService.validateTotalSavedExams(teacher.getId());

        int questionCount = 0;
        boolean hasImages = false;

        if (request.getQuestions() != null) {
            questionCount += request.getQuestions().size();
            for (QuestionRequest qr : request.getQuestions()) {
                if (qr.getAttachedImage() != null && !qr.getAttachedImage().isEmpty()) {
                    hasImages = true;
                }
            }
        }

        if (request.getPassages() != null && !request.getPassages().isEmpty()) {
            subscriptionValidatorService.validateAddPassageQuestion(teacher.getId());
            for (PassageRequest pr : request.getPassages()) {
                if (pr.getAttachedImage() != null && !pr.getAttachedImage().isEmpty()) {
                    hasImages = true;
                }
                if (pr.getQuestions() != null) {
                    questionCount += pr.getQuestions().size();
                    for (QuestionRequest qr : pr.getQuestions()) {
                        if (qr.getAttachedImage() != null && !qr.getAttachedImage().isEmpty()) {
                            hasImages = true;
                        }
                    }
                }
            }
        }

        subscriptionValidatorService.validateMaxQuestionsPerExam(teacher.getId(), questionCount);

        if (hasImages) {
            subscriptionValidatorService.validateAddImage(teacher.getId());
        }
        if (request.getSubjects() != null && request.getSubjects().size() > 1) {
            subscriptionValidatorService.validateMultipleSubjects(teacher.getId());
        }
        // If plan doesn't allow selecting duration, silently ignore the value (no error)
        Integer effectiveDuration = request.getDurationMinutes();
        try {
            if (effectiveDuration != null && effectiveDuration > 0) {
                subscriptionValidatorService.validateSelectExamDuration(teacher.getId());
            }
        } catch (az.testup.exception.SubscriptionLimitExceededException e) {
            // Plan doesn't allow exam duration — silently set to no timer
            effectiveDuration = null;
        }
        if (request.getTemplateId() != null || request.getTemplateSectionId() != null) {
            subscriptionValidatorService.validateUseTemplateExams(teacher.getId());
        }

        Exam exam = Exam.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .subjects(request.getSubjects() != null ? new ArrayList<>(request.getSubjects()) : new ArrayList<>())
                .visibility(request.getVisibility())
                .examType(request.getExamType())
                .status(request.getStatus())
                .durationMinutes(effectiveDuration)
                .teacher(teacher)
                .shareLink(CodeGenerator.generateShareLink())
                .tags(request.getTags() != null ? new ArrayList<>(request.getTags()) : new ArrayList<>())
                .build();

        if (request.getTemplateId() != null) {
            Template template = templateRepository.findById(request.getTemplateId())
                    .orElseThrow(() -> new ResourceNotFoundException("Template not found"));
            exam.setTemplate(template);
        }

        if (request.getTemplateSectionId() != null) {
            TemplateSection section = templateSectionRepository.findById(request.getTemplateSectionId())
                    .orElseThrow(() -> new ResourceNotFoundException("Şablon bölməsi tapılmadı"));
            exam.setTemplateSection(section);
        }

        if (request.getQuestions() != null) {
            for (QuestionRequest qReq : request.getQuestions()) {
                exam.getQuestions().add(mapToQuestion(qReq, exam, null));
            }
        }

        if (request.getPassages() != null) {
            for (PassageRequest pReq : request.getPassages()) {
                addPassageToExam(pReq, exam);
            }
        }

        Exam savedExam = examRepository.save(exam);
        
        // Record usage
        subscriptionValidatorService.recordMonthlyExamCreated(teacher.getId());

        return mapToResponse(savedExam);
    }

    public List<ExamResponse> getTeacherExams(User teacher) {
        return examRepository.findByTeacher(teacher).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Returns all exams published to the site catalog by admin (sitePublished=true, not cancelled/draft).
     */
    public List<ExamResponse> getPublicExams() {
        return examRepository.findAll().stream()
                .filter(e -> e.isSitePublished()
                        && e.getStatus() != ExamStatus.CANCELLED
                        && e.getStatus() != ExamStatus.DRAFT)
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Records a purchase of a paid exam by a student.
     * In this implementation, payment is assumed to have been completed externally.
     */
    @Transactional
    public void purchaseExam(String shareLink, User student) {
        Exam exam = examRepository.findByShareLink(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        if (examPurchaseRepository.existsByUserIdAndExamId(student.getId(), exam.getId())) {
            return; // already purchased — idempotent
        }

        java.math.BigDecimal amount = exam.getPrice() != null ? exam.getPrice() : java.math.BigDecimal.ZERO;

        ExamPurchase purchase = ExamPurchase.builder()
                .user(student)
                .exam(exam)
                .amountPaid(amount)
                .build();
        examPurchaseRepository.save(purchase);

        // Auto-save to student depot on purchase
        if (!studentSavedExamRepository.existsByStudentIdAndExamId(student.getId(), exam.getId())) {
            studentSavedExamRepository.save(StudentSavedExam.builder()
                    .student(student)
                    .exam(exam)
                    .build());
        }
    }

    /**
     * Returns whether the given user has purchased a specific exam.
     */
    @Transactional(readOnly = true)
    public boolean hasPurchased(String shareLink, User user) {
        Exam exam = examRepository.findByShareLink(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        if (exam.getPrice() == null || exam.getPrice().compareTo(java.math.BigDecimal.ZERO) == 0) {
            return true; // free exam
        }
        return examPurchaseRepository.existsByUserIdAndExamId(user.getId(), exam.getId());
    }

    public ExamResponse getExamById(Long id, User teacher) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        if (!exam.getTeacher().getId().equals(teacher.getId())) {
            throw new RuntimeException("Bu imtahana giriş icazəniz yoxdur");
        }

        return mapToResponse(exam);
    }

    @Transactional
    public ExamResponse updateExam(Long id, ExamRequest request, User teacher) {
        subscriptionValidatorService.validateExamEditing(teacher.getId());

        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        if (!exam.getTeacher().getId().equals(teacher.getId())) {
            throw new RuntimeException("Bu imtahanı redaktə etmək icazəniz yoxdur");
        }

        exam.setTitle(request.getTitle());
        exam.setDescription(request.getDescription());
        exam.getSubjects().clear();
        if (request.getSubjects() != null) exam.getSubjects().addAll(request.getSubjects());
        exam.setVisibility(request.getVisibility());
        exam.setExamType(request.getExamType());
        exam.setStatus(request.getStatus());
        exam.setDurationMinutes(request.getDurationMinutes());

        exam.getTags().clear();
        if (request.getTags() != null) {
            exam.getTags().addAll(request.getTags());
        }

        if (request.getTemplateSectionId() != null) {
            TemplateSection section = templateSectionRepository.findById(request.getTemplateSectionId())
                    .orElseThrow(() -> new ResourceNotFoundException("Şablon bölməsi tapılmadı"));
            exam.setTemplateSection(section);
        } else {
            exam.setTemplateSection(null);
        }

        // --- Handle standalone questions ---
        if (request.getQuestions() != null) {
            List<Long> requestQuestionIds = request.getQuestions().stream()
                    .map(QuestionRequest::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // Remove standalone questions no longer in the request
            exam.getQuestions().removeIf(q -> q.getPassage() == null && !requestQuestionIds.contains(q.getId()));

            for (QuestionRequest qReq : request.getQuestions()) {
                if (qReq.getId() != null) {
                    Question existing = exam.getQuestions().stream()
                            .filter(q -> q.getId().equals(qReq.getId()))
                            .findFirst().orElse(null);
                    if (existing != null) {
                        updateQuestionFromRequest(existing, qReq);
                    } else {
                        exam.getQuestions().add(mapToQuestion(qReq, exam, null));
                    }
                } else {
                    exam.getQuestions().add(mapToQuestion(qReq, exam, null));
                }
            }
        } else {
            exam.getQuestions().removeIf(q -> q.getPassage() == null);
        }

        // --- Handle passages ---
        if (request.getPassages() != null) {
            List<Long> requestPassageIds = request.getPassages().stream()
                    .map(PassageRequest::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // Remove passages not in request (also remove their questions from exam.questions)
            List<Passage> passagesToRemove = exam.getPassages().stream()
                    .filter(p -> !requestPassageIds.contains(p.getId()))
                    .collect(Collectors.toList());
            for (Passage p : passagesToRemove) {
                final Long passageId = p.getId();
                exam.getQuestions().removeIf(q -> q.getPassage() != null && q.getPassage().getId().equals(passageId));
                exam.getPassages().remove(p);
            }

            for (PassageRequest pReq : request.getPassages()) {
                if (pReq.getId() != null) {
                    Passage existing = exam.getPassages().stream()
                            .filter(p -> p.getId().equals(pReq.getId()))
                            .findFirst().orElse(null);
                    if (existing != null) {
                        updatePassageFromRequest(existing, pReq, exam);
                    } else {
                        addPassageToExam(pReq, exam);
                    }
                } else {
                    addPassageToExam(pReq, exam);
                }
            }
        } else {
            // Remove all passages and their questions
            for (Passage p : new ArrayList<>(exam.getPassages())) {
                final Long passageId = p.getId();
                exam.getQuestions().removeIf(q -> q.getPassage() != null && q.getPassage().getId().equals(passageId));
            }
            exam.getPassages().clear();
        }

        Exam savedExam = examRepository.save(exam);
        return mapToResponse(savedExam);
    }

    private void addPassageToExam(PassageRequest req, Exam exam) {
        Passage passage = Passage.builder()
                .passageType(req.getPassageType())
                .title(req.getTitle())
                .textContent(req.getTextContent())
                .attachedImage(req.getAttachedImage())
                .audioContent(req.getAudioContent())
                .listenLimit(req.getListenLimit())
                .orderIndex(req.getOrderIndex())
                .subjectGroup(req.getSubjectGroup())
                .exam(exam)
                .build();
        exam.getPassages().add(passage);

        if (req.getQuestions() != null) {
            for (QuestionRequest qReq : req.getQuestions()) {
                exam.getQuestions().add(mapToQuestion(qReq, exam, passage));
            }
        }
    }

    private void updatePassageFromRequest(Passage passage, PassageRequest req, Exam exam) {
        passage.setPassageType(req.getPassageType());
        passage.setTitle(req.getTitle());
        passage.setTextContent(req.getTextContent());
        passage.setAttachedImage(req.getAttachedImage());
        passage.setAudioContent(req.getAudioContent());
        passage.setListenLimit(req.getListenLimit());
        passage.setOrderIndex(req.getOrderIndex());
        passage.setSubjectGroup(req.getSubjectGroup());

        if (req.getQuestions() != null) {
            List<Long> reqQuestionIds = req.getQuestions().stream()
                    .map(QuestionRequest::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // Remove passage questions no longer in the request
            final Long passageId = passage.getId();
            exam.getQuestions().removeIf(q -> q.getPassage() != null
                    && q.getPassage().getId().equals(passageId)
                    && !reqQuestionIds.contains(q.getId()));

            for (QuestionRequest qReq : req.getQuestions()) {
                if (qReq.getId() != null) {
                    Question existing = exam.getQuestions().stream()
                            .filter(q -> q.getId().equals(qReq.getId()))
                            .findFirst().orElse(null);
                    if (existing != null) {
                        updateQuestionFromRequest(existing, qReq);
                    } else {
                        exam.getQuestions().add(mapToQuestion(qReq, exam, passage));
                    }
                } else {
                    exam.getQuestions().add(mapToQuestion(qReq, exam, passage));
                }
            }
        } else {
            final Long passageId = passage.getId();
            exam.getQuestions().removeIf(q -> q.getPassage() != null && q.getPassage().getId().equals(passageId));
        }
    }

    private void updateQuestionFromRequest(Question question, QuestionRequest req) {
        String content = req.getContent() != null ? req.getContent() : req.getText();
        question.setContent(content);
        question.setAttachedImage(req.getAttachedImage());
        question.setQuestionType(req.getQuestionType());
        question.setPoints(req.getPoints());
        question.setOrderIndex(req.getOrderIndex());
        question.setCorrectAnswer(req.getCorrectAnswer());
        question.setSubjectGroup(req.getSubjectGroup());

        // Update options
        if (req.getOptions() != null) {
            List<Long> reqOptionIds = req.getOptions().stream()
                    .map(OptionRequest::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            question.getOptions().removeIf(o -> !reqOptionIds.contains(o.getId()));

            for (OptionRequest oReq : req.getOptions()) {
                if (oReq.getId() != null) {
                    Option existingOpt = question.getOptions().stream()
                            .filter(o -> o.getId().equals(oReq.getId()))
                            .findFirst().orElse(null);
                    if (existingOpt != null) {
                        existingOpt.setContent(oReq.getContent());
                        existingOpt.setIsCorrect(oReq.getIsCorrect());
                        existingOpt.setOrderIndex(oReq.getOrderIndex());
                        existingOpt.setAttachedImage(oReq.getAttachedImage());
                    } else {
                        question.getOptions().add(mapToOption(oReq, question));
                    }
                } else {
                    question.getOptions().add(mapToOption(oReq, question));
                }
            }
        } else {
            question.getOptions().clear();
        }

        // Update matching pairs
        if (req.getMatchingPairs() != null) {
            List<Long> reqPairIds = req.getMatchingPairs().stream()
                    .map(MatchingPairRequest::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            question.getMatchingPairs().removeIf(p -> !reqPairIds.contains(p.getId()));

            for (MatchingPairRequest pReq : req.getMatchingPairs()) {
                if (pReq.getId() != null) {
                    MatchingPair existingPair = question.getMatchingPairs().stream()
                            .filter(p -> p.getId().equals(pReq.getId()))
                            .findFirst().orElse(null);
                    if (existingPair != null) {
                        existingPair.setLeftItem(pReq.getLeftItem());
                        existingPair.setRightItem(pReq.getRightItem());
                        existingPair.setAttachedImageLeft(pReq.getAttachedImageLeft());
                        existingPair.setAttachedImageRight(pReq.getAttachedImageRight());
                        existingPair.setOrderIndex(pReq.getOrderIndex());
                    } else {
                        question.getMatchingPairs().add(mapToPair(pReq, question));
                    }
                } else {
                    question.getMatchingPairs().add(mapToPair(pReq, question));
                }
            }
        } else {
            question.getMatchingPairs().clear();
        }
    }

    private Option mapToOption(OptionRequest oReq, Question question) {
        return Option.builder()
                .content(oReq.getContent())
                .isCorrect(oReq.getIsCorrect())
                .orderIndex(oReq.getOrderIndex())
                .attachedImage(oReq.getAttachedImage())
                .question(question)
                .build();
    }

    private MatchingPair mapToPair(MatchingPairRequest pReq, Question question) {
        return MatchingPair.builder()
                .leftItem(pReq.getLeftItem())
                .rightItem(pReq.getRightItem())
                .attachedImageLeft(pReq.getAttachedImageLeft())
                .attachedImageRight(pReq.getAttachedImageRight())
                .orderIndex(pReq.getOrderIndex())
                .question(question)
                .build();
    }

    public ExamResponse getExamByShareLink(String shareLink) {
        Exam exam = examRepository.findByShareLink(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        return mapToResponse(exam);
    }

    @Transactional
    public Map<String, Object> generateAccessCode(Long examId, User teacher) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        if (!exam.getTeacher().getId().equals(teacher.getId())) {
            throw new RuntimeException("Bu əməliyyat üçün icazəniz yoxdur");
        }

        String code = CodeGenerator.generateAccessCode();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(12);

        exam.setAccessCode(code);
        exam.setAccessCodeExpiresAt(expiresAt);
        examRepository.save(exam);

        return Map.of("accessCode", code, "expiresAt", expiresAt.toString());
    }

    @Transactional
    public ExamResponse toggleStatus(Long id, User teacher) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        if (!exam.getTeacher().getId().equals(teacher.getId())) {
            throw new UnauthorizedException("Bu əməliyyat üçün icazəniz yoxdur");
        }
        if (exam.getStatus() == ExamStatus.DRAFT) {
            throw new BadRequestException("Qaralama imtahanını birbaşa aça bilməzsiniz. Əvvəlcə yayımlayın.");
        }
        exam.setStatus(exam.getStatus() == ExamStatus.PUBLISHED ? ExamStatus.CANCELLED : ExamStatus.PUBLISHED);
        return mapToResponse(examRepository.save(exam));
    }

    @Transactional
    public void deleteExam(Long id, User teacher) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        if (!exam.getTeacher().getId().equals(teacher.getId())) {
             throw new RuntimeException("Bu əməliyyat üçün icazəniz yoxdur");
        }

        examRepository.delete(exam);
    }

    private Question mapToQuestion(QuestionRequest req, Exam exam, Passage passage) {
        String content = req.getContent() != null ? req.getContent() : req.getText();
        Question question = Question.builder()
                .content(content)
                .attachedImage(req.getAttachedImage())
                .questionType(req.getQuestionType())
                .points(req.getPoints())
                .orderIndex(req.getOrderIndex())
                .correctAnswer(req.getCorrectAnswer())
                .subjectGroup(req.getSubjectGroup())
                .exam(exam)
                .passage(passage)
                .build();

        if (req.getOptions() != null) {
            for (OptionRequest oReq : req.getOptions()) {
                question.getOptions().add(mapToOption(oReq, question));
            }
        }

        if (req.getMatchingPairs() != null) {
            for (MatchingPairRequest pReq : req.getMatchingPairs()) {
                question.getMatchingPairs().add(mapToPair(pReq, question));
            }
        }

        return question;
    }

    private ExamResponse mapToResponse(Exam exam) {
        // Split questions into standalone vs. by-passage
        Map<Long, List<QuestionResponse>> byPassage = exam.getQuestions().stream()
                .filter(q -> q.getPassage() != null)
                .collect(Collectors.groupingBy(
                        q -> q.getPassage().getId(),
                        Collectors.mapping(this::mapToQuestionResponse, Collectors.toList())
                ));

        List<QuestionResponse> standaloneQuestions = exam.getQuestions().stream()
                .filter(q -> q.getPassage() == null)
                .map(this::mapToQuestionResponse)
                .collect(Collectors.toList());

        List<PassageResponse> passages = exam.getPassages().stream()
                .map(p -> PassageResponse.builder()
                        .id(p.getId())
                        .passageType(p.getPassageType())
                        .title(p.getTitle())
                        .textContent(p.getTextContent())
                        .attachedImage(p.getAttachedImage())
                        .audioContent(p.getAudioContent())
                        .listenLimit(p.getListenLimit())
                        .orderIndex(p.getOrderIndex())
                        .subjectGroup(p.getSubjectGroup())
                        .questions(byPassage.getOrDefault(p.getId(), new ArrayList<>()))
                        .build())
                .collect(Collectors.toList());

        return ExamResponse.builder()
                .id(exam.getId())
                .title(exam.getTitle())
                .description(exam.getDescription())
                .subjects(exam.getSubjects())
                .visibility(exam.getVisibility())
                .examType(exam.getExamType())
                .status(exam.getStatus())
                .accessCode(exam.getAccessCode())
                .accessCodeExpiresAt(exam.getAccessCodeExpiresAt())
                .shareLink(exam.getShareLink())
                .durationMinutes(exam.getDurationMinutes())
                .teacherId(exam.getTeacher().getId())
                .teacherName(exam.getTeacher().getFullName())
                .templateId(exam.getTemplate() != null ? exam.getTemplate().getId() : null)
                .templateSectionId(exam.getTemplateSection() != null ? exam.getTemplateSection().getId() : null)
                .price(exam.getPrice())
                .sitePublished(exam.isSitePublished())
                .questions(standaloneQuestions)
                .passages(passages)
                .createdAt(exam.getCreatedAt())
                .updatedAt(exam.getUpdatedAt())
                .tags(exam.getTags())
                .build();
    }

    private QuestionResponse mapToQuestionResponse(Question q) {
        return QuestionResponse.builder()
                .id(q.getId())
                .content(q.getContent())
                .attachedImage(q.getAttachedImage())
                .questionType(q.getQuestionType())
                .points(q.getPoints())
                .orderIndex(q.getOrderIndex())
                .correctAnswer(q.getCorrectAnswer())
                .subjectGroup(q.getSubjectGroup())
                .options(q.getOptions().stream().map(this::mapToOptionResponse).collect(Collectors.toList()))
                .matchingPairs(q.getMatchingPairs().stream().map(this::mapToPairResponse).collect(Collectors.toList()))
                .build();
    }

    private OptionResponse mapToOptionResponse(Option o) {
        return OptionResponse.builder()
                .id(o.getId())
                .content(o.getContent())
                .isCorrect(o.getIsCorrect())
                .orderIndex(o.getOrderIndex())
                .attachedImage(o.getAttachedImage())
                .build();
    }

    private MatchingPairResponse mapToPairResponse(MatchingPair p) {
        return MatchingPairResponse.builder()
                .id(p.getId())
                .leftItem(p.getLeftItem())
                .rightItem(p.getRightItem())
                .orderIndex(p.getOrderIndex())
                .build();
    }
}
