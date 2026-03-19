package az.testup.repository;

import az.testup.entity.SubjectTopic;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SubjectTopicRepository extends JpaRepository<SubjectTopic, Long> {
    List<SubjectTopic> findBySubjectIdOrderByOrderIndexAscNameAsc(Long subjectId);
    boolean existsBySubjectIdAndName(Long subjectId, String name);
    void deleteBySubjectId(Long subjectId);
    long countBySubjectId(Long subjectId);
}
