package az.testup.controller;

import az.testup.repository.ExamSubjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/subjects")
@RequiredArgsConstructor
public class SubjectController {

    private final ExamSubjectRepository subjectRepository;

    @GetMapping
    public ResponseEntity<List<String>> getSubjects() {
        List<String> names = subjectRepository.findAllByOrderByNameAsc()
                .stream()
                .map(s -> s.getName())
                .toList();
        return ResponseEntity.ok(names);
    }
}
