package az.testup.controller;

import az.testup.dto.request.AdminNotificationRequest;
import az.testup.dto.request.AssignExamRequest;
import az.testup.dto.request.BannerRequest;
import az.testup.dto.request.ChangeRoleRequest;
import az.testup.dto.request.TemplateRequest;
import az.testup.dto.request.TemplateSubtitleRequest;
import az.testup.dto.request.TemplateSectionRequest;
import az.testup.dto.response.AuditLogResponse;
import az.testup.dto.response.BannerResponse;
import az.testup.dto.response.NotificationLogResponse;
import az.testup.dto.response.SubjectStatsResponse;
import az.testup.dto.response.TemplateResponse;
import az.testup.dto.response.TemplateSubtitleResponse;
import az.testup.dto.response.TemplateSectionResponse;
import az.testup.service.AuditLogService;
import az.testup.entity.Banner;
import az.testup.entity.ExamSubject;
import az.testup.entity.SubjectTopic;
import az.testup.enums.BannerPosition;
import az.testup.repository.BannerRepository;
import az.testup.service.TemplateService;
import az.testup.dto.request.SetExamPriceRequest;
import az.testup.dto.response.AdminExamResponse;
import az.testup.dto.response.AdminStatsResponse;
import az.testup.dto.response.AdminUserResponse;
import az.testup.enums.ExamStatus;
import az.testup.enums.Role;
import az.testup.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final TemplateService templateService;
    private final BannerRepository bannerRepository;
    private final AuditLogService auditLogService;

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

    @PostMapping("/users/{id}/assign-exam")
    public ResponseEntity<Void> assignExamToStudent(
            @PathVariable Long id,
            @RequestBody AssignExamRequest request) {
        adminService.assignExamToStudent(id, request.examId());
        return ResponseEntity.ok().build();
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

    @PostMapping("/subjects/{id}/topics")
    public ResponseEntity<SubjectTopic> addTopic(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "");
        String gradeLevel = body.get("gradeLevel");
        return ResponseEntity.ok(adminService.addTopicToSubject(id, name, gradeLevel));
    }

    @DeleteMapping("/subjects/{id}/topics/{topicId}")
    public ResponseEntity<Void> removeTopic(@PathVariable Long id, @PathVariable Long topicId) {
        adminService.removeTopicFromSubject(id, topicId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/subjects/{id}/metadata")
    public ResponseEntity<ExamSubject> updateSubjectMetadata(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String color = body.get("color");
        String iconEmoji = body.get("iconEmoji");
        String description = body.get("description");
        return ResponseEntity.ok(adminService.updateSubjectMetadata(id, color, iconEmoji, description));
    }

    @GetMapping("/subjects/{id}/stats")
    public ResponseEntity<SubjectStatsResponse> getSubjectStats(@PathVariable Long id) {
        return ResponseEntity.ok(adminService.getSubjectStats(id));
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

    // ───── Notifications ─────

    @PostMapping(value = "/notifications/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<NotificationLogResponse> sendNotification(
            @RequestPart("request") AdminNotificationRequest request,
            @RequestPart(value = "attachment", required = false) MultipartFile attachment,
            @AuthenticationPrincipal UserDetails userDetails) {
        String adminEmail = userDetails != null ? userDetails.getUsername() : "admin";
        return ResponseEntity.ok(adminService.sendAdminNotification(request, attachment, adminEmail));
    }

    @GetMapping("/notifications/history")
    public ResponseEntity<Page<NotificationLogResponse>> getNotificationHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("sentAt").descending());
        return ResponseEntity.ok(adminService.getNotificationHistory(pageable));
    }

    // ───── Banners ─────

    @GetMapping("/banners")
    public ResponseEntity<List<BannerResponse>> getBanners() {
        return ResponseEntity.ok(bannerRepository.findAllByOrderByOrderIndexAsc().stream()
                .map(this::toBannerResponse).toList());
    }

    @PostMapping("/banners")
    public ResponseEntity<BannerResponse> createBanner(@RequestBody BannerRequest req) {
        Banner banner = Banner.builder()
                .title(req.getTitle())
                .subtitle(req.getSubtitle())
                .imageUrl(req.getImageUrl())
                .linkUrl(req.getLinkUrl())
                .linkText(req.getLinkText() != null ? req.getLinkText() : "Ətraflı bax")
                .isActive(Boolean.TRUE.equals(req.getIsActive()))
                .position(BannerPosition.valueOf(req.getPosition() != null ? req.getPosition() : "INLINE"))
                .bgGradient(req.getBgGradient())
                .orderIndex(req.getOrderIndex() != null ? req.getOrderIndex() : 0)
                .build();
        return ResponseEntity.ok(toBannerResponse(bannerRepository.save(banner)));
    }

    @PutMapping("/banners/{id}")
    public ResponseEntity<BannerResponse> updateBanner(@PathVariable Long id, @RequestBody BannerRequest req) {
        Banner banner = bannerRepository.findById(id)
                .orElseThrow(() -> new az.testup.exception.ResourceNotFoundException("Banner tapılmadı"));
        if (req.getTitle() != null) banner.setTitle(req.getTitle());
        if (req.getSubtitle() != null) banner.setSubtitle(req.getSubtitle());
        if (req.getImageUrl() != null) banner.setImageUrl(req.getImageUrl());
        if (req.getLinkUrl() != null) banner.setLinkUrl(req.getLinkUrl());
        if (req.getLinkText() != null) banner.setLinkText(req.getLinkText());
        if (req.getIsActive() != null) banner.setActive(req.getIsActive());
        if (req.getPosition() != null) banner.setPosition(BannerPosition.valueOf(req.getPosition()));
        if (req.getBgGradient() != null) banner.setBgGradient(req.getBgGradient());
        if (req.getOrderIndex() != null) banner.setOrderIndex(req.getOrderIndex());
        return ResponseEntity.ok(toBannerResponse(bannerRepository.save(banner)));
    }

    @DeleteMapping("/banners/{id}")
    public ResponseEntity<Void> deleteBanner(@PathVariable Long id) {
        bannerRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ───── Logs ─────

    @GetMapping("/logs")
    public ResponseEntity<Page<AuditLogResponse>> getLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        var pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auditLogService.getLogs(action, category, search, period, pageable));
    }

    private BannerResponse toBannerResponse(Banner b) {
        return BannerResponse.builder()
                .id(b.getId())
                .title(b.getTitle())
                .subtitle(b.getSubtitle())
                .imageUrl(b.getImageUrl())
                .linkUrl(b.getLinkUrl())
                .linkText(b.getLinkText())
                .isActive(b.isActive())
                .position(b.getPosition() != null ? b.getPosition().name() : null)
                .bgGradient(b.getBgGradient())
                .orderIndex(b.getOrderIndex())
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .build();
    }
}
