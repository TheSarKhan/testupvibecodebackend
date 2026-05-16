package az.testup.service;

import az.testup.dto.request.BannerRequest;
import az.testup.dto.response.BannerResponse;
import az.testup.entity.Banner;
import az.testup.enums.AuditAction;
import az.testup.enums.BannerAudience;
import az.testup.enums.BannerPosition;
import az.testup.exception.ResourceNotFoundException;
import az.testup.repository.BannerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminBannerService {

    private final BannerRepository bannerRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public Page<BannerResponse> getBanners(Pageable pageable) {
        return bannerRepository.findAllByOrderByOrderIndexAsc(pageable).map(this::toResponse);
    }

    @Transactional
    public BannerResponse createBanner(BannerRequest req) {
        Banner banner = Banner.builder()
                .title(req.getTitle())
                .subtitle(req.getSubtitle())
                .imageUrl(req.getImageUrl())
                .linkUrl(req.getLinkUrl())
                .linkText(req.getLinkText() != null ? req.getLinkText() : "Ətraflı bax")
                .isActive(Boolean.TRUE.equals(req.getIsActive()))
                .position(BannerPosition.valueOf(req.getPosition() != null ? req.getPosition() : "INLINE"))
                .bgGradient(req.getBgGradient())
                .orderIndex(req.getOrderIndex() != null ? req.getOrderIndex() : 0)
                .targetAudience(req.getTargetAudience() != null
                        ? BannerAudience.valueOf(req.getTargetAudience())
                        : BannerAudience.ALL)
                .startAt(req.getStartAt())
                .endAt(req.getEndAt())
                .impressionCount(0L)
                .clickCount(0L)
                .build();
        Banner saved = bannerRepository.save(banner);
        auditLogService.logCurrent(AuditAction.BANNER_CREATED, "BANNER", saved.getTitle(),
                "Pozisiya: " + saved.getPosition());
        return toResponse(saved);
    }

    @Transactional
    public BannerResponse updateBanner(Long id, BannerRequest req) {
        Banner banner = bannerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Banner tapılmadı"));
        if (req.getTitle() != null) banner.setTitle(req.getTitle());
        if (req.getSubtitle() != null) banner.setSubtitle(req.getSubtitle());
        if (req.getImageUrl() != null) banner.setImageUrl(req.getImageUrl());
        if (req.getLinkUrl() != null) banner.setLinkUrl(req.getLinkUrl());
        if (req.getLinkText() != null) banner.setLinkText(req.getLinkText());
        if (req.getIsActive() != null) banner.setActive(req.getIsActive());
        if (req.getPosition() != null) banner.setPosition(BannerPosition.valueOf(req.getPosition()));
        if (req.getBgGradient() != null) banner.setBgGradient(req.getBgGradient());
        if (req.getOrderIndex() != null) banner.setOrderIndex(req.getOrderIndex());
        if (req.getTargetAudience() != null) banner.setTargetAudience(BannerAudience.valueOf(req.getTargetAudience()));
        if (req.getStartAt() != null) banner.setStartAt(req.getStartAt());
        if (req.getEndAt() != null) banner.setEndAt(req.getEndAt());
        Banner saved = bannerRepository.save(banner);
        auditLogService.logCurrent(AuditAction.BANNER_UPDATED, "BANNER", saved.getTitle(), null);
        return toResponse(saved);
    }

    @Transactional
    public void recordImpression(Long id) {
        bannerRepository.findById(id).ifPresent(b -> {
            b.setImpressionCount((b.getImpressionCount() == null ? 0L : b.getImpressionCount()) + 1);
            bannerRepository.save(b);
        });
    }

    @Transactional
    public void recordClick(Long id) {
        bannerRepository.findById(id).ifPresent(b -> {
            b.setClickCount((b.getClickCount() == null ? 0L : b.getClickCount()) + 1);
            bannerRepository.save(b);
        });
    }

    @Transactional
    public void deleteBanner(Long id) {
        String title = bannerRepository.findById(id).map(Banner::getTitle).orElse("ID:" + id);
        bannerRepository.deleteById(id);
        auditLogService.logCurrent(AuditAction.BANNER_DELETED, "BANNER", title, null);
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
                .targetAudience(b.getTargetAudience() != null
                        ? b.getTargetAudience().name()
                        : BannerAudience.ALL.name())
                .startAt(b.getStartAt())
                .endAt(b.getEndAt())
                .impressionCount(b.getImpressionCount())
                .clickCount(b.getClickCount())
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .build();
    }
}
