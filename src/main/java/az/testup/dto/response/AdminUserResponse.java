package az.testup.dto.response;

import az.testup.enums.Role;
import java.time.LocalDateTime;

public record AdminUserResponse(
        Long id,
        String fullName,
        String email,
        Role role,
        String profilePicture,
        LocalDateTime createdAt,
        boolean enabled
) {}
