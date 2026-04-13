package az.testup.dto.request;

import lombok.Data;

@Data
public class BannerRequest {
    private String title;
    private String subtitle;
    private String imageUrl;
    private String linkUrl;
    private String linkText;
    private Boolean isActive;
    private String position; // HERO | INLINE | BOTTOM
    private String bgGradient;
    private Integer orderIndex;
    private String targetAudience; // ALL | GUEST | TEACHER | STUDENT
}
