package az.testup.controller.admin;

import az.testup.dto.response.AdminStatsResponse;
import az.testup.service.AdminStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    @GetMapping
    public ResponseEntity<AdminStatsResponse> getStats() {
        return ResponseEntity.ok(adminStatsService.getStats());
    }
}
