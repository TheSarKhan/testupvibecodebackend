package az.testup.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartSubmissionRequest {
    /** Required only if the user is a guest */
    private String guestName;
    
    /** Required if it's a private exam */
    private String accessCode;
}
