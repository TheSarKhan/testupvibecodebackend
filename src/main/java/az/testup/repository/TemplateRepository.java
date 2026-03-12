package az.testup.repository;

import az.testup.entity.Template;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TemplateRepository extends JpaRepository<Template, Long> {
    List<Template> findAllByOrderByCreatedAtDesc();
    Optional<Template> findByTitle(String title);
}
