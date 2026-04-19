package az.testup.service;

import az.testup.dto.request.TemplateSectionRequest;
import az.testup.dto.request.TemplateSectionTypeCountRequest;
import az.testup.dto.request.TemplateRequest;
import az.testup.dto.request.TemplateSubtitleRequest;
import az.testup.dto.response.TemplateSectionResponse;
import az.testup.dto.response.TemplateSectionTypeCountResponse;
import az.testup.dto.response.TemplateResponse;
import az.testup.dto.response.TemplateSubtitleResponse;
import az.testup.entity.Template;
import az.testup.entity.TemplateSection;
import az.testup.entity.TemplateSectionTypeCount;
import az.testup.entity.TemplateSubtitle;
import az.testup.entity.User;
import az.testup.enums.QuestionType;
import az.testup.enums.TemplateType;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.TemplateSectionRepository;
import az.testup.repository.TemplateRepository;
import az.testup.repository.TemplateSubtitleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TemplateService {

    private final TemplateRepository templateRepository;
    private final TemplateSubtitleRepository subtitleRepository;
    private final TemplateSectionRepository sectionRepository;

    // ─── Templates ────────────────────────────────────────────────────────────

    public List<TemplateResponse> getAllTemplates() {
        return templateRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::mapTemplate)
                .collect(Collectors.toList());
    }

    public List<TemplateResponse> getTemplatesByType(TemplateType type) {
        return templateRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(t -> type == (t.getTemplateType() != null ? t.getTemplateType() : TemplateType.STANDARD))
                .map(this::mapTemplate)
                .collect(Collectors.toList());
    }

    @Transactional
    public TemplateResponse createTemplate(TemplateRequest request, User admin) {
        TemplateType type = TemplateType.STANDARD;
        if (request.templateType() != null) {
            try { type = TemplateType.valueOf(request.templateType()); } catch (IllegalArgumentException ignored) {}
        }
        Template template = Template.builder()
                .title(request.title())
                .templateType(type)
                .createdBy(admin)
                .build();
        return mapTemplate(templateRepository.save(template));
    }

    @Transactional
    public TemplateResponse updateTemplate(Long id, TemplateRequest request) {
        Template template = templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Şablon tapılmadı"));
        template.setTitle(request.title());
        if (request.templateType() != null) {
            try { template.setTemplateType(TemplateType.valueOf(request.templateType())); } catch (IllegalArgumentException ignored) {}
        }
        return mapTemplate(templateRepository.save(template));
    }

    @Transactional
    public void deleteTemplate(Long id) {
        if (!templateRepository.existsById(id)) {
            throw new ResourceNotFoundException("Şablon tapılmadı");
        }
        templateRepository.deleteById(id);
    }

    // ─── Subtitles ────────────────────────────────────────────────────────────

    public List<TemplateSubtitleResponse> getSubtitlesByTemplate(Long templateId) {
        if (!templateRepository.existsById(templateId)) {
            throw new ResourceNotFoundException("Şablon tapılmadı");
        }
        return subtitleRepository.findAllByTemplateIdOrderByOrderIndexAsc(templateId).stream()
                .map(this::mapSubtitle)
                .collect(Collectors.toList());
    }

    @Transactional
    public TemplateSubtitleResponse createSubtitle(Long templateId, TemplateSubtitleRequest request) {
        Template template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Şablon tapılmadı"));
        int nextOrder = subtitleRepository.findAllByTemplateIdOrderByOrderIndexAsc(templateId).size();
        TemplateSubtitle subtitle = TemplateSubtitle.builder()
                .template(template)
                .subtitle(request.subtitle())
                .orderIndex(nextOrder)
                .build();
        return mapSubtitle(subtitleRepository.save(subtitle));
    }

    @Transactional
    public TemplateSubtitleResponse updateSubtitle(Long subtitleId, TemplateSubtitleRequest request) {
        TemplateSubtitle subtitle = subtitleRepository.findById(subtitleId)
                .orElseThrow(() -> new ResourceNotFoundException("Altbaşlıq tapılmadı"));
        subtitle.setSubtitle(request.subtitle());
        return mapSubtitle(subtitleRepository.save(subtitle));
    }

    @Transactional
    public void deleteSubtitle(Long subtitleId) {
        if (!subtitleRepository.existsById(subtitleId)) {
            throw new ResourceNotFoundException("Altbaşlıq tapılmadı");
        }
        subtitleRepository.deleteById(subtitleId);
    }

    // ─── Sections ─────────────────────────────────────────────────────────────

    public List<TemplateSectionResponse> getSectionsBySubtitle(Long subtitleId) {
        TemplateSubtitle subtitle = subtitleRepository.findById(subtitleId)
                .orElseThrow(() -> new ResourceNotFoundException("Altbaşlıq tapılmadı"));
        return subtitle.getSections().stream()
                .map(s -> mapSection(s, subtitle.getTemplate().getTitle(), subtitle.getSubtitle()))
                .collect(Collectors.toList());
    }

    @Transactional
    public TemplateSectionResponse createSection(Long subtitleId, TemplateSectionRequest request) {
        TemplateSubtitle subtitle = subtitleRepository.findById(subtitleId)
                .orElseThrow(() -> new ResourceNotFoundException("Altbaşlıq tapılmadı"));
        int nextOrder = subtitle.getSections().size();
        TemplateSection section = buildSection(request, subtitle, nextOrder);
        subtitle.getSections().add(section);
        TemplateSubtitle saved = subtitleRepository.save(subtitle);
        TemplateSection savedSection = saved.getSections().get(saved.getSections().size() - 1);
        return mapSection(savedSection, subtitle.getTemplate().getTitle(), subtitle.getSubtitle());
    }

    @Transactional
    public TemplateSectionResponse updateSection(Long sectionId, TemplateSectionRequest request) {
        TemplateSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn bölməsi tapılmadı"));
        TemplateSubtitle subtitle = section.getSubtitle();

        section.setSubjectName(request.subjectName());
        section.setFormula(request.formula());
        section.setPointGroups(request.pointGroups());
        section.setMaxScore(request.maxScore());
        section.setAllowCustomPoints(request.allowCustomPoints() != null ? request.allowCustomPoints() : true);
        section.getTypeCounts().clear();

        if (request.typeCounts() != null) {
            for (int j = 0; j < request.typeCounts().size(); j++) {
                TemplateSectionTypeCountRequest tc = request.typeCounts().get(j);
                section.getTypeCounts().add(TemplateSectionTypeCount.builder()
                        .section(section)
                        .questionType(QuestionType.valueOf(tc.questionType()))
                        .passageType(tc.passageType())
                        .count(tc.count())
                        .orderIndex(j)
                        .build());
            }
        }
        int total = section.getTypeCounts().stream().mapToInt(TemplateSectionTypeCount::getCount).sum();
        section.setQuestionCount(total);

        TemplateSection saved = sectionRepository.save(section);
        return mapSection(saved, subtitle.getTemplate().getTitle(), subtitle.getSubtitle());
    }

    @Transactional
    public void deleteSection(Long sectionId) {
        if (!sectionRepository.existsById(sectionId)) {
            throw new ResourceNotFoundException("Fənn bölməsi tapılmadı");
        }
        sectionRepository.deleteById(sectionId);
    }

    // ─── Public (for teachers / ExamEditor edit mode) ─────────────────────────

    public TemplateSectionResponse getSectionById(Long sectionId) {
        TemplateSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Fənn bölməsi tapılmadı"));
        return mapSection(section,
                section.getSubtitle().getTemplate().getTitle(),
                section.getSubtitle().getSubtitle());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private TemplateSection buildSection(TemplateSectionRequest request, TemplateSubtitle subtitle, int orderIndex) {
        TemplateSection section = TemplateSection.builder()
                .subjectName(request.subjectName())
                .formula(request.formula())
                .pointGroups(request.pointGroups())
                .maxScore(request.maxScore())
                .allowCustomPoints(request.allowCustomPoints() != null ? request.allowCustomPoints() : true)
                .orderIndex(request.orderIndex() != null ? request.orderIndex() : orderIndex)
                .subtitle(subtitle)
                .build();
        if (request.typeCounts() != null) {
            for (int j = 0; j < request.typeCounts().size(); j++) {
                TemplateSectionTypeCountRequest tc = request.typeCounts().get(j);
                section.getTypeCounts().add(TemplateSectionTypeCount.builder()
                        .section(section)
                        .questionType(QuestionType.valueOf(tc.questionType()))
                        .passageType(tc.passageType())
                        .count(tc.count())
                        .orderIndex(j)
                        .build());
            }
        }
        int total = section.getTypeCounts().stream().mapToInt(TemplateSectionTypeCount::getCount).sum();
        section.setQuestionCount(total);
        return section;
    }

    private TemplateResponse mapTemplate(Template t) {
        return new TemplateResponse(t.getId(), t.getTitle(),
                t.getSubtitles() != null ? t.getSubtitles().size() : 0,
                t.getCreatedAt(),
                t.getTemplateType() != null ? t.getTemplateType().name() : TemplateType.STANDARD.name());
    }

    private TemplateSubtitleResponse mapSubtitle(TemplateSubtitle s) {
        List<TemplateSectionResponse> sections = s.getSections().stream()
                .map(sec -> mapSection(sec, s.getTemplate().getTitle(), s.getSubtitle()))
                .collect(Collectors.toList());
        return new TemplateSubtitleResponse(s.getId(), s.getSubtitle(), s.getOrderIndex(), sections);
    }

    private TemplateSectionResponse mapSection(TemplateSection s, String templateTitle, String subtitleName) {
        List<TemplateSectionTypeCountResponse> typeCounts = s.getTypeCounts().stream()
                .map(tc -> new TemplateSectionTypeCountResponse(tc.getId(), tc.getQuestionType().name(), tc.getCount(), tc.getOrderIndex(), tc.getPassageType()))
                .collect(Collectors.toList());
        int total = typeCounts.stream().mapToInt(TemplateSectionTypeCountResponse::count).sum();
        return new TemplateSectionResponse(
                s.getId(), s.getSubjectName(),
                total > 0 ? total : s.getQuestionCount(),
                typeCounts, s.getFormula(), s.getOrderIndex(),
                templateTitle, subtitleName, s.getPointGroups(), s.getMaxScore(), s.isAllowCustomPoints());
    }
}
