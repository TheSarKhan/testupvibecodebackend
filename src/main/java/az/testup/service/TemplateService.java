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
    private final az.testup.repository.ExamRepository examRepository;

    // ─── Templates ────────────────────────────────────────────────────────────

    public List<TemplateResponse> getAllTemplates() {
        return templateRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::mapTemplate)
                .collect(Collectors.toList());
    }

    public org.springframework.data.domain.Page<TemplateResponse> getAllTemplates(
            org.springframework.data.domain.Pageable pageable) {
        return getAllTemplates(null, pageable);
    }

    public az.testup.dto.response.TemplateStatsResponse getTemplateStats() {
        List<TemplateResponse> all = getAllTemplates();
        long totalUsage = all.stream().mapToLong(TemplateResponse::examCount).sum();
        TemplateResponse top = all.stream()
                .max((a, b) -> Long.compare(a.examCount(), b.examCount()))
                .orElse(null);
        TemplateResponse recent = all.stream()
                .filter(t -> t.createdAt() != null)
                .max((a, b) -> a.createdAt().compareTo(b.createdAt()))
                .orElse(null);
        return new az.testup.dto.response.TemplateStatsResponse(
                all.size(),
                totalUsage,
                top != null ? new az.testup.dto.response.TemplateStatsResponse.TopTemplate(top.id(), top.title(), top.examCount()) : null,
                recent != null ? new az.testup.dto.response.TemplateStatsResponse.RecentTemplate(recent.id(), recent.title(), recent.createdAt()) : null
        );
    }

    public org.springframework.data.domain.Page<TemplateResponse> getAllTemplates(
            String search, org.springframework.data.domain.Pageable pageable) {
        List<TemplateResponse> all = getAllTemplates();
        if (search != null && !search.isBlank()) {
            String q = search.trim().toLowerCase();
            all = all.stream().filter(t -> t.title() != null && t.title().toLowerCase().contains(q)).toList();
        }
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return new org.springframework.data.domain.PageImpl<>(all.subList(from, to), pageable, all.size());
    }

    public org.springframework.data.domain.Page<TemplateSubtitleResponse> getSubtitlesByTemplate(
            Long templateId, org.springframework.data.domain.Pageable pageable) {
        List<TemplateSubtitleResponse> all = getSubtitlesByTemplate(templateId);
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return new org.springframework.data.domain.PageImpl<>(all.subList(from, to), pageable, all.size());
    }

    public org.springframework.data.domain.Page<TemplateSectionResponse> getSectionsBySubtitle(
            Long subtitleId, org.springframework.data.domain.Pageable pageable) {
        List<TemplateSectionResponse> all = getSectionsBySubtitle(subtitleId);
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return new org.springframework.data.domain.PageImpl<>(all.subList(from, to), pageable, all.size());
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

    @Transactional
    public TemplateResponse cloneTemplate(Long sourceId) {
        Template source = templateRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Şablon tapılmadı"));

        Template copy = Template.builder()
                .title(source.getTitle() + " (kopya)")
                .templateType(source.getTemplateType())
                .createdBy(source.getCreatedBy())
                .build();

        for (var srcSub : source.getSubtitles()) {
            var subCopy = az.testup.entity.TemplateSubtitle.builder()
                    .template(copy)
                    .subtitle(srcSub.getSubtitle())
                    .orderIndex(srcSub.getOrderIndex())
                    .build();

            for (var srcSec : srcSub.getSections()) {
                var secCopy = az.testup.entity.TemplateSection.builder()
                        .subtitle(subCopy)
                        .subjectName(srcSec.getSubjectName())
                        .questionCount(srcSec.getQuestionCount())
                        .formula(srcSec.getFormula())
                        .pointGroups(srcSec.getPointGroups())
                        .maxScore(srcSec.getMaxScore())
                        .allowCustomPoints(srcSec.isAllowCustomPoints())
                        .orderIndex(srcSec.getOrderIndex())
                        .build();

                for (var srcTc : srcSec.getTypeCounts()) {
                    secCopy.getTypeCounts().add(
                            az.testup.entity.TemplateSectionTypeCount.builder()
                                    .section(secCopy)
                                    .questionType(srcTc.getQuestionType())
                                    .passageType(srcTc.getPassageType())
                                    .count(srcTc.getCount())
                                    .orderIndex(srcTc.getOrderIndex())
                                    .build()
                    );
                }
                subCopy.getSections().add(secCopy);
            }
            copy.getSubtitles().add(subCopy);
        }

        return mapTemplate(templateRepository.save(copy));
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

        // Kaskad: bu bölməyə bağlı imtahanları sil (soft-delete) və bütün FK
        // istinadlarını boşalt ki, bölmənin özü silinə bilsin (əks halda DB FK
        // pozuntusu → 409 "Verilənlər bazası xətası" verir).

        // 1) Birbaşa template_section_id ilə bağlı imtahanlar
        List<az.testup.entity.Exam> direct = examRepository.findByTemplateSection_Id(sectionId);
        for (var exam : direct) {
            exam.setDeleted(true);
            exam.setTemplateSection(null);
        }
        examRepository.saveAll(direct);

        // 2) Çoxlu-bölmə join (exam_template_sections) ilə bağlı imtahanlar
        List<Long> joinExamIds = examRepository.findExamIdsByTemplateSectionLink(sectionId);
        if (!joinExamIds.isEmpty()) {
            List<az.testup.entity.Exam> joinExams = examRepository.findAllById(joinExamIds);
            for (var exam : joinExams) exam.setDeleted(true);
            examRepository.saveAll(joinExams);
        }

        // 3) Qalan FK istinadlarını native sil (join cədvəli + collaborator section ref-ləri)
        examRepository.deleteExamTemplateSectionLinks(sectionId);
        examRepository.deleteCollaboratorSectionLinks(sectionId);

        // 4) Bölməni sil (typeCounts cascade/orphanRemoval ilə avtomatik silinir)
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
        long examCount = examRepository.countByTemplateIdAndDeletedFalse(t.getId());
        return new TemplateResponse(t.getId(), t.getTitle(),
                t.getSubtitles() != null ? t.getSubtitles().size() : 0,
                examCount,
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
