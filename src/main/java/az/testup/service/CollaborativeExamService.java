package az.testup.service;

import az.testup.dto.request.CollaboratorAssignment;
import az.testup.dto.request.CreateCollaborativeExamRequest;
import az.testup.dto.response.CollaborativeExamResponse;
import az.testup.dto.response.CollaboratorResponse;
import az.testup.dto.response.CollaboratorSectionInfo;
import az.testup.dto.response.CollaboratorStatsResponse;
import az.testup.dto.response.CollaboratorStatsResponse.CollaboratorStatQuestion;
import az.testup.dto.response.CollaboratorStatsResponse.CollaboratorStatStudent;
import az.testup.entity.*;
import az.testup.enums.AuditAction;
import az.testup.enums.CollaboratorStatus;
import az.testup.enums.ExamStatus;
import az.testup.enums.ExamType;
import az.testup.enums.ExamVisibility;
import az.testup.enums.QuestionReviewStatus;
import az.testup.enums.QuestionType;
import az.testup.enums.Role;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.exception.UnauthorizedException;
import az.testup.repository.*;
import az.testup.util.CodeGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CollaborativeExamService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CollaborativeExamService.class);

    /**
     * Defer a side-effect (notification / audit log) until AFTER the surrounding
     * transaction commits. Plain try/catch was not enough: AuditLogService.logCurrent
     * self-invokes its own @Async log() method, so @Async never fires and the inner
     * repository.save runs synchronously inside the outer tx. If that save fails, JPA
     * marks the tx rollback-only and the swallowing catch can't undo that — Spring
     * still throws UnexpectedRollbackException at commit.
     *
     * Running the side-effect from {@code afterCommit} guarantees it can never poison
     * the main tx: if the main work fails, the side-effect simply doesn't run.
     */
    private void safeSideEffect(String label, Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                action.run();
                            } catch (Exception e) {
                                log.warn("[collab] after-commit '{}' failed: {}", label, e.toString());
                            }
                        }
                    });
        } else {
            try {
                action.run();
            } catch (Exception e) {
                log.warn("[collab] side-effect '{}' failed: {}", label, e.toString());
            }
        }
    }


    private final ExamRepository examRepository;
    private final ExamCollaboratorRepository collaboratorRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final TemplateSectionRepository templateSectionRepository;
    private final TemplateRepository templateRepository;
    private final AuditLogService auditLogService;
    private final QuestionRepository questionRepository;
    private final SubmissionRepository submissionRepository;
    private final PassageRepository passageRepository;

    // ─── Admin operations ───────────────────────────────────────────────────

    /**
     * Hybrid create: each {@link CollaboratorAssignment} chooses its own kind based on
     * which field is populated — {@code templateSectionIds} for template-section teachers,
     * {@code subjects} for free-form teachers. Both kinds can coexist in one exam.
     *
     * The exam-level {@code examType} / {@code template} are derived:
     *   • exactly one template referenced AND no free assignments → TEMPLATE + that template
     *   • anything else (mixed, multi-template, all-free)         → FREE + null template
     *
     * The legacy {@code req.examType} / {@code req.templateId} are accepted but ignored —
     * we trust the per-assignment shape over the old global toggle.
     */
    @Transactional
    public CollaborativeExamResponse createCollaborativeExam(CreateCollaborativeExamRequest req, User admin) {
        if (req.title() == null || req.title().isBlank()) {
            throw new BadRequestException("İmtahan adı boş ola bilməz");
        }
        if (req.collaborators() == null || req.collaborators().isEmpty()) {
            throw new BadRequestException("Ən az bir müəllim təyin edilməlidir");
        }

        // Validate every assignment + collect derived exam-level data in one pass.
        java.util.LinkedHashSet<String> allSubjects = new java.util.LinkedHashSet<>();
        java.util.Set<Long> referencedTemplateIds = new java.util.HashSet<>();
        // Aggregate every template section referenced across all collaborators. The parent
        // exam carries these so the editor can subject-level-lock template-bound subjects
        // even when the overall examType ends up FREE (hybrid case).
        java.util.LinkedHashMap<Long, TemplateSection> allTemplateSections = new java.util.LinkedHashMap<>();
        boolean anyFree = false, anyTemplate = false;

        for (CollaboratorAssignment a : req.collaborators()) {
            boolean isTemplate = a.templateSectionIds() != null && !a.templateSectionIds().isEmpty();
            boolean isFree     = a.subjects() != null && !a.subjects().isEmpty();
            if (!isTemplate && !isFree) {
                throw new BadRequestException(
                        a.teacherEmail() + " üçün fənn və ya şablon bölməsi seçin");
            }
            if (isTemplate) {
                anyTemplate = true;
                List<TemplateSection> secs = templateSectionRepository.findAllById(a.templateSectionIds());
                if (secs.size() != a.templateSectionIds().size()) {
                    throw new ResourceNotFoundException("Bəzi şablon bölmələri tapılmadı");
                }
                for (TemplateSection sec : secs) {
                    if (sec.getSubtitle() != null && sec.getSubtitle().getTemplate() != null) {
                        referencedTemplateIds.add(sec.getSubtitle().getTemplate().getId());
                    }
                    allSubjects.add(sec.getSubjectName());
                    allTemplateSections.putIfAbsent(sec.getId(), sec);
                }
            }
            if (isFree) {
                anyFree = true;
                allSubjects.addAll(a.subjects());
            }
        }

        // Exam-level template only when assignments are uniformly from one template.
        Template template = null;
        if (anyTemplate && !anyFree && referencedTemplateIds.size() == 1) {
            Long tid = referencedTemplateIds.iterator().next();
            template = templateRepository.findById(tid)
                    .orElseThrow(() -> new ResourceNotFoundException("Şablon tapılmadı"));
        }
        ExamType examType = template != null ? ExamType.TEMPLATE : ExamType.FREE;
        List<TemplateSection> parentSections = new ArrayList<>(allTemplateSections.values());

        Exam exam = Exam.builder()
                .title(req.title())
                .description(req.description())
                .durationMinutes(req.durationMinutes())
                .subjects(new ArrayList<>(allSubjects))
                // Collab exams are intended for students once approved; PUBLIC keeps the
                // admin's settings page coherent ("why does my exam say Private when I
                // built it to publish?"). Public catalog visibility still requires
                // sitePublished=true + status=PUBLISHED — both flipped together by
                // POST /admin/collaborative-exams/{id}/publish.
                .visibility(ExamVisibility.PUBLIC)
                .examType(examType)
                .status(ExamStatus.DRAFT)
                .shareLink(CodeGenerator.generateShareLink())
                .teacher(admin)
                .isCollaborative(true)
                .template(template)
                // Carry every contributed template section on the parent so the editor can
                // lock template-bound subjects per-subject even when examType is FREE.
                .templateSection(parentSections.size() == 1 ? parentSections.get(0) : null)
                .templateSections(new ArrayList<>(parentSections))
                .build();
        exam = examRepository.save(exam);

        // Create collaborator entries — kind is decided per-row, not by exam.
        List<ExamCollaborator> collaborators = new ArrayList<>();
        int templateCount = 0, freeCount = 0;
        for (CollaboratorAssignment assignment : req.collaborators()) {
            User teacher = userRepository.findByEmail(assignment.teacherEmail())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Müəllim tapılmadı: " + assignment.teacherEmail()));

            // A single collaborator can carry BOTH template sections AND free subjects
            // (hybrid). Merge both lists so stats filtering by subjectGroup later finds
            // questions from either kind. Section-derived names are appended after free
            // subjects, de-duped, to preserve the order the admin entered.
            boolean hasTemplate = assignment.templateSectionIds() != null && !assignment.templateSectionIds().isEmpty();
            boolean hasFree     = assignment.subjects() != null && !assignment.subjects().isEmpty();

            List<Long> sectionIds = hasTemplate ? assignment.templateSectionIds() : new ArrayList<>();
            List<String> assignedSubjects = new ArrayList<>();
            if (hasFree) assignedSubjects.addAll(assignment.subjects());
            if (hasTemplate) {
                for (TemplateSection sec : templateSectionRepository.findAllById(sectionIds)) {
                    if (!assignedSubjects.contains(sec.getSubjectName())) {
                        assignedSubjects.add(sec.getSubjectName());
                    }
                }
            }
            if (hasTemplate) templateCount++;
            if (hasFree)     freeCount++;

            ExamCollaborator collaborator = ExamCollaborator.builder()
                    .collaborativeExam(exam)
                    .teacher(teacher)
                    .subjects(assignedSubjects)
                    .templateSectionIds(sectionIds)
                    .status(CollaboratorStatus.ASSIGNED)
                    .build();
            collaborators.add(collaboratorRepository.save(collaborator));

            notificationService.send(teacher,
                    "Birgə İmtahan Təyinatı",
                    "\"" + exam.getTitle() + "\" imtahanı üçün sual əlavə etmək üçün seçildiniz. " +
                    "Fənnlər: " + String.join(", ", assignedSubjects));
        }

        String kindLabel = templateCount > 0 && freeCount > 0 ? "HYBRID"
                : templateCount > 0 ? "TEMPLATE" : "FREE";
        auditLogService.log(AuditAction.COLLABORATIVE_EXAM_CREATED, admin.getEmail(), admin.getFullName(),
                "COLLABORATIVE_EXAM", exam.getTitle(),
                "Müəllim sayı: " + collaborators.size() + ", Tip: " + kindLabel
                        + " (şablon: " + templateCount + ", sərbəst: " + freeCount + ")");

        return toExamResponse(exam, collaborators);
    }

    @Transactional(readOnly = true)
    public Page<CollaborativeExamResponse> getCollaborativeExams(Pageable pageable) {
        return examRepository.findByIsCollaborativeTrueAndDeletedFalseOrderByCreatedAtDesc(pageable)
                .map(exam -> {
                    List<ExamCollaborator> collabs = collaboratorRepository.findByCollaborativeExamId(exam.getId());
                    return toExamResponse(exam, collabs);
                });
    }

    @Transactional(readOnly = true)
    public CollaborativeExamResponse getCollaborativeExamDetail(Long examId) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        if (!exam.isCollaborative()) {
            throw new BadRequestException("Bu birgə imtahan deyil");
        }
        List<ExamCollaborator> collabs = collaboratorRepository.findByCollaborativeExamId(examId);
        return toExamResponse(exam, collabs);
    }

    /**
     * Add one collaborator to an existing collaborative exam. The kind (template vs free)
     * is decided per-payload — exam-level template no longer dictates. This lets admins
     * extend a previously template-only exam with a free-form teacher and vice versa.
     */
    @Transactional
    public CollaboratorResponse addCollaborator(Long examId, CollaboratorAssignment assignment) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        User teacher = userRepository.findByEmail(assignment.teacherEmail())
                .orElseThrow(() -> new ResourceNotFoundException("Müəllim tapılmadı: " + assignment.teacherEmail()));

        if (collaboratorRepository.findByCollaborativeExamIdAndTeacherId(examId, teacher.getId()).isPresent()) {
            throw new BadRequestException("Bu müəllim artıq bu imtahana təyin edilib");
        }

        boolean hasTemplate = assignment.templateSectionIds() != null && !assignment.templateSectionIds().isEmpty();
        boolean hasFree     = assignment.subjects() != null && !assignment.subjects().isEmpty();
        if (!hasTemplate && !hasFree) {
            throw new BadRequestException("Fənn və ya şablon bölməsi seçin");
        }

        // Hybrid-friendly: merge both kinds into one collaborator.
        List<Long> sectionIds = hasTemplate ? assignment.templateSectionIds() : new ArrayList<>();
        List<String> assignedSubjects = new ArrayList<>();
        if (hasFree) assignedSubjects.addAll(assignment.subjects());
        if (hasTemplate) {
            List<TemplateSection> secs = templateSectionRepository.findAllById(sectionIds);
            if (secs.size() != sectionIds.size()) {
                throw new ResourceNotFoundException("Bəzi şablon bölmələri tapılmadı");
            }
            for (TemplateSection sec : secs) {
                if (!assignedSubjects.contains(sec.getSubjectName())) {
                    assignedSubjects.add(sec.getSubjectName());
                }
            }
        }

        ExamCollaborator collab = ExamCollaborator.builder()
                .collaborativeExam(exam)
                .teacher(teacher)
                .subjects(assignedSubjects)
                .templateSectionIds(sectionIds)
                .status(CollaboratorStatus.ASSIGNED)
                .build();
        collab = collaboratorRepository.save(collab);

        // Add this collaborator's subjects to the exam's master subject list (de-duped).
        List<String> examSubjects = exam.getSubjects() != null ? new ArrayList<>(exam.getSubjects()) : new ArrayList<>();
        boolean changed = false;
        for (String s : assignedSubjects) {
            if (!examSubjects.contains(s)) { examSubjects.add(s); changed = true; }
        }
        if (changed) exam.setSubjects(examSubjects);

        // And carry the new template sections on the parent so the editor's per-subject
        // lock detection picks them up (mirrors createCollaborativeExam).
        if (hasTemplate) {
            List<Long> existingIds = exam.getTemplateSections() != null
                    ? exam.getTemplateSections().stream().map(TemplateSection::getId).collect(Collectors.toList())
                    : new ArrayList<>();
            List<TemplateSection> toAdd = templateSectionRepository.findAllById(sectionIds).stream()
                    .filter(sec -> !existingIds.contains(sec.getId()))
                    .collect(Collectors.toList());
            if (!toAdd.isEmpty()) {
                if (exam.getTemplateSections() == null) exam.setTemplateSections(new ArrayList<>());
                exam.getTemplateSections().addAll(toAdd);
                if (exam.getTemplateSection() == null && !exam.getTemplateSections().isEmpty()) {
                    exam.setTemplateSection(exam.getTemplateSections().get(0));
                }
                changed = true;
            }
        }

        if (changed) examRepository.save(exam);

        notificationService.send(teacher,
                "Birgə İmtahan Təyinatı",
                "\"" + exam.getTitle() + "\" imtahanı üçün sual əlavə etmək üçün seçildiniz.");

        String kindLabel = hasTemplate && hasFree ? "HYBRID" : hasTemplate ? "TEMPLATE" : "FREE";
        auditLogService.logCurrent(AuditAction.COLLABORATIVE_COLLABORATOR_ADDED, "COLLABORATIVE_EXAM",
                exam.getTitle(),
                "Müəllim: " + teacher.getEmail() + ", Tip: " + kindLabel);

        return toCollaboratorResponse(collab);
    }

    /**
     * Batch-approve: marks every PENDING question in the draft as APPROVED, copies them
     * to the parent exam, and finalises the collaborator. Already-approved questions are
     * left alone (they were copied on a previous round), already-rejected questions are
     * NOT auto-flipped — admin must explicitly re-review them.
     */
    @Transactional
    public void approveDraft(Long collaboratorId) {
        log.info("[collab] approveDraft start: collaboratorId={}", collaboratorId);
        ExamCollaborator collab = collaboratorRepository.findById(collaboratorId)
                .orElseThrow(() -> new ResourceNotFoundException("Collaborator tapılmadı"));
        requireSubmitted(collab);
        if (collab.getDraftExam() == null) throw new BadRequestException("Draft imtahan tapılmadı");

        Exam parentExam = collab.getCollaborativeExam();
        List<Question> snapshot = new ArrayList<>(collab.getDraftExam().getQuestions());

        // Sort by subject so each fənn lands in the parent exam as one contiguous block —
        // the student-facing question nav would otherwise interleave subjects exactly as
        // the teacher typed them (Az-Az-Tarix-Az-Az…). Order between subjects follows the
        // collaborator.subjects list (the admin's original assignment order); a stable sort
        // preserves the teacher's intra-subject ordering. Questions with no subject (legacy
        // data) sort to the end.
        final List<String> subjectOrder = collab.getSubjects() != null
                ? collab.getSubjects() : java.util.Collections.emptyList();
        snapshot.sort(java.util.Comparator.comparingInt(q -> {
            String s = q.getSubjectGroup();
            if (s == null) return Integer.MAX_VALUE;
            int idx = subjectOrder.indexOf(s);
            return idx < 0 ? Integer.MAX_VALUE - 1 : idx;
        }));

        log.info("[collab] approveDraft loaded: draft has {} questions; parent has {} existing; subject order={}",
                snapshot.size(), parentExam.getQuestions().size(), subjectOrder);

        // One parent passage per draft passage across the whole batch (COL-1 dedup).
        Map<Long, Passage> passageMap = new HashMap<>();
        int processed = 0;
        List<Long> autoRejected = new ArrayList<>();
        for (Question q : snapshot) {
            if (q.getReviewStatus() != QuestionReviewStatus.PENDING) continue;

            // COL-9: a typeless draft question can't be copied (questions.question_type is
            // NOT NULL) and previously 500'd the whole batch. Auto-reject it with a clear
            // comment so the valid questions still get approved and the teacher can fix it.
            if (q.getQuestionType() == null) {
                q.setReviewStatus(QuestionReviewStatus.REJECTED);
                q.setReviewComment("Sualın tipi təyin edilməyib — zəhmət olmasa sual tipini seçib yenidən göndərin.");
                questionRepository.save(q);
                autoRejected.add(q.getId());
                continue;
            }

            try {
                approveQuestionInternal(q, parentExam, passageMap);
                processed++;
            } catch (Exception e) {
                log.error("[collab] approveQuestion FAILED: qid={}, type={}, subject={}, content-len={}",
                        q.getId(), q.getQuestionType(), q.getSubjectGroup(),
                        q.getContent() != null ? q.getContent().length() : -1, e);
                throw e;
            }
        }
        if (!autoRejected.isEmpty()) {
            log.warn("[collab] approveDraft: {} question(s) auto-rejected (missing type): {}",
                    autoRejected.size(), autoRejected);
        }
        log.info("[collab] approveDraft loop done: approved={} in this round", processed);
        resortParentQuestionsBySubject(parentExam);
        examRepository.save(parentExam);
        finalizeReviewInternal(collab);
        log.info("[collab] approveDraft finalize done");
    }

    /** Batch-reject: every PENDING question becomes REJECTED with the same comment. */
    @Transactional
    public void rejectDraft(Long collaboratorId, String comment) {
        ExamCollaborator collab = collaboratorRepository.findById(collaboratorId)
                .orElseThrow(() -> new ResourceNotFoundException("Collaborator tapılmadı"));
        requireSubmitted(collab);

        for (Question q : new ArrayList<>(collab.getDraftExam().getQuestions())) {
            if (q.getReviewStatus() == QuestionReviewStatus.PENDING) {
                q.setReviewStatus(QuestionReviewStatus.REJECTED);
                q.setReviewComment(comment);
                questionRepository.save(q);
            }
        }
        collab.setAdminComment(comment);
        finalizeReviewInternal(collab);
    }

    // ─── Per-question (hybrid) review ────────────────────────────────────────

    /**
     * Approve a single draft question: copies it into the parent collaborative exam and
     * marks it APPROVED. Caller must invoke {@link #finalizeReview} when finished with
     * the batch — the collaborator's overall status is recomputed there.
     */
    @Transactional
    public void approveQuestion(Long questionId) {
        Question q = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Sual tapılmadı"));
        ExamCollaborator collab = collaboratorFromDraftQuestion(q);
        requireSubmitted(collab);

        if (q.getReviewStatus() == QuestionReviewStatus.APPROVED) return; // idempotent
        if (q.getReviewStatus() != QuestionReviewStatus.PENDING) {
            throw new BadRequestException("Bu sual təsdiq mərhələsində deyil");
        }
        if (q.getQuestionType() == null) {
            throw new BadRequestException("Sualın tipi təyin edilməyib — təsdiqlənə bilməz.");
        }
        Exam parent = collab.getCollaborativeExam();
        approveQuestionInternal(q, parent, new HashMap<>());
        // Keep the parent exam's question order grouped by subject after each per-question
        // approval — otherwise admin's hand-approval (Az-1, Tarix-1, Az-2) leaves the
        // parent interleaved and the student question-nav looks scrambled.
        resortParentQuestionsBySubject(parent);
        examRepository.save(parent);
    }

    /** Reject a single draft question with a per-question comment (visible to teacher). */
    @Transactional
    public void rejectQuestion(Long questionId, String comment) {
        Question q = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Sual tapılmadı"));
        ExamCollaborator collab = collaboratorFromDraftQuestion(q);
        requireSubmitted(collab);

        if (q.getReviewStatus() != QuestionReviewStatus.PENDING) {
            throw new BadRequestException("Bu sual təsdiq mərhələsində deyil");
        }
        q.setReviewStatus(QuestionReviewStatus.REJECTED);
        q.setReviewComment(comment);
        questionRepository.save(q);
    }

    /**
     * Close the review round: derive collaborator status from per-question states.
     *   • all APPROVED, none rejected → APPROVED, "təsdiqləndi" notification
     *   • any REJECTED                → REJECTED, "düzəliş et" notification
     *   • still has PENDING           → BadRequest (admin hasn't finished reviewing)
     */
    @Transactional
    public void finalizeReview(Long collaboratorId) {
        ExamCollaborator collab = collaboratorRepository.findById(collaboratorId)
                .orElseThrow(() -> new ResourceNotFoundException("Collaborator tapılmadı"));
        requireSubmitted(collab);
        finalizeReviewInternal(collab);
    }

    // ─── Private review helpers ──────────────────────────────────────────────

    private void requireSubmitted(ExamCollaborator collab) {
        if (collab.getStatus() != CollaboratorStatus.SUBMITTED) {
            throw new BadRequestException("Bu suallar hələ göndərilməyib");
        }
    }

    /** Find the collaborator that owns the draft exam this question belongs to. */
    private ExamCollaborator collaboratorFromDraftQuestion(Question q) {
        if (q.getExam() == null) throw new BadRequestException("Sual heç bir imtahana bağlı deyil");
        return collaboratorRepository.findByDraftExamId(q.getExam().getId())
                .orElseThrow(() -> new BadRequestException("Bu sual birgə imtahan draft-ına aid deyil"));
    }

    /**
     * Reorder the parent exam's questions so every subjectGroup sits in one contiguous
     * block, ordered by the subject's position in parent.subjects (the admin's
     * authoritative subject order). Stable sort preserves intra-subject ordering. Only
     * runs while the parent is still DRAFT — once students may have started submissions,
     * reshuffling orderIndex would break their question-nav UX.
     *
     * Called after every approval (per-question + batch) so an admin who hand-approves
     * Az-1, Tarix-1, Az-2 still ends up with [Az-1, Az-2, Tarix-1] in the parent. Also
     * fixes the re-edit cycle: questions re-approved after a teacher edit no longer get
     * appended at the end out of subject order.
     */
    private void resortParentQuestionsBySubject(Exam parent) {
        if (parent.getStatus() != ExamStatus.DRAFT) return;
        final List<String> subjectOrder = parent.getSubjects() != null
                ? parent.getSubjects() : java.util.Collections.emptyList();
        List<Question> qs = new ArrayList<>(parent.getQuestions());
        qs.sort(java.util.Comparator.comparingInt(q -> {
            String s = q.getSubjectGroup();
            if (s == null) return Integer.MAX_VALUE;
            int idx = subjectOrder.indexOf(s);
            return idx < 0 ? Integer.MAX_VALUE - 1 : idx;
        }));
        for (int i = 0; i < qs.size(); i++) {
            Question q = qs.get(i);
            Integer cur = q.getOrderIndex();
            if (cur == null || cur != i) q.setOrderIndex(i);
        }
    }

    /**
     * Promote a draft question into the parent exam. Phase 4 semantics:
     *   • first approval        → create a new parent question, remember its id on the draft
     *   • re-approval after edit → overwrite the previously promoted parent question in place
     *
     * Overwriting in place keeps existing student answers intact (Answer.question_id stays
     * valid), and keeps the parent exam stable for in-progress sessions.
     */
    private void approveQuestionInternal(Question draftQ, Exam parentExam, Map<Long, Passage> passageMap) {
        // COL-7: resolve the previously-promoted parent question directly by id instead of
        // scanning every parent question. Verify it still belongs to this parent exam.
        Question target = null;
        if (draftQ.getParentCopyId() != null) {
            target = questionRepository.findById(draftQ.getParentCopyId())
                    .filter(pq -> pq.getExam() != null && pq.getExam().getId() != null
                            && pq.getExam().getId().equals(parentExam.getId()))
                    .orElse(null);
        }

        if (target == null) {
            // First-time promotion — create fresh and append to parent.
            target = Question.builder()
                    .orderIndex(parentExam.getQuestions().size())
                    .exam(parentExam)
                    .build();
            parentExam.getQuestions().add(target);
        }

        // Copy/overwrite scalar fields. orderIndex is preserved when overwriting (don't
        // shuffle questions during a re-edit cycle). Null-guard the not-null columns —
        // older draft data sometimes has empty content stored as null, and the parent
        // copy would otherwise fail the questions.content NOT NULL constraint at flush.
        target.setContent(draftQ.getContent() != null ? draftQ.getContent() : "");
        target.setAttachedImage(draftQ.getAttachedImage());
        target.setQuestionType(draftQ.getQuestionType());
        target.setPoints(draftQ.getPoints() != null ? draftQ.getPoints() : 1.0);
        target.setCorrectAnswer(draftQ.getCorrectAnswer());
        target.setSampleAnswer(draftQ.getSampleAnswer());
        target.setSubjectGroup(draftQ.getSubjectGroup());

        // COL-1: bring the passage (reading text / listening audio / image) into the PARENT
        // exam. The draft passage belongs to the draft exam, so we create/reuse an equivalent
        // passage owned by the parent — never link the draft passage directly.
        target.setPassage(draftQ.getPassage() == null
                ? null
                : resolveParentPassage(draftQ.getPassage(), parentExam, target, passageMap));

        // Rebuild options. orphanRemoval on the @OneToMany handles row deletion.
        // Option.isCorrect is NOT NULL on the DB — coerce null → false so a teacher who
        // never toggled a variant doesn't blow up the whole batch approve.
        target.getOptions().clear();
        for (Option opt : draftQ.getOptions()) {
            target.getOptions().add(Option.builder()
                    .content(opt.getContent() != null ? opt.getContent() : "")
                    .isCorrect(Boolean.TRUE.equals(opt.getIsCorrect()))
                    .orderIndex(opt.getOrderIndex())
                    .attachedImage(opt.getAttachedImage())
                    .question(target)
                    .build());
        }
        target.getMatchingPairs().clear();
        for (MatchingPair pair : draftQ.getMatchingPairs()) {
            target.getMatchingPairs().add(MatchingPair.builder()
                    .leftItem(pair.getLeftItem())
                    .rightItem(pair.getRightItem())
                    .attachedImageLeft(pair.getAttachedImageLeft())
                    .attachedImageRight(pair.getAttachedImageRight())
                    .orderIndex(pair.getOrderIndex())
                    .question(target)
                    .build());
        }

        // saveAndFlush forces an immediate INSERT/UPDATE — if a constraint fails on this
        // specific question, the exception is thrown HERE rather than at commit time. That
        // surfaces the real cause to the approveDraft loop's try-catch and lets us log
        // which question (and which field) blew up.
        examRepository.saveAndFlush(parentExam);

        // Remember the parent id on the draft so the next re-approval finds it.
        draftQ.setParentCopyId(target.getId());
        draftQ.setReviewStatus(QuestionReviewStatus.APPROVED);
        draftQ.setReviewComment(null);
        questionRepository.saveAndFlush(draftQ);
    }

    /**
     * Resolve the parent-exam passage equivalent for a draft passage. Dedups within a batch
     * via {@code passageMap} and reuses the passage already linked to the existing parent
     * question on re-approval, so multiple questions sharing one passage — and repeated
     * review rounds — never create duplicate passages in the parent exam (COL-1).
     */
    private Passage resolveParentPassage(Passage draftPassage, Exam parentExam,
                                         Question existingTarget, Map<Long, Passage> passageMap) {
        Long draftPassageId = draftPassage.getId();
        if (draftPassageId != null && passageMap.containsKey(draftPassageId)) {
            return passageMap.get(draftPassageId);
        }
        Passage parentPassage = null;
        // Davamlı dedup (COL-1): bu draft passage əvvəl parent-ə köçürülübsə (tək-tək
        // təsdiqdə fərqli passageMap-larla belə), həmin parent passage-i təkrar istifadə et.
        if (draftPassage.getParentCopyId() != null) {
            parentPassage = passageRepository.findById(draftPassage.getParentCopyId())
                    .filter(p -> p.getExam() != null && p.getExam().getId() != null
                            && p.getExam().getId().equals(parentExam.getId()))
                    .orElse(null);
        }
        // Re-approval: reuse the passage already attached to the existing parent question
        // (when it belongs to this parent exam) instead of creating a duplicate.
        if (parentPassage == null && existingTarget != null && existingTarget.getPassage() != null
                && existingTarget.getPassage().getExam() != null
                && existingTarget.getPassage().getExam().getId() != null
                && existingTarget.getPassage().getExam().getId().equals(parentExam.getId())) {
            parentPassage = existingTarget.getPassage();
        }
        if (parentPassage == null) {
            parentPassage = new Passage();
            parentPassage.setExam(parentExam);
            parentExam.getPassages().add(parentPassage);
        }
        parentPassage.setPassageType(draftPassage.getPassageType());
        parentPassage.setTitle(draftPassage.getTitle());
        parentPassage.setTextContent(draftPassage.getTextContent());
        parentPassage.setAttachedImage(draftPassage.getAttachedImage());
        parentPassage.setAudioContent(draftPassage.getAudioContent());
        parentPassage.setListenLimit(draftPassage.getListenLimit());
        parentPassage.setOrderIndex(draftPassage.getOrderIndex());
        parentPassage.setSubjectGroup(draftPassage.getSubjectGroup());
        passageRepository.save(parentPassage);
        if (draftPassageId != null) passageMap.put(draftPassageId, parentPassage);
        // Draft passage-də parent id-ni yadda saxla ki, sonrakı tək-tək təsdiqlər
        // (yeni passageMap ilə) həmin parent passage-i tapıb dublikat yaratmasın.
        if (parentPassage.getId() != null
                && !parentPassage.getId().equals(draftPassage.getParentCopyId())) {
            draftPassage.setParentCopyId(parentPassage.getId());
            passageRepository.save(draftPassage);
        }
        return parentPassage;
    }

    private void finalizeReviewInternal(ExamCollaborator collab) {
        // COL-3: a SUBMITTED collaborator with no draft would NPE here on the
        // reject/finalize paths. Fail clearly instead of 500-ing.
        if (collab.getDraftExam() == null) {
            throw new BadRequestException("Bu təyinat üçün draft imtahan tapılmadı");
        }
        List<Question> draftQs = collab.getDraftExam().getQuestions();
        long pending  = draftQs.stream().filter(q -> q.getReviewStatus() == QuestionReviewStatus.PENDING).count();
        long approved = draftQs.stream().filter(q -> q.getReviewStatus() == QuestionReviewStatus.APPROVED).count();
        long rejected = draftQs.stream().filter(q -> q.getReviewStatus() == QuestionReviewStatus.REJECTED).count();

        if (pending > 0) {
            throw new BadRequestException(pending + " sual hələ yoxlanmayıb. Bütün suallar üçün qərar verin.");
        }

        Exam parent = collab.getCollaborativeExam();

        if (rejected == 0) {
            collab.setStatus(CollaboratorStatus.APPROVED);
            collab.setAdminComment(null);
        } else {
            collab.setStatus(CollaboratorStatus.REJECTED);
        }
        collaboratorRepository.save(collab);

        // Side effects (notify + audit) run AFTER the main entity update so their failure
        // can't poison the rollback flag of the main transaction. See safeSideEffect.
        final long approvedFinal = approved, rejectedFinal = rejected;
        if (rejected == 0) {
            safeSideEffect("notify-approved", () -> notificationService.send(collab.getTeacher(),
                    "Suallarınız Təsdiqləndi",
                    "\"" + parent.getTitle() + "\" imtahanı üçün göndərdiyiniz " + approvedFinal
                            + " sual admin tərəfindən təsdiqləndi."));
            safeSideEffect("audit-approved", () -> auditLogService.logCurrent(
                    AuditAction.COLLABORATIVE_DRAFT_APPROVED, "COLLABORATIVE_EXAM",
                    parent.getTitle(),
                    "Müəllim: " + collab.getTeacher().getEmail() + ", Təsdiqlənmiş sual: " + approvedFinal));
        } else {
            String base = "\"" + parent.getTitle() + "\" imtahanı üçün " + rejectedFinal
                    + " sual geri qaytarıldı. Hər sualın altında admin şərhini görəcəksiniz.";
            final String msg = approvedFinal > 0
                    ? approvedFinal + " sual təsdiqləndi, " + rejectedFinal + " sual rədd edildi. Rədd ediləni düzəldib yenidən göndərin."
                    : base;
            safeSideEffect("notify-rejected", () -> notificationService.send(collab.getTeacher(),
                    "Suallarınız Geri Qaytarıldı", msg));
            safeSideEffect("audit-rejected", () -> auditLogService.logCurrent(
                    AuditAction.COLLABORATIVE_DRAFT_REJECTED, "COLLABORATIVE_EXAM",
                    parent.getTitle(),
                    "Müəllim: " + collab.getTeacher().getEmail()
                            + ", Təsdiqlənmiş: " + approvedFinal + ", Rədd edilmiş: " + rejectedFinal));
        }
    }

    public long getPendingCount() {
        return collaboratorRepository.countByStatus(CollaboratorStatus.SUBMITTED);
    }

    /**
     * One-click publish for a collaborative exam: flips sitePublished AND promotes the
     * parent's status to PUBLISHED AND switches visibility to PUBLIC. Replaces the old
     * three-step dance (PUT /exams/:id for status, PATCH .../toggle-site-published for the
     * flag, and a manual settings change for visibility) that left admins wondering why
     * students still couldn't see the exam after they "published" it.
     */
    @Transactional
    public CollaborativeExamResponse publishCollaborativeExam(Long examId) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        if (!exam.isCollaborative()) {
            throw new BadRequestException("Bu birgə imtahan deyil");
        }
        // Publish on the strength of what has been ACCEPTED, not on every collaborator
        // being fully APPROVED. Only a SUBMITTED collaborator blocks — those questions are
        // still in the review queue and the admin hasn't decided on them yet, so we surface
        // who to review. A REJECTED collaborator no longer blocks: their approved questions
        // are already promoted into the parent exam and the rejected ones are simply left
        // out, so the exam can go live with the accepted subset while the teacher keeps
        // fixing the rest (their later re-approvals flow into the published exam). (BUG:
        // birgə imtahan — bütün müəllimlərin təsdiqi tələb olunurdu.)
        List<ExamCollaborator> collabs = collaboratorRepository.findByCollaborativeExamId(examId);
        for (ExamCollaborator c : collabs) {
            if (c.getStatus() == CollaboratorStatus.SUBMITTED) {
                throw new BadRequestException(
                        c.getTeacher().getFullName() + " müəllimin sualları hələ yoxlanmayıb");
            }
        }

        // The parent exam holds exactly the promoted (approved) questions, so an empty
        // parent means nothing has been accepted yet — there is nothing to show students.
        if (exam.getQuestions() == null || exam.getQuestions().isEmpty()) {
            throw new BadRequestException("Yayımlamaq üçün ən azı bir təsdiqlənmiş sual olmalıdır");
        }

        exam.setStatus(ExamStatus.PUBLISHED);
        exam.setSitePublished(true);
        exam.setVisibility(ExamVisibility.PUBLIC);
        Exam saved = examRepository.save(exam);

        safeSideEffect("audit-publish", () -> auditLogService.logCurrent(
                AuditAction.EXAM_SITE_PUBLISHED, "COLLABORATIVE_EXAM", saved.getTitle(),
                "Birgə imtahan yayımlandı"));

        return toExamResponse(saved, collabs);
    }

    /**
     * Delete a collaborative exam (admin-only). Soft-deletes the parent exam so it leaves
     * the admin list and the student catalog (mirrors ExamService.deleteExam's setDeleted
     * flag), then tears down the per-teacher scaffolding: every collaborator's draft exam is
     * soft-deleted too (so it disappears from that teacher's exam list) and the collaborator
     * rows are removed — otherwise teachers would keep seeing ghost "my-assignments" pointing
     * at a deleted exam.
     */
    @Transactional
    public void deleteCollaborativeExam(Long examId) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        if (!exam.isCollaborative()) {
            throw new BadRequestException("Bu birgə imtahan deyil");
        }

        String examTitle = exam.getTitle();
        List<ExamCollaborator> collabs = collaboratorRepository.findByCollaborativeExamId(examId);
        for (ExamCollaborator c : collabs) {
            Exam draft = c.getDraftExam();
            // Break the FK from collaborator → draft before removing the row, then soft-delete
            // the draft so the section teacher's own exam list no longer surfaces it.
            c.setDraftExam(null);
            if (draft != null) {
                draft.setDeleted(true);
                examRepository.save(draft);
            }
        }
        collaboratorRepository.deleteAll(collabs);

        exam.setDeleted(true);
        examRepository.save(exam);

        auditLogService.logCurrent(AuditAction.EXAM_DELETED, "COLLABORATIVE_EXAM", examTitle,
                "Birgə imtahan silindi (müəllim sayı: " + collabs.size() + ")");
    }

    /** Unpublish: hide from the public catalog and roll back to DRAFT so the admin can edit. */
    @Transactional
    public CollaborativeExamResponse unpublishCollaborativeExam(Long examId) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        if (!exam.isCollaborative()) {
            throw new BadRequestException("Bu birgə imtahan deyil");
        }
        exam.setSitePublished(false);
        // Leave status as PUBLISHED if it was — only flip site-publish. Admins who really
        // want to edit pull status back via the regular flow.
        examRepository.save(exam);
        List<ExamCollaborator> collabs = collaboratorRepository.findByCollaborativeExamId(examId);
        return toExamResponse(exam, collabs);
    }

    // ─── Teacher operations ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CollaboratorResponse> getMyCollaborativeAssignments(User teacher) {
        return collaboratorRepository.findByTeacherId(teacher.getId()).stream()
                .map(this::toCollaboratorResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public Long getOrCreateDraftExam(Long collaboratorId, User teacher) {
        ExamCollaborator collab = collaboratorRepository.findById(collaboratorId)
                .orElseThrow(() -> new ResourceNotFoundException("Təyinat tapılmadı"));

        if (!collab.getTeacher().getId().equals(teacher.getId())) {
            throw new UnauthorizedException("Bu təyinat sizə aid deyil");
        }

        if (collab.getStatus() == CollaboratorStatus.SUBMITTED) {
            throw new BadRequestException("Suallarınız artıq göndərilib, admin yoxlayır");
        }

        // REJECTED or APPROVED → reset to ASSIGNED so the teacher can re-edit. APPROVED
        // questions stay APPROVED in the draft and remain locked in the parent exam until
        // a content change kicks them back to PENDING (handled in ExamService.update).
        if (collab.getStatus() == CollaboratorStatus.REJECTED
                || collab.getStatus() == CollaboratorStatus.APPROVED) {
            collab.setStatus(CollaboratorStatus.ASSIGNED);
            collab.setAdminComment(null);
        }

        if (collab.getDraftExam() == null) {
            Exam parent = collab.getCollaborativeExam();
            boolean hasTemplate = !collab.getTemplateSectionIds().isEmpty();

            // Resolve template entities up-front so the draft can be wired exactly like a
            // standalone template exam — the editor checks data.templateSectionId(s) to
            // unlock template behaviour (formula card, locked counts, AI gen with section
            // context, etc.).
            List<TemplateSection> assignedSections = hasTemplate
                    ? new ArrayList<>(templateSectionRepository.findAllById(collab.getTemplateSectionIds()))
                    : new ArrayList<>();
            Template template = null;
            if (!assignedSections.isEmpty()) {
                TemplateSection first = assignedSections.get(0);
                if (first.getSubtitle() != null) template = first.getSubtitle().getTemplate();
            }

            // Subject ordering: template-bound subjects first (so they become the editor's
            // "main"/"extra" template sections), then any pure-free subjects as extras.
            List<String> draftSubjects = new ArrayList<>();
            for (TemplateSection sec : assignedSections) {
                if (!draftSubjects.contains(sec.getSubjectName())) draftSubjects.add(sec.getSubjectName());
            }
            for (String s : collab.getSubjects()) {
                if (!draftSubjects.contains(s)) draftSubjects.add(s);
            }

            Exam draftExam = Exam.builder()
                    .title(parent.getTitle())
                    .description(parent.getDescription())
                    .durationMinutes(parent.getDurationMinutes())
                    .subjects(draftSubjects)
                    .visibility(ExamVisibility.PRIVATE)
                    .examType(hasTemplate ? ExamType.TEMPLATE : ExamType.FREE)
                    .status(ExamStatus.DRAFT)
                    .shareLink(CodeGenerator.generateShareLink())
                    .teacher(teacher)
                    .collaborativeParentId(parent.getId())
                    .template(template)
                    .templateSection(assignedSections.size() == 1 ? assignedSections.get(0) : null)
                    .templateSections(assignedSections.size() >= 2 ? new ArrayList<>(assignedSections) : new ArrayList<>())
                    .build();
            draftExam = examRepository.save(draftExam);

            // Pre-populate template-section skeletons. Mirrors the frontend's
            // buildQuestionsWithPointGroups so the per-section pointGroups (e.g. DİM
            // "Q1-15 = 1, Q16-20 = 1.5") drive each skeleton question's points — instead
            // of the hard 1.0 we used before, which left teachers staring at confusing
            // values that didn't match the section's formula.
            if (hasTemplate) {
                List<TemplateSection> sections = assignedSections.isEmpty()
                        ? templateSectionRepository.findAllById(collab.getTemplateSectionIds())
                        : assignedSections;
                int orderIdx = 0;
                for (TemplateSection section : sections) {
                    List<double[]> pointRanges = az.testup.util.PointGroups.parse(section.getPointGroups());
                    int sectionIdx = 0;
                    for (var tc : section.getTypeCounts()) {
                        for (int i = 0; i < tc.getCount(); i++) {
                            double pts = az.testup.util.PointGroups.pointsFor(pointRanges, sectionIdx + 1);
                            Question q = Question.builder()
                                    .content("")
                                    .questionType(tc.getQuestionType())
                                    .points(pts)
                                    .orderIndex(orderIdx++)
                                    .subjectGroup(section.getSubjectName())
                                    .exam(draftExam)
                                    .build();
                            if (tc.getQuestionType() == QuestionType.MCQ || tc.getQuestionType() == QuestionType.MULTI_SELECT) {
                                // Empty content so the frontend's "A variantı / B variantı..."
                                // placeholders show through. Storing literal "A"/"B"/... was
                                // overwriting the placeholder hint and made every option look
                                // pre-filled with its label as the answer text.
                                for (int j = 0; j < 4; j++) {
                                    q.getOptions().add(Option.builder()
                                            .content("").isCorrect(false).orderIndex(j).question(q).build());
                                }
                            }
                            draftExam.getQuestions().add(q);
                            sectionIdx++;
                        }
                    }
                }
                examRepository.save(draftExam);
            }

            collab.setDraftExam(draftExam);
        }

        collaboratorRepository.save(collab);
        return collab.getDraftExam().getId();
    }

    @Transactional
    public void submitDraft(Long draftExamId, User teacher) {
        ExamCollaborator collab = collaboratorRepository.findByDraftExamId(draftExamId)
                .orElseThrow(() -> new ResourceNotFoundException("Bu draft imtahan üçün təyinat tapılmadı"));

        if (!collab.getTeacher().getId().equals(teacher.getId())) {
            throw new UnauthorizedException("Bu təyinat sizə aid deyil");
        }
        if (collab.getStatus() != CollaboratorStatus.ASSIGNED) {
            throw new BadRequestException("Bu suallar artıq göndərilib və ya təsdiqlənib");
        }
        if (collab.getDraftExam() == null || collab.getDraftExam().getQuestions().isEmpty()) {
            throw new BadRequestException("Göndərməzdən əvvəl ən az bir sual əlavə edin");
        }

        // Mark every non-APPROVED question (new + previously rejected) as PENDING so the
        // admin sees a fresh review queue. APPROVED ones are already in the parent exam.
        int submittedCount = 0;
        for (Question q : collab.getDraftExam().getQuestions()) {
            if (q.getReviewStatus() != QuestionReviewStatus.APPROVED) {
                q.setReviewStatus(QuestionReviewStatus.PENDING);
                q.setReviewComment(null);
                submittedCount++;
            }
        }
        if (submittedCount == 0) {
            throw new BadRequestException("Göndəriləcək yeni və ya rədd edilmiş sual yoxdur");
        }

        collab.setStatus(CollaboratorStatus.SUBMITTED);
        collab.setSubmittedAt(java.time.Instant.now());
        collaboratorRepository.save(collab);

        User admin = collab.getCollaborativeExam().getTeacher();
        notificationService.send(admin,
                "Birgə İmtahan: Yeni Suallar",
                teacher.getFullName() + " \"" + collab.getCollaborativeExam().getTitle() +
                "\" imtahanı üçün suallar göndərdi. Təsdiq etmək üçün admin panelinə baxın.");

        auditLogService.log(AuditAction.COLLABORATIVE_DRAFT_SUBMITTED, teacher.getEmail(), teacher.getFullName(),
                "COLLABORATIVE_EXAM", collab.getCollaborativeExam().getTitle(),
                "Sual sayı: " + collab.getDraftExam().getQuestions().size());
    }

    // ─── Per-teacher statistics ──────────────────────────────────────────────

    /**
     * Returns statistics restricted to one collaborator's slice of the parent exam — only
     * questions whose subjectGroup matches the teacher's assigned subjects. Accessible by
     * the assigned teacher or any admin.
     *
     * Per-question buckets:
     *   correct: graded and full points awarded
     *   partial: graded with 0 < score < points
     *   wrong:   graded with score = 0
     *   pending: OPEN_MANUAL and not yet graded by the section teacher
     *   skipped: no Answer row for this question on the submission
     */
    @Transactional(readOnly = true)
    public CollaboratorStatsResponse getCollaboratorStats(Long collaboratorId, User requester) {
        ExamCollaborator collab = collaboratorRepository.findById(collaboratorId)
                .orElseThrow(() -> new ResourceNotFoundException("Təyinat tapılmadı"));

        boolean isAdmin = requester != null && requester.getRole() == Role.ADMIN;
        boolean isOwner = requester != null && collab.getTeacher().getId().equals(requester.getId());
        if (!isAdmin && !isOwner) {
            throw new UnauthorizedException("Bu təyinatın statistikasına baxa bilmirsiniz");
        }

        Exam parent = collab.getCollaborativeExam();
        Set<String> mySubjects = new HashSet<>(collab.getSubjects());

        // "My questions" in the parent exam = approved copies whose subjectGroup is in my set
        List<Question> myQuestions = parent.getQuestions().stream()
                .filter(q -> q.getSubjectGroup() != null && mySubjects.contains(q.getSubjectGroup()))
                .collect(Collectors.toList());

        double totalPoints = myQuestions.stream()
                .mapToDouble(q -> q.getPoints() != null ? q.getPoints() : 0)
                .sum();

        // Submissions: only fully-submitted, non-hidden
        List<Submission> submissions = submissionRepository.findByExamId(parent.getId()).stream()
                .filter(s -> s.getSubmittedAt() != null)
                .filter(s -> !Boolean.TRUE.equals(s.getHiddenFromTeacher()))
                .collect(Collectors.toList());

        // Pre-index questions by id for per-row lookup
        Set<Long> myQuestionIds = myQuestions.stream().map(Question::getId).collect(Collectors.toSet());
        Map<Long, Question> qById = myQuestions.stream()
                .collect(Collectors.toMap(Question::getId, q -> q));

        // ── Per-question aggregates ──
        Map<Long, int[]> qBuckets = new HashMap<>(); // [attempt, correct, partial, wrong, skipped, pendingManual]
        Map<Long, Double> qSumScore = new HashMap<>();
        for (Question q : myQuestions) {
            qBuckets.put(q.getId(), new int[6]);
            qSumScore.put(q.getId(), 0.0);
        }

        // ── Per-student aggregates ──
        List<CollaboratorStatStudent> studentRows = new ArrayList<>(submissions.size());
        double sumPercent = 0;
        int countWithPct = 0;
        int totalPendingManual = 0;

        for (Submission s : submissions) {
            // Map of answer-by-questionId for this submission
            Map<Long, Answer> answerByQ = new HashMap<>();
            for (Answer a : s.getAnswers()) {
                if (a.getQuestion() != null && myQuestionIds.contains(a.getQuestion().getId())) {
                    answerByQ.put(a.getQuestion().getId(), a);
                }
            }

            double studentScore = 0;
            int correct = 0, partial = 0, wrong = 0, skipped = 0, pending = 0;

            for (Question q : myQuestions) {
                int[] b = qBuckets.get(q.getId());
                Answer a = answerByQ.get(q.getId());
                double qPoints = q.getPoints() != null ? q.getPoints() : 0;

                if (a == null) {
                    skipped++;
                    b[4]++; // skipped
                    continue;
                }

                boolean needsManual = q.getQuestionType() == QuestionType.OPEN_MANUAL;
                if (needsManual && !Boolean.TRUE.equals(a.getIsGraded())) {
                    pending++;
                    b[5]++; // pendingManual
                    continue;
                }

                double sc = a.getScore() != null ? a.getScore() : 0;
                studentScore += sc;
                b[0]++; // attempt
                qSumScore.merge(q.getId(), sc, Double::sum);

                if (qPoints > 0 && sc >= qPoints - 0.0001)        { correct++; b[1]++; }
                else if (sc <= 0.0001)                             { wrong++;   b[3]++; }
                else                                               { partial++; b[2]++; }
            }

            totalPendingManual += pending;
            Double pct = totalPoints > 0 ? (studentScore / totalPoints) * 100 : null;
            if (pct != null) { sumPercent += pct; countWithPct++; }

            studentRows.add(new CollaboratorStatStudent(
                    s.getId(),
                    s.getStudent() != null ? s.getStudent().getId() : null,
                    s.getStudent() != null ? s.getStudent().getFullName() : (s.getGuestName() != null ? s.getGuestName() : "Qonaq"),
                    studentScore,
                    totalPoints,
                    pct,
                    correct, partial, wrong, skipped, pending,
                    s.getSubmittedAt()
            ));
        }

        // Sort student rows by score desc, then submittedAt asc
        studentRows.sort((a, b) -> {
            double sa = a.score() != null ? a.score() : 0;
            double sb = b.score() != null ? b.score() : 0;
            int byScore = Double.compare(sb, sa);
            if (byScore != 0) return byScore;
            if (a.submittedAt() == null) return 1;
            if (b.submittedAt() == null) return -1;
            return a.submittedAt().compareTo(b.submittedAt());
        });

        // ── Build per-question rows ──
        List<CollaboratorStatQuestion> questionRows = new ArrayList<>(myQuestions.size());
        for (Question q : myQuestions) {
            int[] b = qBuckets.get(q.getId());
            int attempt = b[0], correct = b[1], partial = b[2], wrong = b[3], skipped = b[4], pendM = b[5];
            Double avg = attempt > 0 ? qSumScore.get(q.getId()) / attempt : null;
            Double rate = attempt > 0 ? (double) correct / attempt : null;
            questionRows.add(new CollaboratorStatQuestion(
                    q.getId(),
                    q.getContent(),
                    q.getQuestionType() != null ? q.getQuestionType().name() : null,
                    q.getSubjectGroup(),
                    q.getPoints(),
                    attempt, correct, partial, wrong, skipped, pendM,
                    avg, rate
            ));
        }

        Double avgPercent = countWithPct > 0 ? sumPercent / countWithPct : null;
        Double avgScore   = countWithPct > 0 && avgPercent != null
                ? avgPercent * totalPoints / 100
                : null;

        return new CollaboratorStatsResponse(
                collab.getId(),
                parent.getId(),
                parent.getTitle(),
                collab.getTeacher().getId(),
                collab.getTeacher().getFullName(),
                collab.getSubjects(),
                myQuestions.size(),
                totalPoints,
                submissions.size(),
                totalPendingManual,
                avgScore,
                avgPercent,
                studentRows,
                questionRows
        );
    }

    // pointGroups parsing now lives in az.testup.util.PointGroups (shared with
    // the standard template-to-exam flow in ExamService).

    // ─── Mappers ─────────────────────────────────────────────────────────────

    private CollaborativeExamResponse toExamResponse(Exam exam, List<ExamCollaborator> collaborators) {
        int totalQ = exam.getQuestions().size();
        return new CollaborativeExamResponse(
                exam.getId(),
                exam.getTitle(),
                exam.getDescription(),
                exam.getDurationMinutes(),
                exam.getStatus(),
                exam.isSitePublished(),
                exam.getShareLink(),
                collaborators.stream().map(this::toCollaboratorResponse).collect(Collectors.toList()),
                totalQ,
                exam.getCreatedAt()
        );
    }

    public CollaboratorResponse toCollaboratorResponse(ExamCollaborator c) {
        int draftQCount = 0;
        int pending = 0, approved = 0, rejected = 0;
        if (c.getDraftExam() != null) {
            for (Question q : c.getDraftExam().getQuestions()) {
                draftQCount++;
                if (q.getReviewStatus() == QuestionReviewStatus.PENDING)        pending++;
                else if (q.getReviewStatus() == QuestionReviewStatus.APPROVED)  approved++;
                else if (q.getReviewStatus() == QuestionReviewStatus.REJECTED)  rejected++;
            }
        }

        List<CollaboratorSectionInfo> sectionInfos = new ArrayList<>();
        if (!c.getTemplateSectionIds().isEmpty()) {
            sectionInfos = templateSectionRepository.findAllById(c.getTemplateSectionIds()).stream()
                    .map(s -> new CollaboratorSectionInfo(s.getId(), s.getSubjectName(), s.getQuestionCount(), s.getFormula()))
                    .collect(Collectors.toList());
        }

        return new CollaboratorResponse(
                c.getId(),
                c.getCollaborativeExam().getId(),
                c.getCollaborativeExam().getTitle(),
                c.getTeacher().getId(),
                c.getTeacher().getFullName(),
                c.getTeacher().getEmail(),
                c.getSubjects(),
                c.getStatus(),
                c.getAdminComment(),
                c.getDraftExam() != null ? c.getDraftExam().getId() : null,
                draftQCount,
                c.getSubmittedAt(),
                c.getCreatedAt(),
                sectionInfos,
                pending,
                approved,
                rejected
        );
    }
}
