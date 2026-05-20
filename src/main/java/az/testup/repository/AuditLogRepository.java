package az.testup.repository;

import az.testup.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query(
        value = """
            SELECT * FROM audit_logs a
            WHERE (CAST(:action AS TEXT) IS NULL OR a.action = CAST(:action AS TEXT))
              AND (CAST(:actions AS TEXT) IS NULL OR a.action = ANY(string_to_array(CAST(:actions AS TEXT), ',')))
              AND (CAST(:search AS TEXT) IS NULL
                   OR a.actor_email ILIKE CONCAT('%', CAST(:search AS TEXT), '%')
                   OR a.actor_name  ILIKE CONCAT('%', CAST(:search AS TEXT), '%')
                   OR a.target_name ILIKE CONCAT('%', CAST(:search AS TEXT), '%')
                   OR a.details     ILIKE CONCAT('%', CAST(:search AS TEXT), '%'))
              AND (CAST(:actor AS TEXT) IS NULL OR a.actor_email ILIKE CONCAT('%', CAST(:actor AS TEXT), '%'))
              AND (CAST(:targetType AS TEXT) IS NULL OR a.target_type = CAST(:targetType AS TEXT))
              AND (CAST(:since AS TIMESTAMP) IS NULL OR a.created_at >= CAST(:since AS TIMESTAMP))
            ORDER BY a.created_at DESC
        """,
        countQuery = """
            SELECT COUNT(*) FROM audit_logs a
            WHERE (CAST(:action AS TEXT) IS NULL OR a.action = CAST(:action AS TEXT))
              AND (CAST(:actions AS TEXT) IS NULL OR a.action = ANY(string_to_array(CAST(:actions AS TEXT), ',')))
              AND (CAST(:search AS TEXT) IS NULL
                   OR a.actor_email ILIKE CONCAT('%', CAST(:search AS TEXT), '%')
                   OR a.actor_name  ILIKE CONCAT('%', CAST(:search AS TEXT), '%')
                   OR a.target_name ILIKE CONCAT('%', CAST(:search AS TEXT), '%')
                   OR a.details     ILIKE CONCAT('%', CAST(:search AS TEXT), '%'))
              AND (CAST(:actor AS TEXT) IS NULL OR a.actor_email ILIKE CONCAT('%', CAST(:actor AS TEXT), '%'))
              AND (CAST(:targetType AS TEXT) IS NULL OR a.target_type = CAST(:targetType AS TEXT))
              AND (CAST(:since AS TIMESTAMP) IS NULL OR a.created_at >= CAST(:since AS TIMESTAMP))
        """,
        nativeQuery = true
    )
    Page<AuditLog> search(
        @Param("action") String action,
        @Param("actions") String actions,
        @Param("search") String search,
        @Param("actor") String actor,
        @Param("targetType") String targetType,
        @Param("since") LocalDateTime since,
        Pageable pageable
    );

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.action = az.testup.enums.AuditAction.SYSTEM_ERROR AND a.createdAt >= :since")
    long countSystemErrorsSince(@Param("since") LocalDateTime since);

    @Query("SELECT a FROM AuditLog a ORDER BY a.createdAt DESC")
    List<AuditLog> findRecentActivity(Pageable pageable);
}
