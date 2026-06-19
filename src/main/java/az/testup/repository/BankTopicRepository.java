package az.testup.repository;

import az.testup.entity.BankTopic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankTopicRepository extends JpaRepository<BankTopic, Long> {

    List<BankTopic> findBySubjectIdAndOwnerIdOrderByLastUsedAtDesc(Long subjectId, Long ownerId);

    Optional<BankTopic> findBySubjectIdAndOwnerIdAndName(Long subjectId, Long ownerId, String name);

    // Owned-content guard for user deletion.
    long countByOwnerId(Long ownerId);
}
