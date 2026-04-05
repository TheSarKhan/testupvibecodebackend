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
        String rawResponse = callGemini(prompt);
        return parseResponse(rawResponse, req);
    }

    // ── Math subjects ─────────────────────────────────────────────────────────

    private static final java.util.Set<String> MATH_SUBJECTS = java.util.Set.of(
        "Riyaziyyat", "Fizika", "Kimya", "Həndəsə", "Cəbr", "Triqonometriya"
    );

    private boolean isMathSubject(String name) {
        if (name == null) return false;
        return MATH_SUBJECTS.stream().anyMatch(s -> name.toLowerCase().contains(s.toLowerCase()));
    }

    // ── Prompt builder ────────────────────────────────────────────────────────

    private String buildSystemMessage() {
        return "You are a professional exam question generator for Azerbaijani school curriculum. " +
               "You MUST respond with VALID JSON ONLY — no markdown fences, no explanations, no extra text. " +
               "The response must be a JSON object with a single key \"questions\" containing an array. " +
               "CRITICAL: In JSON strings containing LaTeX, escape all backslashes properly. " +
               "For example: to write \\frac in JSON, use the sequence: backslash backslash f r a c";
    }

    private String buildPrompt(GenerateQuestionsRequest req) {
        String diff = switch (req.getDifficulty() == null ? "" : req.getDifficulty()) {
            case "EASY" -> "Asan (sadə, birbaşa)";
            case "HARD" -> "Çətin (mürəkkəb, çoxaddımlı)";
            default     -> "Orta (kifayət qədər çətin)";
        };

        String topic = (req.getTopicName() != null && !req.getTopicName().isBlank())
            ? req.getTopicName() : "ümumi";

        boolean isOpen  = "OPEN_AUTO".equals(req.getQuestionType())
                       || "FILL_IN_THE_BLANK".equals(req.getQuestionType());
        boolean isMulti = "MULTI_SELECT".equals(req.getQuestionType());
        boolean isMath  = isMathSubject(req.getSubjectName());

        String latexNote = isMath ? """

            LaTeX qaydaları (KaTeX formatı):
            - İnline riyazi ifadə: $ifadə$ — məsələn: $x^2 + 3x - 4 = 0$
            - Kəsrlər: $\\frac{a}{b}$ — məsələn: $\\frac{3}{4}$ (JSON-DA: \\\\frac)
            - Kvadrat kök: $\\sqrt{x}$ — məsələn: $\\sqrt{16}$ (JSON-DA: \\\\sqrt)
            - Üst dərəcə: $x^{2}$, alt indeks: $x_{1}$
            - Vurmaq: $\\cdot$ — məsələn: $3 \\cdot x$ (JSON-DA: \\\\cdot)
            - MATRİSLƏR: $\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}$ — QEYDİ: kəsik üçün dörd arxa xətt, sonda \\end{} yazmalı
            - QAYD: JSON-da LaTeX komandasının hər bir backslash DOUBLE-ESCAPE olmalı
            - Bütün riyazi ifadə, ədəd, dəyişən LaTeX ilə yazılmalıdır
            """ : "";

        String typeInstruction;
        String exampleJson;

        if (isOpen) {
            typeInstruction = "FILL_IN_THE_BLANK".equals(req.getQuestionType())
                ? "Boşluq doldurma: cümlədə ___ (üç alt xətt) ilə boşluq qoyulur, correctAnswer boşluğun cavabıdır."
                : "Açıq sual: tələbə qısa cavab yazır, correctAnswer düzgün cavabdır.";

            exampleJson = isMath
                ? "{\"questions\":[{\"content\":\"$2x + 5 = 13$ bərabərliyini həll edin. $x$ = ___\",\"correctAnswer\":\"$x = 4$\"},{\"content\":\"$\\\\frac{3}{4}$ ədədinin $\\\\frac{2}{3}$ hissəsini tapın.\",\"correctAnswer\":\"$\\\\frac{1}{2}$\"},{\"content\":\"$2 \\\\cdot \\\\begin{pmatrix} 1 & 2 \\\\\\\\ 3 & 4 \\\\end{pmatrix} + \\\\begin{pmatrix} 1 & 1 \\\\\\\\ 1 & 1 \\\\end{pmatrix}$ matris əməliyyatının nəticəsinin 1-ci sətir 1-ci sütun elementini tapın.\",\"correctAnswer\":\"4\"}]}"
                : "{\"questions\":[{\"content\":\"Azərbaycanın paytaxtı hansı şəhərdir?\",\"correctAnswer\":\"Bakı\"},{\"content\":\"Su hansı kimyəvi formulla ifadə olunur?\",\"correctAnswer\":\"H₂O\"}]}";
        } else if (isMulti) {
            typeInstruction = "Çox seçimli: 4 variant olmalıdır, 2 variant düzgündür (isCorrect: true), 2 variant səhvdir (isCorrect: false).";
            exampleJson = isMath
                ? "{\"questions\":[{\"content\":\"Hansı ədədlər $x^2 = 16$ tənliyinin həllidir?\",\"options\":[{\"text\":\"$x = 4$\",\"isCorrect\":true},{\"text\":\"$x = -4$\",\"isCorrect\":true},{\"text\":\"$x = 8$\",\"isCorrect\":false},{\"text\":\"$x = 2$\",\"isCorrect\":false}]}]}"
                : "{\"questions\":[{\"content\":\"Hansılar Azərbaycan şəhərləridir?\",\"options\":[{\"text\":\"Gəncə\",\"isCorrect\":true},{\"text\":\"Şəki\",\"isCorrect\":true},{\"text\":\"Tiflis\",\"isCorrect\":false},{\"text\":\"Ankara\",\"isCorrect\":false}]}]}";
        } else {
            typeInstruction = "Test (MCQ): 4 variant olmalıdır, yalnız 1 düzgündür (isCorrect: true), qalan 3 səhvdir (isCorrect: false). Variantlar məntiqi, aldadıcı olmalıdır.";
            exampleJson = isMath
                ? "{\"questions\":[{\"content\":\"$3x - 7 = 8$ tənliyini həll edin.\",\"options\":[{\"text\":\"$x = 5$\",\"isCorrect\":true},{\"text\":\"$x = 3$\",\"isCorrect\":false},{\"text\":\"$x = 7$\",\"isCorrect\":false},{\"text\":\"$x = 1$\",\"isCorrect\":false}]},{\"content\":\"$\\\\frac{2}{3} + \\\\frac{1}{6}$ əməliyyatının nəticəsi nədir?\",\"options\":[{\"text\":\"$\\\\frac{5}{6}$\",\"isCorrect\":true},{\"text\":\"$\\\\frac{3}{9}$\",\"isCorrect\":false},{\"text\":\"$\\\\frac{1}{2}$\",\"isCorrect\":false},{\"text\":\"$\\\\frac{3}{6}$\",\"isCorrect\":false}]}]}"
                : "{\"questions\":[{\"content\":\"Azərbaycan müstəqilliyini neçənci ildə bərpa etdi?\",\"options\":[{\"text\":\"1991\",\"isCorrect\":true},{\"text\":\"1993\",\"isCorrect\":false},{\"text\":\"1988\",\"isCorrect\":false},{\"text\":\"2001\",\"isCorrect\":false}]}]}";
        }

        return ("Fənn: " + req.getSubjectName() + "\n" +
                "Mövzu: " + topic + "\n" +
                "Çətinlik: " + diff + "\n" +
                "Sual tipi: " + typeInstruction + "\n" +
                "Yaradılacaq sual sayı: " + req.getCount() + "\n" +
                latexNote +
                "\nQAYDALAR:\n" +
                "1. Bütün suallar Azərbaycan dilində olmalıdır\n" +
                "2. Variantlar real, məntiqi və aldadıcı olmalıdır — heç biri boş qoyulmamalıdır\n" +
                "3. Hər sualda tam " + (isOpen ? "\"content\" və \"correctAnswer\"" : "\"content\" və 4 variant olan \"options\"") + " olmalıdır\n" +
                "4. JSON obyektinin açarı \"questions\" olmalıdır\n" +
                (isMath ? "5. Bütün riyazi ifadələr KaTeX formatında yazılmalıdır ($...$)\n" +
                         "6. JSON Response-da backslash-lar DOUBLE-ESCAPE olmalı: content-də LaTeX komandaları tək backslash ilə yazılır\n" : "") +
                "\nNÜMUNƏ FORMAT (məzmunu kopyalama, yalnız strukturu izlə):\n" +
                exampleJson);
    }

    // ── API call ──────────────────────────────────────────────────────────────

    private String callGemini(String prompt) {
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
              "temperature": 0.7,
              "max_tokens": 4096,
              "response_format": {"type": "json_object"}
            }
            """.formatted(toJsonString(buildSystemMessage()), toJsonString(prompt));

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

        for (Map.Entry<String, Integer> entry : req.getTypeCounts().entrySet()) {
            int count = entry.getValue();
            if (count <= 0) continue;

            GenerateQuestionsRequest qReq = new GenerateQuestionsRequest();
            qReq.setSubjectName(req.getSubjectName());
            qReq.setTopicName(req.getTopicName());
            qReq.setDifficulty(req.getDifficulty() != null ? req.getDifficulty() : "MEDIUM");
            qReq.setQuestionType(entry.getKey());
            qReq.setCount(Math.min(count, 15));

            List<BankQuestionRequest> generated = generateQuestions(qReq);
            all.addAll(generated);
        }
        return all;
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
