package az.testup.repository;

import az.testup.entity.SubjectCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubjectCategoryRepository extends JpaRepository<SubjectCategory, Long> {
    List<SubjectCategory> findAllByOrderByOrderIndexAscNameAsc();
    Optional<SubjectCategory> findByName(String name);
    boolean existsByName(String name);
}
