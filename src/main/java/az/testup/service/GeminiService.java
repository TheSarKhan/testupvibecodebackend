package az.testup.service;

import az.testup.dto.request.BankMatchingPairRequest;
import az.testup.dto.request.BankOptionRequest;
import az.testup.dto.request.BankQuestionRequest;
import az.testup.dto.request.GenerateExamRequest;
import az.testup.dto.request.GenerateQuestionsRequest;
import az.testup.enums.Difficulty;
import az.testup.enums.QuestionType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiService {

    @Value("${groq.api-key:}")
    private String apiKey;

    private static final String GROQ_URL =
        "https://api.groq.com/openai/v1/chat/completions";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<BankQuestionRequest> generateQuestions(GenerateQuestionsRequest req) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Groq API açarı konfiqurasiya edilməyib. application.yml-ə groq.api-key əlavə edin.");
        }

        String prompt = buildPrompt(req);
        String rawResponse = callGroq(prompt, req);
        List<BankQuestionRequest> parsed = parseResponse(rawResponse, req);
        List<BankQuestionRequest> filtered = filterValidLatex(parsed);

        int requested = req.getCount();
        if (filtered.size() < requested && filtered.size() < parsed.size()) {
            GenerateQuestionsRequest retryReq = new GenerateQuestionsRequest();
            retryReq.setSubjectId(req.getSubjectId());
            retryReq.setSubjectName(req.getSubjectName());
            retryReq.setTopicName(req.getTopicName());
            retryReq.setDifficulty(req.getDifficulty());
            retryReq.setQuestionType(req.getQuestionType());
            retryReq.setCount(requested - filtered.size());
            try {
                String retryRaw = callGroq(buildPrompt(retryReq), retryReq);
                List<BankQuestionRequest> retryFiltered = filterValidLatex(parseResponse(retryRaw, retryReq));
                filtered.addAll(retryFiltered);
            } catch (Exception ignored) {}
        }

        for (int i = 0; i < filtered.size(); i++) filtered.get(i).setOrderIndex(i);
        return filtered;
    }

    private List<BankQuestionRequest> filterValidLatex(List<BankQuestionRequest> questions) {
        List<BankQuestionRequest> result = new ArrayList<>();
        for (BankQuestionRequest q : questions) {
            if (!hasValidLatex(q.getContent())) continue;
            boolean optionsOk = q.getOptions() == null || q.getOptions().stream()
                    .allMatch(o -> hasValidLatex(o.getContent()));
            if (!optionsOk) continue;
            if (q.getCorrectAnswer() != null && !hasValidLatex(q.getCorrectAnswer())) continue;
            result.add(q);
        }
        return result;
    }

    private boolean hasValidLatex(String text) {
        if (text == null || text.isBlank()) return true;
        int beginCount = countMatches(text, "\\begin{");
        int endCount   = countMatches(text, "\\end{");
        if (beginCount != endCount) return false;
        if (text.matches("(?s).*(^|[^\\\\])end\\{.*")) return false;
        long dollars = text.chars().filter(c -> c == '$').count();
        if (dollars % 2 != 0) return false;
        return true;
    }

    private int countMatches(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }

    // ── Subject categorization ────────────────────────────────────────────────

    private static final java.util.Set<String> MATH_SUBJECTS = java.util.Set.of(
        "Riyaziyyat", "Fizika", "Kimya", "Həndəsə", "Cəbr", "Triqonometriya", "İnformatika"
    );

    private static final java.util.Set<String> SCIENCE_SUBJECTS = java.util.Set.of(
        "Biologiya", "Coğrafiya", "Astronomiya"
    );

    private static final java.util.Set<String> HISTORY_SUBJECTS = java.util.Set.of(
        "Tarix", "Azərbaycan tarixi", "Ümumi tarix"
    );

    private static final java.util.Set<String> LANGUAGE_SUBJECTS = java.util.Set.of(
        "Azərbaycan dili", "İngilis dili", "Rus dili", "Ədəbiyyat"
    );

    private boolean containsAny(String name, java.util.Set<String> set) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return set.stream().anyMatch(s -> lower.contains(s.toLowerCase()));
    }

    private boolean isMathSubject(String name) {
        return containsAny(name, MATH_SUBJECTS);
    }

    private boolean isScienceSubject(String name) {
        return containsAny(name, SCIENCE_SUBJECTS);
    }

    private boolean isHistorySubject(String name) {
        return containsAny(name, HISTORY_SUBJECTS);
    }

    private boolean isLanguageSubject(String name) {
        return containsAny(name, LANGUAGE_SUBJECTS);
    }

    /** Returns a subject-specific style/terminology cue to inject into the prompt. */
    private String subjectStyleCue(String subjectName) {
        if (isMathSubject(subjectName)) {
            return "STİL: Hesablamadan əvvəl tələbənin başa düşməsini yoxla — formul yaddaşı yox, məntiq və tətbiq vacibdir. " +
                   "Ədədlər saf və nəticəli olsun (mümkünsə tam ədəd / sadə kəsr). Süni \"71.4256\" kimi qəribə nəticələrdən qaç.";
        }
        if (isScienceSubject(subjectName)) {
            return "STİL: Termin və anlayışlar dəqiq olsun (məs: \"fotosintez\", \"diffuziya\"). Müşahidə + səbəb-nəticə əlaqəsi ön planda olsun.";
        }
        if (isHistorySubject(subjectName)) {
            return "STİL: Yalnız təsdiqlənmiş, ümumi qəbul olunan tarixi faktlardan istifadə et. Mübahisəli interpretasiyalardan qaç. " +
                   "Tarix (il/əsr), şəxsiyyət və hadisə adlarının yazılışı düzgün olsun.";
        }
        // Azərbaycan dili is its own discipline (grammar / orthography /
        // syntax / lexicology). We have to be loud about NOT generating
        // literature or art-history questions when the teacher picks it,
        // because the model otherwise drifts into Nizami / Füzuli / musiqi
        // factoids that don't belong on a language test.
        if (subjectName != null && subjectName.toLowerCase().contains("azərbaycan dili")) {
            return "STİL: YALNIZ Azərbaycan dili (qrammatika / orfoqrafiya / sintaksis / leksikologiya / morfologiya / fonetika) sualları yarat. " +
                   "QADAĞAN olunan mövzular: ƏDƏBİYYAT (yazıçı, şair, əsər təhlili, ədəbi cərəyan), musiqi, incəsənət, tarix faktları, coğrafiya. " +
                   "Bu kateqoriyalardan birini sual mətninə qarışdırma. Hər sual qrammatik və ya leksik qaydaya əsaslansın " +
                   "(məs: söz növləri, hal şəkilçiləri, vurğu, durğu işarələri, sinonim/antonim, etimologiya, cümlə üzvləri). " +
                   "Cümlələr təbii və müasir Azərbaycan dilində olsun.";
        }
        if (subjectName != null && subjectName.toLowerCase().contains("ədəbiyyat")) {
            return "STİL: Ədəbiyyat sualı yarat — yazıçı, əsər, ədəbi cərəyan, üslub, məna və təhlil. " +
                   "Təkcə \"kim yazıb\" deyil, məna və üslub təhlilinə də toxun. " +
                   "Qrammatik suallardan və başqa fənn mövzularından qaç.";
        }
        if (isLanguageSubject(subjectName)) {
            return "STİL: Qrammatik və leksik suallarda kontekst aydın olsun. Cümlələr təbii və müasir dildə olsun. " +
                   "Yalnız seçilən dilə aid sualları yarat — başqa fənn mövzularını qarışdırma.";
        }
        return "STİL: Sual aydın, birmənalı və konkret olsun. Sual mətnində cavaba işarə olmasın.";
    }

    /** Returns the difficulty rubric — what \"easy/medium/hard\" actually means. */
    private String difficultyRubric(String difficulty, boolean isMath) {
        return switch (difficulty == null ? "MEDIUM" : difficulty) {
            case "EASY" -> isMath
                ? "ASAN: 1 addım, birbaşa formul tətbiqi və ya təriflərin xatırlanması. " +
                  "Tələbə kalkulyator olmadan 30 saniyə içində həll edə bilməlidir."
                : "ASAN: Yaddaşa əsaslanan, dərslikdə birbaşa keçən fakt və ya tərif. " +
                  "Bir cümləyə sığan birmənalı cavab.";
            case "HARD" -> isMath
                ? "ÇƏTİN: 3+ addımlı, bir neçə anlayışın birləşdirilməsini tələb edən. " +
                  "Sözlü məsələ və ya qarışıq tənlik. Olimpiada/buraxılış imtahanı səviyyəsi, " +
                  "amma TƏHRİF EDİLMİŞ deyil — həll yolu məntiqi olmalıdır."
                : "ÇƏTİN: Analiz, tətbiq və sintez tələb edən. Sadəcə yaddaş kifayət etmir — " +
                  "anlayışları yeni kontekstdə birləşdirmək, fərqləndirmək və ya nəticə çıxarmaq lazımdır.";
            default -> isMath
                ? "ORTA: 2 addımlı tipik dərslik məsələsi. Standart tənlik və ya konsept tətbiqi. " +
                  "Orta tələbə 1-2 dəqiqədə həll edə bilər."
                : "ORTA: Sadəcə yaddaş deyil, anlayışı tələb edən. " +
                  "Müqayisə, səbəb-nəticə və ya tətbiq sualı.";
        };
    }

    // ── Prompt builder ────────────────────────────────────────────────────────

    private String buildSystemMessage() {
        return """
               You are an EXPERT Azerbaijani K-12 / university-prep exam author with 15+ years of pedagogical experience. \
               You produce questions that real teachers would assign — pedagogically sound, factually correct, age-appropriate, \
               and designed to discriminate between students who understand the material and those who don't.

               OUTPUT CONTRACT — non-negotiable:
               - Respond with ONE valid JSON object ONLY. No markdown fences, no prose, no explanations.
               - Top-level key: "questions" → array.
               - Every string must be valid JSON (escape quotes, newlines as \\n, backslashes doubled).
               - Produce EXACTLY the requested number of questions — not fewer, not more.

               QUALITY BAR — every question must satisfy ALL of these:
               1. FACTUALLY CORRECT. If you are not 100% sure of the answer, do NOT include the question.
               2. UNAMBIGUOUS. Exactly one interpretation; exactly one correct answer (or the requested number of correct answers for MULTI_SELECT).
               3. NO GIVEAWAYS. The stem must not telegraph the answer. Avoid grammatical mismatches between stem and only-correct option.
               4. PLAUSIBLE DISTRACTORS. Wrong options must reflect REAL student misconceptions — common errors, near-miss values, off-by-one mistakes, swapped terms. NEVER use joke options, obviously-wrong fillers, "Bütün yuxarıdakılar", "Heç biri", or repeats.
               5. CALIBRATED LENGTH. Stem 1–3 sentences. Options short, parallel in length and structure.
               6. DIVERSE BATCH. When multiple questions are requested, each must cover a DIFFERENT sub-topic, concept or cognitive level (recall → apply → analyze). Never produce near-duplicates.
               7. CULTURALLY APPROPRIATE. Use Azerbaijani names, places, and curriculum context where relevant. Avoid Western-centric examples unless required by the topic.

               BANNED PATTERNS — DO NOT produce any of these:
               - "Aşağıdakılardan hansı düzgündür?" with one obviously-true option and three absurd ones.
               - Options containing "Yuxarıdakıların hamısı" / "Heç biri".
               - Trick questions whose answer depends on word-play rather than subject knowledge.
               - Questions whose correct answer is literally restated in the stem.
               - Numerical answers like "3.71428..." for school-level math — pick clean numbers.
               - Stem in one language, options in another.

               SELF-CHECK before emitting each question: solve it yourself, confirm the marked correct answer is correct, and confirm at least one distractor reflects a plausible misconception.""";
    }

    private String buildPrompt(GenerateQuestionsRequest req) {
        String topic = (req.getTopicName() != null && !req.getTopicName().isBlank())
            ? req.getTopicName().trim() : null;

        boolean isOpen  = "OPEN_AUTO".equals(req.getQuestionType())
                       || "FILL_IN_THE_BLANK".equals(req.getQuestionType());
        boolean isFill  = "FILL_IN_THE_BLANK".equals(req.getQuestionType());
        boolean isMulti = "MULTI_SELECT".equals(req.getQuestionType());
        boolean isMath  = isMathSubject(req.getSubjectName());

        String diffRubric = difficultyRubric(req.getDifficulty(), isMath);
        String styleCue   = subjectStyleCue(req.getSubjectName());

        String latexNote = isMath ? """

                LaTeX QAYDALARI (KaTeX) — kəsin əməl et:
                - HƏR riyazi ifadə MÜTLƏQ `$...$` daxilində olsun. Çılpaq LaTeX (`$` olmadan) QADAĞANDIR.
                - Adi ədədlər və dəyişənlər də: `$x$`, `$5$`, `$-3$`, `$\\pi$`.
                - Kəsr: `$\\frac{a}{b}$` (JSON-da `\\\\frac`); kök: `$\\sqrt{x}$`; dərəcə: `$x^{2}$`; alt indeks: `$x_{1}$`; vurma: `$\\cdot$`.
                - JSON-da hər `\\` mütləq `\\\\` kimi qoşalansın.
                - `\\begin{...}` işlədilərsə qarşılıqlı `\\end{...}` ilə bağlansın və hər ikisi $...$ içində olsun.
                - Mətndəki ÖLÇÜ VAHİDLƏRİ də formula daxilinə alın: `$10\\text{ m/s}$`, `$25^{\\circ}\\text{C}$`.
                - QAÇIN: matris, çoxsətirli inteqral, mürəkkəb diaqramlar (yoxlanmır, riskli). Sadə tənliklərlə işlə.""" : "";

        String typeRules;
        String exampleJson;

        if (isFill) {
            typeRules = """
                    SUAL TİPİ — BOŞLUQ DOLDURMA (FILL_IN_THE_BLANK):
                    - Cümlədə MƏHZ BİR `___` (üç alt xətt) boşluq qoy.
                    - Boşluqdan kənarda sual aydın və tam başa düşülən olsun.
                    - `correctAnswer` yalnız boşluğa düşən söz/rəqəm/ifadə olmalıdır (tam cümlə yox).
                    - Cavab QISA və birmənalı olsun (1-3 söz, və ya 1 ədəd).""";
            exampleJson = isMath
                ? "{\"questions\":[{\"content\":\"$2x + 5 = 13$ tənliyində $x$ = ___\",\"correctAnswer\":\"$4$\"}]}"
                : "{\"questions\":[{\"content\":\"Azərbaycanın paytaxtı ___ şəhəridir.\",\"correctAnswer\":\"Bakı\"}]}";
        } else if (isOpen) {
            typeRules = """
                    SUAL TİPİ — AÇIQ SUAL (OPEN_AUTO):
                    - Tələbə qısa cavab yazır (ad, ədəd, qısa ifadə).
                    - Cavab birmənalı olsun — bir neçə \"düzgün\" formada yazıla bilməyəcək kimi.
                    - Mümkünsə cavab tək söz / tək ədəd olsun (avtomatik yoxlama üçün).
                    - `correctAnswer` standartlaşdırılmış formada yazılsın (lüğət/normativ forma).""";
            exampleJson = isMath
                ? "{\"questions\":[{\"content\":\"$f(x) = 2x^{2} - 3$ funksiyasında $f(2)$ qiymətini tapın.\",\"correctAnswer\":\"$5$\"}]}"
                : "{\"questions\":[{\"content\":\"Azərbaycan Xalq Cümhuriyyəti hansı ildə elan olunub?\",\"correctAnswer\":\"1918\"}]}";
        } else if (isMulti) {
            typeRules = """
                    SUAL TİPİ — ÇOX SEÇİMLİ (MULTI_SELECT):
                    - DƏQİQ 4 variant. DƏQİQ 2 DÜZGÜN (`isCorrect: true`), DƏQİQ 2 SƏHV (`isCorrect: false`).
                    - Hər iki düzgün variant həqiqətən düzgün olmalı; hər iki səhv variant inandırıcı, lakin tam səhv.
                    - Variantlar bir-birini istisna etməsin, lakin eyni şey OLMASIN.
                    - Stem aydın göstərsin ki, BİRDƏN ÇOX cavab seçilməlidir (məs: \"Hansılar...\").""";
            exampleJson = isMath
                ? "{\"questions\":[{\"content\":\"Hansı ədədlər $x^{2} = 16$ tənliyinin həllidir?\",\"options\":[{\"text\":\"$x = 4$\",\"isCorrect\":true},{\"text\":\"$x = -4$\",\"isCorrect\":true},{\"text\":\"$x = 8$\",\"isCorrect\":false},{\"text\":\"$x = 16$\",\"isCorrect\":false}]}]}"
                : "{\"questions\":[{\"content\":\"Hansı şəhərlər Azərbaycanın qədim mədəniyyət mərkəzləri sayılır?\",\"options\":[{\"text\":\"Gəncə\",\"isCorrect\":true},{\"text\":\"Şamaxı\",\"isCorrect\":true},{\"text\":\"İstanbul\",\"isCorrect\":false},{\"text\":\"Almatı\",\"isCorrect\":false}]}]}";
        } else {
            typeRules = """
                    SUAL TİPİ — TEST (MCQ):
                    - DƏQİQ 4 variant. DƏQİQ 1 DÜZGÜN (`isCorrect: true`), DƏQİQ 3 SƏHV (`isCorrect: false`).
                    - DİSTRAKTOR (səhv variantlar) qaydası:
                      • Hər biri TƏLƏBƏNİN EDƏCƏYİ KONKRET SƏHVƏ uyğun olmalı (məs: işarə səhvi, qatlanma səhvi, vahid qarışdırması).
                      • Düzgün cavabla eyni format, uzunluq, üslubda yazılmalı.
                      • Heç biri açıq-aydın absurd, gülməli və ya nəzəri cəhətdən imkansız olmasın.
                    - Variantların sırası random olsun (düzgün cavab həmişə eyni yerdə qalmasın).""";
            exampleJson = isMath
                ? "{\"questions\":[{\"content\":\"$3x - 7 = 8$ tənliyini həll edin.\",\"options\":[{\"text\":\"$x = 5$\",\"isCorrect\":true},{\"text\":\"$x = -5$\",\"isCorrect\":false},{\"text\":\"$x = 15$\",\"isCorrect\":false},{\"text\":\"$x = \\\\frac{1}{5}$\",\"isCorrect\":false}]}]}"
                : "{\"questions\":[{\"content\":\"Azərbaycan müstəqilliyini bərpa etdi:\",\"options\":[{\"text\":\"18 oktyabr 1991\",\"isCorrect\":true},{\"text\":\"28 may 1918\",\"isCorrect\":false},{\"text\":\"30 avqust 1991\",\"isCorrect\":false},{\"text\":\"1 yanvar 1992\",\"isCorrect\":false}]}]}";
        }

        String topicLine = topic != null
            ? "MÖVZU: \"" + topic + "\" — bütün suallar BU ALT-MÖVZUNUN ƏHATƏSİNDƏ qalmalı, kənara çıxmamalı."
            : "MÖVZU: Mövzu göstərilməyib — fənn üzrə MƏRKƏZİ, dərslikdə təməl sayılan mövzulardan seç.";

        String diversityRule = req.getCount() > 1
            ? "BATCH DIVERSITY: " + req.getCount() + " sual yaradılır — hər biri MÜXTƏLİF alt-konsept, " +
              "fərqli cognitive səviyyə (xatırlamaq / tətbiq etmək / təhlil etmək) və ya fərqli ssenari sınasın. " +
              "Eyni şablonun kiçik dəyişikliyi yox, HƏQİQİ MÜXTƏLİFLİK."
            : "";

        return ("FƏNN: " + req.getSubjectName() + "\n" +
                topicLine + "\n" +
                "ÇƏTİNLİK SƏVİYYƏSİ: " + diffRubric + "\n" +
                styleCue + "\n" +
                (diversityRule.isEmpty() ? "" : diversityRule + "\n") +
                "DİL: Azərbaycan dili (təmiz, müasir orfoqrafiya).\n" +
                "SUAL SAYI: DƏQİQ " + req.getCount() + " sual.\n" +
                latexNote + "\n\n" +
                typeRules + "\n\n" +
                "JSON SXEMİ:\n" +
                "{ \"questions\": [ { \"content\": string" +
                (isOpen ? ", \"correctAnswer\": string"
                        : ", \"options\": [{ \"text\": string, \"isCorrect\": boolean }, … 4 ədəd]") +
                " }, … " + req.getCount() + " ədəd ] }\n\n" +
                "STRUKTUR NÜMUNƏSİ (məzmunu kopyalama, yalnız format):\n" +
                exampleJson + "\n\n" +
                "İndi yuxarıdakı QAYDALARA tam əməl edərək " + req.getCount() + " yüksək keyfiyyətli sual yarat. " +
                "YALNIZ JSON cavab ver.");
    }

    // ── API call ──────────────────────────────────────────────────────────────

    /**
     * Subject-aware temperature:
     * - Math / science: lower temperature → more deterministic, fewer factual errors.
     * - Humanities / language: higher temperature → richer variation.
     */
    private double temperatureFor(GenerateQuestionsRequest req) {
        if (isMathSubject(req.getSubjectName())) return 0.45;
        if (isScienceSubject(req.getSubjectName())) return 0.55;
        if (isHistorySubject(req.getSubjectName())) return 0.55;
        if (isLanguageSubject(req.getSubjectName())) return 0.75;
        return 0.65;
    }

    /** Stricter prompts produce longer JSON — scale token budget with batch size. */
    private int maxTokensFor(GenerateQuestionsRequest req) {
        int perQuestion = isMathSubject(req.getSubjectName()) ? 450 : 350;
        return Math.min(8192, 800 + perQuestion * Math.max(1, req.getCount()));
    }

    private String callGroq(String prompt, GenerateQuestionsRequest req) {
        RestTemplate rest = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String body = """
            {
              "model": "llama-3.3-70b-versatile",
              "messages": [
                {"role": "system", "content": %s},
                {"role": "user",   "content": %s}
              ],
              "temperature": %s,
              "top_p": 0.9,
              "max_tokens": %d,
              "response_format": {"type": "json_object"}
            }
            """.formatted(
                toJsonString(buildSystemMessage()),
                toJsonString(prompt),
                String.format(java.util.Locale.ROOT, "%.2f", temperatureFor(req)),
                maxTokensFor(req));

        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = rest.postForEntity(GROQ_URL, entity, String.class);

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            return root.at("/choices/0/message/content").asText();
        } catch (Exception e) {
            throw new RuntimeException("Groq cavabı parse edilə bilmədi: " + e.getMessage());
        }
    }

    private String toJsonString(String text) {
        try {
            return objectMapper.writeValueAsString(text);
        } catch (Exception e) {
            return "\"" + text.replace("\"", "\\\"") + "\"";
        }
    }

    // ── Response parser ───────────────────────────────────────────────────────

    private List<BankQuestionRequest> parseResponse(String raw, GenerateQuestionsRequest req) {
        String cleaned = raw.trim();
        // Strip markdown fences if present
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
        }

        // Try to parse as {"questions": [...]}
        String arrayJson;
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode questionsNode = root.get("questions");
            if (questionsNode != null && questionsNode.isArray()) {
                arrayJson = questionsNode.toString();
            } else {
                // fallback: look for raw array
                int start = cleaned.indexOf('[');
                int end   = cleaned.lastIndexOf(']');
                if (start < 0 || end < 0) {
                    throw new RuntimeException("Cavabda JSON massivi tapılmadı. Cavab: " + raw.substring(0, Math.min(300, raw.length())));
                }
                arrayJson = cleaned.substring(start, end + 1);
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            int start = cleaned.indexOf('[');
            int end   = cleaned.lastIndexOf(']');
            if (start < 0 || end < 0) {
                throw new RuntimeException("JSON parse xətası. Cavab: " + raw.substring(0, Math.min(300, raw.length())));
            }
            arrayJson = cleaned.substring(start, end + 1);
        }

        String finalJson = arrayJson;

        try {
            List<Map<String, Object>> items = objectMapper.readValue(finalJson, new TypeReference<>() {});
            List<BankQuestionRequest> result = new ArrayList<>();

            for (Map<String, Object> item : items) {
                BankQuestionRequest q = new BankQuestionRequest();
                q.setSubjectId(req.getSubjectId());
                q.setContent((String) item.get("content"));
                q.setPoints(1.0);
                q.setOrderIndex(result.size());
                q.setTopic(req.getTopicName());

                if (req.getDifficulty() != null && !req.getDifficulty().isBlank()) {
                    try { q.setDifficulty(Difficulty.valueOf(req.getDifficulty())); } catch (Exception ignored) {}
                }

                String qt = req.getQuestionType();
                q.setQuestionType(mapQuestionType(qt));

                // Options (MCQ / MULTI_SELECT)
                if (item.containsKey("options")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> opts = (List<Map<String, Object>>) item.get("options");
                    List<BankOptionRequest> optList = new ArrayList<>();
                    for (Map<String, Object> opt : opts) {
                        BankOptionRequest o = new BankOptionRequest();
                        o.setContent((String) opt.get("text"));
                        Object ic = opt.get("isCorrect");
                        o.setIsCorrect(ic instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(ic)));
                        optList.add(o);
                    }
                    q.setOptions(optList);
                    // For MCQ build correctAnswer from first correct option
                    if (!"MULTI_SELECT".equals(qt)) {
                        optList.stream().filter(BankOptionRequest::getIsCorrect).findFirst()
                            .ifPresent(o -> q.setCorrectAnswer(o.getContent()));
                    }
                }

                // correctAnswer (OPEN_AUTO / FILL_IN_THE_BLANK)
                if (item.containsKey("correctAnswer")) {
                    q.setCorrectAnswer((String) item.get("correctAnswer"));
                }

                // Strict guard for the open question families: the AI is
                // explicitly told to emit `correctAnswer`, but it sometimes
                // omits it for FILL_IN_THE_BLANK and leaves the teacher with
                // a question that can't be auto-graded. As a fallback, try
                // to pluck the blank value from the question text — if the
                // teacher used the convention `... ___ ...` with the answer
                // appended after the blank we can't do anything; otherwise
                // skip the question rather than insert a half-broken one.
                String questionType = qt;
                boolean needsAnswer = "OPEN_AUTO".equals(questionType)
                        || "FILL_IN_THE_BLANK".equals(questionType);
                if (needsAnswer && (q.getCorrectAnswer() == null || q.getCorrectAnswer().isBlank())) {
                    log.warn("AI question {} skipped — no correctAnswer for {}", item, questionType);
                    continue;
                }

                q.setMatchingPairs(new ArrayList<>());
                result.add(q);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Suallar parse edilə bilmədi: " + e.getMessage() + ". Raw: " + raw.substring(0, Math.min(300, raw.length())));
        }
    }

    public List<BankQuestionRequest> generateExam(GenerateExamRequest req) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Groq API açarı konfiqurasiya edilməyib.");
        }
        List<BankQuestionRequest> all = new ArrayList<>();
        if (req.getTypeCounts() == null || req.getTypeCounts().isEmpty()) return all;

        // Total questions across all types — used to enforce coherence (no near-duplicates across batches).
        int totalRequested = req.getTypeCounts().values().stream().mapToInt(Integer::intValue).sum();

        // Stable ordering for deterministic generation order (MCQ first, then MULTI_SELECT, then opens).
        List<String> orderedTypes = new ArrayList<>(req.getTypeCounts().keySet());
        orderedTypes.sort(Comparator.comparingInt(this::typePriority));

        List<String> usedHints = new ArrayList<>();

        for (String type : orderedTypes) {
            int count = req.getTypeCounts().getOrDefault(type, 0);
            if (count <= 0) continue;

            GenerateQuestionsRequest qReq = new GenerateQuestionsRequest();
            qReq.setSubjectName(req.getSubjectName());
            // Append exam-level coherence hint so each batch knows it's part of a larger exam
            // and must avoid overlapping sub-topics with previously generated batches.
            qReq.setTopicName(buildExamScopedTopic(req.getTopicName(), totalRequested, usedHints));
            qReq.setDifficulty(req.getDifficulty() != null ? req.getDifficulty() : "MEDIUM");
            qReq.setQuestionType(type);
            qReq.setCount(Math.min(count, 15));

            List<BankQuestionRequest> generated = generateQuestions(qReq);
            all.addAll(generated);

            // Remember a few sub-topics from this batch so the next batch can be told to avoid them.
            for (int i = 0; i < Math.min(2, generated.size()); i++) {
                String c = generated.get(i).getContent();
                if (c != null && !c.isBlank()) {
                    usedHints.add(c.substring(0, Math.min(80, c.length())));
                }
            }
        }

        for (int i = 0; i < all.size(); i++) all.get(i).setOrderIndex(i);
        return all;
    }

    private int typePriority(String type) {
        return switch (type == null ? "" : type) {
            case "MCQ" -> 1;
            case "MULTI_SELECT" -> 2;
            case "FILL_IN_THE_BLANK" -> 3;
            case "OPEN_AUTO" -> 4;
            case "OPEN_MANUAL" -> 5;
            default -> 9;
        };
    }

    /**
     * For multi-type exams, fold the exam-level scope and previously generated stem hints
     * into the per-batch topic so the model can avoid near-duplicate coverage across types.
     */
    private String buildExamScopedTopic(String baseTopic, int totalQuestions, List<String> previousHints) {
        StringBuilder sb = new StringBuilder();
        if (baseTopic != null && !baseTopic.isBlank()) {
            sb.append(baseTopic.trim());
        }
        if (totalQuestions > 1) {
            if (sb.length() > 0) sb.append(" — ");
            sb.append("bu sual partiyası ").append(totalQuestions)
              .append(" suallıq vahid imtahanın bir hissəsidir; başqa sual tiplərində də suallar olacaq.");
        }
        if (!previousHints.isEmpty()) {
            sb.append(" Aşağıdakı stem-lərlə örtüşmə və ya yaxın təkrar QADAĞANDIR: ");
            for (int i = 0; i < previousHints.size(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append("\"").append(previousHints.get(i).replace("\"", "'")).append("\"");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private QuestionType mapQuestionType(String type) {
        if (type == null) return QuestionType.MCQ;
        return switch (type) {
            case "OPEN_AUTO"          -> QuestionType.OPEN_AUTO;
            case "OPEN_MANUAL"        -> QuestionType.OPEN_MANUAL;
            case "FILL_IN_THE_BLANK"  -> QuestionType.FILL_IN_THE_BLANK;
            case "MULTI_SELECT"       -> QuestionType.MULTI_SELECT;
            default                   -> QuestionType.MCQ;
        };
    }
}
