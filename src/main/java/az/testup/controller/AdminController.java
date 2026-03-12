package az.testup.controller;

import az.testup.dto.request.ChangeRoleRequest;
import az.testup.dto.request.TemplateRequest;
import az.testup.dto.request.TemplateSubtitleRequest;
import az.testup.dto.request.TemplateSectionRequest;
import az.testup.dto.response.TemplateResponse;
import az.testup.dto.response.TemplateSubtitleResponse;
import az.testup.dto.response.TemplateSectionResponse;
import az.testup.service.TemplateService;
import az.testup.dto.request.SetExamPriceRequest;
import az.testup.dto.response.AdminExamResponse;
import az.testup.dto.response.AdminStatsResponse;
import az.testup.dto.response.AdminUserResponse;
import az.testup.entity.ExamSubject;
import az.testup.enums.ExamStatus;
import az.testup.enums.Role;
import az.testup.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final TemplateService templateService;

    // ───── Stats ─────

    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> getStats() {
        return ResponseEntity.ok(adminService.getStats());
    }

    // ───── Users ─────

    @GetMapping("/users")
    public ResponseEntity<Page<AdminUserResponse>> getUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Role roleEnum = (role != null && !role.isBlank()) ? Role.valueOf(role) : null;
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(adminService.getUsers(search, roleEnum, pageable));
    }

    @PatchMapping("/users/{id}/role")
    public ResponseEntity<AdminUserResponse> changeRole(
            @PathVariable Long id,
            @RequestBody ChangeRoleRequest request) {
        return ResponseEntity.ok(adminService.changeRole(id, request.role()));
    }

    @PatchMapping("/users/{id}/toggle-status")
    public ResponseEntity<AdminUserResponse> toggleUserStatus(@PathVariable Long id) {
        return ResponseEntity.ok(adminService.toggleEnabled(id));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        adminService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    // ───── Exams ─────

    @GetMapping("/exams")
    public ResponseEntity<Page<AdminExamResponse>> getExams(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) String teacherRoleName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ExamStatus statusEnum = (status != null && !status.isBlank()) ? ExamStatus.valueOf(status) : null;
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(adminService.getExams(search, statusEnum, teacherId,
                (teacherRoleName != null && !teacherRoleName.isBlank()) ? teacherRoleName : null,
                pageable));
    }

    @PatchMapping("/exams/{id}/site-publish")
    public ResponseEntity<AdminExamResponse> toggleSitePublished(@PathVariable Long id) {
        return ResponseEntity.ok(adminService.toggleSitePublished(id));
    }

    @PatchMapping("/exams/{id}/price")
    public ResponseEntity<AdminExamResponse> setExamPrice(
            @PathVariable Long id,
            @RequestBody SetExamPriceRequest request) {
        return ResponseEntity.ok(adminService.setExamPrice(id, request.price()));
    }

    @DeleteMapping("/exams/{id}")
    public ResponseEntity<Void> deleteExam(@PathVariable Long id) {
        adminService.deleteExam(id);
        return ResponseEntity.noContent().build();
    }

    // ───── Subjects ─────

    @GetMapping("/subjects")
    public ResponseEntity<List<ExamSubject>> getSubjects() {
        return ResponseEntity.ok(adminService.getSubjects());
    }

    @PostMapping("/subjects")
    public ResponseEntity<ExamSubject> addSubject(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(adminService.addSubject(body.getOrDefault("name", "")));
    }

    @DeleteMapping("/subjects/{id}")
    public ResponseEntity<Void> deleteSubject(@PathVariable Long id) {
        adminService.deleteSubject(id);
        return ResponseEntity.noContent().build();
    }

    // ───── Templates ─────

    @GetMapping("/templates")
    public ResponseEntity<List<TemplateResponse>> getTemplates() {
        return ResponseEntity.ok(templateService.getAllTemplates());
    }

    @PostMapping("/templates")
    public ResponseEntity<TemplateResponse> createTemplate(@RequestBody TemplateRequest request) {
        return ResponseEntity.ok(templateService.createTemplate(request, null));
    }

    @PutMapping("/templates/{id}")
    public ResponseEntity<TemplateResponse> updateTemplate(
            @PathVariable Long id,
            @RequestBody TemplateRequest request) {
        return ResponseEntity.ok(templateService.updateTemplate(id, request));
    }

    @DeleteMapping("/templates/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    // ───── Subtitles ─────

    @GetMapping("/templates/{templateId}/subtitles")
    public ResponseEntity<List<TemplateSubtitleResponse>> getSubtitles(@PathVariable Long templateId) {
        return ResponseEntity.ok(templateService.getSubtitlesByTemplate(templateId));
    }

    @PostMapping("/templates/{templateId}/subtitles")
    public ResponseEntity<TemplateSubtitleResponse> createSubtitle(
            @PathVariable Long templateId,
            @RequestBody TemplateSubtitleRequest request) {
        return ResponseEntity.ok(templateService.createSubtitle(templateId, request));
    }

    @PutMapping("/subtitles/{id}")
    public ResponseEntity<TemplateSubtitleResponse> updateSubtitle(
            @PathVariable Long id,
            @RequestBody TemplateSubtitleRequest request) {
        return ResponseEntity.ok(templateService.updateSubtitle(id, request));
    }

    @DeleteMapping("/subtitles/{id}")
    public ResponseEntity<Void> deleteSubtitle(@PathVariable Long id) {
        templateService.deleteSubtitle(id);
        return ResponseEntity.noContent().build();
    }

    // ───── Sections ─────

    @GetMapping("/subtitles/{subtitleId}/sections")
    public ResponseEntity<List<TemplateSectionResponse>> getSections(@PathVariable Long subtitleId) {
        return ResponseEntity.ok(templateService.getSectionsBySubtitle(subtitleId));
    }

    @PostMapping("/subtitles/{subtitleId}/sections")
    public ResponseEntity<TemplateSectionResponse> createSection(
            @PathVariable Long subtitleId,
            @RequestBody TemplateSectionRequest request) {
        return ResponseEntity.ok(templateService.createSection(subtitleId, request));
    }

    @PutMapping("/sections/{id}")
    public ResponseEntity<TemplateSectionResponse> updateSection(
            @PathVariable Long id,
            @RequestBody TemplateSectionRequest request) {
        return ResponseEntity.ok(templateService.updateSection(id, request));
    }

    @DeleteMapping("/sections/{id}")
    public ResponseEntity<Void> deleteSection(@PathVariable Long id) {
        templateService.deleteSection(id);
        return ResponseEntity.noContent().build();
    }
}
