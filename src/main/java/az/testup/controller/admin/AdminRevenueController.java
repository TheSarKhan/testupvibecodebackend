package az.testup.controller.admin;

import az.testup.dto.response.PendingOrderResponse;
import az.testup.dto.response.RevenueStatsResponse;
import az.testup.service.AdminRevenueService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/revenue")
@RequiredArgsConstructor
public class AdminRevenueController {

    private final AdminRevenueService adminRevenueService;

    @GetMapping
    public ResponseEntity<RevenueStatsResponse> getRevenue() {
        return ResponseEntity.ok(adminRevenueService.getRevenueStats());
    }

    @GetMapping("/pending-orders")
    public ResponseEntity<org.springframework.data.domain.Page<PendingOrderResponse>> getPendingOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(adminRevenueService.getPendingOrders(
                org.springframework.data.domain.PageRequest.of(page, size)));
    }

    @PostMapping("/verify-order/{orderId}")
    public ResponseEntity<Map<String, Object>> verifyOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(adminRevenueService.verifyOrder(orderId));
    }

    @PostMapping("/force-activate/{orderId}")
    public ResponseEntity<Map<String, Object>> forceActivate(
            @PathVariable String orderId,
            @AuthenticationPrincipal UserDetails principal) {
        String adminEmail = principal != null ? principal.getUsername() : "admin";
        return ResponseEntity.ok(adminRevenueService.forceActivate(orderId, adminEmail));
    }

    @PostMapping("/cancel-order/{orderId}")
    public ResponseEntity<Map<String, Object>> cancelOrder(
            @PathVariable String orderId,
            @AuthenticationPrincipal UserDetails principal) {
        String adminEmail = principal != null ? principal.getUsername() : "admin";
        return ResponseEntity.ok(adminRevenueService.cancelOrder(orderId, adminEmail));
    }

    @GetMapping("/export")
    public ResponseEntity<String> exportCsv(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        String csv = adminRevenueService.exportCsv(status, from, to);
        String filename = "revenue-" + java.time.LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body("﻿" + csv);
    }
}
