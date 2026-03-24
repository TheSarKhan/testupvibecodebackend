package az.testup.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "Ad boş ola bilməz")
    private String fullName;

    @NotBlank(message = "E-poçt boş ola bilməz")
    @Email(message = "Düzgün e-poçt daxil edin")
    private String email;

    @NotBlank(message = "Şifrə boş ola bilməz")
    @Size(min = 6, message = "Şifrə ən azı 6 simvol olmalıdır")
    private String password;

    @NotBlank(message = "Telefon nömrəsi boş ola bilməz")
    @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "Düzgün telefon nömrəsi daxil edin")
    private String phoneNumber;

    @NotBlank(message = "Rol boş ola bilməz")
    private String role;

    @AssertTrue(message = "İstifadə şərtlərini qəbul etməlisiniz")
    private Boolean termsAccepted;
}
