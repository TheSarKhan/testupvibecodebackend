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
import az.testup.entity.PaymentOrder;
import az.testup.entity.StudentSavedExam;
import az.testup.enums.AuditAction;
import az.testup.enums.ExamStatus;
import az.testup.enums.QuestionReviewStatus;
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
    private final ExamCollaboratorRepository examCollaboratorRepository;
    private final SubmissionRepository submissionRepository;
    private final QuestionRepository questionRepository;
    private final AuditLogService auditLogService;
    private final PaymentOrderRepository paymentOrderRepository;
    private final ExamAccessCodeRepository examAccessCodeRepository;

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
                .explanationVideoUrl(request.getExplanationVideoUrl())
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

        if (request.getTemplateSectionIds() != null && !request.getTemplateSectionIds().isEmpty()) {
            List<TemplateSection> sections = templateSectionRepository.findAllById(request.getTemplateSectionIds());
            exam.getTemplateSections().clear();
            exam.getTemplateSections().addAll(sections);
            exam.setTemplateSection(sections.isEmpty() ? null : sections.get(0));
        } else if (request.getTemplateSectionId() != null) {
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

        // Keep questions grouped by subject (new questions to a subject's tail).
        resortQuestionsBySubject(exam);
        // Apply each template section's point groups to the question points so a
        // template-derived exam shows the section's per-position values (e.g.
        // Q11-20 = 1.5), not a flat 1 (#XXX). isCreate=true → set defaults for
        // every section that defines ranges.
        applyTemplatePointGroups(exam, true);

        if (exam.getStatus() == ExamStatus.PUBLISHED) {
            validateForPublish(exam);
        }

        Exam savedExam = examRepository.save(exam);

        // Record usage
        subscriptionValidatorService.recordMonthlyExamCreated(teacher.getId());
        auditLogService.log(AuditAction.EXAM_CREATED, teacher.getEmail(), teacher.getFullName(), "EXAM", savedExam.getTitle(), null);

        return mapToResponse(savedExam);
    }

    @Transactional(readOnly = true)
    public List<ExamResponse> getTeacherExams(User teacher) {
        // Exclude collaborative draft exams (they appear in the collaborative assignments section)
        return examRepository.findByTeacherAndCollaborativeParentIdIsNullAndDeletedFalse(teacher).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * List endpoint optimised for the Teacher "İmtahanlarım" page and the
     * Profile teacher tab. Skips nested questions/options/matching pairs and
     * batches all per-exam aggregate counts into 4 GROUP BY queries instead
     * of ~3*N round trips. For a teacher with 30 exams of 20 questions this
     * drops the call from ~1.4k DB queries to ~5.
     */
    @Transactional(readOnly = true)
    public List<ExamSummaryResponse> getTeacherExamsSummary(User teacher) {
        List<Exam> exams = examRepository.findByTeacherAndCollaborativeParentIdIsNullAndDeletedFalse(teacher);
        if (exams.isEmpty()) return List.of();

        List<Long> examIds = exams.stream().map(Exam::getId).collect(Collectors.toList());

        Map<Long, Long> questionCounts = toCountMap(questionRepository.countByExamIdIn(examIds));
        Map<Long, Long> pendingCounts = toCountMap(submissionRepository.countPendingGradingByExamIdIn(examIds));
        Map<Long, Long> participantCounts = toCountMap(submissionRepository.countParticipantsByExamIdIn(examIds));

        Map<Long, Double> avgRatings = new HashMap<>();
        Map<Long, Long> ratingCounts = new HashMap<>();
        for (Object[] row : submissionRepository.findRatingStatsByExamIdIn(examIds)) {
            Long examId = (Long) row[0];
            Double avg = row[1] != null ? ((Number) row[1]).doubleValue() : null;
            Long cnt = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            if (avg != null) avgRatings.put(examId, avg);
            ratingCounts.put(examId, cnt);
        }

        return exams.stream()
                .map(exam -> ExamSummaryResponse.builder()
                        .id(exam.getId())
                        .title(exam.getTitle())
                        .subjects(exam.getSubjects())
                        .visibility(exam.getVisibility())
                        .examType(exam.getExamType())
                        .status(exam.getStatus())
                        .shareLink(exam.getShareLink())
                        .durationMinutes(exam.getDurationMinutes())
                        .teacherId(exam.getTeacher().getId())
                        .price(exam.getPrice())
                        .sitePublished(exam.isSitePublished())
                        .tags(exam.getTags())
                        .createdAt(exam.getCreatedAt())
                        .updatedAt(exam.getUpdatedAt())
                        .isCollaborative(exam.isCollaborative())
                        .collaborativeParentId(exam.getCollaborativeParentId())
                        .questionCount(questionCounts.getOrDefault(exam.getId(), 0L).intValue())
                        .participantCount(participantCounts.getOrDefault(exam.getId(), 0L))
                        .pendingManualCount(pendingCounts.getOrDefault(exam.getId(), 0L))
                        .averageRating(avgRatings.get(exam.getId()))
                        .ratingCount(ratingCounts.getOrDefault(exam.getId(), 0L))
                        .build())
                .collect(Collectors.toList());
    }

    private static Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            map.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return map;
    }

    /**
     * Returns all exams published to the site catalog by admin (sitePublished=true, not cancelled/draft).
     */
    @Transactional(readOnly = true)
    public List<ExamResponse> getPublicExams() {
        return examRepository.findAll().stream()
                .filter(e -> !e.isDeleted()
                        && e.isSitePublished()
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
        Exam exam = examRepository.findByShareLinkAndDeletedFalse(shareLink)
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

        boolean isPaid = amount != null && amount.compareTo(java.math.BigDecimal.ZERO) > 0;
        auditLogService.log(AuditAction.EXAM_PURCHASED, student.getEmail(), student.getFullName(),
                "EXAM", exam.getTitle(),
                isPaid ? ("Məbləğ: " + amount + " AZN") : "Pulsuz imtahan");
    }

    /**
     * Returns whether the given user has purchased a specific exam.
     */
    @Transactional(readOnly = true)
    public boolean hasPurchased(String shareLink, User user) {
        Exam exam = examRepository.findByShareLinkAndDeletedFalse(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        if (exam.getPrice() == null || exam.getPrice().compareTo(java.math.BigDecimal.ZERO) == 0) {
            return true; // free exam
        }
        return examPurchaseRepository.existsByUserIdAndExamId(user.getId(), exam.getId());
    }

    /**
     * Returns true if the user has an unused exam purchase (paid more times than submitted).
     * Each completed submission "consumes" one purchase.
     */
    @Transactional(readOnly = true)
    public boolean hasUnusedPurchase(Exam exam, User user) {
        if (exam.getPrice() == null || exam.getPrice().compareTo(java.math.BigDecimal.ZERO) == 0) {
            return true; // free exam — always accessible
        }
        long paid = paymentOrderRepository.countByUserIdAndExamIdAndStatus(user.getId(), exam.getId(), "PAID");
        long submitted = submissionRepository.countByExamIdAndStudentIdAndSubmittedAtIsNotNull(exam.getId(), user.getId());
        return paid > submitted;
    }

    /**
     * Returns true if the user has an unused purchase for the exam identified by shareLink.
     */
    @Transactional(readOnly = true)
    public boolean hasPurchasedByShareLink(String shareLink, User user) {
        Exam exam = examRepository.findByShareLinkAndDeletedFalse(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        return hasUnusedPurchase(exam, user);
    }

    /**
     * Returns shareLinks of exams the user has an unused purchase for (can start right now).
     */
    @Transactional(readOnly = true)
    public List<String> getMyPurchasedExamShareLinks(User user) {
        return paymentOrderRepository.findPaidExamOrders(user.getId(), "PAID")
                .stream()
                .map(PaymentOrder::getExam)
                .filter(Objects::nonNull)
                .distinct()
                // Drop deleted / closed (CANCELLED) / site-unpublished exams
                // from "Alınanlar" (purchases only exist for site exams).
                .filter(e -> !e.isDeleted() && e.getStatus() != ExamStatus.CANCELLED && e.isSitePublished())
                .filter(e -> hasUnusedPurchase(e, user))
                .map(Exam::getShareLink)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ExamResponse getExamById(Long id, User teacher) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        boolean isAdmin = teacher.getRole() == az.testup.enums.Role.ADMIN;
        if (!isAdmin && !exam.getTeacher().getId().equals(teacher.getId())) {
            // Was bare RuntimeException — 500 to the user. Use the typed
            // exception so the frontend sees a 403 with the localized message.
            throw new UnauthorizedException("Bu imtahana giriş icazəniz yoxdur");
        }

        return mapToResponse(exam);
    }

    @Transactional
    public ExamResponse updateExam(Long id, ExamRequest request, User teacher) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        boolean isOwner = exam.getTeacher().getId().equals(teacher.getId());
        boolean isAdmin = teacher.getRole() == az.testup.enums.Role.ADMIN;
        boolean isCollaborator = exam.getCollaborativeParentId() != null
                && examCollaboratorRepository.findByDraftExamId(exam.getId())
                    .map(c -> c.getTeacher() != null && c.getTeacher().getId().equals(teacher.getId()))
                    .orElse(false);
        if (!isOwner && !isAdmin && !isCollaborator) {
            // Owner, admin, or assigned collaborator only. Previously this only
            // accepted the owner, so admin-panel edits of teacher-owned exams
            // and collab-draft saves by the assigned collaborator both 403'd.
            throw new UnauthorizedException("Bu imtahanı redaktə etmək icazəniz yoxdur");
        }

        // Subscription gating applies only to a teacher editing their OWN
        // standalone exam. A collaborative draft (collaborativeParentId != null)
        // is the admin's exam that invited teachers fill in — gating a
        // contributor on their personal "exam editing" plan made every bank-pick
        // autosave 403 ("Aktiv abunəlik planı tapılmadı" / "İmtahan redaktəsi
        // ... mövcud deyil"), so the question never saved and couldn't be sent to
        // the admin. Admins bypass the check internally regardless.
        if (exam.getCollaborativeParentId() == null) {
            subscriptionValidatorService.validateExamEditing(teacher.getId());
        }

        // Collab drafts must respect template-section question count caps. Frontend hides
        // the "Sual əlavə et" button when the count is locked, but nothing stops a teacher
        // from posting extras via the API directly. Validate per-subject against each
        // assigned section's declared questionCount before any mutation.
        if (exam.getCollaborativeParentId() != null
                && exam.getTemplateSections() != null
                && !exam.getTemplateSections().isEmpty()
                && request.getQuestions() != null) {
            java.util.Map<String, Integer> bySubject = new java.util.HashMap<>();
            for (az.testup.dto.request.QuestionRequest qReq : request.getQuestions()) {
                String s = qReq.getSubjectGroup();
                if (s == null) continue;
                bySubject.merge(s, 1, Integer::sum);
            }
            for (TemplateSection sec : exam.getTemplateSections()) {
                int actual = bySubject.getOrDefault(sec.getSubjectName(), 0);
                Integer allowed = sec.getQuestionCount();
                if (allowed != null && actual > allowed) {
                    throw new BadRequestException(sec.getSubjectName() + " bölməsində maksimum "
                            + allowed + " sual ola bilər (siz " + actual + " göndərdiniz)");
                }
            }
        }

        exam.setTitle(request.getTitle());
        exam.setDescription(request.getDescription());
        exam.setExplanationVideoUrl(request.getExplanationVideoUrl());
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

        if (request.getTemplateSectionIds() != null && !request.getTemplateSectionIds().isEmpty()) {
            List<TemplateSection> sections = templateSectionRepository.findAllById(request.getTemplateSectionIds());
            exam.getTemplateSections().clear();
            exam.getTemplateSections().addAll(sections);
            exam.setTemplateSection(sections.isEmpty() ? null : sections.get(0));
        } else if (request.getTemplateSectionId() != null) {
            TemplateSection section = templateSectionRepository.findById(request.getTemplateSectionId())
                    .orElseThrow(() -> new ResourceNotFoundException("Şablon bölməsi tapılmadı"));
            exam.setTemplateSection(section);
            exam.getTemplateSections().clear();
        } else {
            exam.setTemplateSection(null);
            exam.getTemplateSections().clear();
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
                        updateQuestionFromRequest(existing, qReq, null);
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

        // Regroup questions by subject so a newly added question lands at the
        // end of its own subject section, not the end of the whole exam (#253).
        resortQuestionsBySubject(exam);
        // Re-apply point groups. isCreate=false → only ENFORCE for locked
        // sections (allowCustomPoints=false); editable sections keep the
        // teacher's per-question points (#XXX).
        applyTemplatePointGroups(exam, false);

        if (exam.getStatus() == ExamStatus.PUBLISHED) {
            validateForPublish(exam);
        }

        Exam savedExam = examRepository.save(exam);
        auditLogService.log(AuditAction.EXAM_UPDATED, teacher.getEmail(), teacher.getFullName(), "EXAM", savedExam.getTitle(), "Status: " + savedExam.getStatus());
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
                        updateQuestionFromRequest(existing, qReq, passage);
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

    private void updateQuestionFromRequest(Question question, QuestionRequest req, Passage passage) {
        // Phase 4: collaborative-draft questions that were APPROVED need to revert to
        // PENDING when their content changes so the admin re-reviews the new version.
        // Snapshot the question BEFORE mutating, then compare AFTER.
        boolean wasApproved = question.getReviewStatus() == QuestionReviewStatus.APPROVED;
        String oldFingerprint = wasApproved ? fingerprintQuestion(question) : null;

        // Keep the passage link in sync with where the request placed this
        // question. Without this, moving a question between standalone and a
        // passage (or between passages) left a stale/orphaned passage_id, and
        // the question silently vanished from the session view (BUG-248).
        question.setPassage(passage);

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
                    // Null-guard o.getId(): once a request option carries an id
                    // that matches no existing option (e.g. a bank-picked option
                    // whose bank id leaked through), it's added as a NEW option
                    // with a null id. A later iteration then streamed over that
                    // freshly-added option and called o.getId().equals(...) on a
                    // null id → NPE → 500. This is the "bazadan sual seçimi"
                    // autosave crash in template mode (the bank replace path).
                    Option existingOpt = question.getOptions().stream()
                            .filter(o -> o.getId() != null && o.getId().equals(oReq.getId()))
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
                    // Same null-guard as the options loop above: a newly-added
                    // pair has a null id, so an unguarded p.getId().equals(...)
                    // NPEs once two request pairs carry unmatched ids.
                    MatchingPair existingPair = question.getMatchingPairs().stream()
                            .filter(p -> p.getId() != null && p.getId().equals(pReq.getId()))
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

        // Phase 4 tail: if any meaningful field of an APPROVED draft question changed,
        // demote back to PENDING so the admin re-reviews. orderIndex changes alone do
        // NOT trigger re-review.
        if (wasApproved) {
            String newFingerprint = fingerprintQuestion(question);
            if (!java.util.Objects.equals(oldFingerprint, newFingerprint)) {
                question.setReviewStatus(QuestionReviewStatus.PENDING);
                question.setReviewComment(null);
            }
        }
    }

    /**
     * Fingerprint a question's reviewable surface — content, attachments, answer key,
     * options (sorted by orderIndex), matching pairs (sorted). orderIndex itself and
     * collection ids are intentionally excluded so option-id renumbering on save does
     * not trip a false "content changed" detection.
     */
    private String fingerprintQuestion(Question q) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("c=").append(q.getContent()).append('|');
        sb.append("img=").append(q.getAttachedImage()).append('|');
        sb.append("ans=").append(q.getCorrectAnswer()).append('|');
        sb.append("sam=").append(q.getSampleAnswer()).append('|');
        sb.append("typ=").append(q.getQuestionType()).append('|');
        sb.append("pts=").append(q.getPoints()).append('|');
        sb.append("sub=").append(q.getSubjectGroup()).append('|');
        sb.append("opts=[");
        if (q.getOptions() != null) {
            q.getOptions().stream()
                    .sorted(java.util.Comparator.comparing(
                            o -> o.getOrderIndex() != null ? o.getOrderIndex() : 0))
                    .forEach(o -> sb.append('(')
                            .append(o.getContent()).append(',')
                            .append(o.getIsCorrect()).append(',')
                            .append(o.getAttachedImage()).append(')'));
        }
        sb.append("]|pairs=[");
        if (q.getMatchingPairs() != null) {
            q.getMatchingPairs().stream()
                    .sorted(java.util.Comparator.comparing(
                            p -> p.getOrderIndex() != null ? p.getOrderIndex() : 0))
                    .forEach(p -> sb.append('(')
                            .append(p.getLeftItem()).append(',')
                            .append(p.getRightItem()).append(',')
                            .append(p.getAttachedImageLeft()).append(',')
                            .append(p.getAttachedImageRight()).append(')'));
        }
        sb.append(']');
        return sb.toString();
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
        Exam exam = examRepository.findByShareLinkAndDeletedFalse(shareLink)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        return mapToResponse(exam);
    }

    @Transactional
    public Map<String, Object> generateAccessCode(Long examId, User teacher) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        if (!exam.getTeacher().getId().equals(teacher.getId())) {
            // Typed exception → clean 403; the bare RuntimeException returned
            // 500 and the user saw a generic "Server xətası" toast.
            throw new UnauthorizedException("Bu əməliyyat üçün icazəniz yoxdur");
        }

        String code = CodeGenerator.generateAccessCode();
        java.time.Instant expiresAt = java.time.Instant.now().plusSeconds(48 * 3600);

        ExamAccessCode accessCode = ExamAccessCode.builder()
                .exam(exam)
                .code(code)
                .expiresAt(expiresAt)
                .build();
        examAccessCodeRepository.save(accessCode);

        auditLogService.log(AuditAction.EXAM_ACCESS_CODE_GENERATED, teacher.getEmail(), teacher.getFullName(),
                "EXAM", exam.getTitle(), "Kod: " + code + " (48 saat keçərli)");

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
        ExamStatus newStatus = exam.getStatus() == ExamStatus.PUBLISHED ? ExamStatus.CANCELLED : ExamStatus.PUBLISHED;
        if (newStatus == ExamStatus.PUBLISHED) {
            validateForPublish(exam);
        }
        exam.setStatus(newStatus);
        Exam saved = examRepository.save(exam);
        auditLogService.log(AuditAction.EXAM_STATUS_CHANGED, teacher.getEmail(), teacher.getFullName(), "EXAM", saved.getTitle(), "Yeni status: " + newStatus);
        return mapToResponse(saved);
    }

    @Transactional
    public ExamResponse cloneExam(Long id, User teacher) {
        Exam original = examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        if (!original.getTeacher().getId().equals(teacher.getId())) {
            throw new UnauthorizedException("Bu əməliyyat üçün icazəniz yoxdur");
        }

        // Validate limits for the cloning teacher
        subscriptionValidatorService.validateMonthlyExamCreation(teacher.getId());
        subscriptionValidatorService.validateTotalSavedExams(teacher.getId());

        Exam clone = Exam.builder()
                .title(original.getTitle() + " (Kopya)")
                .description(original.getDescription())
                .explanationVideoUrl(original.getExplanationVideoUrl())
                .subjects(new ArrayList<>(original.getSubjects()))
                .visibility(original.getVisibility())
                .examType(original.getExamType())
                .status(ExamStatus.DRAFT)
                .durationMinutes(original.getDurationMinutes())
                .price(original.getPrice())
                .sitePublished(false)
                .teacher(teacher)
                .shareLink(CodeGenerator.generateShareLink())
                .tags(new ArrayList<>(original.getTags()))
                .build();

        // Clone passages
        Map<Long, Passage> passageMap = new HashMap<>();
        for (Passage p : original.getPassages()) {
            Passage clonedPassage = Passage.builder()
                    .passageType(p.getPassageType())
                    .title(p.getTitle())
                    .textContent(p.getTextContent())
                    .attachedImage(p.getAttachedImage())
                    .audioContent(p.getAudioContent())
                    .listenLimit(p.getListenLimit())
                    .orderIndex(p.getOrderIndex())
                    .subjectGroup(p.getSubjectGroup())
                    .exam(clone)
                    .build();
            clone.getPassages().add(clonedPassage);
            passageMap.put(p.getId(), clonedPassage);
        }

        // Clone questions
        for (Question q : original.getQuestions()) {
            Passage clonedPassage = q.getPassage() != null ? passageMap.get(q.getPassage().getId()) : null;
            Question clonedQ = Question.builder()
                    .content(q.getContent())
                    .attachedImage(q.getAttachedImage())
                    .questionType(q.getQuestionType())
                    .points(q.getPoints())
                    .orderIndex(q.getOrderIndex())
                    .correctAnswer(q.getCorrectAnswer())
                    .sampleAnswer(q.getSampleAnswer())
                    .subjectGroup(q.getSubjectGroup())
                    .exam(clone)
                    .passage(clonedPassage)
                    .build();

            for (Option o : q.getOptions()) {
                clonedQ.getOptions().add(Option.builder()
                        .content(o.getContent())
                        .isCorrect(o.getIsCorrect())
                        .orderIndex(o.getOrderIndex())
                        .attachedImage(o.getAttachedImage())
                        .question(clonedQ)
                        .build());
            }
            for (MatchingPair mp : q.getMatchingPairs()) {
                clonedQ.getMatchingPairs().add(MatchingPair.builder()
                        .leftItem(mp.getLeftItem())
                        .rightItem(mp.getRightItem())
                        .attachedImageLeft(mp.getAttachedImageLeft())
                        .attachedImageRight(mp.getAttachedImageRight())
                        .orderIndex(mp.getOrderIndex())
                        .question(clonedQ)
                        .build());
            }
            clone.getQuestions().add(clonedQ);
        }

        Exam saved = examRepository.save(clone);
        subscriptionValidatorService.recordMonthlyExamCreated(teacher.getId());
        auditLogService.log(AuditAction.EXAM_CREATED, teacher.getEmail(), teacher.getFullName(), "EXAM", saved.getTitle(), "Kopyalandı: " + original.getTitle());
        return mapToResponse(saved);
    }

    @Transactional
    public void deleteExam(Long id, User teacher) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));

        boolean isAdmin = teacher.getRole() == az.testup.enums.Role.ADMIN;

        // A collaborative draft (exam.collaborativeParentId != null) is a piece of an
        // admin-owned parent — a section teacher must NOT be able to delete it here. If
        // it goes, the collaborator entry points to a dangling exam id and the whole
        // collab exam breaks. Only admins can remove a collab draft (via the parent flow).
        if (exam.getCollaborativeParentId() != null && !isAdmin) {
            throw new BadRequestException(
                    "Bu birgə imtahan draft-ıdır. Silmək üçün admin ilə əlaqə saxlayın.");
        }

        if (!isAdmin && !exam.getTeacher().getId().equals(teacher.getId())) {
             // Was a bare RuntimeException — returned 500 Internal Server Error
             // to the user. UnauthorizedException maps to a clean 403 with the
             // shown message.
             throw new UnauthorizedException("Bu əməliyyat üçün icazəniz yoxdur");
        }

        String examTitle = exam.getTitle();
        exam.setDeleted(true);
        examRepository.save(exam);
        auditLogService.log(AuditAction.EXAM_DELETED, teacher.getEmail(), teacher.getFullName(), "EXAM", examTitle, null);
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

    /**
     * Regroup the exam's questions so a question sits at the end of its own
     * subject section rather than at the very end of the whole exam.
     *
     * A question added through the editor carries the highest orderIndex (exam
     * end). The standard create/update flow used that value verbatim and never
     * regrouped by subject, so in a multi-subject exam the new question landed
     * after every other subject instead of after its own (BUG-253). This mirrors
     * the collaborative flow's resortParentQuestionsBySubject: stable sort by the
     * subject's position in exam.subjects, ties broken by the current orderIndex
     * (so each subject's internal order is preserved and a max-orderIndex new
     * question falls to its subject's tail), then renumber sequentially.
     *
     * Single-subject / ungrouped exams have nothing to regroup and are skipped.
     */
    private void resortQuestionsBySubject(Exam exam) {
        List<String> subjectOrder = exam.getSubjects() != null
                ? exam.getSubjects() : java.util.Collections.emptyList();
        if (subjectOrder.size() <= 1) return;

        java.util.function.ToIntFunction<Question> subjectRank = q -> {
            String s = q.getSubjectGroup();
            if (s == null) return Integer.MAX_VALUE;
            int idx = subjectOrder.indexOf(s);
            return idx < 0 ? Integer.MAX_VALUE - 1 : idx;
        };

        List<Question> qs = new ArrayList<>(exam.getQuestions());
        qs.sort(java.util.Comparator
                .comparingInt(subjectRank)
                .thenComparingInt(q -> q.getOrderIndex() == null ? Integer.MAX_VALUE : q.getOrderIndex()));
        for (int i = 0; i < qs.size(); i++) {
            Question q = qs.get(i);
            if (q.getOrderIndex() == null || q.getOrderIndex() != i) q.setOrderIndex(i);
        }
    }

    /**
     * Enforce that an exam carries complete question content before it can be
     * PUBLISHED. The bean-validation @NotBlank on QuestionRequest/OptionRequest
     * is intentionally not wired (nested lists are not @Valid) because draft
     * saves legitimately hold half-finished questions — so the publish gate
     * lives here instead. Drafts skip this entirely.
     *
     * A question is considered complete when it has either text or an image,
     * and its type-specific answer is present:
     *  • MCQ / TRUE_FALSE / MULTI_SELECT — ≥2 filled options and ≥1 marked correct
     *  • OPEN_AUTO                       — a reference correct answer
     *  • FILL_IN_THE_BLANK               — at least one non-empty blank answer
     *  • MATCHING                        — at least one fully-paired connection
     *  • OPEN_MANUAL                     — content only (graded by hand)
     */
    private void validateForPublish(Exam exam) {
        List<Question> questions = exam.getQuestions();
        if (questions == null || questions.isEmpty()) {
            throw new BadRequestException("İmtahanı yayımlamaq üçün ən azı bir sual əlavə edilməlidir");
        }
        int n = 0;
        for (Question q : questions) {
            n++;
            String label = "Sual " + n;
            if (isBlank(q.getContent()) && isBlank(q.getAttachedImage())) {
                throw new BadRequestException(label + ": sual mətni və ya şəkli daxil edilməlidir");
            }
            az.testup.enums.QuestionType type = q.getQuestionType();
            if (type == az.testup.enums.QuestionType.MCQ
                    || type == az.testup.enums.QuestionType.TRUE_FALSE
                    || type == az.testup.enums.QuestionType.MULTI_SELECT) {
                List<Option> opts = q.getOptions();
                if (opts == null || opts.size() < 2) {
                    throw new BadRequestException(label + ": ən azı iki cavab variantı olmalıdır");
                }
                boolean anyCorrect = false;
                for (Option o : opts) {
                    if (isBlank(o.getContent()) && isBlank(o.getAttachedImage())) {
                        throw new BadRequestException(label + ": bütün cavab variantları doldurulmalıdır");
                    }
                    if (Boolean.TRUE.equals(o.getIsCorrect())) anyCorrect = true;
                }
                if (!anyCorrect) {
                    throw new BadRequestException(label + ": düzgün cavab variantı seçilməlidir");
                }
            } else if (type == az.testup.enums.QuestionType.OPEN_AUTO) {
                if (isBlank(q.getCorrectAnswer())) {
                    throw new BadRequestException(label + ": düzgün cavab daxil edilməlidir");
                }
            } else if (type == az.testup.enums.QuestionType.FILL_IN_THE_BLANK) {
                // correctAnswer is a JSON array of blank answers, e.g. ["x","y"].
                // Strip the structural characters; anything left means a real answer.
                String stripped = q.getCorrectAnswer() == null ? ""
                        : q.getCorrectAnswer().replaceAll("[\\[\\]\",\\s]", "");
                if (stripped.isEmpty()) {
                    throw new BadRequestException(label + ": boşluqların düzgün cavabları daxil edilməlidir");
                }
            } else if (type == az.testup.enums.QuestionType.MATCHING) {
                boolean hasPair = q.getMatchingPairs() != null && q.getMatchingPairs().stream()
                        .anyMatch(p -> !isBlank(p.getLeftItem()) && !isBlank(p.getRightItem()));
                if (!hasPair) {
                    throw new BadRequestException(label + ": ən azı bir uyğunlaşdırma əlaqəsi qurulmalıdır");
                }
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Apply each linked template section's point groups to its questions'
     * points, so a template-derived exam carries the section's per-position
     * values (e.g. Q1-10 = 1, Q11-20 = 1.5) instead of a flat default (#XXX).
     *
     * Semantics:
     *  • On create ({@code isCreate=true}) every section that defines ranges
     *    seeds its questions' points.
     *  • On update ({@code isCreate=false}) only LOCKED sections
     *    (allowCustomPoints=false) are re-enforced; editable sections keep the
     *    teacher's per-question points.
     *
     * Sections without point groups, and exams not linked to a template, are
     * left untouched (no regression for plain exams).
     */
    private void applyTemplatePointGroups(Exam exam, boolean isCreate) {
        List<TemplateSection> sections;
        if (exam.getTemplateSections() != null && !exam.getTemplateSections().isEmpty()) {
            sections = exam.getTemplateSections();
        } else if (exam.getTemplateSection() != null) {
            sections = List.of(exam.getTemplateSection());
        } else {
            return;
        }

        boolean multiSection = sections.size() > 1;
        for (TemplateSection section : sections) {
            List<double[]> ranges = az.testup.util.PointGroups.parse(section.getPointGroups());
            if (ranges.isEmpty()) continue; // no ranges → leave points as-is
            // Editable sections: only seed on create; on update respect the
            // teacher's points so their custom values survive a save.
            if (!isCreate && section.isAllowCustomPoints()) continue;

            // The section's questions: matched by subjectGroup in a multi-section
            // exam, or all questions when there's a single section. Ordered by
            // orderIndex so the 1-based position lines up with the ranges.
            List<Question> sectionQs = exam.getQuestions().stream()
                    .filter(q -> !multiSection
                            || (section.getSubjectName() != null
                                && section.getSubjectName().equals(q.getSubjectGroup())))
                    .sorted(java.util.Comparator.comparingInt(
                            q -> q.getOrderIndex() != null ? q.getOrderIndex() : 0))
                    .collect(Collectors.toList());

            int position = 1;
            for (Question q : sectionQs) {
                q.setPoints(az.testup.util.PointGroups.pointsFor(ranges, position));
                position++;
            }
        }
    }

    private ExamResponse mapToResponse(Exam exam) {
        // A question may point at a passage that is no longer in the exam's
        // passages (orphaned link). Grouping only by existing passages would
        // silently drop such questions from the editor/detail view, mirroring
        // the session-view mismatch (BUG-248). Surface them as standalone so the
        // displayed set always matches the real question count and the teacher
        // can re-home or delete them.
        Set<Long> validPassageIds = exam.getPassages().stream()
                .map(az.testup.entity.Passage::getId)
                .collect(Collectors.toSet());

        // Split questions into standalone vs. by-passage (existing passages only)
        Map<Long, List<QuestionResponse>> byPassage = exam.getQuestions().stream()
                .filter(q -> q.getPassage() != null && validPassageIds.contains(q.getPassage().getId()))
                .collect(Collectors.groupingBy(
                        q -> q.getPassage().getId(),
                        Collectors.mapping(this::mapToQuestionResponse, Collectors.toList())
                ));

        List<QuestionResponse> standaloneQuestions = exam.getQuestions().stream()
                .filter(q -> q.getPassage() == null || !validPassageIds.contains(q.getPassage().getId()))
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

        // Populate collaborative fields for draft exams
        List<String> collaborativeSubjects = null;
        List<az.testup.dto.response.CollaboratorSectionInfo> collaborativeTemplateSections = null;
        if (exam.getCollaborativeParentId() != null) {
            var collabOpt = examCollaboratorRepository.findByDraftExamId(exam.getId());
            if (collabOpt.isPresent()) {
                var collab = collabOpt.get();
                collaborativeSubjects = collab.getSubjects();
                if (collab.getTemplateSectionIds() != null && !collab.getTemplateSectionIds().isEmpty()) {
                    collaborativeTemplateSections = templateSectionRepository
                            .findAllById(collab.getTemplateSectionIds())
                            .stream()
                            .map(s -> new az.testup.dto.response.CollaboratorSectionInfo(
                                    s.getId(), s.getSubjectName(), s.getQuestionCount(), s.getFormula()))
                            .collect(Collectors.toList());
                }
            }
        }

        return ExamResponse.builder()
                .id(exam.getId())
                .title(exam.getTitle())
                .description(exam.getDescription())
                .explanationVideoUrl(exam.getExplanationVideoUrl())
                .subjects(exam.getSubjects())
                .visibility(exam.getVisibility())
                .examType(exam.getExamType())
                .status(exam.getStatus())
                .shareLink(exam.getShareLink())
                .durationMinutes(exam.getDurationMinutes())
                .teacherId(exam.getTeacher().getId())
                .teacherName(exam.getTeacher().getFullName())
                .templateId(exam.getTemplate() != null ? exam.getTemplate().getId() : null)
                .templateSectionId(exam.getTemplateSection() != null ? exam.getTemplateSection().getId() : null)
                .templateSectionIds(exam.getTemplateSections() != null && !exam.getTemplateSections().isEmpty()
                        ? exam.getTemplateSections().stream().map(az.testup.entity.TemplateSection::getId).collect(java.util.stream.Collectors.toList())
                        : null)
                .price(exam.getPrice())
                .sitePublished(exam.isSitePublished())
                .questions(standaloneQuestions)
                .passages(passages)
                .createdAt(exam.getCreatedAt())
                .updatedAt(exam.getUpdatedAt())
                .tags(exam.getTags())
                .isCollaborative(exam.isCollaborative())
                .collaborativeParentId(exam.getCollaborativeParentId())
                .collaborativeSubjects(collaborativeSubjects)
                .collaborativeTemplateSections(collaborativeTemplateSections)
                .pendingManualCount(submissionRepository.countPendingGradingByExamIdExcludingHidden(exam.getId()))
                .averageRating(submissionRepository.findAverageRatingByExamId(exam.getId()))
                .ratingCount(submissionRepository.countRatingsByExamId(exam.getId()))
                // Attempt count (submitted submissions) — same metric as the list
                // endpoint, so the detail page no longer always shows 0 (#255).
                .participantCount(submissionRepository.countByExamIdAndSubmittedAtIsNotNull(exam.getId()))
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
                .reviewStatus(q.getReviewStatus())
                .reviewComment(q.getReviewComment())
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
                .attachedImageLeft(p.getAttachedImageLeft())
                .attachedImageRight(p.getAttachedImageRight())
                .orderIndex(p.getOrderIndex())
                .build();
    }
}
