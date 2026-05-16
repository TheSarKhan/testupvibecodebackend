package az.testup.controller.admin;

import az.testup.dto.request.AssignExamRequest;
import az.testup.dto.request.BulkUserActionRequest;
import az.testup.dto.request.ChangeRoleRequest;
import az.testup.dto.response.AdminUserResponse;
import az.testup.enums.Role;
import az.testup.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public ResponseEntity<Page<AdminUserResponse>> getUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Role roleEnum = (role != null && !role.isBlank()) ? Role.valueOf(role) : null;
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(adminUserService.getUsers(search, roleEnum, pageable));
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<AdminUserResponse> changeRole(
            @PathVariable Long id,
            @RequestBody ChangeRoleRequest request) {
        return ResponseEntity.ok(adminUserService.changeRole(id, request.role()));
    }

    @PatchMapping("/{id}/toggle-status")
    public ResponseEntity<AdminUserResponse> toggleUserStatus(@PathVariable Long id) {
        return ResponseEntity.ok(adminUserService.toggleEnabled(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        adminUserService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/assign-exam")
    public ResponseEntity<Void> assignExamToStudent(
            @PathVariable Long id,
            @RequestBody AssignExamRequest request) {
        adminUserService.assignExamToStudent(id, request.examId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bulk/delete")
    public ResponseEntity<Map<String, Object>> bulkDelete(@RequestBody BulkUserActionRequest req) {
        int count = adminUserService.bulkDelete(req.userIds());
        return ResponseEntity.ok(Map.of("deleted", count));
    }

    @PostMapping("/bulk/toggle-status")
    public ResponseEntity<Map<String, Object>> bulkToggleStatus(@RequestBody BulkUserActionRequest req) {
        boolean enabled = req.enabled() != null && req.enabled();
        int count = adminUserService.bulkToggleEnabled(req.userIds(), enabled);
        return ResponseEntity.ok(Map.of("updated", count));
    }
}
