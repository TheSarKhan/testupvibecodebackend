package az.testup.repository;

import az.testup.entity.ExamPurchase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamPurchaseRepository extends JpaRepository<ExamPurchase, Long> {
    Optional<ExamPurchase> findByUserIdAndExamId(Long userId, Long examId);
    List<ExamPurchase> findByExamId(Long examId);
    boolean existsByUserIdAndExamId(Long userId, Long examId);
    List<ExamPurchase> findByUserId(Long userId);
}
