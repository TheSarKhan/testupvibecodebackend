package az.testup.controller.admin;

import az.testup.dto.response.ExamSubjectResponse;
import az.testup.dto.response.SubjectStatsResponse;
import az.testup.dto.response.SubjectTopicResponse;
import az.testup.service.AdminSubjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/subjects")
@RequiredArgsConstructor
public class AdminSubjectController {

    private final AdminSubjectService adminSubjectService;

    @GetMapping
    public ResponseEntity<Page<ExamSubjectResponse>> getSubjects(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        return ResponseEntity.ok(adminSubjectService.getSubjects(PageRequest.of(page, size)));
    }

    @PostMapping
    public ResponseEntity<ExamSubjectResponse> addSubject(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(adminSubjectService.addSubject(
                body.getOrDefault("name", ""), body.get("category")));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSubject(@PathVariable Long id) {
        adminSubjectService.deleteSubject(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/topics")
    public ResponseEntity<SubjectTopicResponse> addTopic(@PathVariable Long id,
                                                         @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(adminSubjectService.addTopicToSubject(
                id, body.getOrDefault("name", ""), body.get("gradeLevel")));
    }

    @DeleteMapping("/{id}/topics/{topicId}")
    public ResponseEntity<Void> removeTopic(@PathVariable Long id, @PathVariable Long topicId) {
        adminSubjectService.removeTopicFromSubject(id, topicId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/metadata")
    public ResponseEntity<ExamSubjectResponse> updateSubjectMetadata(@PathVariable Long id,
                                                                     @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(adminSubjectService.updateSubjectMetadata(
                id, body.get("color"), body.get("iconEmoji"), body.get("description"),
                body.get("category")));
    }

    @GetMapping("/{id}/stats")
    public ResponseEntity<SubjectStatsResponse> getSubjectStats(@PathVariable Long id) {
        return ResponseEntity.ok(adminSubjectService.getSubjectStats(id));
    }
}
