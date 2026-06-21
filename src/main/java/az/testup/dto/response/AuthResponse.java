package az.testup.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private String role;
    private String fullName;
    private String email;
    private boolean giftPlanAssigned;
    // When true, the caller must confirm an emailed OTP before tokens are issued.
    // accessToken/refreshToken are null in this case and the client should route
    // to the verification screen carrying `email`.
    private boolean emailVerificationRequired;
}
