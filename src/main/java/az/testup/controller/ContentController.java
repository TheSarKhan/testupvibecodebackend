package az.testup.controller;

import az.testup.dto.response.BannerResponse;
import az.testup.entity.Banner;
import az.testup.entity.User;
import az.testup.enums.BannerAudience;
import az.testup.enums.Role;
import az.testup.repository.BannerRepository;
import az.testup.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/content")
@RequiredArgsConstructor
public class ContentController {

    private final BannerRepository bannerRepository;
    private final UserRepository userRepository;

    @GetMapping("/banners")
    public ResponseEntity<List<BannerResponse>> getActiveBanners(Authentication authentication) {
        BannerAudience audience = resolveAudience(authentication);

        List<BannerResponse> banners = bannerRepository.findByIsActiveTrueOrderByOrderIndexAsc()
                .stream()
                .filter(b -> {
                    BannerAudience target = b.getTargetAudience() != null ? b.getTargetAudience() : BannerAudience.ALL;
                    return target == BannerAudience.ALL || target == audience;
                })
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(banners);
    }

    private BannerAudience resolveAudience(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return BannerAudience.GUEST;
        }
        String email = authentication.getName();
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) return BannerAudience.GUEST;
        return user.getRole() == Role.TEACHER || user.getRole() == Role.ADMIN
                ? BannerAudience.TEACHER
                : BannerAudience.STUDENT;
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
                .targetAudience(b.getTargetAudience() != null ? b.getTargetAudience().name() : BannerAudience.ALL.name())
                .build();
    }
}
