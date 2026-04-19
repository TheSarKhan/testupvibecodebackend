package az.testup.config;

import az.testup.entity.*;
import az.testup.enums.*;
import az.testup.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ExamSubjectRepository subjectRepository;
    private final SubjectTopicRepository subjectTopicRepository;
    private final EntityManager entityManager;
    private final TemplateRepository templateRepository;
    private final TemplateSubtitleRepository subtitleRepository;
    private final TemplateSectionRepository sectionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final BannerRepository bannerRepository;
    private final TagRepository tagRepository;
    private final ExamRepository examRepository;
    private final PassageRepository passageRepository;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    // Subject name → metadata [color, iconEmoji]
    private static final Map<String, String[]> SUBJECT_METADATA = Map.ofEntries(
            Map.entry("Riyaziyyat",       new String[]{"#6366f1", "📐"}),
            Map.entry("Fizika",           new String[]{"#0ea5e9", "⚛️"}),
            Map.entry("Kimya",            new String[]{"#10b981", "🧪"}),
            Map.entry("Biologiya",        new String[]{"#22c55e", "🧬"}),
            Map.entry("Azərbaycan dili",  new String[]{"#f59e0b", "📝"}),
            Map.entry("İngilis dili",     new String[]{"#3b82f6", "🌍"}),
            Map.entry("Tarix",            new String[]{"#ef4444", "🏛️"}),
            Map.entry("Coğrafiya",        new String[]{"#84cc16", "🗺️"}),
            Map.entry("Informatika",      new String[]{"#8b5cf6", "💻"}),
            Map.entry("Məntiq",           new String[]{"#f97316", "🧠"}),
            Map.entry("Ədəbiyyat",        new String[]{"#ec4899", "📚"}),
            Map.entry("Rus dili",         new String[]{"#06b6d4", "🇷🇺"}),
            Map.entry("Alman dili",       new String[]{"#14b8a6", "🇩🇪"}),
            Map.entry("Fransız dili",     new String[]{"#a855f7", "🇫🇷"}),
            Map.entry("Həyat bilgisi",    new String[]{"#f43f5e", "🌱"})
    );

    @Override
    public void run(String... args) {
        seedSubscriptionPlans();
        seedAdmin();
        seedTeacher();
        seedStudent();
        seedSubjects();
        migrateExamSubjectsToList();
        seedSubjectTopics();
        seedDimTemplate();
        seedBanners();
        seedTags();
        seedOlimpiyadaTemplate();
        seedSampleOlimpiyadaExam();
        seedSampleRiyaziyyatExam();
        seedSampleMultiSubjectExam();
        seedSampleIngilisDiliExam();
    }

    @Transactional
    public void seedTags() {
        if (tagRepository.count() > 0) return;
        List.of(
            "1-ci sinif", "2-ci sinif", "3-cü sinif", "4-cü sinif",
            "5-ci sinif", "6-cı sinif", "7-ci sinif", "8-ci sinif",
            "9-cu sinif", "10-cu sinif", "11-ci sinif",
            "Asan", "Buraxılış imtahanı", "Cəbr", "Dinləmə",
            "Fəsil sonu", "Həndəsə", "Leksika", "Məktəb daxili",
            "Olimpiada", "Orta", "Oxuma", "Qiymətləndirmə",
            "Qrammatika", "Test", "Yarımillik", "Çətin"
        ).forEach(name -> tagRepository.save(Tag.builder().name(name).build()));
        log.info("Tags seeded: {} tags", tagRepository.count());
    }

    @Transactional
    public void seedSubscriptionPlans() {
        if (subscriptionPlanRepository.count() == 0) {
            // Free Plan
            SubscriptionPlan freePlan = SubscriptionPlan.builder()
                    .name("Free")
                    .price(0.0)
                    .level(0)
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
                    .monthlyAiQuestionLimit(0)
                    .useAiExamGeneration(false)
                    .build();

            // Basic Plan
            SubscriptionPlan basicPlan = SubscriptionPlan.builder()
                    .name("Basic")
                    .price(29.90)
                    .level(1)
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
                    .monthlyAiQuestionLimit(30)
                    .useAiExamGeneration(false)
                    .build();

            // Unlimited Plan
            SubscriptionPlan unlimitedPlan = SubscriptionPlan.builder()
                    .name("Limitsiz")
                    .price(59.90)
                    .level(2)
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
                    .monthlyAiQuestionLimit(-1)
                    .useAiExamGeneration(true)
                    .build();

            subscriptionPlanRepository.saveAll(List.of(freePlan, basicPlan, unlimitedPlan));
            log.info("3 Subscription Plans (Free, Basic, Limitsiz) seeded successfully.");
        } else {
            // Migrate existing plans: ensure level and new AI fields are set correctly
            subscriptionPlanRepository.findAll().forEach(plan -> {
                boolean changed = false;

                if ("Free".equalsIgnoreCase(plan.getName())) {
                    if (plan.getLevel() == null) { plan.setLevel(0); changed = true; }
                    if (!Integer.valueOf(0).equals(plan.getMonthlyAiQuestionLimit()) || plan.isUseAiExamGeneration()) {
                        plan.setMonthlyAiQuestionLimit(0);
                        plan.setUseAiExamGeneration(false);
                        changed = true;
                    }
                } else if ("Basic".equalsIgnoreCase(plan.getName())) {
                    if (plan.getLevel() == null || plan.getLevel() != 1) { plan.setLevel(1); changed = true; }
                    if (!Integer.valueOf(30).equals(plan.getMonthlyAiQuestionLimit()) || plan.isUseAiExamGeneration()) {
                        plan.setMonthlyAiQuestionLimit(30);
                        plan.setUseAiExamGeneration(false);
                        changed = true;
                    }
                } else if ("Limitsiz".equalsIgnoreCase(plan.getName())) {
                    if (plan.getLevel() == null || plan.getLevel() != 2) { plan.setLevel(2); changed = true; }
                    if (!Integer.valueOf(-1).equals(plan.getMonthlyAiQuestionLimit()) || !plan.isUseAiExamGeneration()) {
                        plan.setMonthlyAiQuestionLimit(-1);
                        plan.setUseAiExamGeneration(true);
                        changed = true;
                    }
                }

                if (changed) subscriptionPlanRepository.save(plan);
            });
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
        User admin = userRepository.findByEmail(adminEmail).orElseGet(() -> {
            User u = User.builder()
                    .fullName("Sərxan Babayev")
                    .email(adminEmail)
                    .password(passwordEncoder.encode("salam123"))
                    .role(Role.ADMIN)
                    .enabled(true)
                    .build();
            User saved = userRepository.save(u);
            assignUnlimitedPlanToUser(saved);
            log.info("Admin istifadəçisi yaradıldı: {}", adminEmail);
            return saved;
        });
        if (admin.getPhoneNumber() == null) {
            admin.setPhoneNumber("+994501234567");
            userRepository.save(admin);
        }
    }

    private void seedTeacher() {
        String email = "serxan.babayev.06@gmail.com";
        User teacher = userRepository.findByEmail(email).orElseGet(() -> {
            User u = User.builder()
                    .fullName("Sərxan Babayev")
                    .email(email)
                    .password(passwordEncoder.encode("salam123"))
                    .role(Role.TEACHER)
                    .enabled(true)
                    .build();
            User saved = userRepository.save(u);
            assignUnlimitedPlanToUser(saved);
            log.info("Müəllim hesabı yaradıldı: {}", email);
            return saved;
        });
        if (teacher.getPhoneNumber() == null) {
            teacher.setPhoneNumber("+994557654321");
            userRepository.save(teacher);
        }
    }

    private void seedStudent() {
        String email = "serxanbabayev614@gmail.com";
        User student = userRepository.findByEmail(email).orElseGet(() -> {
            User u = User.builder()
                    .fullName("Sərxan Babayev")
                    .email(email)
                    .password(passwordEncoder.encode("salam123"))
                    .role(Role.STUDENT)
                    .enabled(true)
                    .build();
            log.info("Şagird hesabı yaradıldı: {}", email);
            return userRepository.save(u);
        });
        if (student.getPhoneNumber() == null) {
            student.setPhoneNumber("+994709876543");
            userRepository.save(student);
        }
    }

    private void seedSubjects() {
        if (subjectRepository.count() == 0) {
            for (String name : DEFAULT_SUBJECTS) {
                String[] meta = SUBJECT_METADATA.get(name);
                ExamSubject subject = ExamSubject.builder()
                        .name(name)
                        .isDefault(true)
                        .color(meta != null ? meta[0] : null)
                        .iconEmoji(meta != null ? meta[1] : null)
                        .build();
                subjectRepository.save(subject);
            }
            log.info("{} default fənn əlavə edildi", DEFAULT_SUBJECTS.size());
        } else {
            // Update metadata for existing subjects that don't have it yet
            for (Map.Entry<String, String[]> entry : SUBJECT_METADATA.entrySet()) {
                subjectRepository.findByName(entry.getKey()).ifPresent(subject -> {
                    boolean changed = false;
                    if (subject.getColor() == null) {
                        subject.setColor(entry.getValue()[0]);
                        changed = true;
                    }
                    if (subject.getIconEmoji() == null) {
                        subject.setIconEmoji(entry.getValue()[1]);
                        changed = true;
                    }
                    if (changed) {
                        subjectRepository.save(subject);
                    }
                });
            }
        }
    }

    /**
     * Migrates old single-subject data to the new exam_subject_list table.
     * Step 1: Normalize old enum values (RIYAZIYYAT → Riyaziyyat) in exams.subject column.
     * Step 2: Copy non-null subject values into exam_subject_list where not already present.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public void migrateExamSubjectsToList() {
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

    private void seedSubjectTopics() {
        Map<String, List<String>> topicsBySubject = Map.ofEntries(
            Map.entry("Riyaziyyat", List.of(
                "Cəbr", "Həndəsə", "Triqonometriya", "Logarifm və üstlü ifadələr",
                "Funksiyalar", "Bərabərsizliklər", "Ədədlər nəzəriyyəsi",
                "Ehtimal və statistika", "Ardıcıllıqlar", "Kombinatorika",
                "Diferensial hesab", "İntegral", "Kompleks ədədlər", "Matrislər", "Vektorlar"
            )),
            Map.entry("Fizika", List.of(
                "Mexanika", "Kinematika", "Dinamika", "Termodinamika",
                "Elektrostatika", "Elektrik cərəyanı", "Magnetizm",
                "Elektromaqnit induksiya", "Optika", "Dalğa hərəkəti",
                "Kvant fizikası", "Nüvə fizikası", "Qravitasiya", "Hidrostatika", "Xüsusi nisbilik"
            )),
            Map.entry("Kimya", List.of(
                "Atom quruluşu", "Dövri sistem", "Kimyəvi rabitə",
                "Oksidləşmə-reduksiya", "Turşular və əsaslar", "Duzlar",
                "Üzvi kimya əsasları", "Alkanlar və alkenlar", "Spirtlər və aldehidlər",
                "Karbohidratlar", "Zülallar", "Polimerlər",
                "Elektroliz", "Kimyəvi tarazlıq", "Həll olma"
            )),
            Map.entry("Biologiya", List.of(
                "Hüceyrə quruluşu", "Genetika", "Təkamül nəzəriyyəsi",
                "Ekologiya", "Bitkilər sistematikası", "Heyvanlar sistematikası",
                "Mikroorqanizmlər", "İnsan anatomiyası", "Fiziologiya",
                "Fotosintez", "Tənəffüs", "Sinir sistemi", "Çoxalma", "Biotexnologiya", "Qidalanma"
            )),
            Map.entry("Azərbaycan dili", List.of(
                "Fonetika", "Leksika və frazeologiya", "Morfologiya",
                "Sintaksis", "Durğu işarələri", "İsim", "Sifət", "Feil",
                "Zərf", "Əvəzlik", "Say", "Bağlayıcı və modal sözlər",
                "Orfoqrafiya", "Söz yaradıcılığı", "Mətn və üslub"
            )),
            Map.entry("İngilis dili", List.of(
                "Present Tenses", "Past Tenses", "Future Tenses",
                "Modal Verbs", "Passive Voice", "Conditionals",
                "Reported Speech", "Articles", "Prepositions",
                "Phrasal Verbs", "Vocabulary", "Reading Comprehension",
                "Writing Skills", "Gerund & Infinitive", "Relative Clauses"
            )),
            Map.entry("Tarix", List.of(
                "Qədim dünya tarixi", "Orta əsrlər", "Yeni dövr",
                "Müasir dövr", "Azərbaycan tarixi", "Birinci Dünya müharibəsi",
                "İkinci Dünya müharibəsi", "İnqilablar dövrü",
                "Osmanlı imperiyası", "Sovet dövrü",
                "Müstəqillik dövrü", "Mədəniyyət tarixi", "Arxeologiya", "Antik sivilizasiyalar", "Müstəmləkəçilik"
            )),
            Map.entry("Coğrafiya", List.of(
                "Fiziki coğrafiya", "İqtisadi coğrafiya", "İqlim",
                "Relyef", "Hidrologiya", "Torpaqlar",
                "Əhali coğrafiyası", "Azərbaycanın coğrafiyası",
                "Materiklər", "Okeanlar", "Xəritə oxuma",
                "Ekologiya", "Urbanizasiya", "Kənd təsərrüfatı", "Ölkəşünaslıq"
            )),
            Map.entry("Informatika", List.of(
                "Alqoritmlər", "Proqramlaşdırma əsasları", "Say sistemləri",
                "Məntiqi əməliyyatlar", "Verilənlər bazası", "Kompüter şəbəkəsi",
                "Əməliyyat sistemləri", "Excel və cədvəllər",
                "İnformasiya nəzəriyyəsi", "Web texnologiyalar",
                "Süni intellekt", "Kiber təhlükəsizlik",
                "Kompüter arxitekturası", "Ofis proqramları", "Fayl sistemləri"
            )),
            Map.entry("Məntiq", List.of(
                "Deduksiya", "İnduksiya", "Anologiya",
                "Sillogizm", "Bulmacalar", "Rəqəm ardıcıllıqları",
                "Şəkil ardıcıllığı", "Şərt məntiqi", "İfadə məntiqi",
                "Klassifikasiya", "Müqayisə", "Ziddiyyət",
                "Ehtimal məntiqi", "Qruplaşdırma", "İspat üsulları"
            )),
            Map.entry("Ədəbiyyat", List.of(
                "Azərbaycan klassik ədəbiyyatı", "Müasir Azərbaycan ədəbiyyatı",
                "Dünya ədəbiyyatı", "Şeir janrı", "Nəsr janrı",
                "Dram janrı", "Şifahi xalq ədəbiyyatı", "Ədəbi nəzəriyyə",
                "Maarifçilik dövrü", "Romantizm", "Realizm",
                "Ədəbi şəxsiyyətlər", "Epik janrlar", "Lirik janrlar", "Bədii dil vasitələri"
            )),
            Map.entry("Rus dili", List.of(
                "Fonetika", "Leksika", "İsim (Существительное)",
                "Sifət (Прилагательное)", "Feil (Глагол)", "Zaman formaları",
                "Hal şəkilçiləri", "Söz yaradıcılığı", "Sintaksis",
                "Durğu işarələri", "Orfoqrafiya", "Bağlayıcılar",
                "Saylar", "Feilin görünüşü", "Cümlə üzvləri"
            )),
            Map.entry("Alman dili", List.of(
                "Artikellər", "İsimlər", "Feillər",
                "Zaman formaları", "Hal sistemi (Kasus)", "Modal feillər",
                "Sifətlər", "Zərflər", "Bağlayıcılar",
                "Passiv quruluş", "Şərt cümləsi", "Prepoziçiyalar",
                "Söz sırası", "Söz ehtiyatı", "Frazeologiya"
            )),
            Map.entry("Fransız dili", List.of(
                "Artikellər", "İsimlər", "Feillər",
                "Présent", "Passé composé", "Imparfait",
                "Futur", "Conditionnel", "Subjonctif",
                "Sifətlər", "Zərflər", "Prepoziçiyalar",
                "Söz ehtiyatı", "Cümlə quruluşu", "Bağlayıcılar"
            )),
            Map.entry("Həyat bilgisi", List.of(
                "Ailə və cəmiyyət", "Demokratiya", "Hüquq əsasları",
                "Ekologiya", "Sağlam həyat tərzi", "Əmək hüququ",
                "İqtisadiyyat əsasları", "Media savadlılığı",
                "Vətəndaşlıq", "Etika", "Mədəniyyət müxtəlifliyi",
                "Dövlət quruluşu", "Beynəlxalq münasibətlər", "Fərdi inkişaf", "Maliyyə savadlılığı"
            ))
        );

        int subjectsUpdated = 0;
        for (Map.Entry<String, List<String>> entry : topicsBySubject.entrySet()) {
            subjectRepository.findByName(entry.getKey()).ifPresent(subject -> {
                if (subjectTopicRepository.countBySubjectId(subject.getId()) == 0) {
                    int order = 0;
                    for (String topicName : entry.getValue()) {
                        SubjectTopic topic = SubjectTopic.builder()
                                .name(topicName)
                                .gradeLevel(null)
                                .orderIndex(order++)
                                .subject(subject)
                                .build();
                        subjectTopicRepository.save(topic);
                    }
                }
            });
            subjectsUpdated++;
        }
        log.info("Fənn mövzuları yoxlanıldı ({} fənn)", subjectsUpdated);
    }

    private void seedOlimpiyadaTemplate() {
        // Point groups JSON for each section
        String azDiliPointGroups = "[{\"from\":1,\"to\":15,\"points\":1.0},{\"from\":16,\"to\":20,\"points\":1.5}]";
        String riyazPointGroups  = "[{\"from\":1,\"to\":35,\"points\":1.0},{\"from\":36,\"to\":40,\"points\":1.5}]";
        String olimpFormula      = "s - w/4.0";

        Template template = templateRepository.findByTitle("Olimpiyada").orElse(null);

        if (template != null) {
            // Patch stale fields if template already exists
            boolean changed = false;
            if (template.getTemplateType() != TemplateType.OLIMPIYADA) {
                template.setTemplateType(TemplateType.OLIMPIYADA);
                changed = true;
            }
            if (changed) templateRepository.save(template);

            // Patch sections: formula + pointGroups (use JPQL to avoid lazy collection access)
            final Template finalTemplate = template;
            new TransactionTemplate(transactionManager).execute(status -> {
                List<TemplateSection> sections = entityManager.createQuery(
                        "SELECT s FROM TemplateSection s JOIN s.subtitle sub WHERE sub.template = :t",
                        TemplateSection.class)
                        .setParameter("t", finalTemplate)
                        .getResultList();
                for (TemplateSection sec : sections) {
                    boolean secChanged = false;
                    if (!olimpFormula.equals(sec.getFormula())) {
                        sec.setFormula(olimpFormula);
                        secChanged = true;
                    }
                    String pg = "Azərbaycan dili".equals(sec.getSubjectName()) ? azDiliPointGroups : riyazPointGroups;
                    if (!pg.equals(sec.getPointGroups())) {
                        sec.setPointGroups(pg);
                        secChanged = true;
                    }
                    if (secChanged) sectionRepository.save(sec);
                }
                return null;
            });
            log.debug("Olimpiyada şablonu yoxlanıldı/yeniləndi");
            return;
        }

        // Create new template
        template = templateRepository.save(
                Template.builder()
                        .title("Olimpiyada")
                        .templateType(TemplateType.OLIMPIYADA)
                        .build());

        TemplateSubtitle subtitle = subtitleRepository.save(
                TemplateSubtitle.builder()
                        .template(template)
                        .subtitle("Respublika Fənn Olimpiyadası")
                        .orderIndex(0)
                        .build());

        // ── Azərbaycan dili: 20 MCQ ──────────────────────────────────────────
        // Sual 1-15: 1 bal, Sual 16-20: 1.5 bal  |  Max = 22.5  |  4 yanlış → -1 bal
        TemplateSection azDili = sectionRepository.save(
                TemplateSection.builder()
                        .subtitle(subtitle)
                        .subjectName("Azərbaycan dili")
                        .questionCount(20)
                        .formula(olimpFormula)
                        .pointGroups(azDiliPointGroups)
                        .orderIndex(0)
                        .build());
        addTypeCount(azDili, QuestionType.MCQ, 20, 0);

        // ── Riyaziyyat: 40 MCQ ───────────────────────────────────────────────
        // Sual 1-35: 1 bal, Sual 36-40: 1.5 bal  |  Max = 42.5  |  4 yanlış → -1 bal
        TemplateSection riyaz = sectionRepository.save(
                TemplateSection.builder()
                        .subtitle(subtitle)
                        .subjectName("Riyaziyyat")
                        .questionCount(40)
                        .formula(olimpFormula)
                        .pointGroups(riyazPointGroups)
                        .orderIndex(1)
                        .build());
        addTypeCount(riyaz, QuestionType.MCQ, 40, 0);

        log.info("Olimpiyada şablonu uğurla yaradıldı");
    }

    private void seedDimTemplate() {
        Template template = templateRepository.findByTitle("DİM Buraxılış").orElse(null);

        if (template != null) {
            final Template finalTemplate = template;
            new TransactionTemplate(transactionManager).execute(status -> {
                List<TemplateSection> sections = entityManager.createQuery(
                        "SELECT s FROM TemplateSection s JOIN s.subtitle sub WHERE sub.template = :t",
                        TemplateSection.class)
                        .setParameter("t", finalTemplate)
                        .getResultList();
                for (TemplateSection sec : sections) {
                    // Patch maxScore
                    if (sec.getMaxScore() == null) {
                        sec.setMaxScore(100.0);
                        sectionRepository.save(sec);
                    }
                    // Patch İngilis dili: add TEXT/LISTENING passage type counts if missing
                    if ("İngilis dili".equals(sec.getSubjectName())) {
                        boolean hasText = sec.getTypeCounts().stream().anyMatch(tc -> "TEXT".equals(tc.getPassageType()));
                        boolean hasListening = sec.getTypeCounts().stream().anyMatch(tc -> "LISTENING".equals(tc.getPassageType()));
                        int nextOrder = sec.getTypeCounts().stream().mapToInt(TemplateSectionTypeCount::getOrderIndex).max().orElse(-1) + 1;
                        if (!hasText) addTypeCount(sec, QuestionType.MCQ, 5, nextOrder++, "TEXT");
                        if (!hasListening) addTypeCount(sec, QuestionType.MCQ, 3, nextOrder, "LISTENING");
                    }
                }
                return null;
            });
            log.debug("DİM Buraxılış şablonu yoxlanıldı/yeniləndi");
            return;
        }

        template = templateRepository.save(Template.builder().title("DİM Buraxılış").build());

        TemplateSubtitle subtitle = subtitleRepository.save(TemplateSubtitle.builder()
                .template(template)
                .subtitle("11-ci sinif")
                .orderIndex(0)
                .build());

        // İngilis dili: 15 adi MCQ + Mətn (5 MCQ) + Dinləmə (3 MCQ) + OPEN_MANUAL=7  |  Max = 100 bal
        TemplateSection ingilis = TemplateSection.builder()
                .subtitle(subtitle)
                .subjectName("İngilis dili")
                .questionCount(30)
                .formula("(100.0/37.0)*(2*l+a)")
                .maxScore(100.0)
                .orderIndex(0)
                .build();
        ingilis = sectionRepository.save(ingilis);
        addTypeCount(ingilis, QuestionType.MCQ, 15, 0, null);
        addTypeCount(ingilis, QuestionType.MCQ, 5, 1, "TEXT");
        addTypeCount(ingilis, QuestionType.MCQ, 3, 2, "LISTENING");
        addTypeCount(ingilis, QuestionType.OPEN_MANUAL, 7, 3, null);

        // Azərbaycan dili: MCQ=20, OPEN_MANUAL=10  |  Max = 100 bal
        TemplateSection azDili = TemplateSection.builder()
                .subtitle(subtitle)
                .subjectName("Azərbaycan dili")
                .questionCount(30)
                .formula("(5.0/2.0)*(2*l+a)")
                .maxScore(100.0)
                .orderIndex(1)
                .build();
        azDili = sectionRepository.save(azDili);
        addTypeCount(azDili, QuestionType.MCQ, 20, 0);
        addTypeCount(azDili, QuestionType.OPEN_MANUAL, 10, 1);

        // Riyaziyyat: MCQ=13, OPEN_AUTO=5, OPEN_MANUAL=7  |  Max = 100 bal
        TemplateSection riyaziyyat = TemplateSection.builder()
                .subtitle(subtitle)
                .subjectName("Riyaziyyat")
                .questionCount(25)
                .formula("(25.0/8.0)*(2*l+f+a)")
                .maxScore(100.0)
                .orderIndex(2)
                .build();
        riyaziyyat = sectionRepository.save(riyaziyyat);
        addTypeCount(riyaziyyat, QuestionType.MCQ, 13, 0);
        addTypeCount(riyaziyyat, QuestionType.OPEN_AUTO, 5, 1);
        addTypeCount(riyaziyyat, QuestionType.OPEN_MANUAL, 7, 2);

        log.info("DİM Buraxılış şablonu uğurla yaradıldı");
    }

    private void seedBanners() {
        // ── Müəllim bannerləri ──
        upsertBanner(
            "AI ilə sual yaratma — 7 fərqli format",
            "Mövzu və çətinlik dərəcəsini seçin, qalanını AI etsin. Riyazi simvollar dəstəyi ilə hər fənn üçün hazırdır.",
            "/planlar", "Planları kəşf et",
            BannerPosition.HERO, "from-indigo-600 to-purple-600", 0,
            BannerAudience.TEACHER
        );
        upsertBanner(
            "Sual bazası — bir dəfə yaz, hər dəfə istifadə et",
            "Suallarınızı fənlər üzrə saxlayın, istənilən imtahana bir kliklə əlavə edin.",
            "/planlar", "Planlara bax",
            BannerPosition.INLINE, "from-emerald-500 to-teal-600", 0,
            BannerAudience.TEACHER
        );
        upsertBanner(
            "Avtomatik qiymətləndirmə və ətraflı statistika",
            "Hər sual üzrə doğru, yanlış, boş cavab faizlərini qrafiklərlə izləyin. Bütün planlarda mövcuddur.",
            "/planlar", "Planlara bax",
            BannerPosition.BOTTOM, "from-orange-500 to-pink-500", 0,
            BannerAudience.TEACHER
        );

        // ── Şagird bannerləri ──
        upsertBanner(
            "Nəticələrinizi izləyin, zəif tərəflərinizi tapın",
            "Hər imtahandan sonra ətraflı analiz: doğru, yanlış, boş cavablar fənn üzrə göstərilir.",
            "/imtahanlarim", "Nəticələrə bax",
            BannerPosition.HERO, "from-blue-600 to-cyan-500", 0,
            BannerAudience.STUDENT
        );
        upsertBanner(
            "Yeni imtahanlar sizin üçün hazırdır",
            "Müəllimləriniz tərəfindən hazırlanmış onlarca imtahana baxın və biliklərinizi yoxlayın.",
            "/imtahanlar", "İmtahanlara bax",
            BannerPosition.INLINE, "from-emerald-500 to-teal-600", 0,
            BannerAudience.STUDENT
        );

        // ── Loginsiz ziyarətçi bannerləri ──
        upsertBanner(
            "Pulsuz başlayın — heç bir ödəniş məlumatı lazım deyil",
            "30 saniyəyə qeydiyyatdan keçin, müəllim planını aktivləşdirin və ilk imtahanınızı yaradın.",
            "/qeydiyyat", "İndi qeydiyyatdan keç",
            BannerPosition.HERO, "from-indigo-600 to-purple-600", 0,
            BannerAudience.GUEST
        );
        upsertBanner(
            "Testup.az — müəllim və şagirdlər üçün ağıllı imtahan platforması",
            "AI dəstəyi, avtomatik qiymətləndirmə, ətraflı statistika — hamısı bir yerdə.",
            "/haqqimizda", "Ətraflı öyrən",
            BannerPosition.BOTTOM, "from-gray-800 to-gray-900", 0,
            BannerAudience.GUEST
        );

        log.info("Bannerlər yoxlanıldı/yeniləndi");
    }

    private void upsertBanner(String title, String subtitle, String linkUrl, String linkText,
                               BannerPosition position, String bgGradient, int orderIndex,
                               BannerAudience targetAudience) {
        bannerRepository.findAll().stream()
            .filter(b -> b.getPosition() == position
                    && b.getOrderIndex() == orderIndex
                    && b.getTargetAudience() == targetAudience)
            .findFirst()
            .ifPresentOrElse(existing -> {
                existing.setTitle(title);
                existing.setSubtitle(subtitle);
                existing.setLinkUrl(linkUrl);
                existing.setLinkText(linkText);
                existing.setBgGradient(bgGradient);
                existing.setActive(true);
                bannerRepository.save(existing);
            }, () -> bannerRepository.save(Banner.builder()
                .title(title)
                .subtitle(subtitle)
                .linkUrl(linkUrl)
                .linkText(linkText)
                .isActive(true)
                .position(position)
                .bgGradient(bgGradient)
                .orderIndex(orderIndex)
                .targetAudience(targetAudience)
                .build()));
    }

    @Transactional
    public void seedSampleIngilisDiliExam() {
        String teacherEmail = "serxan.babayev.06@gmail.com";
        User teacher = userRepository.findByEmail(teacherEmail).orElse(null);
        if (teacher == null) { log.warn("Müəllim tapılmadı: {}", teacherEmail); return; }

        String examTitle = "İngilis dili — Mətn & Dinləmə Nümunəsi";
        boolean alreadyExists = examRepository.findByTeacherAndDeletedFalse(teacher).stream()
                .anyMatch(e -> examTitle.equals(e.getTitle()));
        if (alreadyExists) { log.debug("İngilis dili nümunə imtahanı artıq mövcuddur"); return; }

        Template template = templateRepository.findByTitle("DİM Buraxılış").orElse(null);
        TemplateSection section = template == null ? null :
                entityManager.createQuery(
                        "SELECT s FROM TemplateSection s WHERE s.subtitle.template.id = :tid AND s.subjectName = :name",
                        TemplateSection.class)
                        .setParameter("tid", template.getId())
                        .setParameter("name", "İngilis dili")
                        .getResultList().stream().findFirst().orElse(null);

        Exam exam = examRepository.save(Exam.builder()
                .title(examTitle)
                .description("DİM formatında İngilis dili imtahanı: adi testlər, oxuma mətni (TEXT) və dinləmə (LISTENING) tapşırıqları daxildir.")
                .subjects(new java.util.ArrayList<>(List.of("İngilis dili")))
                .visibility(ExamVisibility.PUBLIC)
                .examType(ExamType.TEMPLATE)
                .status(ExamStatus.PUBLISHED)
                .shareLink(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .durationMinutes(90)
                .teacher(teacher)
                .template(template)
                .templateSection(section)
                .build());

        // ── 15 standalone MCQ ──────────────────────────────────────────────
        buildMcq(exam, 0,  "Which sentence is grammatically correct?",
                new String[]{"She don't like coffee.", "She doesn't likes coffee.", "She doesn't like coffee.", "She not like coffee."}, 2);
        buildMcq(exam, 1,  "Choose the correct form: 'I ___ to school every day.'",
                new String[]{"goes", "go", "going", "gone"}, 1);
        buildMcq(exam, 2,  "What is the past tense of 'go'?",
                new String[]{"goed", "gone", "went", "go"}, 2);
        buildMcq(exam, 3,  "Which word is a synonym for 'happy'?",
                new String[]{"sad", "angry", "joyful", "tired"}, 2);
        buildMcq(exam, 4,  "Choose the correct preposition: 'She is good ___ singing.'",
                new String[]{"in", "on", "at", "for"}, 2);
        buildMcq(exam, 5,  "Which sentence uses the Present Perfect correctly?",
                new String[]{"I have went there.", "I have go there.", "I have been there.", "I have been went there."}, 2);
        buildMcq(exam, 6,  "'Despite the rain, ...' — which ending is correct?",
                new String[]{"but we went out.", "however we went out.", "we went out.", "we didn't went out."}, 2);
        buildMcq(exam, 7,  "Choose the correct article: '___ Eiffel Tower is in Paris.'",
                new String[]{"A", "An", "The", "—"}, 2);
        buildMcq(exam, 8,  "What does 'enormous' mean?",
                new String[]{"tiny", "average", "very large", "fast"}, 2);
        buildMcq(exam, 9,  "'If I ___ rich, I would travel the world.' Choose the correct form.",
                new String[]{"am", "was", "were", "be"}, 2);
        buildMcq(exam, 10, "Which is the correct passive form of 'They built this bridge in 1990'?",
                new String[]{"This bridge built in 1990.", "This bridge was built in 1990.", "This bridge is built in 1990.", "This bridge were built in 1990."}, 1);
        buildMcq(exam, 11, "Choose the correct relative pronoun: 'The book ___ I read was interesting.'",
                new String[]{"who", "whose", "which", "whom"}, 2);
        buildMcq(exam, 12, "Which sentence contains a gerund?",
                new String[]{"She wants to swim.", "Swimming is her hobby.", "She swims every day.", "She will swim tomorrow."}, 1);
        buildMcq(exam, 13, "'Neither John nor his friends ___ coming.' Fill in the blank.",
                new String[]{"is", "are", "was", "were"}, 1);
        buildMcq(exam, 14, "What is the correct comparative form of 'good'?",
                new String[]{"gooder", "more good", "better", "more better"}, 2);

        // ── TEXT passage (orderIndex 15) — 5 MCQ ──────────────────────────
        Passage textPassage = passageRepository.save(Passage.builder()
                .exam(exam)
                .passageType(PassageType.TEXT)
                .title("The Amazon Rainforest")
                .textContent(
                    "The Amazon rainforest, often called the 'lungs of the Earth', covers over 5.5 million square " +
                    "kilometres across nine countries in South America. It produces about 20% of the world's oxygen " +
                    "and is home to an estimated 10% of all species on Earth.\n\n" +
                    "Deforestation remains the biggest threat to the Amazon. Every year, thousands of square " +
                    "kilometres of forest are cleared for agriculture, logging, and urban expansion. Scientists warn " +
                    "that if deforestation continues at the current rate, the Amazon could reach a 'tipping point' " +
                    "beyond which it cannot recover.\n\n" +
                    "Conservation efforts include protected reserves, international agreements, and satellite " +
                    "monitoring systems that track illegal logging in real time. Local communities play a crucial " +
                    "role in protecting the forest, as their way of life depends on its survival."
                )
                .orderIndex(15)
                .subjectGroup(null)
                .build());
        exam.getPassages().add(textPassage);

        buildPassageMcq(exam, textPassage, 0, "What percentage of the world's oxygen does the Amazon produce?",
                new String[]{"10%", "5%", "30%", "20%"}, 3);
        buildPassageMcq(exam, textPassage, 1, "What is described as the biggest threat to the Amazon?",
                new String[]{"Climate change", "Tourism", "Deforestation", "Flooding"}, 2);
        buildPassageMcq(exam, textPassage, 2, "What does the phrase 'tipping point' mean in the context of the passage?",
                new String[]{"The highest point of a mountain", "A point of no recovery", "A scientific measurement", "A logging technique"}, 1);
        buildPassageMcq(exam, textPassage, 3, "How many countries does the Amazon rainforest span?",
                new String[]{"Five", "Seven", "Nine", "Twelve"}, 2);
        buildPassageMcq(exam, textPassage, 4, "According to the passage, what role do local communities play?",
                new String[]{"They expand agriculture", "They monitor satellites", "They protect the forest", "They conduct deforestation"}, 2);

        // ── LISTENING passage (orderIndex 16) — 3 MCQ ─────────────────────
        // (audio content is empty for seeder — teacher fills it in the editor)
        Passage listeningPassage = passageRepository.save(Passage.builder()
                .exam(exam)
                .passageType(PassageType.LISTENING)
                .title("A Job Interview")
                .listenLimit(2)
                .orderIndex(16)
                .subjectGroup(null)
                .build());
        exam.getPassages().add(listeningPassage);

        buildPassageMcq(exam, listeningPassage, 0, "What position is the candidate applying for?",
                new String[]{"Software engineer", "Marketing manager", "Graphic designer", "Sales representative"}, 1);
        buildPassageMcq(exam, listeningPassage, 1, "How many years of experience does the candidate have?",
                new String[]{"Two", "Three", "Four", "Five"}, 1);
        buildPassageMcq(exam, listeningPassage, 2, "What does the interviewer say about the salary?",
                new String[]{"It is negotiable", "It is fixed", "It is below average", "It will be discussed next week"}, 0);

        // ── 7 OPEN_MANUAL ──────────────────────────────────────────────────
        buildOpenManual(exam, 17, "Describe your favourite book in 3–4 sentences.");
        buildOpenManual(exam, 18, "Write a short paragraph about the importance of learning English.");
        buildOpenManual(exam, 19, "Rewrite the sentence in passive voice: 'The chef cooked the meal.'");
        buildOpenManual(exam, 20, "Use 'although' to combine these two sentences: 'It was raining. We went for a walk.'");
        buildOpenManual(exam, 21, "Explain the difference between 'since' and 'for' with examples.");
        buildOpenManual(exam, 22, "Write three sentences using the modal verbs: must, should, might.");
        buildOpenManual(exam, 23, "Translate into English: 'Onlar dünən axşam kinoya getdilər.'");

        examRepository.save(exam);
        log.info("İngilis dili nümunə imtahanı yaradıldı: \"{}\"", examTitle);
    }

    private void buildPassageMcq(Exam exam, Passage passage, int subIdx, String content, String[] opts, int correctIdx) {
        Question q = Question.builder()
                .exam(exam)
                .passage(passage)
                .content(content)
                .questionType(QuestionType.MCQ)
                .points(1.0)
                .orderIndex(subIdx)
                .build();
        for (int i = 0; i < opts.length; i++) {
            q.getOptions().add(Option.builder()
                    .question(q)
                    .content(opts[i])
                    .isCorrect(i == correctIdx)
                    .orderIndex(i)
                    .build());
        }
        exam.getQuestions().add(q);
    }

    private void addTypeCount(TemplateSection section, QuestionType type, int count, int order) {
        addTypeCount(section, type, count, order, null);
    }

    private void addTypeCount(TemplateSection section, QuestionType type, int count, int order, String passageType) {
        TemplateSectionTypeCount tc = TemplateSectionTypeCount.builder()
                .section(section)
                .questionType(type)
                .count(count)
                .orderIndex(order)
                .passageType(passageType)
                .build();
        section.getTypeCounts().add(tc);
        sectionRepository.save(section);
    }

    @Transactional
    public void seedSampleRiyaziyyatExam() {
        String teacherEmail = "serxan.babayev.06@gmail.com";
        User teacher = userRepository.findByEmail(teacherEmail).orElse(null);
        if (teacher == null) {
            log.warn("Müəllim tapılmadı: {}", teacherEmail);
            return;
        }

        String examTitle = "Riyaziyyat — DİM Buraxılış Nümunəsi";
        boolean alreadyExists = examRepository.findByTeacherAndDeletedFalse(teacher).stream()
                .anyMatch(e -> examTitle.equals(e.getTitle()));
        if (alreadyExists) {
            log.debug("Nümunə imtahan artıq mövcuddur, keçilir");
            return;
        }

        Template template = templateRepository.findByTitle("DİM Buraxılış").orElse(null);
        TemplateSection section = template == null ? null :
                entityManager.createQuery(
                        "SELECT s FROM TemplateSection s WHERE s.subtitle.template.id = :tid AND s.subjectName = :name",
                        TemplateSection.class)
                        .setParameter("tid", template.getId())
                        .setParameter("name", "Riyaziyyat")
                        .getResultList()
                        .stream().findFirst().orElse(null);

        Exam exam = Exam.builder()
                .title(examTitle)
                .description("Riyaziyyat fənni üzrə DİM buraxılış imtahanı formatında nümunə test. "
                        + "13 test, 5 açıq (avtomatik), 7 açıq (müəllim) sualdan ibarətdir.")
                .subjects(new java.util.ArrayList<>(List.of("Riyaziyyat")))
                .visibility(ExamVisibility.PUBLIC)
                .examType(ExamType.TEMPLATE)
                .status(ExamStatus.PUBLISHED)
                .shareLink(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .durationMinutes(90)
                .teacher(teacher)
                .template(template)
                .templateSection(section)
                .build();

        // Persist exam first so questions can reference its id
        exam = examRepository.save(exam);

        // ── 13 MCQ ──────────────────────────────────────────────────────────────
        buildMcq(exam, 0,  "2x + 5 = 11 bərabərliyini həll edin.",
                new String[]{"x = 2", "x = 3", "x = 4", "x = 5"}, 1);
        buildMcq(exam, 1,  "3² + 4² ifadəsinin qiyməti neçədir?",
                new String[]{"35", "49", "25", "12"}, 2);
        buildMcq(exam, 2,  "log₂(8) = ?",
                new String[]{"2", "4", "8", "3"}, 3);
        buildMcq(exam, 3,  "x² − 5x + 6 = 0 tənliyinin köklərinin cəmi neçədir?",
                new String[]{"6", "3", "2", "5"}, 3);
        buildMcq(exam, 4,  "Radiusu 5 olan dairənin sahəsi neçədir?",
                new String[]{"10π", "5π", "50π", "25π"}, 3);
        buildMcq(exam, 5,  "f(x) = x² − 3x + 2 funksiyası üçün f(4) = ?",
                new String[]{"4", "8", "10", "6"}, 3);
        buildMcq(exam, 6,  "Tərəfləri 3, 4 və 5 olan üçbucaq hansı növdür?",
                new String[]{"İtiburcaqlı", "Kəskin", "Bərabərtərəfli", "Düzburcaqlı"}, 3);
        buildMcq(exam, 7,  "sin(30°) = ?",
                new String[]{"√3/2", "√2/2", "1", "1/2"}, 3);
        buildMcq(exam, 8,  "12 rəqəmi 48-in neçə faizini təşkil edir?",
                new String[]{"20%", "30%", "4%", "25%"}, 3);
        buildMcq(exam, 9,  "Həndəsi silsilə: 2, 6, 18, ... — 5-ci həddini tapın.",
                new String[]{"54", "162", "486", "324"}, 1);
        buildMcq(exam, 10, "2^x = 32 tənliyini həll edin.",
                new String[]{"4", "6", "3", "5"}, 3);
        buildMcq(exam, 11, "Arifmetik silsilə: 3, 7, 11, ... — 10-cu həddini tapın.",
                new String[]{"39", "43", "47", "35"}, 0);
        buildMcq(exam, 12, "(x − 3)(x + 5) = 0 tənliyinin kökləri hansılardır?",
                new String[]{"x = 3 və x = 5", "x = −3 və x = 5", "x = 3 və x = −5", "x = −3 və x = −5"}, 2);

        // ── 5 OPEN_AUTO ─────────────────────────────────────────────────────────
        buildOpenAuto(exam, 13, "3x − 7 = 14 tənliyini həll edin. (yalnız rəqəm yazın)", "7");
        buildOpenAuto(exam, 14, "5! (faktorial) hesablayın.", "120");
        buildOpenAuto(exam, 15, "Tərəfi 6 olan kvadratın sahəsi neçədir?", "36");
        buildOpenAuto(exam, 16, "ƏBOB(24, 36) = ?", "12");
        buildOpenAuto(exam, 17, "(1, 2) və (3, 6) nöqtələrindən keçən düz xəttin meyil əmsalını tapın.", "2");

        // ── 7 OPEN_MANUAL ───────────────────────────────────────────────────────
        buildOpenManual(exam, 18, "Üçbucaqda daxili bucaqların cəminin 180° olduğunu isbat edin.");
        buildOpenManual(exam, 19, "x² − 7x + 12 = 0 tənliyini həll edin. Həlli addım-addım göstərin.");
        buildOpenManual(exam, 20, "f(x) = 3x² − 2x + 1 funksiyasının törəməsini tapın.");
        buildOpenManual(exam, 21, "(x² − 4) / (x − 2) ifadəsini sadələşdirin (x ≠ 2).");
        buildOpenManual(exam, 22, "Qatarın sürəti 60 km/saat, hərəkət müddəti 2,5 saatdır. Qatarın qət etdiyi məsafəni tapın.");
        buildOpenManual(exam, 23, "√2 ədədinin irrational olduğunu isbat edin.");
        buildOpenManual(exam, 24, "Düzburcaqlı üçbucaqda katetlər 5 və 12-dir. Hipotenuzanı tapın.");

        // Single save — cascade persists all questions and their options
        examRepository.save(exam);
        log.info("Nümunə Riyaziyyat imtahanı yaradıldı: \"{}\"", examTitle);
    }

    @Transactional
    public void seedSampleMultiSubjectExam() {
        String teacherEmail = "serxan.babayev.06@gmail.com";
        User teacher = userRepository.findByEmail(teacherEmail).orElse(null);
        if (teacher == null) {
            log.warn("Müəllim tapılmadı: {}", teacherEmail);
            return;
        }

        String examTitle = "Riyaziyyat + İngilis dili — DİM Buraxılış Nümunəsi";
        boolean alreadyExists = examRepository.findByTeacherAndDeletedFalse(teacher).stream()
                .anyMatch(e -> examTitle.equals(e.getTitle()));
        if (alreadyExists) {
            log.debug("Çoxfənli nümunə imtahan artıq mövcuddur, keçilir");
            return;
        }

        Template template = templateRepository.findByTitle("DİM Buraxılış").orElse(null);
        if (template == null) {
            log.warn("DİM Buraxılış şablonu tapılmadı");
            return;
        }

        TemplateSection riyazSection = entityManager.createQuery(
                        "SELECT s FROM TemplateSection s WHERE s.subtitle.template.id = :tid AND s.subjectName = :name",
                        TemplateSection.class)
                .setParameter("tid", template.getId())
                .setParameter("name", "Riyaziyyat")
                .getResultList()
                .stream().findFirst().orElse(null);

        TemplateSection ingilisDiliSection = entityManager.createQuery(
                        "SELECT s FROM TemplateSection s WHERE s.subtitle.template.id = :tid AND s.subjectName = :name",
                        TemplateSection.class)
                .setParameter("tid", template.getId())
                .setParameter("name", "İngilis dili")
                .getResultList()
                .stream().findFirst().orElse(null);

        if (riyazSection == null || ingilisDiliSection == null) {
            log.warn("Tələb olunan şablon bölmələri tapılmadı (Riyaziyyat={}, İngilis dili={})",
                    riyazSection != null, ingilisDiliSection != null);
            return;
        }

        Exam exam = Exam.builder()
                .title(examTitle)
                .description("Riyaziyyat və İngilis dili fənləri üzrə DİM buraxılış imtahanı formatında "
                        + "kombinə nümunə test. 55 sualdan ibarətdir.")
                .subjects(new java.util.ArrayList<>(List.of("Riyaziyyat", "İngilis dili")))
                .visibility(ExamVisibility.PUBLIC)
                .examType(ExamType.TEMPLATE)
                .status(ExamStatus.PUBLISHED)
                .shareLink(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .durationMinutes(120)
                .teacher(teacher)
                .template(template)
                .templateSection(riyazSection)
                .templateSections(new java.util.ArrayList<>(List.of(riyazSection, ingilisDiliSection)))
                .build();

        exam = examRepository.save(exam);

        // ── Riyaziyyat: 13 MCQ ──────────────────────────────────────────────────
        buildMcq(exam, 0,  "2x + 5 = 11 bərabərliyini həll edin.",
                new String[]{"x = 2", "x = 3", "x = 4", "x = 5"}, 1, "Riyaziyyat");
        buildMcq(exam, 1,  "log₂(16) = ?",
                new String[]{"2", "8", "4", "3"}, 2, "Riyaziyyat");
        buildMcq(exam, 2,  "x² − 5x + 6 = 0 tənliyinin köklərinin cəmi neçədir?",
                new String[]{"6", "3", "2", "5"}, 3, "Riyaziyyat");
        buildMcq(exam, 3,  "Radiusu 5 olan dairənin sahəsi neçədir?",
                new String[]{"10π", "5π", "50π", "25π"}, 3, "Riyaziyyat");
        buildMcq(exam, 4,  "f(x) = x² − 3x + 2, f(4) = ?",
                new String[]{"4", "8", "10", "6"}, 3, "Riyaziyyat");
        buildMcq(exam, 5,  "sin(30°) = ?",
                new String[]{"√3/2", "√2/2", "1", "1/2"}, 3, "Riyaziyyat");
        buildMcq(exam, 6,  "Tərəfləri 3, 4 və 5 olan üçbucaq hansı növdür?",
                new String[]{"İtiburcaqlı", "Kəskin", "Bərabərtərəfli", "Düzburcaqlı"}, 3, "Riyaziyyat");
        buildMcq(exam, 7,  "12 rəqəmi 48-in neçə faizini təşkil edir?",
                new String[]{"20%", "30%", "4%", "25%"}, 3, "Riyaziyyat");
        buildMcq(exam, 8,  "Həndəsi silsilə: 2, 6, 18, ... — 5-ci həddini tapın.",
                new String[]{"54", "162", "486", "324"}, 1, "Riyaziyyat");
        buildMcq(exam, 9,  "2^x = 32 tənliyini həll edin.",
                new String[]{"4", "6", "3", "5"}, 3, "Riyaziyyat");
        buildMcq(exam, 10, "Arifmetik silsilə: 3, 7, 11, ... — 10-cu həddini tapın.",
                new String[]{"39", "43", "47", "35"}, 0, "Riyaziyyat");
        buildMcq(exam, 11, "(x − 3)(x + 5) = 0 tənliyinin kökləri hansılardır?",
                new String[]{"x = 3 və x = 5", "x = −3 və x = 5", "x = 3 və x = −5", "x = −3 və x = −5"}, 2, "Riyaziyyat");
        buildMcq(exam, 12, "3² + 4² ifadəsinin qiyməti neçədir?",
                new String[]{"35", "49", "25", "12"}, 2, "Riyaziyyat");

        // ── Riyaziyyat: 5 OPEN_AUTO ─────────────────────────────────────────────
        buildOpenAuto(exam, 13, "3x − 7 = 14 tənliyini həll edin. (yalnız rəqəm yazın)", "7", "Riyaziyyat");
        buildOpenAuto(exam, 14, "5! (faktorial) hesablayın.", "120", "Riyaziyyat");
        buildOpenAuto(exam, 15, "Tərəfi 6 olan kvadratın sahəsi neçədir?", "36", "Riyaziyyat");
        buildOpenAuto(exam, 16, "ƏBOB(24, 36) = ?", "12", "Riyaziyyat");
        buildOpenAuto(exam, 17, "(1, 2) və (3, 6) nöqtələrindən keçən düz xəttin meyil əmsalını tapın.", "2", "Riyaziyyat");

        // ── Riyaziyyat: 7 OPEN_MANUAL ───────────────────────────────────────────
        buildOpenManual(exam, 18, "Üçbucaqda daxili bucaqların cəminin 180° olduğunu isbat edin.", "Riyaziyyat");
        buildOpenManual(exam, 19, "x² − 7x + 12 = 0 tənliyini həll edin. Həlli addım-addım göstərin.", "Riyaziyyat");
        buildOpenManual(exam, 20, "f(x) = 3x² − 2x + 1 funksiyasının törəməsini tapın.", "Riyaziyyat");
        buildOpenManual(exam, 21, "(x² − 4) / (x − 2) ifadəsini sadələşdirin (x ≠ 2).", "Riyaziyyat");
        buildOpenManual(exam, 22, "Qatarın sürəti 60 km/saat, hərəkət müddəti 2,5 saatdır. Məsafəni tapın.", "Riyaziyyat");
        buildOpenManual(exam, 23, "√2 ədədinin irrational olduğunu isbat edin.", "Riyaziyyat");
        buildOpenManual(exam, 24, "Düzburcaqlı üçbucaqda katetlər 5 və 12-dir. Hipotenuzanı tapın.", "Riyaziyyat");

        // ── İngilis dili: 23 MCQ ─────────────────────────────────────────────────
        buildMcq(exam, 25, "She ___ to school every day.",
                new String[]{"go", "goes", "going", "gone"}, 1, "İngilis dili");
        buildMcq(exam, 26, "They ___ football when it started raining.",
                new String[]{"play", "played", "were playing", "are playing"}, 2, "İngilis dili");
        buildMcq(exam, 27, "I ___ my homework by 8 o'clock.",
                new String[]{"finish", "had finished", "have finish", "finishing"}, 1, "İngilis dili");
        buildMcq(exam, 28, "If I ___ rich, I would travel the world.",
                new String[]{"am", "was", "were", "be"}, 2, "İngilis dili");
        buildMcq(exam, 29, "The report ___ written by the manager.",
                new String[]{"is", "was", "were", "has"}, 1, "İngilis dili");
        buildMcq(exam, 30, "She asked me where I ___ from.",
                new String[]{"come", "came", "comes", "coming"}, 1, "İngilis dili");
        buildMcq(exam, 31, "Choose the correct article: ___ Eiffel Tower is in Paris.",
                new String[]{"A", "An", "The", "—"}, 2, "İngilis dili");
        buildMcq(exam, 32, "He is good ___ playing chess.",
                new String[]{"in", "on", "at", "for"}, 2, "İngilis dili");
        buildMcq(exam, 33, "I look forward to ___ you again.",
                new String[]{"see", "seeing", "seen", "saw"}, 1, "İngilis dili");
        buildMcq(exam, 34, "You ___ smoke here — it's not allowed.",
                new String[]{"must", "mustn't", "should", "can"}, 1, "İngilis dili");
        buildMcq(exam, 35, "The synonym of 'happy' is:",
                new String[]{"sad", "angry", "joyful", "tired"}, 2, "İngilis dili");
        buildMcq(exam, 36, "She has lived here ___ 2010.",
                new String[]{"for", "since", "during", "while"}, 1, "İngilis dili");
        buildMcq(exam, 37, "The man ___ lives next door is a doctor.",
                new String[]{"which", "what", "who", "whom"}, 2, "İngilis dili");
        buildMcq(exam, 38, "By the time we arrived, the film ___.",
                new String[]{"starts", "started", "has started", "had started"}, 3, "İngilis dili");
        buildMcq(exam, 39, "She suggested ___ a taxi.",
                new String[]{"take", "to take", "taking", "took"}, 2, "İngilis dili");
        buildMcq(exam, 40, "Choose the correct form: Neither of them ___ ready.",
                new String[]{"are", "were", "is", "be"}, 2, "İngilis dili");
        buildMcq(exam, 41, "The opposite of 'ancient' is:",
                new String[]{"old", "modern", "historic", "classic"}, 1, "İngilis dili");
        buildMcq(exam, 42, "They ___ for three hours before they found a taxi.",
                new String[]{"walk", "walked", "had been walking", "have walked"}, 2, "İngilis dili");
        buildMcq(exam, 43, "Choose the correct preposition: She is afraid ___ spiders.",
                new String[]{"from", "about", "of", "with"}, 2, "İngilis dili");
        buildMcq(exam, 44, "I wish I ___ more time yesterday.",
                new String[]{"have", "had", "had had", "will have"}, 2, "İngilis dili");
        buildMcq(exam, 45, "The word 'enormous' means:",
                new String[]{"tiny", "average", "huge", "normal"}, 2, "İngilis dili");
        buildMcq(exam, 46, "She ___ just left when I arrived.",
                new String[]{"has", "have", "had", "was"}, 2, "İngilis dili");
        buildMcq(exam, 47, "Pick the correct sentence:",
                new String[]{"He don't like coffee.", "He doesn't likes coffee.",
                             "He doesn't like coffee.", "He not like coffee."}, 2, "İngilis dili");

        // ── İngilis dili: 7 OPEN_MANUAL ─────────────────────────────────────────
        buildOpenManual(exam, 48, "Write a short paragraph (5–6 sentences) about your favourite season.", "İngilis dili");
        buildOpenManual(exam, 49, "Rewrite the sentence in passive voice: 'The chef prepared the meal.'", "İngilis dili");
        buildOpenManual(exam, 50, "Use the word 'although' to combine these sentences: 'It was raining. We went for a walk.'", "İngilis dili");
        buildOpenManual(exam, 51, "Explain the difference between 'make' and 'do' with two examples each.", "İngilis dili");
        buildOpenManual(exam, 52, "Write a conditional sentence (Type 2) about winning the lottery.", "İngilis dili");
        buildOpenManual(exam, 53, "Describe a memorable event from your life using the past simple and past continuous.", "İngilis dili");
        buildOpenManual(exam, 54, "Read the prompt and write an opinion essay introduction (3–4 sentences): 'Social media does more harm than good.'", "İngilis dili");

        examRepository.save(exam);
        log.info("Çoxfənli nümunə imtahan yaradıldı: \"{}\"", examTitle);
    }

    @Transactional
    public void seedSampleOlimpiyadaExam() {
        String teacherEmail = "serxan.babayev.06@gmail.com";
        User teacher = userRepository.findByEmail(teacherEmail).orElse(null);
        if (teacher == null) { log.warn("Müəllim tapılmadı: {}", teacherEmail); return; }

        String examTitle = "Azərbaycan dili + Riyaziyyat — Respublika Olimpiyadası Nümunəsi";
        examRepository.findByTeacherAndDeletedFalse(teacher).stream()
                .filter(e -> examTitle.equals(e.getTitle()))
                .findFirst()
                .ifPresent(existing -> {
                    // If existing exam has stale 1.0pt questions (seeded before per-question points were added),
                    // delete and recreate so the 1.5pt questions are stored correctly.
                    Long staleCount = (Long) entityManager.createQuery(
                                    "SELECT COUNT(q) FROM Question q WHERE q.exam.id = :eid" +
                                    " AND q.subjectGroup = 'Azərbaycan dili'" +
                                    " AND q.orderIndex >= 15 AND (q.points IS NULL OR q.points < 1.4)")
                            .setParameter("eid", existing.getId())
                            .getSingleResult();
                    if (staleCount > 0) {
                        log.info("Olimpiyada nümunə imtahanı köhnə (1.0pt) strukturdadır, yenilənir...");
                        Long examId = existing.getId();
                        new TransactionTemplate(transactionManager).execute(status -> {
                            // Delete in FK-safe order using native SQL (JPQL bulk DELETE does not cascade)
                            entityManager.createNativeQuery(
                                    "DELETE FROM matching_pairs WHERE question_id IN (SELECT id FROM questions WHERE exam_id = :eid)")
                                    .setParameter("eid", examId).executeUpdate();
                            entityManager.createNativeQuery(
                                    "DELETE FROM options WHERE question_id IN (SELECT id FROM questions WHERE exam_id = :eid)")
                                    .setParameter("eid", examId).executeUpdate();
                            entityManager.createNativeQuery(
                                    "DELETE FROM answers WHERE submission_id IN (SELECT id FROM submissions WHERE exam_id = :eid)")
                                    .setParameter("eid", examId).executeUpdate();
                            entityManager.createNativeQuery(
                                    "DELETE FROM submissions WHERE exam_id = :eid")
                                    .setParameter("eid", examId).executeUpdate();
                            entityManager.createNativeQuery(
                                    "DELETE FROM questions WHERE exam_id = :eid")
                                    .setParameter("eid", examId).executeUpdate();
                            entityManager.createNativeQuery(
                                    "DELETE FROM exam_subject_list WHERE exam_id = :eid")
                                    .setParameter("eid", examId).executeUpdate();
                            entityManager.createNativeQuery(
                                    "DELETE FROM student_saved_exams WHERE exam_id = :eid")
                                    .setParameter("eid", examId).executeUpdate();
                            entityManager.createNativeQuery(
                                    "DELETE FROM exam_access_codes WHERE exam_id = :eid")
                                    .setParameter("eid", examId).executeUpdate();
                            entityManager.createNativeQuery(
                                    "DELETE FROM exam_tags WHERE exam_id = :eid")
                                    .setParameter("eid", examId).executeUpdate();
                            entityManager.createNativeQuery(
                                    "DELETE FROM exams WHERE id = :eid")
                                    .setParameter("eid", examId).executeUpdate();
                            return null;
                        });
                    }
                });
        boolean alreadyExists = examRepository.findByTeacherAndDeletedFalse(teacher).stream()
                .anyMatch(e -> examTitle.equals(e.getTitle()));
        if (alreadyExists) { log.debug("Olimpiyada nümunə imtahanı artıq mövcuddur, keçilir"); return; }

        Template template = templateRepository.findByTitle("Olimpiyada").orElse(null);
        if (template == null) { log.warn("Olimpiyada şablonu tapılmadı"); return; }

        TemplateSection azSection = entityManager.createQuery(
                "SELECT s FROM TemplateSection s WHERE s.subtitle.template.id = :tid AND s.subjectName = :name",
                TemplateSection.class)
                .setParameter("tid", template.getId()).setParameter("name", "Azərbaycan dili")
                .getResultList().stream().findFirst().orElse(null);

        TemplateSection riyazSection = entityManager.createQuery(
                "SELECT s FROM TemplateSection s WHERE s.subtitle.template.id = :tid AND s.subjectName = :name",
                TemplateSection.class)
                .setParameter("tid", template.getId()).setParameter("name", "Riyaziyyat")
                .getResultList().stream().findFirst().orElse(null);

        if (azSection == null || riyazSection == null) {
            log.warn("Olimpiyada bölmələri tapılmadı"); return;
        }

        Exam exam = Exam.builder()
                .title(examTitle)
                .description("Azərbaycan dili (20 sual) və Riyaziyyat (40 sual) üzrə Respublika Fənn Olimpiyadası "
                        + "formatında nümunə imtahan. Bütün düzgün cavablar E variantındadır. "
                        + "4 yanlış cavab 1 doğrunu silir.")
                .subjects(new java.util.ArrayList<>(List.of("Azərbaycan dili", "Riyaziyyat")))
                .visibility(ExamVisibility.PUBLIC)
                .examType(ExamType.OLIMPIYADA)
                .status(ExamStatus.PUBLISHED)
                .shareLink(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .durationMinutes(120)
                .teacher(teacher)
                .template(template)
                .templateSection(azSection)
                .templateSections(new java.util.ArrayList<>(List.of(azSection, riyazSection)))
                .build();
        exam = examRepository.save(exam);

        final int E = 4; // bütün düzgün cavablar E (index 4)

        // ═══════════════════════════════════════════════════════════════════
        // AZƏRBAYCAN DİLİ — 1-15: 1 bal (orderIndex 0-14)
        // ═══════════════════════════════════════════════════════════════════
        buildMcq(exam, 0,
            "Azərbaycan dilinin neçə saiti var?",
            new String[]{"6", "7", "8", "10", "9"}, E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 1,
            "Aşağıdakılardan hansı düzəltmə sözdür?",
            new String[]{"daş", "su", "ev", "yol", "yazıçı"}, E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 2,
            "İsmin neçə halı var?",
            new String[]{"4", "5", "7", "8", "6"}, E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 3,
            "Hansı söz çoxmənalıdır?",
            new String[]{"kitab", "qələm", "dəftər", "stol", "baş"}, E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 4,
            "Hansı cüt antonim təşkil edir?",
            new String[]{"şad — sevincli", "böyük — nəhəng", "igid — mərd", "sürət — hərəkət", "ağ — qara"},
            E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 5,
            "\"Dəmir yolu\" sözü hansı üsulla yaranmışdır?",
            new String[]{"şəkilçiləmə", "söz birləşməsi yolu", "qısaltma", "kəsilmə", "mürəkkəbləşmə"},
            E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 6,
            "\"Kim?\" sualına cavab verən cümlə üzvü hansıdır?",
            new String[]{"xəbər", "tamamlıq", "zərflik", "təyin", "mübtəda"}, E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 7,
            "\"Əl-ələ vermək\" frazeologizmi nə bildirir?",
            new String[]{"döyüşmək", "ayrılmaq", "ağlamaq", "razılaşmamaq", "birləşmək"},
            E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 8,
            "Hansı sözdə bitişdirici sait işlədilmişdir?",
            new String[]{"yazı", "oxucu", "gəliş", "bilgi", "günəbaxan"}, E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 9,
            "\"O, müəllimdir.\" cümləsinin xəbəri hansı növdəndir?",
            new String[]{"feli", "modal", "inkar", "feli-ismi", "ismi"}, E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 10,
            "Ziddiyyət bildirən bağlayıcı hansıdır?",
            new String[]{"və", "həm...həm də", "ya...ya da", "çünki", "lakin"}, E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 11,
            "Azərbaycan dilinin neçə samiti var?",
            new String[]{"23", "25", "28", "30", "32"}, E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 12,
            "\"Əlbəttə\" sözündə vurğu hansı hecaya düşür?",
            new String[]{"son", "ikinci", "üçüncü", "dördüncü", "birinci"}, E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 13,
            "\"Kitab oxudum.\" cümləsinin məqsəd üzrə növü nədir?",
            new String[]{"sual", "nida", "əmr", "şərt", "xəbər"}, E, "Azərbaycan dili", 1.0);
        buildMcq(exam, 14,
            "\"Şad\" sözünün sinonimi hansıdır?",
            new String[]{"kədərli", "üzgün", "qüssəli", "məyus", "sevincli"}, E, "Azərbaycan dili", 1.0);

        // ═══════════════════════════════════════════════════════════════════
        // AZƏRBAYCAN DİLİ — 16-20: 1,5 bal (orderIndex 15-19)
        // ═══════════════════════════════════════════════════════════════════
        buildMcq(exam, 15,
            "\"Hava soyuq olduğundan palto geydim.\" cümləsi hansı növdədir?",
            new String[]{"sadə", "tabesiz mürəkkəb", "şərtli", "xəbərsiz", "tabeli mürəkkəb"},
            E, "Azərbaycan dili", 1.5);
        buildMcq(exam, 16,
            "Ard-arda gələn misralarda eyni samit səslərin təkrarı hansı üslubi fiqurdur?",
            new String[]{"assonans", "bənzətmə", "metafora", "epitet", "alliterasiya"},
            E, "Azərbaycan dili", 1.5);
        buildMcq(exam, 17,
            "\"Pələng kimi cəld\" ifadəsi hansı bədii ifadə vasitəsidir?",
            new String[]{"metonimiya", "metafora", "hiperbola", "epitet", "təşbeh"},
            E, "Azərbaycan dili", 1.5);
        buildMcq(exam, 18,
            "Tabeli mürəkkəb cümlənin neçə növü var?",
            new String[]{"3", "4", "5", "6", "7"}, E, "Azərbaycan dili", 1.5);
        buildMcq(exam, 19,
            "Mətndə ardıcıl yerləşən, mövzu birliyinə malik cümlələr qrupuna nə deyilir?",
            new String[]{"abzas", "fəsil", "bölmə", "paraqraf", "mürəkkəb sintaktik bütöv"},
            E, "Azərbaycan dili", 1.5);

        // ═══════════════════════════════════════════════════════════════════
        // RİYAZİYYAT — 1-35: 1 bal (orderIndex 20-54)
        // ═══════════════════════════════════════════════════════════════════
        buildMcq(exam, 20,
            "3x − 4 = 17 tənliyinin kökü nədir?",
            new String[]{"3", "5", "6", "8", "7"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 21,
            "log₂(128) = ?",
            new String[]{"5", "8", "9", "6", "7"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 22,
            "C(7, 2) = ?",
            new String[]{"14", "35", "28", "42", "21"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 23,
            "Düzburcaqlı üçbucaqda katetlər 5 və 12 olduqda hipotenuz neçədir?",
            new String[]{"10", "11", "15", "17", "13"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 24,
            "Düzgün altıbucaqlının daxili bucaqlarının cəmi neçə dərəcədir?",
            new String[]{"540", "900", "1080", "1260", "720"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 25,
            "sin²α + cos²α = ?",
            new String[]{"0", "2", "sin α", "cos α", "1"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 26,
            "f(x) = x² − 4x + 3 funksiyasının minimum nöqtəsi hansı x-dədir?",
            new String[]{"0", "1", "3", "5", "2"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 27,
            "Birinci 10 natural ədədin cəmi neçədir?",
            new String[]{"45", "50", "60", "48", "55"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 28,
            "x² = 49 tənliyinin köklərinin hasili nədir?",
            new String[]{"49", "7", "14", "0", "−49"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 29,
            "Radiusu 6 olan dairənin sahəsi (π ilə) neçədir?",
            new String[]{"12π", "24π", "48π", "6π", "36π"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 30,
            "2¹⁰ = ?",
            new String[]{"256", "512", "2048", "128", "1024"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 31,
            "Arifmetik silsilənin ilk həddi 3, fərqi 4 olduqda 8-ci hədd nədir?",
            new String[]{"28", "30", "32", "26", "31"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 32,
            "|−15| + |8| = ?",
            new String[]{"7", "−7", "8", "15", "23"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 33,
            "√169 = ?",
            new String[]{"12", "14", "11", "15", "13"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 34,
            "3⁴ = ?",
            new String[]{"27", "64", "243", "256", "81"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 35,
            "A(3, 4) nöqtəsinin koordinat başlanğıcına məsafəsi neçədir?",
            new String[]{"3", "4", "6", "7", "5"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 36,
            "5! = ?",
            new String[]{"25", "60", "100", "240", "120"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 37,
            "ƏBOB(36, 48) = ?",
            new String[]{"6", "8", "9", "18", "12"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 38,
            "x² − 9 = 0 tənliyinin köklərinin hasili nədir?",
            new String[]{"9", "3", "−3", "0", "−9"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 39,
            "Tərəfi 8 sm olan kubun həcmi neçə sm³-dir?",
            new String[]{"64", "192", "256", "384", "512"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 40,
            "Hansı funksiya cüt funksiyadır?",
            new String[]{"f(x)=x", "f(x)=x³", "f(x)=sin x", "f(x)=x²+x", "f(x)=x²"},
            E, "Riyaziyyat", 1.0);
        buildMcq(exam, 41,
            "2x + 3y = 12, x = 3 olduqda y = ?",
            new String[]{"1", "3", "4", "5", "2"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 42,
            "Tam hissə funksiyası: [7,9] = ?",
            new String[]{"8", "9", "6", "0", "7"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 43,
            "p(x) = x³ − x funksiyasının x = 2-dəki qiyməti nədir?",
            new String[]{"2", "4", "8", "10", "6"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 44,
            "Ədədin 20%-i 8-ə bərabərdir. Ədəd neçədir?",
            new String[]{"16", "20", "32", "48", "40"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 45,
            "Sürəti 60 km/saat olan avtomobil 150 km yolu neçə saatə qət edir?",
            new String[]{"1", "2", "4", "3", "2,5"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 46,
            "(a + b)² = ?",
            new String[]{"a² + b²", "2ab", "a² − b²", "a + b", "a² + 2ab + b²"},
            E, "Riyaziyyat", 1.0);
        buildMcq(exam, 47,
            "Ölçüləri 2, 3, 6 olan düzburcaqlı paralelepipedin diaqonalı neçədir?",
            new String[]{"5", "6", "8", "9", "7"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 48,
            "Həndəsi silsilə: 3, 6, 12, 24, ... — 6-cı hədd nədir?",
            new String[]{"36", "48", "72", "108", "96"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 49,
            "x = 4, y = 3 üçün (x+y)² − (x−y)² = ?",
            new String[]{"12", "24", "36", "42", "48"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 50,
            "35 ədədinin neçə müsbət tam bölənı var?",
            new String[]{"2", "3", "5", "6", "4"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 51,
            "sin(45°) × cos(45°) = ?",
            new String[]{"1", "√2/2", "√2", "0", "1/2"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 52,
            "A = {1,2,3,4}, B = {3,4,5,6} — A∪B-nin elementlərinin sayı neçədir?",
            new String[]{"4", "5", "7", "8", "6"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 53,
            "3x ≡ 1 (mod 7) uyğunluğunun ən kiçik müsbət həlli nədir?",
            new String[]{"1", "2", "3", "4", "5"}, E, "Riyaziyyat", 1.0);
        buildMcq(exam, 54,
            "ƏKOM(4, 6) = ?",
            new String[]{"4", "6", "8", "18", "12"}, E, "Riyaziyyat", 1.0);

        // ═══════════════════════════════════════════════════════════════════
        // RİYAZİYYAT — 36-40: 1,5 bal (orderIndex 55-59)
        // ═══════════════════════════════════════════════════════════════════
        buildMcq(exam, 55,
            "f(x) = x³ − 3x² funksiyasının x = 2 nöqtəsindəki törəməsi nədir?",
            new String[]{"2", "4", "6", "−2", "0"}, E, "Riyaziyyat", 1.5);
        buildMcq(exam, 56,
            "2·log₃(x) = log₃(4x − 4) tənliyinin həlli nədir?",
            new String[]{"1", "3", "4", "6", "2"}, E, "Riyaziyyat", 1.5);
        buildMcq(exam, 57,
            "P(6, 2) + C(6, 2) = ?",
            new String[]{"30", "36", "40", "42", "45"}, E, "Riyaziyyat", 1.5);
        buildMcq(exam, 58,
            "5 fərqli kitabdan 3-ü seçilib rəfə düzülürsə, neçə variant var?",
            new String[]{"10", "20", "30", "45", "60"}, E, "Riyaziyyat", 1.5);
        buildMcq(exam, 59,
            "x² − 5x + 6 > 0 bərabərsizliyinin həll çoxluğu hansıdır?",
            new String[]{"{x|x<3}", "{x|2<x<3}", "{x|x>2}", "{x|1<x<4}", "{x|x<2 və ya x>3}"},
            E, "Riyaziyyat", 1.5);

        examRepository.save(exam);
        log.info("Olimpiyada nümunə imtahanı yaradıldı: \"{}\"", examTitle);
    }

    private void buildMcq(Exam exam, int orderIndex, String content, String[] opts, int correctIdx) {
        buildMcq(exam, orderIndex, content, opts, correctIdx, null, 1.0);
    }

    private void buildMcq(Exam exam, int orderIndex, String content, String[] opts, int correctIdx, String subjectGroup) {
        buildMcq(exam, orderIndex, content, opts, correctIdx, subjectGroup, 1.0);
    }

    private void buildOpenAuto(Exam exam, int orderIndex, String content, String correctAnswer) {
        buildOpenAuto(exam, orderIndex, content, correctAnswer, null);
    }

    private void buildOpenManual(Exam exam, int orderIndex, String content) {
        buildOpenManual(exam, orderIndex, content, null);
    }

    private void buildMcq(Exam exam, int orderIndex, String content, String[] opts, int correctIdx, String subjectGroup, double points) {
        Question q = Question.builder()
                .exam(exam)
                .content(content)
                .questionType(QuestionType.MCQ)
                .points(points)
                .orderIndex(orderIndex)
                .subjectGroup(subjectGroup)
                .build();
        for (int i = 0; i < opts.length; i++) {
            q.getOptions().add(Option.builder()
                    .question(q)
                    .content(opts[i])
                    .isCorrect(i == correctIdx)
                    .orderIndex(i)
                    .build());
        }
        exam.getQuestions().add(q);
    }

    private void buildOpenAuto(Exam exam, int orderIndex, String content, String correctAnswer, String subjectGroup) {
        exam.getQuestions().add(Question.builder()
                .exam(exam)
                .content(content)
                .questionType(QuestionType.OPEN_AUTO)
                .points(1.0)
                .orderIndex(orderIndex)
                .correctAnswer(correctAnswer)
                .subjectGroup(subjectGroup)
                .build());
    }

    private void buildOpenManual(Exam exam, int orderIndex, String content, String subjectGroup) {
        exam.getQuestions().add(Question.builder()
                .exam(exam)
                .content(content)
                .questionType(QuestionType.OPEN_MANUAL)
                .points(1.0)
                .orderIndex(orderIndex)
                .subjectGroup(subjectGroup)
                .build());
    }
}
