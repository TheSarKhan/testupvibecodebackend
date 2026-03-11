package az.testup.repository;

import az.testup.entity.ExamSubject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamSubjectRepository extends JpaRepository<ExamSubject, Long> {
    List<ExamSubject> findAllByOrderByNameAsc();
    boolean existsByName(String name);
    Optional<ExamSubject> findByName(String name);
}
