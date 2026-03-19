package az.testup.repository;

import az.testup.entity.Banner;
import az.testup.enums.BannerPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BannerRepository extends JpaRepository<Banner, Long> {
    List<Banner> findByIsActiveTrueOrderByOrderIndexAsc();
    List<Banner> findByIsActiveTrueAndPositionOrderByOrderIndexAsc(BannerPosition position);
    List<Banner> findAllByOrderByOrderIndexAsc();
}
