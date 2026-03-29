package az.testup.config;

import az.testup.entity.*;
import az.testup.enums.BannerPosition;
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
    private final SubjectTopicRepository subjectTopicRepository;
    private final EntityManager entityManager;
    private final TemplateRepository templateRepository;
    private final TemplateSubtitleRepository subtitleRepository;
    private final TemplateSectionRepository sectionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final BannerRepository bannerRepository;
    private final TagRepository tagRepository;

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
        if (!userRepository.existsByEmail(adminEmail)) {
            User admin = User.builder()
                    .fullName("Sərxan Babayev")
                    .email(adminEmail)
                    .password(passwordEncoder.encode("salam123"))
                    .role(Role.ADMIN)
                    .enabled(true)
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
                    .enabled(true)
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
                    .enabled(true)
                    .build());
            log.info("Şagird hesabı yaradıldı: {}", email);
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

    private void seedBanners() {
        if (bannerRepository.count() > 0) {
            log.debug("Bannerlər artıq mövcuddur, keçilir");
            return;
        }

        List<Banner> banners = List.of(
            Banner.builder()
                .title("3 Ay Basic Plan — Pulsuz!")
                .subtitle("Müəllim kimi qeydiyyatdan keçin, 3 aylıq Basic abunəliyini hədiyyə alın.")
                .linkUrl("/register")
                .linkText("İndi qeydiyyatdan keç")
                .isActive(true)
                .position(BannerPosition.HERO)
                .bgGradient("from-indigo-600 to-purple-600")
                .orderIndex(0)
                .build(),

            Banner.builder()
                .title("Sual bazanızı qurun")
                .subtitle("Fənlər üzrə sualları saxlayın, istənilən imtahana əlavə edin. Basic planda daxildir.")
                .linkUrl("/planlar")
                .linkText("Planlara bax")
                .isActive(true)
                .position(BannerPosition.INLINE)
                .bgGradient("from-emerald-500 to-teal-600")
                .orderIndex(0)
                .build(),

            Banner.builder()
                .title("PDF-dən sual idxalı — Limitsiz planda")
                .subtitle("Mövcud test materiallarınızı PDF formatında yükləyin, suallar avtomatik əlavə edilsin.")
                .linkUrl("/planlar")
                .linkText("Limitsiz plana keç")
                .isActive(true)
                .position(BannerPosition.BOTTOM)
                .bgGradient("from-orange-500 to-pink-500")
                .orderIndex(0)
                .build()
        );

        bannerRepository.saveAll(banners);
        log.info("{} banner əlavə edildi", banners.size());
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
