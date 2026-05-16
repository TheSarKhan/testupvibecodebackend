package az.testup.controller.admin;

import az.testup.dto.request.TemplateRequest;
import az.testup.dto.request.TemplateSectionRequest;
import az.testup.dto.request.TemplateSubtitleRequest;
import az.testup.dto.response.TemplateResponse;
import az.testup.dto.response.TemplateSectionResponse;
import az.testup.dto.response.TemplateSubtitleResponse;
import az.testup.enums.AuditAction;
import az.testup.service.AuditLogService;
import az.testup.service.TemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminTemplateController {

    private final TemplateService templateService;
    private final AuditLogService auditLogService;

    // ───── Templates ─────

    @GetMapping("/templates")
    public ResponseEntity<Page<TemplateResponse>> getTemplates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(templateService.getAllTemplates(search, PageRequest.of(page, size)));
    }

    @GetMapping("/templates/stats")
    public ResponseEntity<az.testup.dto.response.TemplateStatsResponse> getTemplateStats() {
        return ResponseEntity.ok(templateService.getTemplateStats());
    }

    @PostMapping("/templates")
    public ResponseEntity<TemplateResponse> createTemplate(@RequestBody TemplateRequest request) {
        TemplateResponse resp = templateService.createTemplate(request, null);
        auditLogService.logCurrent(AuditAction.TEMPLATE_CREATED, "TEMPLATE", resp.title(),
                "Tip: " + resp.templateType());
        return ResponseEntity.ok(resp);
    }

    @PutMapping("/templates/{id}")
    public ResponseEntity<TemplateResponse> updateTemplate(
            @PathVariable Long id,
            @RequestBody TemplateRequest request) {
        TemplateResponse resp = templateService.updateTemplate(id, request);
        auditLogService.logCurrent(AuditAction.TEMPLATE_UPDATED, "TEMPLATE", resp.title(), null);
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping("/templates/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long id) {
        String title = templateService.getAllTemplates().stream()
                .filter(t -> t.id().equals(id))
                .map(TemplateResponse::title)
                .findFirst()
                .orElse("ID:" + id);
        templateService.deleteTemplate(id);
        auditLogService.logCurrent(AuditAction.TEMPLATE_DELETED, "TEMPLATE", title, null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/templates/{id}/clone")
    public ResponseEntity<TemplateResponse> cloneTemplate(@PathVariable Long id) {
        TemplateResponse resp = templateService.cloneTemplate(id);
        auditLogService.logCurrent(AuditAction.TEMPLATE_CREATED, "TEMPLATE", resp.title(), "Kopyalandı");
        return ResponseEntity.ok(resp);
    }

    // ───── Subtitles ─────

    @GetMapping("/templates/{templateId}/subtitles")
    public ResponseEntity<Page<TemplateSubtitleResponse>> getSubtitles(
            @PathVariable Long templateId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        return ResponseEntity.ok(templateService.getSubtitlesByTemplate(templateId, PageRequest.of(page, size)));
    }

    @PostMapping("/templates/{templateId}/subtitles")
    public ResponseEntity<TemplateSubtitleResponse> createSubtitle(
            @PathVariable Long templateId,
            @RequestBody TemplateSubtitleRequest request) {
        TemplateSubtitleResponse resp = templateService.createSubtitle(templateId, request);
        auditLogService.logCurrent(AuditAction.TEMPLATE_SUBTITLE_CREATED, "TEMPLATE_SUBTITLE",
                resp.subtitle(), "Şablon ID: " + templateId);
        return ResponseEntity.ok(resp);
    }

    @PutMapping("/subtitles/{id}")
    public ResponseEntity<TemplateSubtitleResponse> updateSubtitle(
            @PathVariable Long id,
            @RequestBody TemplateSubtitleRequest request) {
        TemplateSubtitleResponse resp = templateService.updateSubtitle(id, request);
        auditLogService.logCurrent(AuditAction.TEMPLATE_SUBTITLE_UPDATED, "TEMPLATE_SUBTITLE",
                resp.subtitle(), null);
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping("/subtitles/{id}")
    public ResponseEntity<Void> deleteSubtitle(@PathVariable Long id) {
        templateService.deleteSubtitle(id);
        auditLogService.logCurrent(AuditAction.TEMPLATE_SUBTITLE_DELETED, "TEMPLATE_SUBTITLE",
                "ID:" + id, null);
        return ResponseEntity.noContent().build();
    }

    // ───── Sections ─────

    @GetMapping("/subtitles/{subtitleId}/sections")
    public ResponseEntity<Page<TemplateSectionResponse>> getSections(
            @PathVariable Long subtitleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        return ResponseEntity.ok(templateService.getSectionsBySubtitle(subtitleId, PageRequest.of(page, size)));
    }

    @PostMapping("/subtitles/{subtitleId}/sections")
    public ResponseEntity<TemplateSectionResponse> createSection(
            @PathVariable Long subtitleId,
            @RequestBody TemplateSectionRequest request) {
        TemplateSectionResponse resp = templateService.createSection(subtitleId, request);
        auditLogService.logCurrent(AuditAction.TEMPLATE_SECTION_CREATED, "TEMPLATE_SECTION",
                resp.subjectName(), "Altbaşlıq ID: " + subtitleId);
        return ResponseEntity.ok(resp);
    }

    @PutMapping("/sections/{id}")
    public ResponseEntity<TemplateSectionResponse> updateSection(
            @PathVariable Long id,
            @RequestBody TemplateSectionRequest request) {
        TemplateSectionResponse resp = templateService.updateSection(id, request);
        auditLogService.logCurrent(AuditAction.TEMPLATE_SECTION_UPDATED, "TEMPLATE_SECTION",
                resp.subjectName(), null);
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping("/sections/{id}")
    public ResponseEntity<Void> deleteSection(@PathVariable Long id) {
        templateService.deleteSection(id);
        auditLogService.logCurrent(AuditAction.TEMPLATE_SECTION_DELETED, "TEMPLATE_SECTION",
                "ID:" + id, null);
        return ResponseEntity.noContent().build();
    }
}
