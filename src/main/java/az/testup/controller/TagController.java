package az.testup.controller;

import az.testup.dto.response.TagResponse;
import az.testup.dto.response.TagStatsResponse;
import az.testup.entity.Tag;
import az.testup.enums.AuditAction;
import az.testup.exception.BadRequestException;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.ExamRepository;
import az.testup.repository.TagRepository;
import az.testup.service.AuditLogService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class TagController {

    private final TagRepository tagRepository;
    private final ExamRepository examRepository;
    private final AuditLogService auditLogService;

    @PersistenceContext
    private EntityManager em;

    /** GET /api/tags — public, returns all tag names sorted */
    @GetMapping("/api/tags")
    public List<String> getAllTags() {
        return tagRepository.findAll().stream()
                .map(Tag::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** GET /api/admin/tags — paginated, with usage counts */
    @GetMapping("/api/admin/tags")
    @PreAuthorize("hasRole('ADMIN')")
    public Page<TagResponse> getAdminTags(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.asc("name").ignoreCase()));
        Map<String, Long> usage = loadUsageMap();
        return tagRepository.findAll(pageable)
                .map(t -> new TagResponse(t.getId(), t.getName(), t.getColor(),
                        usage.getOrDefault(t.getName(), 0L)));
    }

    /** GET /api/admin/tags/stats */
    @GetMapping("/api/admin/tags/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public TagStatsResponse getStats() {
        Map<String, Long> usage = loadUsageMap();
        List<Tag> all = tagRepository.findAll();
        long total = all.size();
        long totalUsages = usage.values().stream().mapToLong(Long::longValue).sum();
        long untagged = examRepository.countUntaggedExams();

        List<TagResponse> top = all.stream()
                .map(t -> new TagResponse(t.getId(), t.getName(), t.getColor(),
                        usage.getOrDefault(t.getName(), 0L)))
                .sorted(Comparator.comparingLong(TagResponse::usageCount).reversed())
                .limit(8)
                .toList();

        return new TagStatsResponse(total, totalUsages, untagged, top);
    }

    @PostMapping("/api/admin/tags")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TagResponse> createTag(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        String color = body.get("color");
        if (name.isEmpty()) throw new BadRequestException("Teq adı boş ola bilməz");
        if (name.length() > 50) throw new BadRequestException("Teq adı maksimum 50 simvol ola bilər");
        if (tagRepository.existsByNameIgnoreCase(name)) {
            throw new BadRequestException("Bu teq artıq mövcuddur");
        }
        Tag saved = tagRepository.save(Tag.builder().name(name).color(color).build());
        auditLogService.logCurrent(AuditAction.TAG_CREATED, "TAG", saved.getName(), null);
        return ResponseEntity.ok(new TagResponse(saved.getId(), saved.getName(), saved.getColor(), 0L));
    }

    @PutMapping("/api/admin/tags/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<TagResponse> updateTag(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Teq tapılmadı"));
        String oldName = tag.getName();
        boolean nameChanged = false;
        if (body.containsKey("name")) {
            String newName = body.get("name").trim();
            if (newName.isEmpty()) throw new BadRequestException("Teq adı boş ola bilməz");
            if (!newName.equalsIgnoreCase(oldName) && tagRepository.existsByNameIgnoreCase(newName)) {
                throw new BadRequestException("Bu adda teq artıq mövcuddur");
            }
            tag.setName(newName);
            nameChanged = !newName.equals(oldName);
        }
        if (body.containsKey("color")) tag.setColor(body.get("color"));
        Tag saved = tagRepository.save(tag);

        // Cascade rename in exam_tags
        if (nameChanged) {
            em.createNativeQuery("UPDATE exam_tags SET tag = :newName WHERE tag = :oldName")
                    .setParameter("newName", saved.getName())
                    .setParameter("oldName", oldName)
                    .executeUpdate();
        }
        long usage = examRepository.countExamsWithTag(saved.getName());
        return ResponseEntity.ok(new TagResponse(saved.getId(), saved.getName(), saved.getColor(), usage));
    }

    @DeleteMapping("/api/admin/tags/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteTag(@PathVariable Long id) {
        Tag existing = tagRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        tagRepository.deleteById(id);
        auditLogService.logCurrent(AuditAction.TAG_DELETED, "TAG", existing.getName(), null);
        return ResponseEntity.ok().build();
    }

    /** Merge source tag into target — moves all exam_tags rows and deletes source */
    @PostMapping("/api/admin/tags/{sourceId}/merge/{targetId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<Map<String, Object>> mergeTags(@PathVariable Long sourceId, @PathVariable Long targetId) {
        if (sourceId.equals(targetId)) throw new BadRequestException("Mənbə və hədəf eyni ola bilməz");
        Tag source = tagRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Mənbə teq tapılmadı"));
        Tag target = tagRepository.findById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Hədəf teq tapılmadı"));

        // Remove rows where exam already has target (avoid duplicate)
        em.createNativeQuery("DELETE FROM exam_tags WHERE tag = :source " +
                        "AND exam_id IN (SELECT exam_id FROM exam_tags WHERE tag = :target)")
                .setParameter("source", source.getName())
                .setParameter("target", target.getName())
                .executeUpdate();

        // Rename remaining source rows to target
        int moved = em.createNativeQuery("UPDATE exam_tags SET tag = :target WHERE tag = :source")
                .setParameter("source", source.getName())
                .setParameter("target", target.getName())
                .executeUpdate();

        tagRepository.delete(source);
        auditLogService.logCurrent(AuditAction.TAG_DELETED, "TAG", source.getName(),
                "Merged into '" + target.getName() + "', moved " + moved + " exams");
        return ResponseEntity.ok(Map.of("merged", moved, "target", target.getName()));
    }

    /** Bulk delete */
    @PostMapping("/api/admin/tags/bulk-delete")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> bulkDelete(@RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) return ResponseEntity.ok(Map.of("deleted", 0));
        int count = 0;
        for (Long id : ids) {
            Tag t = tagRepository.findById(id).orElse(null);
            if (t != null) {
                tagRepository.delete(t);
                count++;
            }
        }
        return ResponseEntity.ok(Map.of("deleted", count));
    }

    private Map<String, Long> loadUsageMap() {
        Map<String, Long> map = new HashMap<>();
        for (Object[] row : examRepository.tagUsageCounts()) {
            map.put((String) row[0], ((Number) row[1]).longValue());
        }
        return map;
    }
}
