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

    @GetMapping
    public ResponseEntity<List<TemplateResponse>> getAllTemplates() {
        return ResponseEntity.ok(templateService.getTemplatesByType(TemplateType.STANDARD));
    }

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
