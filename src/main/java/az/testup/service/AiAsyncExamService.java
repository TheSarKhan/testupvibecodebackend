package az.testup.service;

import az.testup.dto.request.*;
import az.testup.dto.response.ExamResponse;
import az.testup.entity.User;
import az.testup.enums.ExamStatus;
import az.testup.enums.ExamType;
import az.testup.enums.ExamVisibility;
import az.testup.enums.NotificationType;
import az.testup.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Background AI exam generation. A 50–60 question exam takes minutes to generate
 * (each question type is a separate Gemini call); doing it inside the HTTP
 * request would blow past Cloudflare's ~100s proxy limit and return a 504. So
 * the controller validates limits, returns immediately, and hands the work to
 * this {@code @Async} method. When it finishes it saves the result as a DRAFT
 * exam and pushes a notification ("İmtahanınız hazırdır") which the navbar shows
 * as a toast and links straight to the draft.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAsyncExamService {

    private final GeminiService geminiService;
    private final ExamService examService;
    private final NotificationService notificationService;
    private final SubscriptionValidatorService subscriptionValidatorService;
    private final UserRepository userRepository;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Async
    public void generateExamInBackground(Long teacherId, GenerateExamRequest req, String title) {
        User teacher = userRepository.findById(teacherId).orElse(null);
        if (teacher == null) {
            log.warn("Async exam generation: teacher {} not found", teacherId);
            return;
        }
        try {
            List<BankQuestionRequest> generated = geminiService.generateExam(req);
            if (generated == null || generated.isEmpty()) {
                notificationService.send(teacher, "İmtahan yaradıla bilmədi",
                        "AI heç bir sual qaytarmadı. Mövzunu dəyişib yenidən cəhd edin.",
                        NotificationType.WARNING, null);
                return;
            }

            // Record AI usage now that questions actually came back.
            subscriptionValidatorService.recordAiQuestions(teacherId, generated.size());

            ExamRequest examRequest = buildDraftExamRequest(req, title, generated);
            ExamResponse saved = examService.createExam(examRequest, teacher);

            notificationService.send(teacher,
                    "İmtahanınız hazırdır ✅",
                    "\"" + saved.getTitle() + "\" qaralamalara əlavə olundu (" + generated.size()
                            + " sual). Redaktə edib yayımlaya bilərsiniz.",
                    NotificationType.EXAM_CREATED,
                    "/imtahanlar/edit/" + saved.getId());
            log.info("Async exam '{}' ({} questions) created for teacher {}",
                    saved.getTitle(), generated.size(), teacherId);
        } catch (Exception e) {
            log.error("Async exam generation failed for teacher {}: {}", teacherId, e.getMessage(), e);
            notificationService.send(teacher, "İmtahan yaradıla bilmədi",
                    "Generasiya zamanı xəta baş verdi. Yenidən cəhd edin.",
                    NotificationType.WARNING, null);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ExamRequest buildDraftExamRequest(GenerateExamRequest req, String title,
                                              List<BankQuestionRequest> generated) {
        String finalTitle = (title != null && !title.isBlank())
                ? title.trim()
                : "AI İmtahanı — " + req.getSubjectName() + " · " + LocalDate.now().format(DATE_FMT);

        List<QuestionRequest> questions = new ArrayList<>();
        for (int i = 0; i < generated.size(); i++) {
            questions.add(toQuestionRequest(generated.get(i), i));
        }

        return ExamRequest.builder()
                .title(finalTitle)
                .subjects(req.getSubjectName() != null ? List.of(req.getSubjectName()) : new ArrayList<>())
                .visibility(ExamVisibility.PRIVATE)
                .examType(ExamType.FREE)
                .status(ExamStatus.DRAFT)
                .questions(questions)
                .build();
    }

    private QuestionRequest toQuestionRequest(BankQuestionRequest b, int order) {
        List<OptionRequest> options = null;
        if (b.getOptions() != null && !b.getOptions().isEmpty()) {
            options = new ArrayList<>();
            for (BankOptionRequest o : b.getOptions()) {
                options.add(OptionRequest.builder()
                        .content(o.getContent() != null ? o.getContent() : "")
                        .isCorrect(o.getIsCorrect() != null && o.getIsCorrect())
                        .orderIndex(o.getOrderIndex())
                        .attachedImage(o.getAttachedImage())
                        .build());
            }
        }

        List<MatchingPairRequest> pairs = null;
        if (b.getMatchingPairs() != null && !b.getMatchingPairs().isEmpty()) {
            pairs = new ArrayList<>();
            for (BankMatchingPairRequest mp : b.getMatchingPairs()) {
                pairs.add(MatchingPairRequest.builder()
                        .leftItem(mp.getLeftItem())
                        .rightItem(mp.getRightItem())
                        .attachedImageLeft(mp.getAttachedImageLeft())
                        .attachedImageRight(mp.getAttachedImageRight())
                        .orderIndex(mp.getOrderIndex())
                        .build());
            }
        }

        return QuestionRequest.builder()
                .content(b.getContent())
                .attachedImage(b.getAttachedImage())
                .questionType(b.getQuestionType())
                .points(b.getPoints() != null ? b.getPoints() : 1.0)
                .orderIndex(order)
                .correctAnswer(b.getCorrectAnswer())
                .options(options)
                .matchingPairs(pairs)
                .build();
    }
}
