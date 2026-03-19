package az.testup.repository;

import az.testup.entity.TemplateSubtitle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TemplateSubtitleRepository extends JpaRepository<TemplateSubtitle, Long> {
    List<TemplateSubtitle> findAllByTemplateIdOrderByOrderIndexAsc(Long templateId);
}
