package az.testup.repository;

import az.testup.entity.ExamSubject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ExamSubjectRepository extends JpaRepository<ExamSubject, Long> {
    @Query("SELECT DISTINCT s FROM ExamSubject s LEFT JOIN FETCH s.topics ORDER BY s.name ASC")
    List<ExamSubject> findAllByOrderByNameAsc();
    boolean existsByName(String name);
    Optional<ExamSubject> findByName(String name);
}
