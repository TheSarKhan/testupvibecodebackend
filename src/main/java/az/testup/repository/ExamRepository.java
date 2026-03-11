package az.testup.repository;

import az.testup.entity.Exam;
import az.testup.entity.User;
import az.testup.enums.ExamStatus;
import az.testup.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExamRepository extends JpaRepository<Exam, Long> {
    List<Exam> findByTeacher(User teacher);
    List<Exam> findByStatus(ExamStatus status);
    Optional<Exam> findByShareLink(String shareLink);

    @Query("SELECT e FROM Exam e WHERE " +
           "(:teacherId IS NULL OR e.teacher.id = :teacherId) AND " +
           "(:teacherRoleName IS NULL OR e.teacher.role = az.testup.enums.Role.TEACHER) AND " +
           "(:status IS NULL OR e.status = :status) AND " +
           "(:search IS NULL OR LOWER(e.title) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))")
    Page<Exam> searchExams(@Param("search") String search,
                           @Param("status") ExamStatus status,
                           @Param("teacherId") Long teacherId,
                           @Param("teacherRoleName") String teacherRoleName,
                           Pageable pageable);
}
