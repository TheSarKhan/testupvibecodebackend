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
    List<Exam> findByTeacherAndDeletedFalse(User teacher);
    List<Exam> findByTeacherAndCollaborativeParentIdIsNullAndDeletedFalse(User teacher);
    List<Exam> findByStatusAndDeletedFalse(ExamStatus status);
    Optional<Exam> findByShareLinkAndDeletedFalse(String shareLink);
    Page<Exam> findByIsCollaborativeTrueAndDeletedFalseOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT e FROM Exam e WHERE " +
           "e.deleted = false AND " +
           "(:teacherId IS NULL OR e.teacher.id = :teacherId) AND " +
           "(:teacherRoleName IS NULL OR e.teacher.role = az.testup.enums.Role.TEACHER) AND " +
           "(:status IS NULL OR e.status = :status) AND " +
           "(:search IS NULL OR LOWER(e.title) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))")
    Page<Exam> searchExams(@Param("search") String search,
                           @Param("status") ExamStatus status,
                           @Param("teacherId") Long teacherId,
                           @Param("teacherRoleName") String teacherRoleName,
                           Pageable pageable);

    long countByTeacherId(Long teacherId);

    long countByStatus(ExamStatus status);

    long countByTemplateIdAndDeletedFalse(Long templateId);

    List<Exam> findTop5ByDeletedFalseOrderByCreatedAtDesc();

    @Query(value = "SELECT COUNT(DISTINCT exam_id) FROM exam_tags WHERE tag = :tag", nativeQuery = true)
    long countExamsWithTag(@Param("tag") String tag);

    @Query(value = "SELECT COUNT(*) FROM exams e WHERE e.deleted = false " +
            "AND NOT EXISTS (SELECT 1 FROM exam_tags et WHERE et.exam_id = e.id)", nativeQuery = true)
    long countUntaggedExams();

    @Query(value = "SELECT tag, COUNT(DISTINCT exam_id) AS cnt FROM exam_tags GROUP BY tag", nativeQuery = true)
    List<Object[]> tagUsageCounts();

    long countByStatusAndDeletedFalse(ExamStatus status);

    @Query(value = """
            SELECT e.teacher_id, COUNT(DISTINCT e.id) AS exam_count,
                   COALESCE((SELECT COUNT(*) FROM submissions s WHERE s.exam_id IN
                       (SELECT id FROM exams WHERE teacher_id = e.teacher_id AND deleted = false)), 0) AS sub_count
            FROM exams e
            WHERE e.deleted = false
            GROUP BY e.teacher_id
            ORDER BY exam_count DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findTopTeachersByExamCount(@Param("limit") int limit);

    @Query(value = """
            SELECT e.id, e.title, e.status, u.full_name,
                   (SELECT COUNT(*) FROM submissions s WHERE s.exam_id = e.id AND s.submitted_at IS NOT NULL) AS sub_count
            FROM exams e
            JOIN users u ON e.teacher_id = u.id
            WHERE e.deleted = false
            ORDER BY sub_count DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findTopExamsBySubmissions(@Param("limit") int limit);
}

