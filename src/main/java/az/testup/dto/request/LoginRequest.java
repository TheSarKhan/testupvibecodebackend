package az.testup.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "E-poçt boş ola bilməz")
    @Email(message = "Düzgün e-poçt daxil edin")
    private String email;

    @NotBlank(message = "Şifrə boş ola bilməz")
    private String password;
}
