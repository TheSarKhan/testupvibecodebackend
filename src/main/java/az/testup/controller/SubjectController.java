package az.testup.controller;

import az.testup.dto.response.SubjectTopicResponse;
import az.testup.entity.ExamSubject;
import az.testup.repository.ExamSubjectRepository;
import az.testup.repository.SubjectTopicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/subjects")
@RequiredArgsConstructor
public class SubjectController {

    private final ExamSubjectRepository subjectRepository;
    private final SubjectTopicRepository subjectTopicRepository;

    @GetMapping
    public ResponseEntity<List<String>> getSubjects() {
        List<String> names = subjectRepository.findAllByOrderByNameAsc()
                .stream()
                .map(ExamSubject::getName)
                .toList();
        return ResponseEntity.ok(names);
    }

    /** Returns all subjects with metadata (id, name, color, iconEmoji). */
    @GetMapping("/meta")
    public ResponseEntity<List<java.util.Map<String, Object>>> getSubjectsMeta() {
        List<java.util.Map<String, Object>> result = subjectRepository.findAllByOrderByNameAsc()
                .stream()
                .map(s -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("name", s.getName());
                    m.put("color", s.getColor());
                    m.put("iconEmoji", s.getIconEmoji());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    /** Returns the topics for a subject matched by name. Empty list if not found. */
    @GetMapping("/topics")
    public ResponseEntity<List<SubjectTopicResponse>> getTopicsBySubjectName(@RequestParam String name) {
        return subjectRepository.findByName(name)
                .map(subject -> {
                    List<SubjectTopicResponse> topics = subjectTopicRepository
                            .findBySubjectIdOrderByOrderIndexAscNameAsc(subject.getId())
                            .stream()
                            .map(t -> new SubjectTopicResponse(t.getId(), t.getName(), t.getGradeLevel()))
                            .toList();
                    return ResponseEntity.ok(topics);
                })
                .orElse(ResponseEntity.ok(Collections.emptyList()));
    }
}
