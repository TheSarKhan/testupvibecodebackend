package az.testup.service;

import az.testup.dto.response.ExamSubjectResponse;
import az.testup.dto.response.SubjectCategoryResponse;
import az.testup.dto.response.SubjectStatsResponse;
import az.testup.dto.response.SubjectTopicResponse;
import az.testup.entity.ExamSubject;
import az.testup.entity.SubjectCategory;
import az.testup.entity.SubjectTopic;
import az.testup.enums.AuditAction;
import az.testup.enums.Difficulty;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.BankQuestionRepository;
import az.testup.repository.ExamSubjectRepository;
import az.testup.repository.SubjectCategoryRepository;
import az.testup.repository.SubjectTopicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminSubjectService {

    private final ExamSubjectRepository subjectRepository;
    private final SubjectTopicRepository subjectTopicRepository;
    private final SubjectCategoryRepository subjectCategoryRepository;
    private final BankQuestionRepository bankQuestionRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public Page<ExamSubjectResponse> getSubjects(Pageable pageable) {
        List<ExamSubject> all = subjectRepository.findAllByOrderByNameAsc();
        List<ExamSubjectResponse> mapped = all.stream().map(this::toResponse).toList();
        int from = (int) Math.min(pageable.getOffset(), mapped.size());
        int to = Math.min(from + pageable.getPageSize(), mapped.size());
        return new PageImpl<>(mapped.subList(from, to), pageable, mapped.size());
    }

    @Transactional
    public ExamSubjectResponse addSubject(String name, Long categoryId) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) throw new BadRequestException("Fənn adı boş ola bilməz");
        if (subjectRepository.existsByName(trimmed)) {
            throw new BadRequestException("Bu fənn artıq mövcuddur");
        }
        ExamSubject saved = subjectRepository.save(ExamSubject.builder()
                .name(trimmed)
                .category(resolveCategory(categoryId))
                .isDefault(false)
                .build());
        auditLogService.log(AuditAction.SUBJECT_ADDED, "admin", "Admin", "SUBJECT", trimmed, null);
        return toResponse(saved);
    }

    @Transactional
    public void deleteSubject(Long id) {
        ExamSubject subject = subjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (subject.isDefault()) {
            throw new BadRequestException("Default fənnlər silinə bilməz");
        }
        auditLogService.log(AuditAction.SUBJECT_DELETED, "admin", "Admin", "SUBJECT", subject.getName(), null);
        subjectRepository.deleteById(id);
    }

    @Transactional
    public SubjectTopicResponse addTopicToSubject(Long subjectId, String name, String gradeLevel) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) throw new BadRequestException("Mövzu adı boş ola bilməz");
        ExamSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (subjectTopicRepository.existsBySubjectIdAndName(subjectId, trimmed)) {
            throw new BadRequestException("Bu mövzu artıq mövcuddur");
        }
        long count = subjectTopicRepository.countBySubjectId(subjectId);
        SubjectTopic topic = SubjectTopic.builder()
                .name(trimmed)
                .gradeLevel(gradeLevel)
                .orderIndex((int) count)
                .subject(subject)
                .build();
        SubjectTopic saved = subjectTopicRepository.save(topic);
        auditLogService.logCurrent(AuditAction.TOPIC_ADDED, "TOPIC", saved.getName(),
                "Fənn: " + subject.getName() + (gradeLevel != null ? ", Sinif: " + gradeLevel : ""));
        return toTopicResponse(saved);
    }

    @Transactional
    public void removeTopicFromSubject(Long subjectId, Long topicId) {
        SubjectTopic topic = subjectTopicRepository.findById(topicId)
                .orElseThrow(() -> new ResourceNotFoundException("Mövzu tapılmadı"));
        if (!topic.getSubject().getId().equals(subjectId)) {
            throw new BadRequestException("Bu mövzu bu fənnə aid deyil");
        }
        auditLogService.logCurrent(AuditAction.TOPIC_DELETED, "TOPIC", topic.getName(),
                "Fənn: " + topic.getSubject().getName());
        subjectTopicRepository.deleteById(topicId);
    }

    @Transactional
    public ExamSubjectResponse updateSubjectMetadata(Long id, String color, String iconEmoji,
                                                     String description, String categoryIdRaw) {
        ExamSubject subject = subjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (color != null) subject.setColor(color);
        if (iconEmoji != null) subject.setIconEmoji(iconEmoji);
        if (description != null) subject.setDescription(description);
        // categoryId comes as a string from the generic map body:
        // null = leave untouched, "" = clear, numeric = assign that category.
        if (categoryIdRaw != null) {
            subject.setCategory(categoryIdRaw.isBlank() ? null : resolveCategory(parseCategoryId(categoryIdRaw)));
        }
        ExamSubject saved = subjectRepository.save(subject);
        auditLogService.logCurrent(AuditAction.SUBJECT_UPDATED, "SUBJECT", saved.getName(), null);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public SubjectStatsResponse getSubjectStats(Long subjectId) {
        if (!subjectRepository.existsById(subjectId)) {
            throw new ResourceNotFoundException("Fənn tapılmadı");
        }
        List<Object[]> rows = bankQuestionRepository.countBySubjectGroupByTopicAndDifficulty(subjectId);

        long totalQuestions = 0;
        Map<String, Long> byDifficulty = new HashMap<>();
        Map<String, Map<String, Long>> topicMap = new LinkedHashMap<>();

        for (Object[] row : rows) {
            String topic = (String) row[0];
            Difficulty diff = (Difficulty) row[1];
            long count = ((Number) row[2]).longValue();

            totalQuestions += count;

            String diffKey = diff != null ? diff.name() : "UNSET";
            byDifficulty.merge(diffKey, count, Long::sum);

            String topicKey = topic != null ? topic : "(Mövzusuz)";
            topicMap.computeIfAbsent(topicKey, k -> new HashMap<>()).merge(diffKey, count, Long::sum);
        }

        List<SubjectStatsResponse.TopicStat> byTopic = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> entry : topicMap.entrySet()) {
            long topicTotal = entry.getValue().values().stream().mapToLong(Long::longValue).sum();
            byTopic.add(new SubjectStatsResponse.TopicStat(entry.getKey(), topicTotal, entry.getValue()));
        }

        return new SubjectStatsResponse(totalQuestions, byDifficulty, byTopic);
    }

    private ExamSubjectResponse toResponse(ExamSubject s) {
        List<SubjectTopicResponse> topics = s.getTopics() == null ? List.of()
                : s.getTopics().stream()
                    .sorted(Comparator.comparingInt(t -> t.getOrderIndex() == null ? 0 : t.getOrderIndex()))
                    .map(this::toTopicResponse)
                    .toList();
        return new ExamSubjectResponse(
                s.getId(),
                s.getName(),
                s.getColor(),
                s.getIconEmoji(),
                s.getCategory() != null ? s.getCategory().getId() : null,
                s.getCategory() != null ? s.getCategory().getName() : null,
                s.getDescription(),
                s.isDefault(),
                topics
        );
    }

    private SubjectTopicResponse toTopicResponse(SubjectTopic t) {
        return new SubjectTopicResponse(t.getId(), t.getName(), t.getGradeLevel());
    }

    // ── Subject categories (admin-managed picker groups) ──

    @Transactional(readOnly = true)
    public List<SubjectCategoryResponse> getCategories() {
        return subjectCategoryRepository.findAllByOrderByOrderIndexAscNameAsc()
                .stream().map(this::toCategoryResponse).toList();
    }

    @Transactional
    public SubjectCategoryResponse createCategory(String name, Integer orderIndex, String color) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) throw new BadRequestException("Kateqoriya adı boş ola bilməz");
        if (subjectCategoryRepository.existsByName(trimmed)) {
            throw new BadRequestException("Bu kateqoriya artıq mövcuddur");
        }
        SubjectCategory saved = subjectCategoryRepository.save(SubjectCategory.builder()
                .name(trimmed)
                .orderIndex(orderIndex)
                .color(color)
                .isDefault(false)
                .build());
        auditLogService.logCurrent(AuditAction.SUBJECT_ADDED, "SUBJECT_CATEGORY", saved.getName(), null);
        return toCategoryResponse(saved);
    }

    @Transactional
    public SubjectCategoryResponse updateCategory(Long id, String name, Integer orderIndex, String color) {
        SubjectCategory category = subjectCategoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Kateqoriya tapılmadı"));
        if (name != null && !name.isBlank()) {
            String trimmed = name.trim();
            if (!trimmed.equals(category.getName()) && subjectCategoryRepository.existsByName(trimmed)) {
                throw new BadRequestException("Bu kateqoriya artıq mövcuddur");
            }
            category.setName(trimmed);
        }
        if (orderIndex != null) category.setOrderIndex(orderIndex);
        if (color != null) category.setColor(color.isBlank() ? null : color);
        SubjectCategory saved = subjectCategoryRepository.save(category);
        auditLogService.logCurrent(AuditAction.SUBJECT_UPDATED, "SUBJECT_CATEGORY", saved.getName(), null);
        return toCategoryResponse(saved);
    }

    @Transactional
    public void deleteCategory(Long id) {
        SubjectCategory category = subjectCategoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Kateqoriya tapılmadı"));
        if (category.isDefault()) {
            throw new BadRequestException("Default kateqoriyalar silinə bilməz");
        }
        // Detach subjects in-session too (the DB FK is ON DELETE SET NULL, but
        // managed ExamSubject entities must not keep a reference to a deleted row).
        subjectRepository.findAll().stream()
                .filter(s -> s.getCategory() != null && s.getCategory().getId().equals(id))
                .forEach(s -> {
                    s.setCategory(null);
                    subjectRepository.save(s);
                });
        auditLogService.logCurrent(AuditAction.SUBJECT_DELETED, "SUBJECT_CATEGORY", category.getName(), null);
        subjectCategoryRepository.deleteById(id);
    }

    private SubjectCategoryResponse toCategoryResponse(SubjectCategory c) {
        return new SubjectCategoryResponse(c.getId(), c.getName(), c.getOrderIndex(), c.getColor(), c.isDefault());
    }

    private SubjectCategory resolveCategory(Long categoryId) {
        if (categoryId == null) return null;
        return subjectCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Kateqoriya tapılmadı"));
    }

    private Long parseCategoryId(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Kateqoriya ID düzgün deyil");
        }
    }
}
