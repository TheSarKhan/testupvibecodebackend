package az.testup.service;

import az.testup.dto.request.BankMatchingPairRequest;
import az.testup.dto.request.BankOptionRequest;
import az.testup.dto.request.BankQuestionRequest;
import az.testup.dto.request.BankSubjectRequest;
import az.testup.dto.response.*;
import az.testup.entity.*;
import az.testup.enums.AuditAction;
import az.testup.enums.Difficulty;
import az.testup.enums.QuestionType;
import az.testup.enums.Role;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.BankQuestionRepository;
import az.testup.repository.BankSubjectRepository;
import az.testup.repository.BankTopicRepository;
import az.testup.repository.ExamSubjectRepository;
import az.testup.repository.SubjectTopicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BankService {

    private final BankSubjectRepository subjectRepository;
    private final BankQuestionRepository questionRepository;
    private final BankTopicRepository topicRepository;
    private final ExamSubjectRepository examSubjectRepository;
    private final SubjectTopicRepository subjectTopicRepository;
    private final AuditLogService auditLogService;

    /** Number of recently-used topics pinned to the top of the picker. */
    private static final int RECENT_TOPIC_LIMIT = 6;

    // ─── Subjects ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BankSubjectResponse> getSubjectsForUser(User user) {
        List<BankSubject> own = subjectRepository.findByOwnerIdOrderByCreatedAtDesc(user.getId());

        List<BankSubject> global = subjectRepository.findByIsGlobalTrueOrderByCreatedAtDesc()
                .stream()
                .filter(g -> !g.getOwner().getId().equals(user.getId()))
                .toList();

        List<BankSubject> all = new ArrayList<>(own);
        all.addAll(global);
        return all.stream().map(this::mapSubject).collect(Collectors.toList());
    }

    @Transactional
    public BankSubjectResponse createSubject(User user, BankSubjectRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BadRequestException("Fənn adı boş ola bilməz");
        }
        BankSubject subject = BankSubject.builder()
                .name(req.getName().trim())
                .owner(user)
                .isGlobal(user.getRole() == Role.ADMIN)
                .build();
        BankSubject saved = subjectRepository.save(subject);
        auditLogService.log(AuditAction.BANK_SUBJECT_CREATED, user.getEmail(), user.getFullName(),
                "BANK_SUBJECT", saved.getName(), saved.getIsGlobal() ? "Qlobal" : "Şəxsi");
        return mapSubject(saved);
    }

    @Transactional
    public BankSubjectResponse updateSubject(Long id, User user, BankSubjectRequest req) {
        BankSubject subject = findSubjectOwned(id, user);
        if (req.getName() != null && !req.getName().isBlank()) {
            subject.setName(req.getName().trim());
        }
        BankSubject saved = subjectRepository.save(subject);
        auditLogService.log(AuditAction.BANK_SUBJECT_UPDATED, user.getEmail(), user.getFullName(),
                "BANK_SUBJECT", saved.getName(), null);
        return mapSubject(saved);
    }

    @Transactional
    public void deleteSubject(Long id, User user) {
        BankSubject subject = findSubjectOwned(id, user);
        String name = subject.getName();
        subjectRepository.delete(subject);
        auditLogService.log(AuditAction.BANK_SUBJECT_DELETED, user.getEmail(), user.getFullName(),
                "BANK_SUBJECT", name, null);
    }

    // ─── Questions: list / filter / page / sort ──────────────────────────────

    @Transactional(readOnly = true)
    public List<BankQuestionResponse> getQuestions(Long subjectId, User user) {
        BankSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (!subject.getOwner().getId().equals(user.getId()) && !subject.getIsGlobal()) {
            throw new BadRequestException("Bu fənnə giriş icazəniz yoxdur");
        }
        return questionRepository.findBySubjectIdOrderByOrderIndexAscCreatedAtAsc(subjectId)
                .stream().map(this::mapQuestion).collect(Collectors.toList());
    }

    /**
     * Filtered/sorted paginated questions.
     *
     * @param sort one of: order, newest, oldest, difficulty_asc, difficulty_desc, topic, points_desc, points_asc
     */
    @Transactional(readOnly = true)
    public Page<BankQuestionResponse> getQuestionsFiltered(
            Long subjectId, User user,
            String search, String topic, Difficulty difficulty,
            QuestionType type, String gradeLevel, Set<String> tags,
            String sort, int page, int size) {

        BankSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (!subject.getOwner().getId().equals(user.getId()) && !subject.getIsGlobal()) {
            throw new BadRequestException("Bu fənnə giriş icazəniz yoxdur");
        }

        List<BankQuestion> all = questionRepository.findBySubjectIdOrderByOrderIndexAscCreatedAtAsc(subjectId);

        // Filter
        String q = search == null ? null : search.trim().toLowerCase(Locale.ROOT);
        List<BankQuestion> filtered = all.stream().filter(bq -> {
            if (topic != null && !topic.isBlank() && !topic.equalsIgnoreCase(bq.getTopic())) return false;
            if (difficulty != null && bq.getDifficulty() != difficulty) return false;
            if (type != null && bq.getQuestionType() != type) return false;
            if (gradeLevel != null && !gradeLevel.isBlank() && !gradeLevel.equalsIgnoreCase(bq.getGradeLevel())) return false;
            if (tags != null && !tags.isEmpty()) {
                Set<String> qt = bq.getTags() == null ? Set.of() : bq.getTags();
                if (qt.stream().noneMatch(tags::contains)) return false;
            }
            if (q != null && !q.isEmpty()) {
                if (matches(bq.getContent(), q)) return true;
                if (matches(bq.getCorrectAnswer(), q)) return true;
                if (bq.getOptions() != null
                        && bq.getOptions().stream().anyMatch(o -> matches(o.getContent(), q))) return true;
                if (bq.getMatchingPairs() != null
                        && bq.getMatchingPairs().stream().anyMatch(mp ->
                                matches(mp.getLeftItem(), q) || matches(mp.getRightItem(), q))) return true;
                return false;
            }
            return true;
        }).collect(Collectors.toList());

        // Sort
        Comparator<BankQuestion> cmp = sortComparator(sort);
        filtered.sort(cmp);

        // Page
        int total = filtered.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<BankQuestionResponse> slice = filtered.subList(from, to)
                .stream().map(this::mapQuestion).toList();
        return new PageImpl<>(slice, PageRequest.of(page, size), total);
    }

    private boolean matches(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    private Comparator<BankQuestion> sortComparator(String sort) {
        Comparator<BankQuestion> byOrder = Comparator
                .comparing(BankQuestion::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(BankQuestion::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
        if (sort == null) return byOrder;
        return switch (sort) {
            case "newest" -> Comparator.comparing(BankQuestion::getCreatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            case "oldest" -> Comparator.comparing(BankQuestion::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "difficulty_asc" -> Comparator.comparing(
                    (BankQuestion b) -> difficultyWeight(b.getDifficulty()))
                    .thenComparing(byOrder);
            case "difficulty_desc" -> Comparator.comparing(
                    (BankQuestion b) -> -difficultyWeight(b.getDifficulty()))
                    .thenComparing(byOrder);
            case "topic" -> Comparator.comparing(
                    (BankQuestion b) -> b.getTopic() == null ? "~" : b.getTopic().toLowerCase(Locale.ROOT))
                    .thenComparing(byOrder);
            case "points_desc" -> Comparator.comparing(
                    BankQuestion::getPoints, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(byOrder);
            case "points_asc" -> Comparator.comparing(
                    BankQuestion::getPoints, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(byOrder);
            default -> byOrder;
        };
    }

    private int difficultyWeight(Difficulty d) {
        if (d == null) return 99;
        return switch (d) {
            case EASY -> 1;
            case MEDIUM -> 2;
            case HARD -> 3;
        };
    }

    // ─── Topics ──────────────────────────────────────────────────────────────

    /**
     * Topic options for the question editor: the teacher's own topics for this
     * subject (recently-used pinned first) merged with the admin's preset topics
     * for the matching exam subject (alphabetical).
     */
    @Transactional(readOnly = true)
    public List<BankTopicResponse> getTopics(Long subjectId, User user) {
        BankSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (!subject.getOwner().getId().equals(user.getId()) && !subject.getIsGlobal()) {
            throw new BadRequestException("Bu fənnə giriş icazəniz yoxdur");
        }

        // Teacher's own topics, most-recently-used first.
        List<String> ownNames = topicRepository
                .findBySubjectIdAndOwnerIdOrderByLastUsedAtDesc(subjectId, user.getId())
                .stream().map(BankTopic::getName).toList();

        // Admin preset topics for the matching exam subject (by name).
        List<String> presetNames = examSubjectRepository.findByName(subject.getName())
                .map(es -> subjectTopicRepository
                        .findBySubjectIdOrderByOrderIndexAscNameAsc(es.getId())
                        .stream().map(SubjectTopic::getName).toList())
                .orElse(List.of());

        List<String> recent = ownNames.stream().limit(RECENT_TOPIC_LIMIT).toList();
        Set<String> recentSet = new HashSet<>(recent);

        // Everything else, de-duplicated and sorted alphabetically (az locale).
        Set<String> rest = new LinkedHashSet<>(ownNames);
        rest.addAll(presetNames);
        rest.removeAll(recentSet);
        Collator collator = Collator.getInstance(new Locale("az"));
        List<String> restSorted = rest.stream().sorted(collator).toList();

        List<BankTopicResponse> out = new ArrayList<>();
        recent.forEach(n -> out.add(new BankTopicResponse(n, true)));
        restSorted.forEach(n -> out.add(new BankTopicResponse(n, false)));
        return out;
    }

    /** Upsert the teacher's topic registry so a saved topic is reusable next time. */
    private void touchTopic(BankSubject subject, User user, String topicRaw) {
        String name = normalizeText(topicRaw);
        if (name == null) return;
        BankTopic t = topicRepository
                .findBySubjectIdAndOwnerIdAndName(subject.getId(), user.getId(), name)
                .orElseGet(() -> BankTopic.builder()
                        .subject(subject).owner(user).name(name)
                        .createdAt(Instant.now()).build());
        t.setLastUsedAt(Instant.now());
        topicRepository.save(t);
    }

    // ─── Questions: CRUD ─────────────────────────────────────────────────────

    @Transactional
    public BankQuestionResponse createQuestion(User user, BankQuestionRequest req) {
        BankSubject subject = subjectRepository.findById(req.getSubjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        checkWriteAccess(subject, user);

        int nextOrder = (int) questionRepository.countBySubjectId(subject.getId());
        BankQuestion q = BankQuestion.builder()
                .content(req.getContent())
                .attachedImage(req.getAttachedImage())
                .questionType(req.getQuestionType())
                .points(req.getPoints() != null ? req.getPoints() : 1.0)
                .orderIndex(req.getOrderIndex() != null ? req.getOrderIndex() : nextOrder)
                .correctAnswer(req.getCorrectAnswer())
                .topic(normalizeText(req.getTopic()))
                .difficulty(req.getDifficulty())
                .gradeLevel(normalizeText(req.getGradeLevel()))
                .tags(normalizeTags(req.getTags()))
                .subject(subject)
                .build();

        applyOptions(q, req.getOptions());
        applyMatchingPairs(q, req.getMatchingPairs());
        BankQuestion savedQ = questionRepository.save(q);
        touchTopic(subject, user, req.getTopic());
        auditLogService.log(AuditAction.BANK_QUESTION_CREATED, user.getEmail(), user.getFullName(),
                "BANK_QUESTION", "ID:" + savedQ.getId(),
                "Fənn: " + subject.getName() + ", Tip: " + savedQ.getQuestionType());
        return mapQuestion(savedQ);
    }

    @Transactional
    public BankQuestionResponse updateQuestion(Long id, User user, BankQuestionRequest req) {
        BankQuestion q = questionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sual tapılmadı"));
        checkWriteAccess(q.getSubject(), user);

        q.setContent(req.getContent());
        q.setAttachedImage(req.getAttachedImage());
        q.setQuestionType(req.getQuestionType());
        q.setPoints(req.getPoints() != null ? req.getPoints() : 1.0);
        if (req.getOrderIndex() != null) q.setOrderIndex(req.getOrderIndex());
        q.setCorrectAnswer(req.getCorrectAnswer());
        q.setTopic(normalizeText(req.getTopic()));
        q.setDifficulty(req.getDifficulty());
        q.setGradeLevel(normalizeText(req.getGradeLevel()));
        if (q.getTags() == null) q.setTags(new HashSet<>());
        q.getTags().clear();
        Set<String> newTags = normalizeTags(req.getTags());
        if (newTags != null) q.getTags().addAll(newTags);

        q.getOptions().clear();
        applyOptions(q, req.getOptions());
        q.getMatchingPairs().clear();
        applyMatchingPairs(q, req.getMatchingPairs());
        BankQuestion savedQ = questionRepository.save(q);
        touchTopic(q.getSubject(), user, req.getTopic());
        auditLogService.log(AuditAction.BANK_QUESTION_UPDATED, user.getEmail(), user.getFullName(),
                "BANK_QUESTION", "ID:" + savedQ.getId(),
                "Fənn: " + q.getSubject().getName());
        return mapQuestion(savedQ);
    }

    @Transactional
    public void deleteQuestion(Long id, User user) {
        BankQuestion q = questionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sual tapılmadı"));
        checkWriteAccess(q.getSubject(), user);
        String subjectName = q.getSubject().getName();
        questionRepository.delete(q);
        auditLogService.log(AuditAction.BANK_QUESTION_DELETED, user.getEmail(), user.getFullName(),
                "BANK_QUESTION", "ID:" + id, "Fənn: " + subjectName);
    }

    @Transactional
    public int bulkDelete(User user, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        List<BankQuestion> qs = questionRepository.findAllById(ids);
        int deleted = 0;
        for (BankQuestion q : qs) {
            if (q.getSubject().getOwner().getId().equals(user.getId()) || user.getRole() == Role.ADMIN) {
                questionRepository.delete(q);
                deleted++;
            }
        }
        if (deleted > 0) {
            auditLogService.log(AuditAction.BANK_QUESTION_DELETED, user.getEmail(), user.getFullName(),
                    "BANK_QUESTION", "BULK", "Silinən sual sayı: " + deleted);
        }
        return deleted;
    }

    @Transactional
    public BankQuestionResponse cloneQuestion(Long id, User user) {
        BankQuestion src = questionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sual tapılmadı"));
        checkWriteAccess(src.getSubject(), user);

        int nextOrder = (int) questionRepository.countBySubjectId(src.getSubject().getId());
        BankQuestion copy = BankQuestion.builder()
                .content(src.getContent())
                .attachedImage(src.getAttachedImage())
                .questionType(src.getQuestionType())
                .points(src.getPoints())
                .orderIndex(nextOrder)
                .correctAnswer(src.getCorrectAnswer())
                .topic(src.getTopic())
                .difficulty(src.getDifficulty())
                .gradeLevel(src.getGradeLevel())
                .tags(src.getTags() == null ? new HashSet<>() : new HashSet<>(src.getTags()))
                .subject(src.getSubject())
                .build();
        if (src.getOptions() != null) {
            for (BankOption o : src.getOptions()) {
                copy.getOptions().add(BankOption.builder()
                        .content(o.getContent()).isCorrect(o.getIsCorrect())
                        .orderIndex(o.getOrderIndex()).attachedImage(o.getAttachedImage())
                        .question(copy).build());
            }
        }
        if (src.getMatchingPairs() != null) {
            for (BankMatchingPair mp : src.getMatchingPairs()) {
                copy.getMatchingPairs().add(BankMatchingPair.builder()
                        .leftItem(mp.getLeftItem()).rightItem(mp.getRightItem())
                        .attachedImageLeft(mp.getAttachedImageLeft())
                        .attachedImageRight(mp.getAttachedImageRight())
                        .orderIndex(mp.getOrderIndex()).question(copy).build());
            }
        }
        BankQuestion saved = questionRepository.save(copy);
        auditLogService.log(AuditAction.BANK_QUESTION_CREATED, user.getEmail(), user.getFullName(),
                "BANK_QUESTION", "ID:" + saved.getId(), "Klon mənbə: " + src.getId());
        return mapQuestion(saved);
    }

    @Transactional
    public void reorder(Long subjectId, User user, List<Long> orderedIds) {
        BankSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        checkWriteAccess(subject, user);
        if (orderedIds == null) return;
        Map<Long, Integer> orderMap = new HashMap<>();
        for (int i = 0; i < orderedIds.size(); i++) orderMap.put(orderedIds.get(i), i);
        List<BankQuestion> qs = questionRepository.findBySubjectIdOrderByOrderIndexAscCreatedAtAsc(subjectId);
        for (BankQuestion q : qs) {
            Integer idx = orderMap.get(q.getId());
            if (idx != null) q.setOrderIndex(idx);
        }
        questionRepository.saveAll(qs);
    }

    // ─── Stats ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getStats(Long subjectId, User user) {
        BankSubject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (!subject.getOwner().getId().equals(user.getId()) && !subject.getIsGlobal()) {
            throw new BadRequestException("Bu fənnə giriş icazəniz yoxdur");
        }
        List<BankQuestion> qs = questionRepository.findBySubjectIdOrderByOrderIndexAscCreatedAtAsc(subjectId);

        Map<String, Long> byType = qs.stream()
                .collect(Collectors.groupingBy(b -> b.getQuestionType().name(), Collectors.counting()));
        Map<String, Long> byDifficulty = qs.stream()
                .filter(b -> b.getDifficulty() != null)
                .collect(Collectors.groupingBy(b -> b.getDifficulty().name(), Collectors.counting()));
        Map<String, Long> byGrade = qs.stream()
                .filter(b -> b.getGradeLevel() != null && !b.getGradeLevel().isBlank())
                .collect(Collectors.groupingBy(BankQuestion::getGradeLevel, Collectors.counting()));
        long topics = qs.stream()
                .map(BankQuestion::getTopic)
                .filter(t -> t != null && !t.isBlank())
                .distinct().count();
        Set<String> tagSet = new HashSet<>();
        qs.forEach(b -> { if (b.getTags() != null) tagSet.addAll(b.getTags()); });

        Instant lastAdded = qs.stream()
                .map(BankQuestion::getCreatedAt)
                .filter(t -> t != null)
                .max(Comparator.naturalOrder())
                .orElse(null);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", qs.size());
        stats.put("topics", topics);
        stats.put("tagsCount", tagSet.size());
        stats.put("byType", byType);
        stats.put("byDifficulty", byDifficulty);
        stats.put("byGrade", byGrade);
        stats.put("lastAddedAt", lastAdded == null ? null : lastAdded.toString());
        return stats;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private BankSubject findSubjectOwned(Long id, User user) {
        BankSubject s = subjectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn tapılmadı"));
        if (!s.getOwner().getId().equals(user.getId()) && user.getRole() != Role.ADMIN) {
            throw new BadRequestException("Bu əməliyyat üçün icazəniz yoxdur");
        }
        return s;
    }

    private void checkWriteAccess(BankSubject subject, User user) {
        if (!subject.getOwner().getId().equals(user.getId()) && user.getRole() != Role.ADMIN) {
            throw new BadRequestException("Bu əməliyyat üçün icazəniz yoxdur");
        }
    }

    private String normalizeText(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private Set<String> normalizeTags(Set<String> tags) {
        if (tags == null) return new HashSet<>();
        return tags.stream()
                .filter(t -> t != null && !t.trim().isEmpty())
                .map(t -> t.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
    }

    private void applyOptions(BankQuestion q, List<BankOptionRequest> opts) {
        if (opts == null) return;
        for (int i = 0; i < opts.size(); i++) {
            BankOptionRequest o = opts.get(i);
            // Fall back to the list position when the caller leaves orderIndex
            // null (AI generation, distractors, legacy clients). Persisting a
            // null orderIndex made downstream sorting non-deterministic and
            // scrambled options/answers in PDF export (BUG-05) and pinned every
            // AI answer to the first slot (BUG-09).
            Integer orderIndex = o.getOrderIndex() != null ? o.getOrderIndex() : i;
            q.getOptions().add(BankOption.builder()
                    .content(o.getContent() != null ? o.getContent() : "")
                    .isCorrect(o.getIsCorrect() != null ? o.getIsCorrect() : false)
                    .orderIndex(orderIndex)
                    .attachedImage(o.getAttachedImage())
                    .question(q)
                    .build());
        }
    }

    private void applyMatchingPairs(BankQuestion q, List<BankMatchingPairRequest> pairs) {
        if (pairs == null) return;
        for (BankMatchingPairRequest mp : pairs) {
            q.getMatchingPairs().add(BankMatchingPair.builder()
                    .leftItem(mp.getLeftItem())
                    .rightItem(mp.getRightItem())
                    .attachedImageLeft(mp.getAttachedImageLeft())
                    .attachedImageRight(mp.getAttachedImageRight())
                    .orderIndex(mp.getOrderIndex())
                    .question(q)
                    .build());
        }
    }

    private BankSubjectResponse mapSubject(BankSubject s) {
        // All questions for this subject (small lists; OK for index page).
        List<BankQuestion> qs = questionRepository.findBySubjectIdOrderByOrderIndexAscCreatedAtAsc(s.getId());

        int easy = 0, medium = 0, hard = 0;
        Set<String> topicSet = new HashSet<>();
        Instant last = null;
        for (BankQuestion q : qs) {
            if (q.getDifficulty() != null) {
                switch (q.getDifficulty()) {
                    case EASY -> easy++;
                    case MEDIUM -> medium++;
                    case HARD -> hard++;
                }
            }
            if (q.getTopic() != null && !q.getTopic().isBlank()) topicSet.add(q.getTopic());
            if (q.getCreatedAt() != null && (last == null || q.getCreatedAt().isAfter(last))) {
                last = q.getCreatedAt();
            }
        }

        // Subject metadata (icon, color) from matching ExamSubject by name (if exists)
        String iconEmoji = null;
        String color = null;
        var meta = examSubjectRepository.findByName(s.getName()).orElse(null);
        if (meta != null) {
            iconEmoji = meta.getIconEmoji();
            color = meta.getColor();
        }

        return new BankSubjectResponse(
                s.getId(), s.getName(),
                s.getOwner().getId(), s.getOwner().getFullName(),
                s.getIsGlobal(), qs.size(),
                s.getCreatedAt() != null ? s.getCreatedAt().toString() : null,
                iconEmoji, color,
                easy, medium, hard,
                topicSet.size(),
                last == null ? null : last.toString()
        );
    }

    public BankQuestionResponse mapQuestion(BankQuestion q) {
        List<BankOptionResponse> opts = q.getOptions().stream()
                .map(o -> new BankOptionResponse(o.getId(), o.getContent(), o.getIsCorrect(), o.getOrderIndex(), o.getAttachedImage()))
                .collect(Collectors.toList());
        List<BankMatchingPairResponse> pairs = q.getMatchingPairs().stream()
                .map(mp -> new BankMatchingPairResponse(
                        mp.getId(), mp.getLeftItem(), mp.getRightItem(),
                        mp.getAttachedImageLeft(), mp.getAttachedImageRight(),
                        mp.getOrderIndex()))
                .collect(Collectors.toList());
        return new BankQuestionResponse(
                q.getId(), q.getSubject().getId(), q.getSubject().getName(),
                q.getContent(), q.getAttachedImage(), q.getQuestionType(),
                q.getPoints(), q.getOrderIndex(), q.getCorrectAnswer(),
                q.getTopic(), q.getDifficulty(),
                q.getGradeLevel(),
                q.getTags() == null ? Set.of() : new HashSet<>(q.getTags()),
                opts, pairs,
                q.getCreatedAt() != null ? q.getCreatedAt().toString() : null
        );
    }
}
