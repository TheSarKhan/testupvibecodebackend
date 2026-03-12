package az.testup.repository;

import az.testup.entity.BankSubject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankSubjectRepository extends JpaRepository<BankSubject, Long> {
    List<BankSubject> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);
    List<BankSubject> findByIsGlobalTrueOrderByCreatedAtDesc();
    boolean existsByNameAndOwnerId(String name, Long ownerId);
}
