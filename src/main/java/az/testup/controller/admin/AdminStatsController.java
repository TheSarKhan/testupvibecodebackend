package az.testup.controller.admin;

import az.testup.dto.response.AdminStatsResponse;
import az.testup.dto.response.ExecutiveDashboardResponse;
import az.testup.service.AdminDashboardService;
import az.testup.service.AdminStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;
    private final AdminDashboardService adminDashboardService;

    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> getStats() {
        return ResponseEntity.ok(adminStatsService.getStats());
    }

    @GetMapping("/dashboard/executive")
    public ResponseEntity<ExecutiveDashboardResponse> getExecutiveOverview() {
        return ResponseEntity.ok(adminDashboardService.getExecutiveOverview());
    }
}
