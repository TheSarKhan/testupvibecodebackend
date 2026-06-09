package az.testup.service;

import az.testup.dto.response.ExamSubjectResponse;
import az.testup.dto.response.SubjectStatsResponse;
import az.testup.dto.response.SubjectTopicResponse;
import az.testup.entity.ExamSubject;
import az.testup.entity.SubjectTopic;
import az.testup.enums.AuditAction;
import az.testup.enums.Difficulty;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.BankQuestionRepository;
import az.testup.repository.ExamSubjectRepository;
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
    public ExamSubjectResponse addSubject(String name, String category) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) throw new BadRequestException("Fənn adı boş ola bilməz");
        if (subjectRepository.existsByName(trimmed)) {
            throw new BadRequestException("Bu fənn artıq mövcuddur");
        }
        String trimmedCategory = category == null || category.isBlank() ? null : category.trim();
        ExamSubject saved = subjectRepository.save(ExamSubject.builder()
                .name(trimmed)
                .category(trimmedCategory)
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
                                                     String description, String category) {
        ExamSubject subject = subjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (color != null) subject.setColor(color);
        if (iconEmoji != null) subject.setIconEmoji(iconEmoji);
        if (description != null) subject.setDescription(description);
        // Empty string clears the category (uncategorised); null = leave untouched.
        if (category != null) subject.setCategory(category.isBlank() ? null : category.trim());
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
                s.getCategory(),
                s.getDescription(),
                s.isDefault(),
                topics
        );
    }

    private SubjectTopicResponse toTopicResponse(SubjectTopic t) {
        return new SubjectTopicResponse(t.getId(), t.getName(), t.getGradeLevel());
    }
}
