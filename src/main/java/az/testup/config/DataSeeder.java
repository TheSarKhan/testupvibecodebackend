package az.testup.config;

import az.testup.entity.User;
import az.testup.enums.Role;
import az.testup.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        String adminEmail = "sarxanbabayevcontact@gmail.com";

        if (!userRepository.existsByEmail(adminEmail)) {
            User admin = User.builder()
                    .fullName("Sərxan Babayev")
                    .email(adminEmail)
                    .password(passwordEncoder.encode("salam123"))
                    .role(Role.ADMIN)
                    .build();
            userRepository.save(admin);
            log.info("Admin istifadəçisi yaradıldı: {}", adminEmail);
        }
    }
}
