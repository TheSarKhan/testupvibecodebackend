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

    // Renamed from `groq.api-key` to `openai.api-key` so the env var
    // matches the provider it's actually pointing at. Keep both readable
    // by falling back to the old key when only the legacy var is set —
    // avoids breaking deployments that still ship `GROQ_API_KEY`.
    @Value("${openai.api-key:${groq.api-key:}}")
    private String apiKey;

    private static final String OPENAI_URL =
        "https://api.openai.com/v1/chat/completions";

    // Pinned to the dated GPT-4o release that supports structured outputs
    // (`response_format: json_schema`) — the unpinned `gpt-4o` alias can
    // silently rotate to a checkpoint without this feature.
    private static final String OPENAI_MODEL = "gpt-4o-2024-11-20";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<BankQuestionRequest> generateQuestions(GenerateQuestionsRequest req) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API açarı konfiqurasiya edilməyib. .env-də OPENAI_API_KEY təyin edin.");
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

    // Foreign-language fənn → questions must be written IN THAT LANGUAGE,
    // not Azerbaijani. Azərbaycan dili & Ədəbiyyat stay in Azerbaijani.
    // The map's value is the language name we tell the AI to write in.
    // Uses Map.ofEntries because Map.of caps at 10 K/V pairs.
    private static final java.util.Map<String, String> FOREIGN_LANGUAGE_SUBJECTS = java.util.Map.ofEntries(
        java.util.Map.entry("ingilis dili", "English"),
        java.util.Map.entry("english",      "English"),
        java.util.Map.entry("rus dili",     "Russian (русский язык)"),
        java.util.Map.entry("русский",      "Russian (русский язык)"),
        java.util.Map.entry("alman dili",   "German (Deutsch)"),
        java.util.Map.entry("deutsch",      "German (Deutsch)"),
        java.util.Map.entry("fransız dili", "French (français)"),
        java.util.Map.entry("french",       "French (français)"),
        java.util.Map.entry("türk dili",    "Turkish (Türkçe)"),
        java.util.Map.entry("ərəb dili",    "Arabic (العربية)"),
        java.util.Map.entry("ispan dili",   "Spanish (español)")
    );

    /** If the subject is a foreign language fənn, returns the language name to write the question in. */
    private String foreignLanguageFor(String subjectName) {
        if (subjectName == null) return null;
        String lower = subjectName.toLowerCase();
        for (var entry : FOREIGN_LANGUAGE_SUBJECTS.entrySet()) {
            if (lower.contains(entry.getKey())) return entry.getValue();
        }
        return null;
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
               You are a SENIOR Azerbaijani K-12 / university-prep exam author and subject-matter expert with 20+ years of \
               classroom experience designing high-stakes tests. You write questions that real teachers would use on a final exam: \
               pedagogically sound, factually airtight, age-appropriate, and calibrated to discriminate between students who truly \
               understand the material and those who only memorised the surface. Your reputation is built on ZERO factual errors.

               ── REASONING PROTOCOL (do this silently, do NOT output it) ──
               Before emitting each question, walk through this checklist in your head:
                 (a) Pick a sub-topic that hasn't been covered in this batch yet.
                 (b) Decide the cognitive target (recall / apply / analyze / evaluate).
                 (c) Draft the stem.
                 (d) SOLVE the question yourself end-to-end. Write the answer in your head — DO NOT include the work in the output.
                 (e) Stress-test the answer: is there any other interpretation? Is there a sneaky second-correct option? If yes, revise.
                 (f) Engineer 3 distractors that each capture a SPECIFIC, COMMON student mistake (off-by-one, wrong sign, swapped \
                     definitions, near-miss numeric, similar-sounding term). Discard any joke/absurd/filler distractor.
                 (g) Re-read the stem aloud (mentally) — does it telegraph the answer through grammar, length, specificity? If yes, fix.
                 (h) ONLY THEN emit the question as JSON. If at any step you weren't 100% sure of correctness, DISCARD and start over.

               ── OUTPUT CONTRACT — non-negotiable ──
               - Respond with ONE valid JSON object ONLY. No markdown fences, no prose, no preamble, no closing remarks.
               - Top-level key: "questions" → array.
               - Produce EXACTLY the requested count — never fewer, never more, never "approximately".
               - JSON STRING ESCAPING:
                 • LaTeX commands use a backslash (\\frac, \\sqrt). Inside a JSON string this backslash MUST be doubled, \
                   so the JSON source looks like "\\\\frac{1}{2}" (read literally: backslash, backslash, f, r, a, c, …). \
                   A single backslash followed by f/b/v/n/t/r/0 is a CONTROL character in JSON — emit it and "\\frac" becomes \
                   an invisible form-feed + "rac". Always double the backslash.
                 • Newlines inside strings: use \\n (the two-character escape), never a literal line break.
                 • Quotes inside strings: \\".
                 • Do NOT use Unicode escapes (\\uXXXX) — write Azerbaijani characters directly (ə, ş, ç, ı, ö, ü, ğ).

               ── QUALITY BAR — every question must satisfy ALL ──
               1. FACTUALLY CORRECT. Zero tolerance for errors. If uncertain, drop the question and pick a different sub-topic.
               2. UNAMBIGUOUS. Exactly one interpretation; exactly one correct answer (or the required count for MULTI_SELECT).
               3. NO GIVEAWAYS. Stem grammar/length must not leak the answer. The correct option must not be the obviously-longest \
                  or only-grammatically-fitting choice.
               4. PLAUSIBLE DISTRACTORS. Wrong options must reflect REAL, documentable student misconceptions. Each one should make a \
                  teacher think "yes, I've seen students pick that". Never use joke options, generic fillers, "Bütün yuxarıdakılar", \
                  "Heç biri", or near-duplicates.
               5. CALIBRATED LENGTH. Stem: 1–3 sentences. Options: short and PARALLEL — same grammatical structure, same approximate \
                  length, same level of specificity.
               6. DIVERSE BATCH. Across the batch, vary sub-topic, cognitive level, and surface form. No two questions should share \
                  a template. No "what is X?" then "what is Y?" with same shape.
               7. CULTURALLY GROUNDED. Use Azerbaijani names, places, and curriculum context where it fits naturally. Do not import \
                  Western examples unless the topic demands it (e.g. world history).
               8. CURRICULUM-ALIGNED. Stay within what an Azerbaijani teacher would actually teach at the stated level — no obscure \
                  trivia, no graduate-level esoterica.

               ── BANNED PATTERNS — automatic discard if present ──
               - "Aşağıdakılardan hansı düzgündür?" with one obvious-true option and three absurd fillers.
               - Options "Yuxarıdakıların hamısı", "Heç biri", "A və B", "B və C" etc.
               - Trick questions resolved by word-play rather than subject knowledge.
               - Stem that restates the correct answer verbatim.
               - Ugly numbers in school math (3.71428…, 17/23) — pick clean values that fall out of the computation.
               - Stem in one language, options in another.
               - Two questions in the same batch testing the same fact.

               ── FINAL VERIFICATION (silent) ──
               Re-scan the array you are about to emit. Count the questions — does it equal the requested number EXACTLY? Are all \
               LaTeX backslashes doubled in the JSON source? Did every MCQ end up with exactly the required number of correct \
               options? If anything is off, FIX IT before responding. The user will not see any of your reasoning — only the final JSON.""";
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

                ── LaTeX (KaTeX) QAYDALARI — ŞƏRTSİZ ──
                EVERY math expression — including bare numbers, variables, symbols — MUST be wrapped in $...$.
                JSON ESCAPING: a literal backslash in the rendered LaTeX command (\\frac, \\sqrt, \\pi, \\cdot, …)
                MUST appear in the JSON string as a DOUBLE backslash. In this prompt I write the JSON forms.

                FORMS (exactly as they must appear inside the JSON string value):
                  • Kəsr:        "$\\\\frac{a}{b}$"
                  • Kök:         "$\\\\sqrt{x}$"   (or "$\\\\sqrt[n]{x}$")
                  • Dərəcə:      "$x^{2}$"
                  • Alt indeks:  "$x_{1}$"
                  • Vurma:       "$a \\\\cdot b$"
                  • Yunan:       "$\\\\pi$", "$\\\\alpha$", "$\\\\theta$"
                  • Sonsuzluq:   "$\\\\infty$"
                  • Bərabər deyil:"$\\\\neq$"
                  • Vahidlər:    "$10\\\\,\\\\text{m/s}$", "$25^{\\\\circ}\\\\text{C}$"
                  • Funksiya:    "$f(x) = 2x^{2} - 3$"

                SELF-CHECK before emitting any math:
                  1. Every \\\\ in the source — count the backslashes. There must be TWO of them per LaTeX command.
                  2. Every $ has a matching closing $ on the same line.
                  3. Every \\\\begin{…} has a matching \\\\end{…}.
                  4. No bare \\frac, \\sqrt etc. outside of $...$.

                AVOID: matrices, multi-line integrals, complicated diagrams, \\begin{align} — keep math one-line, simple.""" : "";

        String typeRules;
        String exampleJson;

        if (isFill) {
            typeRules = """
                    SUAL TİPİ — BOŞLUQ DOLDURMA (FILL_IN_THE_BLANK):
                    - Cümlədə MƏHZ BİR `___` (üç alt xətt) boşluq qoy.
                    - Boşluqdan kənarda sual aydın və tam başa düşülən olsun.
                    - `correctAnswer` yalnız boşluğa düşən söz/rəqəm/ifadə olmalıdır (tam cümlə yox).
                    - Cavab QISA və birmənalı olsun (1-3 söz, və ya 1 ədəd).
                    - `distractors`: DƏQİQ 3 yanlış variant ver — şagird üçün inandırıcı, lakin tam səhv olsun.
                      • Hər biri düzgün cavabla EYNİ format, uzunluq və üslubda olmalı (kateqoriya, hal şəkilçisi, vahid eyni).
                      • TƏLƏBƏNİN EDƏCƏYİ KONKRET SƏHVƏ uyğun olmalı (qarışdırılan anlayış, near-miss ədəd, vahid səhvi, oxşar səslənən söz).
                      • Heç biri açıq absurd və ya düzgün cavabla eyni olmasın.""";
            exampleJson = isMath
                ? "{\"questions\":[{\"content\":\"$2x + 5 = 13$ tənliyində $x$ = ___\",\"correctAnswer\":\"$4$\",\"distractors\":[\"$8$\",\"$9$\",\"$3$\"]}]}"
                : "{\"questions\":[{\"content\":\"Azərbaycanın paytaxtı ___ şəhəridir.\",\"correctAnswer\":\"Bakı\",\"distractors\":[\"Gəncə\",\"Şamaxı\",\"Naxçıvan\"]}]}";
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

        // Foreign-language fənn (İngilis dili, Rus dili, ...) — the question stem,
        // options and answer must be in THAT language so students see authentic
        // material instead of an Azerbaijani translation. Azərbaycan dili /
        // Ədəbiyyat / native subjects keep the default Azerbaijani.
        String foreignLang = foreignLanguageFor(req.getSubjectName());
        String langLine = foreignLang != null
            ? "LANGUAGE / DİL: Write the ENTIRE question (stem, options, answer, distractors) in " + foreignLang + ". " +
              "DO NOT translate into Azerbaijani — the student is being tested ON this language. " +
              "Yalnız xahiş: bütün məzmun (sual mətni, variantlar, cavab, distraktorlar) " + foreignLang + " dilində olsun.\n"
            : "DİL: Azərbaycan dili (təmiz, müasir orfoqrafiya).\n";

        return ("FƏNN: " + req.getSubjectName() + "\n" +
                topicLine + "\n" +
                "ÇƏTİNLİK SƏVİYYƏSİ: " + diffRubric + "\n" +
                styleCue + "\n" +
                (diversityRule.isEmpty() ? "" : diversityRule + "\n") +
                langLine +
                "SUAL SAYI: DƏQİQ " + req.getCount() + " sual.\n" +
                latexNote + "\n\n" +
                typeRules + "\n\n" +
                "JSON SXEMİ:\n" +
                "{ \"questions\": [ { \"content\": string" +
                (isFill ? ", \"correctAnswer\": string, \"distractors\": [string, string, string]"
                        : isOpen ? ", \"correctAnswer\": string"
                        : ", \"options\": [{ \"text\": string, \"isCorrect\": boolean }, … 4 ədəd]") +
                " }, … " + req.getCount() + " ədəd ] }\n\n" +
                "STRUKTUR NÜMUNƏSİ (məzmunu kopyalama, yalnız format):\n" +
                exampleJson + "\n\n" +
                "── FINAL TASK ──\n" +
                "İndi yuxarıdakı bütün QAYDALARA tam əməl edərək " + req.getCount() +
                " yüksək keyfiyyətli, müəllimin bu gün dərsdə işlədə biləcəyi səviyyədə sual yarat. " +
                "Hər sualı emit etməzdən əvvəl SƏN SUALIN CAVABINI ÖZÜNDÜR YOXLAMA — düzgün cavabın həqiqətən düzgün, " +
                "distraktorların həqiqətən səhv olmasına əmin ol. ƏMİN OLMADIĞIN heç bir sualı çıxarma. " +
                "YALNIZ JSON cavab ver — şərh, başlıq, markdown YOX.");
    }

    // ── API call ──────────────────────────────────────────────────────────────

    /**
     * Subject-aware temperature, tuned for GPT-4o.
     * - Math / science: very low → deterministic, fewer hallucinated numbers.
     * - History: low → factual recall must stay accurate.
     * - Language / humanities: moderate → richer phrasing without losing precision.
     * GPT-4o is calibrated so 0.3–0.7 is the sweet spot; going above 0.8 noticeably
     * degrades reasoning quality, even on creative tasks.
     */
    private double temperatureFor(GenerateQuestionsRequest req) {
        if (isMathSubject(req.getSubjectName())) return 0.30;
        if (isScienceSubject(req.getSubjectName())) return 0.40;
        if (isHistorySubject(req.getSubjectName())) return 0.40;
        if (isLanguageSubject(req.getSubjectName())) return 0.60;
        return 0.50;
    }

    /**
     * Token budget. GPT-4o supports up to 16 384 output tokens; we cap below
     * that to leave headroom for the JSON schema overhead. Math questions
     * spend more tokens on LaTeX escaping so they get a larger per-question
     * allowance.
     */
    private int maxTokensFor(GenerateQuestionsRequest req) {
        int perQuestion = isMathSubject(req.getSubjectName()) ? 600 : 450;
        return Math.min(16000, 1200 + perQuestion * Math.max(1, req.getCount()));
    }

    private String callGroq(String prompt, GenerateQuestionsRequest req) {
        RestTemplate rest = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        // Structured Outputs (`response_format: json_schema`) constrains the
        // model to exactly one shape — wrong shape = automatic regenerate
        // inside OpenAI before the response even reaches us. Eliminates the
        // class of bugs where the model emits {"data": [...]} or wraps the
        // array in prose. The schema is question-type-specific so the model
        // can't put `options` on a FILL_IN_THE_BLANK or `distractors` on an MCQ.
        String schema = buildJsonSchema(req);

        String body = """
            {
              "model": "%s",
              "messages": [
                {"role": "system", "content": %s},
                {"role": "user",   "content": %s}
              ],
              "temperature": %s,
              "top_p": %s,
              "max_tokens": %d,
              "frequency_penalty": 0.2,
              "presence_penalty": 0.1,
              "response_format": %s
            }
            """.formatted(
                OPENAI_MODEL,
                toJsonString(buildSystemMessage()),
                toJsonString(prompt),
                String.format(java.util.Locale.ROOT, "%.2f", temperatureFor(req)),
                String.format(java.util.Locale.ROOT, "%.2f", topPFor(req)),
                maxTokensFor(req),
                schema);

        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = rest.postForEntity(OPENAI_URL, entity, String.class);

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            // OpenAI returns the model's refusal in `message.refusal` when
            // safety filters trip. Surface that instead of silently returning
            // empty content, which would otherwise look like a parse error.
            JsonNode refusal = root.at("/choices/0/message/refusal");
            if (refusal != null && !refusal.isMissingNode() && !refusal.isNull() && !refusal.asText().isBlank()) {
                throw new RuntimeException("OpenAI sualı rədd etdi: " + refusal.asText());
            }
            return root.at("/choices/0/message/content").asText();
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("OpenAI cavabı parse edilə bilmədi: " + e.getMessage());
        }
    }

    /**
     * Build a JSON Schema constraining the model's output to the exact shape
     * required for the question type being requested. OpenAI's Structured
     * Outputs feature uses this to guarantee the response matches.
     *
     * Strict mode requires every property to be in `required` and
     * `additionalProperties: false` everywhere — so we generate one of three
     * schemas (MCQ/MULTI_SELECT, FILL_IN_THE_BLANK, OPEN_AUTO) instead of one
     * "union" schema with optional fields.
     */
    private String buildJsonSchema(GenerateQuestionsRequest req) {
        String qType = req.getQuestionType();
        boolean isFill = "FILL_IN_THE_BLANK".equals(qType);
        boolean isOpen = "OPEN_AUTO".equals(qType) || isFill;

        String questionItemSchema;
        if (isFill) {
            // Note: OpenAI's strict-mode schema doesn't support minItems/maxItems —
            // count constraints live in the prompt text instead ("DƏQİQ 3 yanlış variant").
            questionItemSchema = """
                {
                  "type": "object",
                  "properties": {
                    "content":       { "type": "string", "description": "Question stem containing exactly one ___ (three underscores)." },
                    "correctAnswer": { "type": "string", "description": "The correct word/number/phrase that fills the blank. 1–3 words or one number." },
                    "distractors":   {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "Exactly 3 plausible-but-wrong answers, each matching the format/length of the correct answer."
                    }
                  },
                  "required": ["content", "correctAnswer", "distractors"],
                  "additionalProperties": false
                }
                """;
        } else if (isOpen) {
            questionItemSchema = """
                {
                  "type": "object",
                  "properties": {
                    "content":       { "type": "string", "description": "Question stem. Tələbə qısa cavab yazır." },
                    "correctAnswer": { "type": "string", "description": "The canonical correct answer — one word, one number, or one short phrase." }
                  },
                  "required": ["content", "correctAnswer"],
                  "additionalProperties": false
                }
                """;
        } else {
            // MCQ and MULTI_SELECT share the same shape; the SYSTEM prompt
            // and user prompt tell the model how many `isCorrect=true` options
            // to produce (1 for MCQ, 2 for MULTI_SELECT).
            questionItemSchema = """
                {
                  "type": "object",
                  "properties": {
                    "content": { "type": "string", "description": "Question stem." },
                    "options": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "text":      { "type": "string" },
                          "isCorrect": { "type": "boolean" }
                        },
                        "required": ["text", "isCorrect"],
                        "additionalProperties": false
                      }
                    }
                  },
                  "required": ["content", "options"],
                  "additionalProperties": false
                }
                """;
        }

        // Wrap the per-question schema in { questions: [...] }. The exact array
        // size constraint cannot live in the strict schema (OpenAI doesn't
        // support minItems/maxItems there) — the prompt text and the in-Java
        // truncation in `generateQuestions` enforce it.
        return ("""
            {
              "type": "json_schema",
              "json_schema": {
                "name": "exam_questions",
                "strict": true,
                "schema": {
                  "type": "object",
                  "properties": {
                    "questions": {
                      "type": "array",
                      "items": %s
                    }
                  },
                  "required": ["questions"],
                  "additionalProperties": false
                }
              }
            }
            """).formatted(questionItemSchema.trim());
    }

    /** Top-p tuned per subject family. Math/science needs low diversity, language/humanities benefits from breadth. */
    private double topPFor(GenerateQuestionsRequest req) {
        if (isMathSubject(req.getSubjectName())) return 0.85;
        if (isScienceSubject(req.getSubjectName())) return 0.88;
        if (isHistorySubject(req.getSubjectName())) return 0.88;
        return 0.92;
    }

    private String toJsonString(String text) {
        try {
            return objectMapper.writeValueAsString(text);
        } catch (Exception e) {
            return "\"" + text.replace("\"", "\\\"") + "\"";
        }
    }

    // ── Response parser ───────────────────────────────────────────────────────

    // The AI emits LaTeX commands like `\frac{1}{2}` inside JSON string values
    // with a single backslash (technically invalid JSON). Jackson interprets
    // those backslash-letter pairs as the C-style control character: `\f` →
    // form feed (U+000C), `\b` → backspace, `\v` → vertical tab, `\0` → null.
    // That leaves "rac{1}{2}" with an invisible control char prefix in the
    // string we save — both the validator ("Düstur xətalı" badge) and KaTeX
    // can't recover, so the teacher sees `rac14` instead of a rendered ½.
    // We restore the two-character `\X` form so the saved content has real
    // LaTeX commands the frontend can render directly.
    private static String restoreLatexControlChars(String s) {
        if (s == null || s.isEmpty()) return s;
        return s
            .replace("\f", "\\f")        // form feed → \f (\frac, \forall, …)
            .replace("", "\\v")    // vertical tab → \v (\vec, \varphi, …)
            .replace("\b", "\\b")        // backspace → \b (\beta, \binom, …)
            .replace("\0", "\\0");       // null → \0 (rare, but cheap to handle)
    }

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
                q.setContent(restoreLatexControlChars((String) item.get("content")));
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
                        o.setContent(restoreLatexControlChars((String) opt.get("text")));
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
                    q.setCorrectAnswer(restoreLatexControlChars((String) item.get("correctAnswer")));
                }

                // FILL_IN_THE_BLANK distractors: stash them as isCorrect=false
                // options so the frontend's chip pool gets both correct answer
                // and plausible wrong choices in one list.
                if ("FILL_IN_THE_BLANK".equals(qt) && item.containsKey("distractors")) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<Object> rawDistractors = (List<Object>) item.get("distractors");
                        List<BankOptionRequest> distractorOpts = q.getOptions() != null
                                ? new ArrayList<>(q.getOptions())
                                : new ArrayList<>();
                        for (Object d : rawDistractors) {
                            if (d == null) continue;
                            String text = restoreLatexControlChars(String.valueOf(d).trim());
                            if (text.isEmpty()) continue;
                            if (!hasValidLatex(text)) continue;
                            BankOptionRequest o = new BankOptionRequest();
                            o.setContent(text);
                            o.setIsCorrect(false);
                            distractorOpts.add(o);
                        }
                        if (!distractorOpts.isEmpty()) q.setOptions(distractorOpts);
                    } catch (Exception ignored) {}
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
            throw new IllegalStateException("OpenAI API açarı konfiqurasiya edilməyib.");
        }
        List<BankQuestionRequest> all = new ArrayList<>();
        if (req.getTypeCounts() == null || req.getTypeCounts().isEmpty()) return all;

        // Total questions across all types — used to enforce coherence (no near-duplicates across batches).
        int totalRequested = req.getTypeCounts().values().stream().mapToInt(Integer::intValue).sum();

        // Stable ordering for deterministic generation order (MCQ first, then MULTI_SELECT, then opens).
        List<String> orderedTypes = new ArrayList<>(req.getTypeCounts().keySet());
        orderedTypes.sort(Comparator.comparingInt(this::typePriority));

        // Normalise topic input: prefer the new multi-topic list, fall back to the
        // legacy single string (parsed for comma-separated values too, so a client
        // sending "Cəbr, Həndəsə" through the old field still gets multi-topic
        // behaviour). Blank entries are dropped.
        List<String> topics = new ArrayList<>();
        if (req.getTopicNames() != null) {
            for (String t : req.getTopicNames()) {
                if (t != null && !t.trim().isEmpty()) topics.add(t.trim());
            }
        }
        if (topics.isEmpty() && req.getTopicName() != null && !req.getTopicName().isBlank()) {
            for (String t : req.getTopicName().split(",")) {
                if (!t.trim().isEmpty()) topics.add(t.trim());
            }
        }

        List<String> usedHints = new ArrayList<>();

        for (String type : orderedTypes) {
            int count = req.getTypeCounts().getOrDefault(type, 0);
            if (count <= 0) continue;

            GenerateQuestionsRequest qReq = new GenerateQuestionsRequest();
            qReq.setSubjectName(req.getSubjectName());
            // Append exam-level coherence hint so each batch knows it's part of a larger exam
            // and must avoid overlapping sub-topics with previously generated batches.
            qReq.setTopicName(buildExamScopedTopic(topics, totalRequested, count, usedHints));
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
     *
     * Multi-topic handling:
     *   • 0 topics  → null (open-ended)
     *   • 1 topic   → that topic, unchanged
     *   • 2+ topics, batch=1 → instruct the model to either combine the topics
     *                          in a single question OR pick one of them
     *   • 2+ topics, batch>1 → instruct the model to distribute coverage across them
     */
    private String buildExamScopedTopic(List<String> topics, int totalQuestions, int batchCount, List<String> previousHints) {
        StringBuilder sb = new StringBuilder();
        if (topics != null && !topics.isEmpty()) {
            if (topics.size() == 1) {
                sb.append(topics.get(0));
            } else {
                String joined = String.join(", ", topics);
                if (batchCount <= 1) {
                    sb.append("Mövzular: ").append(joined)
                      .append(". Yalnız bir sual yaradılır — ya sayılan mövzuların hər birini bir suala birləşdir, ya da onların təsadüfi birini seç.");
                } else {
                    sb.append("Mövzular: ").append(joined)
                      .append(". Bu batch-da ").append(batchCount)
                      .append(" sual var — sualları mövzular arasında mümkün qədər bərabər paylaşdır; hər sual ən azı bir mövzunu əhatə etsin.");
                }
            }
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
