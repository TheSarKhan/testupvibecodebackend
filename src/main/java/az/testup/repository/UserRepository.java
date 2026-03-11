package az.testup.repository;

import az.testup.entity.User;
import az.testup.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    long countByRole(Role role);
    List<User> findTop5ByOrderByCreatedAtDesc();

    @Query(value = "SELECT TO_CHAR(created_at, 'YYYY-MM') as month, COUNT(*) as count FROM users WHERE created_at >= :since GROUP BY month ORDER BY month", nativeQuery = true)
    List<Object[]> countRegistrationsByMonth(@Param("since") LocalDateTime since);

    @Query("SELECT u FROM User u WHERE " +
           "(:role IS NULL OR u.role = :role) AND " +
           "(:search IS NULL OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))")
    Page<User> searchUsers(@Param("search") String search, @Param("role") Role role, Pageable pageable);
}
