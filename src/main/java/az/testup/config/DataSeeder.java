package az.testup.config;

import az.testup.entity.*;
import az.testup.enums.QuestionType;
import az.testup.enums.Role;
import az.testup.repository.*;
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
    private final TemplateRepository templateRepository;
    private final TemplateSubtitleRepository subtitleRepository;
    private final TemplateSectionRepository sectionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;

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
        seedSubscriptionPlans();
        seedAdmin();
        seedTeacher();
        seedStudent();
        seedSubjects();
        migrateExamSubjectsToList();
        seedDimTemplate();
    }

    private void seedSubscriptionPlans() {
        if (subscriptionPlanRepository.count() == 0) {
            // Free Plan
            SubscriptionPlan freePlan = SubscriptionPlan.builder()
                    .name("Free")
                    .price(0.0)
                    .description("Platformamızla tanış olmaq üçün limitsiz müddətli pulsuz plan.")
                    .monthlyExamLimit(2)
                    .maxQuestionsPerExam(20)
                    .maxSavedExamsLimit(5)
                    .maxParticipantsPerExam(10)
                    .studentResultAnalysis(false)
                    .examEditing(false)
                    .addImage(false)
                    .addPassageQuestion(false)
                    .downloadPastExams(false)
                    .downloadAsPdf(false)
                    .multipleSubjects(false)
                    .useTemplateExams(false)
                    .manualChecking(false)
                    .selectExamDuration(false)
                    .useQuestionBank(false)
                    .createQuestionBank(false)
                    .importQuestionsFromPdf(false)
                    .build();

            // Basic Plan
            SubscriptionPlan basicPlan = SubscriptionPlan.builder()
                    .name("Basic")
                    .price(29.90)
                    .description("Fərdi müəllimlər üçün nəzərdə tutulmuş orta səviyyəli plan.")
                    .monthlyExamLimit(10)
                    .maxQuestionsPerExam(100)
                    .maxSavedExamsLimit(50)
                    .maxParticipantsPerExam(50)
                    .studentResultAnalysis(true)
                    .examEditing(true)
                    .addImage(true)
                    .addPassageQuestion(true)
                    .downloadPastExams(true)
                    .downloadAsPdf(true)
                    .multipleSubjects(true)
                    .useTemplateExams(true)
                    .manualChecking(false)
                    .selectExamDuration(true)
                    .useQuestionBank(true)
                    .createQuestionBank(false)
                    .importQuestionsFromPdf(false)
                    .build();

            // Unlimited Plan
            SubscriptionPlan unlimitedPlan = SubscriptionPlan.builder()
                    .name("Limitsiz")
                    .price(59.90)
                    .description("Bütün funksionallıqlardan və məhdudiyyətsiz limitlərdən faydalanın.")
                    .monthlyExamLimit(-1)
                    .maxQuestionsPerExam(-1)
                    .maxSavedExamsLimit(-1)
                    .maxParticipantsPerExam(-1)
                    .studentResultAnalysis(true)
                    .examEditing(true)
                    .addImage(true)
                    .addPassageQuestion(true)
                    .downloadPastExams(true)
                    .downloadAsPdf(true)
                    .multipleSubjects(true)
                    .useTemplateExams(true)
                    .manualChecking(true)
                    .selectExamDuration(true)
                    .useQuestionBank(true)
                    .createQuestionBank(true)
                    .importQuestionsFromPdf(true)
                    .build();

            subscriptionPlanRepository.saveAll(List.of(freePlan, basicPlan, unlimitedPlan));
            log.info("3 Subscription Plans (Free, Basic, Limitsiz) seeded successfully.");
        }
    }

    private void assignUnlimitedPlanToUser(User user) {
        SubscriptionPlan unlimitedPlan = subscriptionPlanRepository.findByName("Limitsiz")
                .orElseThrow(() -> new RuntimeException("Limitsiz plan tapılmadı"));
        
        UserSubscription subscription = UserSubscription.builder()
                .user(user)
                .plan(unlimitedPlan)
                .startDate(java.time.LocalDateTime.now())
                .endDate(java.time.LocalDateTime.now().plusYears(100))
                .isActive(true)
                .paymentProvider("SYSTEM_SEED")
                .build();
        userSubscriptionRepository.save(subscription);
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
            assignUnlimitedPlanToUser(admin);
            log.info("Admin istifadəçisi yaradıldı: {}", adminEmail);
        }
    }

    private void seedTeacher() {
        String email = "serxan.babayev.06@gmail.com";
        if (!userRepository.existsByEmail(email)) {
            User teacher = User.builder()
                    .fullName("Sərxan Babayev")
                    .email(email)
                    .password(passwordEncoder.encode("salam123"))
                    .role(Role.TEACHER)
                    .build();
            userRepository.save(teacher);
            assignUnlimitedPlanToUser(teacher);
            log.info("Müəllim hesabı yaradıldı: {}", email);
        }
    }

    private void seedStudent() {
        String email = "serxanbabayev614@gmail.com";
        if (!userRepository.existsByEmail(email)) {
            userRepository.save(User.builder()
                    .fullName("Sərxan Babayev")
                    .email(email)
                    .password(passwordEncoder.encode("salam123"))
                    .role(Role.STUDENT)
                    .build());
            log.info("Şagird hesabı yaradıldı: {}", email);
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

    private void seedDimTemplate() {
        if (templateRepository.findByTitle("DİM Buraxılış").isPresent()) {
            log.debug("DİM Buraxılış şablonu artıq mövcuddur, keçilir");
            return;
        }

        Template template = Template.builder()
                .title("DİM Buraxılış")
                .build();
        template = templateRepository.save(template);

        TemplateSubtitle subtitle = TemplateSubtitle.builder()
                .template(template)
                .subtitle("11-ci sinif")
                .orderIndex(0)
                .build();
        subtitle = subtitleRepository.save(subtitle);

        // İngilis dili: MCQ=23, OPEN_MANUAL=7
        TemplateSection ingilis = TemplateSection.builder()
                .subtitle(subtitle)
                .subjectName("İngilis dili")
                .questionCount(30)
                .formula("(100.0/37.0)*(2*l+a)")
                .orderIndex(0)
                .build();
        ingilis = sectionRepository.save(ingilis);
        addTypeCount(ingilis, QuestionType.MCQ, 23, 0);
        addTypeCount(ingilis, QuestionType.OPEN_MANUAL, 7, 1);

        // Az dili: MCQ=20, OPEN_MANUAL=10
        TemplateSection azDili = TemplateSection.builder()
                .subtitle(subtitle)
                .subjectName("Azərbaycan dili")
                .questionCount(30)
                .formula("(5.0/2.0)*(2*l+a)")
                .orderIndex(1)
                .build();
        azDili = sectionRepository.save(azDili);
        addTypeCount(azDili, QuestionType.MCQ, 20, 0);
        addTypeCount(azDili, QuestionType.OPEN_MANUAL, 10, 1);

        // Riyaziyyat: MCQ=13, OPEN_AUTO=5, OPEN_MANUAL=7
        TemplateSection riyaziyyat = TemplateSection.builder()
                .subtitle(subtitle)
                .subjectName("Riyaziyyat")
                .questionCount(25)
                .formula("(25.0/8.0)*(2*l+f+a)")
                .orderIndex(2)
                .build();
        riyaziyyat = sectionRepository.save(riyaziyyat);
        addTypeCount(riyaziyyat, QuestionType.MCQ, 13, 0);
        addTypeCount(riyaziyyat, QuestionType.OPEN_AUTO, 5, 1);
        addTypeCount(riyaziyyat, QuestionType.OPEN_MANUAL, 7, 2);

        log.info("DİM Buraxılış şablonu uğurla yaradıldı");
    }

    private void addTypeCount(TemplateSection section, QuestionType type, int count, int order) {
        TemplateSectionTypeCount tc = TemplateSectionTypeCount.builder()
                .section(section)
                .questionType(type)
                .count(count)
                .orderIndex(order)
                .build();
        section.getTypeCounts().add(tc);
        sectionRepository.save(section);
    }
}
