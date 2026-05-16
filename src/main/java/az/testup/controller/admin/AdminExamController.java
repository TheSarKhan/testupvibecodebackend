package az.testup.controller.admin;

import az.testup.dto.request.SetExamPriceRequest;
import az.testup.dto.response.AdminExamResponse;
import az.testup.enums.AuditAction;
import az.testup.enums.ExamStatus;
import az.testup.service.AdminExamService;
import az.testup.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/exams")
@RequiredArgsConstructor
public class AdminExamController {

    private final AdminExamService adminExamService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<Page<AdminExamResponse>> getExams(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) String teacherRoleName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ExamStatus statusEnum = (status != null && !status.isBlank()) ? ExamStatus.valueOf(status) : null;
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(adminExamService.getExams(
                search, statusEnum, teacherId,
                (teacherRoleName != null && !teacherRoleName.isBlank()) ? teacherRoleName : null,
                pageable));
    }

    @PatchMapping("/{id}/site-publish")
    public ResponseEntity<AdminExamResponse> toggleSitePublished(@PathVariable Long id) {
        return ResponseEntity.ok(adminExamService.toggleSitePublished(id));
    }

    @PatchMapping("/{id}/price")
    public ResponseEntity<AdminExamResponse> setExamPrice(
            @PathVariable Long id,
            @RequestBody SetExamPriceRequest request) {
        AdminExamResponse resp = adminExamService.setExamPrice(id, request.price());
        auditLogService.logCurrent(AuditAction.EXAM_PRICE_CHANGED, "EXAM", resp.title(),
                "Yeni qiymət: " + request.price());
        return ResponseEntity.ok(resp);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteExam(@PathVariable Long id) {
        adminExamService.deleteExam(id);
        return ResponseEntity.noContent().build();
    }
}
