package az.testup.dto.request;

import lombok.Data;

@Data
public class BankOptionRequest {
    private Long id;
    private String content;
    private Boolean isCorrect;
    private Integer orderIndex;
    private String attachedImage;
}
