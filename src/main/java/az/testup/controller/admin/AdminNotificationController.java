package az.testup.controller.admin;

import az.testup.dto.request.AdminNotificationRequest;
import az.testup.dto.response.NotificationLogResponse;
import az.testup.service.AdminNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final AdminNotificationService adminNotificationService;

    @PostMapping(value = "/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<NotificationLogResponse> sendNotification(
            @RequestPart("request") AdminNotificationRequest request,
            @RequestPart(value = "attachment", required = false) MultipartFile attachment,
            @AuthenticationPrincipal UserDetails userDetails) {
        String adminEmail = userDetails != null ? userDetails.getUsername() : "admin";
        return ResponseEntity.ok(adminNotificationService.sendAdminNotification(request, attachment, adminEmail));
    }

    @GetMapping("/history")
    public ResponseEntity<Page<NotificationLogResponse>> getNotificationHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("sentAt").descending());
        return ResponseEntity.ok(adminNotificationService.getHistory(pageable));
    }
}
