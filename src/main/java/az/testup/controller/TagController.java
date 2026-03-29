package az.testup.controller;

import az.testup.entity.Tag;
import az.testup.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class TagController {

    private final TagRepository tagRepository;

    /** GET /api/tags — public, returns all tag names sorted alphabetically */
    @GetMapping("/api/tags")
    public List<String> getAllTags() {
        return tagRepository.findAll().stream()
                .map(Tag::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** POST /api/admin/tags — admin creates a new tag */
    @PostMapping("/api/admin/tags")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createTag(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Teq adı boş ola bilməz"));
        }
        name = name.trim();
        if (name.length() > 50) {
            return ResponseEntity.badRequest().body(Map.of("error", "Teq adı maksimum 50 simvol ola bilər"));
        }
        if (tagRepository.existsByNameIgnoreCase(name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Bu teq artıq mövcuddur"));
        }
        Tag saved = tagRepository.save(Tag.builder().name(name).build());
        return ResponseEntity.ok(Map.of("id", saved.getId(), "name", saved.getName()));
    }

    /** DELETE /api/admin/tags/{id} — admin deletes a tag */
    @DeleteMapping("/api/admin/tags/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteTag(@PathVariable Long id) {
        if (!tagRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        tagRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    /** GET /api/admin/tags — admin list with IDs */
    @GetMapping("/api/admin/tags")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> getAdminTags() {
        return tagRepository.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(t -> Map.<String, Object>of("id", t.getId(), "name", t.getName()))
                .toList();
    }
}
