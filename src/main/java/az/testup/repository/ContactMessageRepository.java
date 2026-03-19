package az.testup.repository;

import az.testup.entity.ContactMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContactMessageRepository extends JpaRepository<ContactMessage, Long> {

    long countByIsReadFalse();

    @Query("SELECT m FROM ContactMessage m WHERE " +
           "(:subject IS NULL OR m.subject = :subject) AND " +
           "(:read IS NULL OR m.isRead = :read) AND " +
           "(:search IS NULL OR LOWER(m.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) " +
           "  OR LOWER(m.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) " +
           "  OR LOWER(m.message) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) " +
           "ORDER BY m.createdAt DESC")
    Page<ContactMessage> search(@Param("subject") String subject,
                                @Param("read") Boolean read,
                                @Param("search") String search,
                                Pageable pageable);
}
