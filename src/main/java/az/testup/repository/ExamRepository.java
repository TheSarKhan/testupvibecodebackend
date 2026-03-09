package az.testup.repository;

import az.testup.entity.Exam;
import az.testup.entity.User;
import az.testup.enums.ExamStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamRepository extends JpaRepository<Exam, Long> {
    List<Exam> findByTeacher(User teacher);
    List<Exam> findByStatus(ExamStatus status);
    Optional<Exam> findByShareLink(String shareLink);
}
