package az.testup.controller.admin;

import az.testup.dto.request.BannerRequest;
import az.testup.dto.response.BannerResponse;
import az.testup.service.AdminBannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/banners")
@RequiredArgsConstructor
public class AdminBannerController {

    private final AdminBannerService adminBannerService;

    @GetMapping
    public ResponseEntity<Page<BannerResponse>> getBanners(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(adminBannerService.getBanners(PageRequest.of(page, size)));
    }

    @PostMapping
    public ResponseEntity<BannerResponse> createBanner(@RequestBody BannerRequest req) {
        return ResponseEntity.ok(adminBannerService.createBanner(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BannerResponse> updateBanner(@PathVariable Long id, @RequestBody BannerRequest req) {
        return ResponseEntity.ok(adminBannerService.updateBanner(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBanner(@PathVariable Long id) {
        adminBannerService.deleteBanner(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/impression")
    public ResponseEntity<Void> trackImpression(@PathVariable Long id) {
        adminBannerService.recordImpression(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/click")
    public ResponseEntity<Void> trackClick(@PathVariable Long id) {
        adminBannerService.recordClick(id);
        return ResponseEntity.noContent().build();
    }
}
