package az.testup.controller.admin;

import az.testup.dto.response.AuditLogResponse;
import az.testup.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/logs")
@RequiredArgsConstructor
public class AdminAuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<Page<AuditLogResponse>> getLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String targetType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        var pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(auditLogService.getLogs(action, category, search, period, actor, targetType, pageable));
    }
}
