package az.testup.repository;

import az.testup.entity.ExamAccessCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExamAccessCodeRepository extends JpaRepository<ExamAccessCode, Long> {

    /** Find a code by its value (used during submission validation) */
    Optional<ExamAccessCode> findByCode(String code);
}
