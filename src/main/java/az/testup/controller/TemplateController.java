package az.testup.controller;

import az.testup.dto.response.TemplateSectionResponse;
import az.testup.dto.response.TemplateResponse;
import az.testup.dto.response.TemplateSubtitleResponse;
import az.testup.enums.TemplateType;
import az.testup.service.TemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    /**
     * Returns every active template regardless of legacy templateType. The olimpiada vs
     * standard distinction has been retired — both kinds are surfaced through this single
     * endpoint and the editor treats them uniformly via the template's pointGroups +
     * allowCustomPoints config.
     */
    @GetMapping
    public ResponseEntity<List<TemplateResponse>> getAllTemplates() {
        return ResponseEntity.ok(templateService.getAllTemplates());
    }

    /**
     * @deprecated Olimpiada-only listing is no longer surfaced in the UI. Kept so older
     * clients keep working; new code should call {@link #getAllTemplates()} instead.
     */
    @Deprecated
    @GetMapping("/olimpiyada")
    public ResponseEntity<List<TemplateResponse>> getOlimpiyadaTemplates() {
        return ResponseEntity.ok(templateService.getTemplatesByType(TemplateType.OLIMPIYADA));
    }

    @GetMapping("/{templateId}/subtitles")
    public ResponseEntity<List<TemplateSubtitleResponse>> getSubtitles(@PathVariable Long templateId) {
        return ResponseEntity.ok(templateService.getSubtitlesByTemplate(templateId));
    }

    @GetMapping("/sections/{sectionId}")
    public ResponseEntity<TemplateSectionResponse> getSection(@PathVariable Long sectionId) {
        return ResponseEntity.ok(templateService.getSectionById(sectionId));
    }
}
