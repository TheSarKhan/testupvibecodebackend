package az.testup.controller;

import az.testup.dto.request.ChangeRoleRequest;
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
}
