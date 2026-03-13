package az.testup.controller;

import az.testup.dto.request.AssignSubscriptionRequest;
import az.testup.dto.response.UserSubscriptionResponse;
import az.testup.service.UserSubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user-subscriptions")

@RequiredArgsConstructor
public class UserSubscriptionController {

    private final UserSubscriptionService userSubscriptionService;

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
            @Valid @RequestBody AssignSubscriptionRequest request) {
        return ResponseEntity.ok(userSubscriptionService.assignSubscription(request));
    }

    @PostMapping("/{subscriptionId}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> cancelSubscription(@PathVariable Long subscriptionId) {
        userSubscriptionService.cancelSubscription(subscriptionId);
        return ResponseEntity.noContent().build();
    }
}
