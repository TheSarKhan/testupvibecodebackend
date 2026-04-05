package az.testup.service;

import az.testup.dto.request.CollaboratorAssignment;
import az.testup.dto.request.CreateCollaborativeExamRequest;
import az.testup.dto.response.CollaborativeExamResponse;
import az.testup.dto.response.CollaboratorResponse;
import az.testup.dto.response.CollaboratorSectionInfo;
import az.testup.entity.*;
import az.testup.enums.CollaboratorStatus;
import az.testup.enums.ExamStatus;
import az.testup.enums.ExamType;
import az.testup.enums.ExamVisibility;
import az.testup.enums.QuestionType;
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
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CollaborativeExamService {

    private final ExamRepository examRepository;
    private final ExamCollaboratorRepository collaboratorRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final TemplateSectionRepository templateSectionRepository;
    private final TemplateRepository templateRepository;

    // ─── Admin operations ───────────────────────────────────────────────────

    @Transactional
    public CollaborativeExamResponse createCollaborativeExam(CreateCollaborativeExamRequest req, User admin) {
        if (req.title() == null || req.title().isBlank()) {
            throw new BadRequestException("İmtahan adı boş ola bilməz");
        }
        if (req.collaborators() == null || req.collaborators().isEmpty()) {
            throw new BadRequestException("Ən az bir müəllim təyin edilməlidir");
        }

        boolean isTemplate = "TEMPLATE".equalsIgnoreCase(req.examType());

        // Resolve template if template mode
        Template template = null;
        if (isTemplate) {
            if (req.templateId() == null) throw new BadRequestException("Şablon seçilməlidir");
            template = templateRepository.findById(req.templateId())
                    .orElseThrow(() -> new ResourceNotFoundException("Şablon tapılmadı"));
        }

        // Collect all subjects
        List<String> allSubjects;
        if (isTemplate) {
            // Subjects come from template section names
            List<Long> allSectionIds = req.collaborators().stream()
                    .flatMap(c -> c.templateSectionIds() != null ? c.templateSectionIds().stream() : java.util.stream.Stream.empty())
                    .distinct()
                    .collect(Collectors.toList());
            allSubjects = templateSectionRepository.findAllById(allSectionIds).stream()
                    .map(TemplateSection::getSubjectName)
                    .distinct()
                    .collect(Collectors.toList());
        } else {
            allSubjects = req.collaborators().stream()
                    .flatMap(c -> c.subjects() != null ? c.subjects().stream() : java.util.stream.Stream.empty())
                    .distinct()
                    .collect(Collectors.toList());
        }

        // Create the main collaborative exam owned by admin
        Exam exam = Exam.builder()
                .title(req.title())
                .description(req.description())
                .durationMinutes(req.durationMinutes())
                .subjects(allSubjects)
                .visibility(ExamVisibility.PRIVATE)
                .examType(isTemplate ? ExamType.TEMPLATE : ExamType.FREE)
                .status(ExamStatus.DRAFT)
                .shareLink(CodeGenerator.generateShareLink())
                .teacher(admin)
                .isCollaborative(true)
                .template(template)
                .build();
        exam = examRepository.save(exam);

        // Create collaborator entries
        List<ExamCollaborator> collaborators = new ArrayList<>();
        for (CollaboratorAssignment assignment : req.collaborators()) {
            User teacher = userRepository.findByEmail(assignment.teacherEmail())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Müəllim tapılmadı: " + assignment.teacherEmail()));

            List<String> assignedSubjects;
            List<Long> sectionIds;
            if (isTemplate) {
                sectionIds = assignment.templateSectionIds() != null ? assignment.templateSectionIds() : new ArrayList<>();
                assignedSubjects = templateSectionRepository.findAllById(sectionIds).stream()
                        .map(TemplateSection::getSubjectName)
                        .collect(Collectors.toList());
            } else {
                sectionIds = new ArrayList<>();
                assignedSubjects = assignment.subjects() != null ? assignment.subjects() : new ArrayList<>();
            }

            ExamCollaborator collaborator = ExamCollaborator.builder()
                    .collaborativeExam(exam)
                    .teacher(teacher)
                    .subjects(assignedSubjects)
                    .templateSectionIds(sectionIds)
                    .status(CollaboratorStatus.ASSIGNED)
                    .build();
            collaborators.add(collaboratorRepository.save(collaborator));

            String subjectDisplay = isTemplate
                    ? assignedSubjects.stream().map(s -> s + " (" + sectionIds.size() + " bölmə)").collect(Collectors.joining(", "))
                    : String.join(", ", assignedSubjects);

            notificationService.send(teacher,
                    "Birgə İmtahan Təyinatı",
                    "\"" + exam.getTitle() + "\" imtahanı üçün sual əlavə etmək üçün seçildiniz. " +
                    "Fənnlər: " + String.join(", ", assignedSubjects));
        }

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

    @Transactional
    public CollaboratorResponse addCollaborator(Long examId, CollaboratorAssignment assignment) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("İmtahan tapılmadı"));
        User teacher = userRepository.findByEmail(assignment.teacherEmail())
                .orElseThrow(() -> new ResourceNotFoundException("Müəllim tapılmadı: " + assignment.teacherEmail()));

        if (collaboratorRepository.findByCollaborativeExamIdAndTeacherId(examId, teacher.getId()).isPresent()) {
            throw new BadRequestException("Bu müəllim artıq bu imtahana təyin edilib");
        }

        boolean isTemplate = exam.getTemplate() != null;
        List<Long> sectionIds = isTemplate && assignment.templateSectionIds() != null ? assignment.templateSectionIds() : new ArrayList<>();
        List<String> assignedSubjects = isTemplate
                ? templateSectionRepository.findAllById(sectionIds).stream().map(TemplateSection::getSubjectName).collect(Collectors.toList())
                : (assignment.subjects() != null ? assignment.subjects() : new ArrayList<>());

        ExamCollaborator collab = ExamCollaborator.builder()
                .collaborativeExam(exam)
                .teacher(teacher)
                .subjects(assignedSubjects)
                .templateSectionIds(sectionIds)
                .status(CollaboratorStatus.ASSIGNED)
                .build();
        collab = collaboratorRepository.save(collab);

        notificationService.send(teacher,
                "Birgə İmtahan Təyinatı",
                "\"" + exam.getTitle() + "\" imtahanı üçün sual əlavə etmək üçün seçildiniz.");

        return toCollaboratorResponse(collab);
    }

    @Transactional
    public void approveDraft(Long collaboratorId) {
        ExamCollaborator collab = collaboratorRepository.findById(collaboratorId)
                .orElseThrow(() -> new ResourceNotFoundException("Collaborator tapılmadı"));

        if (collab.getStatus() != CollaboratorStatus.SUBMITTED) {
            throw new BadRequestException("Bu suallar hələ göndərilməyib");
        }
        if (collab.getDraftExam() == null) {
            throw new BadRequestException("Draft imtahan tapılmadı");
        }

        Exam draftExam = collab.getDraftExam();
        Exam parentExam = collab.getCollaborativeExam();

        // Copy all questions from draft to parent exam
        int nextOrder = parentExam.getQuestions().size();
        for (Question draftQ : draftExam.getQuestions()) {
            Question newQ = Question.builder()
                    .content(draftQ.getContent())
                    .attachedImage(draftQ.getAttachedImage())
                    .questionType(draftQ.getQuestionType())
                    .points(draftQ.getPoints())
                    .orderIndex(nextOrder++)
                    .correctAnswer(draftQ.getCorrectAnswer())
                    .sampleAnswer(draftQ.getSampleAnswer())
                    .subjectGroup(draftQ.getSubjectGroup())
                    .exam(parentExam)
                    .build();

            for (Option opt : draftQ.getOptions()) {
                Option newOpt = Option.builder()
                        .content(opt.getContent())
                        .isCorrect(opt.getIsCorrect())
                        .orderIndex(opt.getOrderIndex())
                        .attachedImage(opt.getAttachedImage())
                        .question(newQ)
                        .build();
                newQ.getOptions().add(newOpt);
            }

            for (MatchingPair pair : draftQ.getMatchingPairs()) {
                MatchingPair newPair = MatchingPair.builder()
                        .leftItem(pair.getLeftItem())
                        .rightItem(pair.getRightItem())
                        .attachedImageLeft(pair.getAttachedImageLeft())
                        .attachedImageRight(pair.getAttachedImageRight())
                        .orderIndex(pair.getOrderIndex())
                        .question(newQ)
                        .build();
                newQ.getMatchingPairs().add(newPair);
            }

            parentExam.getQuestions().add(newQ);
        }

        examRepository.save(parentExam);

        collab.setStatus(CollaboratorStatus.APPROVED);
        collab.setAdminComment(null);
        collaboratorRepository.save(collab);

        notificationService.send(collab.getTeacher(),
                "Suallarınız Təsdiqləndi",
                "\"" + parentExam.getTitle() + "\" imtahanı üçün göndərdiyiniz suallar admin tərəfindən təsdiqləndi.");
    }

    @Transactional
    public void rejectDraft(Long collaboratorId, String comment) {
        ExamCollaborator collab = collaboratorRepository.findById(collaboratorId)
                .orElseThrow(() -> new ResourceNotFoundException("Collaborator tapılmadı"));

        if (collab.getStatus() != CollaboratorStatus.SUBMITTED) {
            throw new BadRequestException("Bu suallar hələ göndərilməyib");
        }

        collab.setStatus(CollaboratorStatus.REJECTED);
        collab.setAdminComment(comment);
        collaboratorRepository.save(collab);

        String msg = "\"" + collab.getCollaborativeExam().getTitle() + "\" imtahanı üçün göndərdiyiniz suallar geri qaytarıldı.";
        if (comment != null && !comment.isBlank()) {
            msg += " Şərh: " + comment;
        }
        notificationService.send(collab.getTeacher(), "Suallarınız Geri Qaytarıldı", msg);
    }

    public long getPendingCount() {
        return collaboratorRepository.countByStatus(CollaboratorStatus.SUBMITTED);
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
        if (collab.getStatus() == CollaboratorStatus.APPROVED) {
            throw new BadRequestException("Bu suallar artıq təsdiqlənib");
        }

        if (collab.getStatus() == CollaboratorStatus.REJECTED) {
            collab.setStatus(CollaboratorStatus.ASSIGNED);
            collab.setAdminComment(null);
        }

        if (collab.getDraftExam() == null) {
            Exam parent = collab.getCollaborativeExam();
            boolean isTemplateMode = !collab.getTemplateSectionIds().isEmpty();

            List<String> draftSubjects = isTemplateMode
                    ? templateSectionRepository.findAllById(collab.getTemplateSectionIds()).stream()
                            .map(TemplateSection::getSubjectName)
                            .collect(Collectors.toList())
                    : new ArrayList<>(collab.getSubjects());

            Exam draftExam = Exam.builder()
                    .title(parent.getTitle())
                    .description(parent.getDescription())
                    .durationMinutes(parent.getDurationMinutes())
                    .subjects(draftSubjects)
                    .visibility(ExamVisibility.PRIVATE)
                    .examType(ExamType.FREE)
                    .status(ExamStatus.DRAFT)
                    .shareLink(CodeGenerator.generateShareLink())
                    .teacher(teacher)
                    .collaborativeParentId(parent.getId())
                    .build();
            draftExam = examRepository.save(draftExam);

            // Pre-populate questions for template mode
            if (isTemplateMode) {
                List<TemplateSection> sections = templateSectionRepository.findAllById(collab.getTemplateSectionIds());
                int orderIdx = 0;
                for (TemplateSection section : sections) {
                    for (var tc : section.getTypeCounts()) {
                        for (int i = 0; i < tc.getCount(); i++) {
                            Question q = Question.builder()
                                    .content("")
                                    .questionType(tc.getQuestionType())
                                    .points(1.0)
                                    .orderIndex(orderIdx++)
                                    .subjectGroup(section.getSubjectName())
                                    .exam(draftExam)
                                    .build();
                            if (tc.getQuestionType() == QuestionType.MCQ || tc.getQuestionType() == QuestionType.MULTI_SELECT) {
                                String[] labels = {"A", "B", "C", "D"};
                                for (int j = 0; j < 4; j++) {
                                    q.getOptions().add(Option.builder()
                                            .content(labels[j]).isCorrect(false).orderIndex(j).question(q).build());
                                }
                            }
                            draftExam.getQuestions().add(q);
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

        collab.setStatus(CollaboratorStatus.SUBMITTED);
        collab.setSubmittedAt(java.time.Instant.now());
        collaboratorRepository.save(collab);

        User admin = collab.getCollaborativeExam().getTeacher();
        notificationService.send(admin,
                "Birgə İmtahan: Yeni Suallar",
                teacher.getFullName() + " \"" + collab.getCollaborativeExam().getTitle() +
                "\" imtahanı üçün suallar göndərdi. Təsdiq etmək üçün admin panelinə baxın.");
    }

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
        int draftQCount = (c.getDraftExam() != null) ? c.getDraftExam().getQuestions().size() : 0;

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
                sectionInfos
        );
    }
}
