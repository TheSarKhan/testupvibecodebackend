package az.testup.config;

import az.testup.entity.ExamSubject;
import az.testup.entity.User;
import az.testup.enums.Role;
import az.testup.repository.ExamSubjectRepository;
import az.testup.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ExamSubjectRepository subjectRepository;
    private final EntityManager entityManager;

    private static final List<String> DEFAULT_SUBJECTS = List.of(
            "Riyaziyyat", "Fizika", "Kimya", "Biologiya",
            "Azərbaycan dili", "İngilis dili", "Tarix", "Coğrafiya",
            "Informatika", "Məntiq", "Ədəbiyyat", "Xarici dil",
            "Rus dili", "Alman dili", "Fransız dili", "Həyat bilgisi",
            "İncəsənət", "Musiqi", "Fiziki tərbiyə", "Texnologiya"
    );

    // Old enum value → display name
    private static final Map<String, String> ENUM_TO_DISPLAY = Map.ofEntries(
            Map.entry("RIYAZIYYAT", "Riyaziyyat"),
            Map.entry("FIZIKA", "Fizika"),
            Map.entry("KIMYA", "Kimya"),
            Map.entry("BIOLOGIYA", "Biologiya"),
            Map.entry("AZERBAYCAN_DILI", "Azərbaycan dili"),
            Map.entry("INGILIS_DILI", "İngilis dili"),
            Map.entry("TARIX", "Tarix"),
            Map.entry("COGRAFIYA", "Coğrafiya"),
            Map.entry("INFORMATIKA", "Informatika"),
            Map.entry("MANTIQ", "Məntiq"),
            Map.entry("EDEBIYYAT", "Ədəbiyyat"),
            Map.entry("XARICI_DILL", "Xarici dil"),
            Map.entry("RUS_DILI", "Rus dili"),
            Map.entry("ALMAN_DILI", "Alman dili"),
            Map.entry("FRANSIZ_DILI", "Fransız dili"),
            Map.entry("HAYAT_BILGISI", "Həyat bilgisi"),
            Map.entry("INCASANAT", "İncəsənət"),
            Map.entry("MUSIQI", "Musiqi"),
            Map.entry("FIZIKI_TERBIYE", "Fiziki tərbiyə"),
            Map.entry("TEXNOLOGIYA", "Texnologiya")
    );

    @Override
    @Transactional
    public void run(String... args) {
        seedAdmin();
        seedSubjects();
        migrateExamSubjectsToList();
    }

    private void seedAdmin() {
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

    private void seedSubjects() {
        if (subjectRepository.count() == 0) {
            for (String name : DEFAULT_SUBJECTS) {
                subjectRepository.save(ExamSubject.builder()
                        .name(name)
                        .isDefault(true)
                        .build());
            }
            log.info("{} default fənn əlavə edildi", DEFAULT_SUBJECTS.size());
        }
    }

    /**
     * Migrates old single-subject data to the new exam_subject_list table.
     * Step 1: Normalize old enum values (RIYAZIYYAT → Riyaziyyat) in exams.subject column.
     * Step 2: Copy non-null subject values into exam_subject_list where not already present.
     */
    @SuppressWarnings("unchecked")
    private void migrateExamSubjectsToList() {
        // Guard: if 'subject' column was already dropped, migration is done
        Number colCount = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_name = 'exams' AND column_name = 'subject'"
        ).getSingleResult();
        if (colCount.intValue() == 0) {
            log.debug("Column 'subject' not present in exams — migration already done, skipping");
            return;
        }

        // Step 1: normalize old enum-named values
        for (Map.Entry<String, String> entry : ENUM_TO_DISPLAY.entrySet()) {
            int updated = entityManager.createNativeQuery(
                    "UPDATE exams SET subject = :display WHERE subject = :enum_val"
            ).setParameter("display", entry.getValue())
             .setParameter("enum_val", entry.getKey())
             .executeUpdate();
            if (updated > 0) {
                log.info("Normalized {} exam(s): {} → {}", updated, entry.getKey(), entry.getValue());
            }
        }

        // Step 2: copy subject → exam_subject_list (idempotent)
        int inserted = entityManager.createNativeQuery(
                "INSERT INTO exam_subject_list (exam_id, subject) " +
                "SELECT id, subject FROM exams " +
                "WHERE subject IS NOT NULL AND subject != '' " +
                "AND id NOT IN (SELECT DISTINCT exam_id FROM exam_subject_list)"
        ).executeUpdate();

        if (inserted > 0) {
            log.info("Migrated {} exam(s) subjects to exam_subject_list", inserted);
        }
    }
}
