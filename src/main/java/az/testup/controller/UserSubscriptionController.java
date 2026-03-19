package az.testup.controller;

import az.testup.dto.request.AssignSubscriptionRequest;
import az.testup.dto.response.UserSubscriptionResponse;
import az.testup.enums.AuditAction;
import az.testup.repository.SubscriptionPlanRepository;
import az.testup.repository.UserRepository;
import az.testup.service.AuditLogService;
import az.testup.service.UserSubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user-subscriptions")

@RequiredArgsConstructor
public class UserSubscriptionController {

    private final UserSubscriptionService userSubscriptionService;
    private final AuditLogService auditLogService;
    private final UserRepository userRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserSubscriptionResponse>> getAllSubscriptions() {
        return ResponseEntity.ok(userSubscriptionService.getAllSubscriptions());
    }

    @GetMapping("/user/{userId}/active")
    @PreAuthorize("hasRole('ADMIN') or principal.id == #userId")
    public ResponseEntity<UserSubscriptionResponse> getActiveSubscription(@PathVariable Long userId) {
        UserSubscriptionResponse response = userSubscriptionService.getActiveSubscription(userId);
        return response != null ? ResponseEntity.ok(response) : ResponseEntity.noContent().build();
    }

    @PostMapping("/assign")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserSubscriptionResponse> assignSubscription(
            @Valid @RequestBody AssignSubscriptionRequest request,
            @AuthenticationPrincipal UserDetails principal) {
        UserSubscriptionResponse result = userSubscriptionService.assignSubscription(request);
        String planName = subscriptionPlanRepository.findById(request.getPlanId())
                .map(p -> p.getName()).orElse("ID:" + request.getPlanId());
        String targetEmail = userRepository.findById(request.getUserId())
                .map(u -> u.getEmail()).orElse("ID:" + request.getUserId());
        String adminEmail = principal != null ? principal.getUsername() : "system";
        String adminName = principal != null ? userRepository.findByEmail(adminEmail)
                .map(u -> u.getFullName()).orElse(adminEmail) : "system";
        auditLogService.log(AuditAction.SUBSCRIPTION_ASSIGNED_MANUAL, adminEmail, adminName,
                "SUBSCRIPTION", planName,
                "İstifadəçi: " + targetEmail + ". Müddət: " + request.getDurationMonths() + " ay");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{subscriptionId}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> cancelSubscription(
            @PathVariable Long subscriptionId,
            @AuthenticationPrincipal UserDetails principal) {
        userSubscriptionService.cancelSubscription(subscriptionId);
        String adminEmail = principal != null ? principal.getUsername() : "system";
        String adminName = principal != null ? userRepository.findByEmail(adminEmail)
                .map(u -> u.getFullName()).orElse(adminEmail) : "system";
        auditLogService.log(AuditAction.SUBSCRIPTION_CANCELLED, adminEmail, adminName,
                "SUBSCRIPTION", "ID:" + subscriptionId, "Admin tərəfindən ləğv edildi");
        return ResponseEntity.noContent().build();
    }
}
