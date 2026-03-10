package az.testup.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    @NotBlank(message = "Cari şifrə boş ola bilməz")
    private String currentPassword;

    @NotBlank(message = "Yeni şifrə boş ola bilməz")
    @Size(min = 6, message = "Yeni şifrə ən azı 6 simvol olmalıdır")
    private String newPassword;
}
