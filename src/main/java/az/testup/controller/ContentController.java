package az.testup.controller;

import az.testup.dto.response.BannerResponse;
import az.testup.entity.Banner;
import az.testup.repository.BannerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/content")
@RequiredArgsConstructor
public class ContentController {

    private final BannerRepository bannerRepository;

    @GetMapping("/banners")
    public ResponseEntity<List<BannerResponse>> getActiveBanners() {
        return ResponseEntity.ok(bannerRepository.findByIsActiveTrueOrderByOrderIndexAsc().stream()
                .map(this::toResponse).toList());
    }

    private BannerResponse toResponse(Banner b) {
        return BannerResponse.builder()
                .id(b.getId())
                .title(b.getTitle())
                .subtitle(b.getSubtitle())
                .imageUrl(b.getImageUrl())
                .linkUrl(b.getLinkUrl())
                .linkText(b.getLinkText())
                .isActive(b.isActive())
                .position(b.getPosition() != null ? b.getPosition().name() : null)
                .bgGradient(b.getBgGradient())
                .orderIndex(b.getOrderIndex())
                .build();
    }
}
