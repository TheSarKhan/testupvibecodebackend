package az.testup.service;

import az.testup.entity.Exam;
import az.testup.entity.Option;
import az.testup.entity.Passage;
import az.testup.entity.Question;
import az.testup.enums.PassageType;
import az.testup.enums.QuestionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Decode the JSON-array form of sampleAnswer into a human-readable comma
     * list. Each element comes out of Jackson already JSON-unescaped, so
     * literal backslash pairs (`\\int`) collapse to single `\int` and
     * downstream LaTeX rendering can actually parse the formula.
     */
    private String decodeFillInAnswers(String json) {
        try {
            java.util.List<?> parsed = JSON.readValue(json, java.util.List.class);
            StringBuilder sb = new StringBuilder();
            for (Object item : parsed) {
                if (item == null) continue;
                String s = item.toString();
                if (s.isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(s);
            }
            return sb.toString();
        } catch (Exception e) {
            // Malformed JSON — fall back to the naive strip so we don't blank
            // out the answer cell entirely.
            return json.replace("[", "").replace("]", "").replace("\"", "").replace(",", ", ");
        }
    }


    // Three flavours of LaTeX, in priority order:
    //   group 1 (…): math-node sentinel, injected by
    //     preprocessMathNodes() so editor-stored <span data-latex="…">
    //     blocks survive into addLineRichText with their LaTeX intact.
    //   group 2 ($$…$$): display-style LaTeX from copy-paste / AI imports.
    //   group 3 ($…$):  inline LaTeX from copy-paste / AI imports.
    private static final java.util.regex.Pattern LATEX_PATTERN = java.util.regex.Pattern.compile(
        "([^]+)|\\$\\$([^$]+)\\$\\$|\\$([^\\n$]+?)\\$"
    );

    // <span ... data-latex="…">…</span> — the editor's canonical math
    // storage. data-latex holds the raw LaTeX (HTML-attribute encoded);
    // the span body is KaTeX-rendered HTML we must throw away or it leaks
    // into the PDF as garbage glyphs.
    private static final java.util.regex.Pattern MATH_NODE_PATTERN = java.util.regex.Pattern.compile(
        "<span[^>]*\\bdata-latex\\s*=\\s*\"([^\"]*)\"[^>]*>.*?</span>",
        java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE
    );

    // \bigm/\Bigm/\biggm/\Biggm (the amsmath "medium spacing" delimiter-sizing
    // commands, e.g. "f(x)\bigm|_{x=3}") are valid LaTeX that the frontend's
    // KaTeX editor renders fine, but JLaTeXMath 1.0.7 (used for the PDF) has no
    // definition for the "m" variants — only \big/\Big/\bigg/\Bigg. Parsing
    // throws, and the WHOLE surrounding formula (however long) falls back to
    // raw "$$...$$" source text in the PDF. Longest-alternative-first so
    // "Biggm" isn't cut short by an earlier match on "Big".
    private static final java.util.regex.Pattern UNSUPPORTED_BIG_M_PATTERN = java.util.regex.Pattern.compile(
        "\\\\(Bigg|bigg|Big|big)m(?![A-Za-z])"
    );

    /**
     * Rewrite LaTeX constructs that JLaTeXMath can't parse into the closest
     * equivalent it supports, so one obscure command doesn't blank out an
     * otherwise-valid formula in the PDF.
     */
    private String sanitizeLatex(String latex) {
        return UNSUPPORTED_BIG_M_PATTERN.matcher(latex).replaceAll("\\\\$1");
    }

    public byte[] generateExamPdf(Exam exam) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 50, 50, 50, 50);
        PdfWriter.getInstance(document, out);

        document.open();

        // Fonts for Azerbaijani support
        Font titleFont;
        Font metaFont;
        Font qFont;
        Font oFont;
        
        try {
            BaseFont bf = null;

            // 1. Classpath font (bundled in jar — works on all platforms)
            try (java.io.InputStream is = getClass().getResourceAsStream("/fonts/DejaVuSans.ttf")) {
                if (is != null) {
                    byte[] fontBytes = is.readAllBytes();
                    bf = BaseFont.createFont("DejaVuSans.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, fontBytes, null);
                    log.info("Loaded font from classpath: /fonts/DejaVuSans.ttf");
                }
            } catch (Exception ignored) {}

            // 2. System fonts fallback
            if (bf == null) {
                String[] fontPaths = {
                    "C:\\Windows\\Fonts\\arial.ttf",
                    "C:\\Windows\\Fonts\\calibri.ttf",
                    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
                    "/usr/share/fonts/truetype/freefont/FreeSans.ttf"
                };
                for (String path : fontPaths) {
                    try {
                        bf = BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                        log.info("Loaded font from: " + path);
                        break;
                    } catch (Exception ignored) {}
                }
            }

            if (bf == null) {
                throw new IOException("Unicode font tapılmadı. /fonts/DejaVuSans.ttf faylını resources-a əlavə edin.");
            }


            // Slightly compact fonts (q 12→11, o 11→10) so more fits per page.
            titleFont = new Font(bf, 18, Font.BOLD);
            metaFont = new Font(bf, 9, Font.ITALIC);
            qFont = new Font(bf, 11, Font.BOLD);
            oFont = new Font(bf, 10, Font.NORMAL);
        } catch (Exception e) {
            log.warn("Could not load any Unicode font, falling back to Helvetica.", e);
            titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
            metaFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Font.ITALIC);
            qFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
            oFont = FontFactory.getFont(FontFactory.HELVETICA, 10);
        }

        // Platform logo (centered, above the title). Loaded from the classpath
        // so it ships with the JAR — no external file dependency at runtime.
        // Failures are non-fatal: the PDF still renders without it.
        renderLogo(document);

        // Title
        Paragraph title = new Paragraph(exam.getTitle(), titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(10f);
        document.add(title);

        // Metadata
        String subjects = exam.getSubjects() != null ? String.join(", ", exam.getSubjects()) : "";
        Paragraph meta = new Paragraph("Fənn: " + subjects + " | Müəllim: " + exam.getTeacher().getFullName(), metaFont);
        meta.setAlignment(Element.ALIGN_CENTER);
        meta.setSpacingAfter(20f);
        document.add(meta);

        document.add(new LineSeparator());
        document.add(new Paragraph(" "));

        // Build the display-ordered list of items: standalone questions
        // (q.getPassage() == null) interleaved with ALL passages — TEXT and
        // LISTENING alike — sorted purely by orderIndex. This matches the
        // teacher's editor view 1:1; previously LISTENING passages were
        // force-appended to the end which scrambled exams that intentionally
        // mixed reading and listening tasks in a specific order.
        List<Question> allQuestions = exam.getQuestions();
        List<Question> standaloneQuestions = allQuestions.stream()
                .filter(q -> q.getPassage() == null)
                .toList();
        List<Passage> allPassages = exam.getPassages() == null
                ? List.<Passage>of()
                : exam.getPassages();

        java.util.List<Object> ordered = new java.util.ArrayList<>();
        ordered.addAll(standaloneQuestions);
        ordered.addAll(allPassages);
        ordered.sort((a, b) -> {
            int oa = (a instanceof Question) ? java.util.Objects.requireNonNullElse(((Question) a).getOrderIndex(), Integer.MAX_VALUE)
                                              : java.util.Objects.requireNonNullElse(((Passage) a).getOrderIndex(), Integer.MAX_VALUE);
            int ob = (b instanceof Question) ? java.util.Objects.requireNonNullElse(((Question) b).getOrderIndex(), Integer.MAX_VALUE)
                                              : java.util.Objects.requireNonNullElse(((Passage) b).getOrderIndex(), Integer.MAX_VALUE);
            return Integer.compare(oa, ob);
        });

        // Build a flat render-script: passage entries (header to print) are
        // followed by their child questions immediately. Previously this loop
        // also called renderPassageHeader inline, which wrote the headers to
        // the document EAGERLY — long before the per-question render loop ran.
        // The effect: all passage headers piled up at the top of the PDF and
        // every question (passage children + standalones) followed in a flat
        // list, exactly the bug the user reported. We now record passages as
        // tasks in renderItems and let the question loop print headers at the
        // correct moment, right before each passage's first child.
        java.util.List<Object> renderItems = new java.util.ArrayList<>();
        java.util.List<Question> renderOrder = new java.util.ArrayList<>();
        for (Object item : ordered) {
            if (item instanceof Passage p) {
                renderItems.add(p);
                allQuestions.stream()
                        .filter(q -> q.getPassage() != null && q.getPassage().getId().equals(p.getId()))
                        .sorted(Comparator.comparing(Question::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder())))
                        .forEach(child -> {
                            renderItems.add(child);
                            renderOrder.add(child);
                        });
            } else if (item instanceof Question q) {
                renderItems.add(q);
                renderOrder.add(q);
            }
        }

        // renderOrder is the flat question list for the answer-key page (which
        // needs continuous global numbering). renderItems below is what we
        // iterate to print the question paper itself — passages and their
        // child questions are interleaved so headers print immediately above
        // their questions.
        List<Question> questions = renderOrder;

        // Multi-subject exam → print a section header at every subject change
        // so the student can tell where one fənn ends and the next begins.
        // Numbering stays GLOBAL (matches the on-screen exam where qNum runs
        // continuously across subjects); the header shows the subject name
        // plus the global question range for that section ("Suallar 5-12").
        // `subjectGroup == null` means "main section" which by convention is
        // exam.subjects[0]; we normalise so the first header gets a name too.
        List<String> examSubjects = exam.getSubjects();
        boolean multiSubject = examSubjects != null && examSubjects.size() > 1;
        String firstSubject = (examSubjects != null && !examSubjects.isEmpty())
                ? examSubjects.get(0) : null;

        // Resolve the effective subject of every render item ONCE, on the same
        // renderItems list the print loop walks. Previously the section ranges
        // were pre-computed on renderOrder (questions only) while the loop
        // detected subject changes on renderItems (passages included) — a
        // passage with a blank subjectGroup fell back to firstSubject, created
        // a phantom section boundary the range list never counted, and every
        // header after it was paired with the wrong "Suallar X-Y" range
        // (BUG-19). Single source of truth now: one list, one traversal.
        // A blank-subject passage inherits the subject of its first child
        // question (children always immediately follow their passage in
        // renderItems), so a passage can never split its own subject section.
        java.util.List<String> itemSubjects = new java.util.ArrayList<>(renderItems.size());
        if (multiSubject) {
            for (int idx = 0; idx < renderItems.size(); idx++) {
                Object it = renderItems.get(idx);
                String raw;
                if (it instanceof Passage p) {
                    raw = p.getSubjectGroup();
                    if ((raw == null || raw.isBlank())
                            && idx + 1 < renderItems.size()
                            && renderItems.get(idx + 1) instanceof Question child
                            && child.getPassage() != null
                            && p.getId().equals(child.getPassage().getId())) {
                        raw = child.getSubjectGroup();
                    }
                } else {
                    raw = ((Question) it).getSubjectGroup();
                }
                itemSubjects.add((raw != null && !raw.isBlank()) ? raw : firstSubject);
            }
        }

        String currentSubject = null;
        int qNum = 0;
        for (int itemIdx = 0; itemIdx < renderItems.size(); itemIdx++) {
            Object renderItem = renderItems.get(itemIdx);
            if (multiSubject) {
                String itemSubject = itemSubjects.get(itemIdx);
                if (itemSubject != null && !itemSubject.equals(currentSubject)) {
                    // Section start. Range = global numbers of this section's
                    // questions, found by scanning forward over the SAME list
                    // until the subject changes, counting only questions.
                    int sectionQuestions = 0;
                    for (int j = itemIdx; j < renderItems.size(); j++) {
                        if (!java.util.Objects.equals(itemSubjects.get(j), itemSubject)) break;
                        if (renderItems.get(j) instanceof Question) sectionQuestions++;
                    }
                    int[] range = sectionQuestions > 0
                            ? new int[]{qNum + 1, qNum + sectionQuestions}
                            : null;
                    renderSubjectHeader(document, itemSubject, range, qFont, metaFont);
                    currentSubject = itemSubject;
                }
            }
            if (renderItem instanceof Passage pas) {
                renderPassageHeader(document, pas, qFont, oFont, metaFont);
                continue;
            }
            Question q = (Question) renderItem;
            int i = qNum++;

            boolean contentBlank = isContentEffectivelyEmpty(q.getContent());
            boolean hasImage = q.getAttachedImage() != null && !q.getAttachedImage().trim().isEmpty();
            boolean hasOptionImage = q.getOptions() != null && q.getOptions().stream()
                    .anyMatch(o -> o.getAttachedImage() != null && !o.getAttachedImage().trim().isEmpty());
            // Only questions that carry an image need to be held together as a
            // single block (so the picture never gets separated from its text
            // across a page break). Text-only questions flow normally and pack
            // densely — forcing THEM to keep together just pushed half-fitting
            // questions to the next page and wasted whole pages of paper.
            boolean keepTogether = hasImage || hasOptionImage;

            // Collect every element of this question — stem, image, options /
            // answer area. Image questions are emitted as one keep-together
            // block (qWrap); text-only ones are streamed straight to the
            // document so they fill the page.
            java.util.List<Element> qElements = new java.util.ArrayList<>();

            // Image first: the picture is part of the question, so it leads and
            // the stem text sits beneath it. Image-only questions show just the
            // number under the picture. (Already bounded to the standard box.)
            if (hasImage) {
                try {
                    Image img = loadAndScaleImage(q.getAttachedImage());
                    if (img != null) {
                        img.setSpacingAfter(4f);
                        qElements.add(img);
                    }
                } catch (Exception e) {
                    log.warn("Could not embed question image", e);
                }
            }

            // Stem: number + question text (just the number for image-only
            // questions, where the picture IS the question).
            if (contentBlank && hasImage) {
                qElements.add(new Paragraph((i + 1) + ".", qFont));
            } else {
                Paragraph qPara = new Paragraph();
                qPara.add(new Chunk((i + 1) + ". ", qFont));
                addRichText(qPara, q.getContent(), qFont);
                qPara.setSpacingAfter(5f);
                qElements.add(qPara);
            }

            // Question Type Specifics
            if (q.getQuestionType() == QuestionType.MCQ || q.getQuestionType() == QuestionType.TRUE_FALSE || q.getQuestionType() == QuestionType.MULTI_SELECT) {
                List<Option> options = q.getOptions();
                options.sort(Comparator.comparing(Option::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder())));

                Font oLabelFont = derivedFont(oFont, true, false);
                boolean anyOptionImage = options.stream()
                        .anyMatch(o -> o.getAttachedImage() != null && !o.getAttachedImage().trim().isEmpty());

                if (anyOptionImage) {
                    // When options carry images, render each option (label + text
                    // + image) as one PdfPTable cell so the picture can never be
                    // laid out next to a different option's text — the bug where
                    // image-MCQ variants and their pictures got scrambled in the
                    // PDF (BUG-247). Same approach already used for MATCHING.
                    PdfPTable optTable = new PdfPTable(1);
                    try { optTable.setWidthPercentage(100f); } catch (Exception ignored) {}
                    optTable.setSpacingBefore(2f);
                    optTable.setSpacingAfter(2f);
                    char optionChar = 'A';
                    for (Option opt : options) {
                        optTable.addCell(buildOptionCell(optionChar, opt.getContent(), opt.getAttachedImage(), oLabelFont, oFont));
                        optionChar++;
                    }
                    qElements.add(optTable);
                } else {
                    // Text-only options: pack as many per row as fit. Very short
                    // options (typical for maths: "5", "3.14") go FOUR across, so
                    // a whole question's options take a single line; medium ones
                    // go two across; long ones drop to one per row so they don't
                    // wrap into a cramped block.
                    int longestOption = options.stream()
                            .map(o -> plainTextLength(o.getContent()))
                            .max(Integer::compareTo).orElse(0);
                    int columns;
                    if (longestOption <= OPTION_FOUR_COL_MAX_CHARS) columns = 4;
                    else if (longestOption <= OPTION_TWO_COL_MAX_CHARS) columns = 2;
                    else columns = 1;
                    // Never more columns than options.
                    columns = Math.min(columns, Math.max(1, options.size()));

                    PdfPTable optTable = new PdfPTable(columns);
                    try { optTable.setWidthPercentage(100f); } catch (Exception ignored) {}
                    optTable.setSpacingBefore(2f);
                    optTable.setSpacingAfter(2f);
                    char optionChar = 'A';
                    for (Option opt : options) {
                        optTable.addCell(buildTextOptionCell(optionChar, opt.getContent(), oLabelFont, oFont));
                        optionChar++;
                    }
                    // Pad the final row with empty cells so an incomplete last
                    // row still renders as a full grid row.
                    int remainder = options.size() % columns;
                    if (remainder != 0) {
                        for (int f = 0; f < columns - remainder; f++) {
                            PdfPCell filler = new PdfPCell();
                            filler.setBorder(Rectangle.NO_BORDER);
                            optTable.addCell(filler);
                        }
                    }
                    qElements.add(optTable);
                }
            } else if (q.getQuestionType() == QuestionType.OPEN_AUTO || q.getQuestionType() == QuestionType.OPEN_MANUAL) {
                Paragraph field = new Paragraph("   Cavab: __________________________________________________", oFont);
                field.setSpacingBefore(5f);
                qElements.add(field);
            } else if (q.getQuestionType() == QuestionType.MATCHING) {
                PdfPTable matchingTable = buildMatchingTable(q, oFont);
                if (matchingTable != null) qElements.add(matchingTable);
            } else if (q.getQuestionType() == QuestionType.FILL_IN_THE_BLANK) {
                // Choices list (e.g. "Seçimlər: x, y, z") — render every option
                // individually through addRichText so LaTeX ($x^2$) inside an
                // option still renders as math, not as raw $ symbols.
                List<Option> options = q.getOptions();
                if (options != null && !options.isEmpty()) {
                    Paragraph choiceHeader = new Paragraph();
                    choiceHeader.add(new Chunk("   Seçimlər: ", derivedFont(metaFont, true, false)));
                    boolean first = true;
                    for (Option opt : options) {
                        String optContent = opt.getContent();
                        if (optContent == null || isContentEffectivelyEmpty(optContent)) continue;
                        if (!first) choiceHeader.add(new Chunk(", ", oFont));
                        addRichText(choiceHeader, optContent, oFont);
                        first = false;
                    }
                    choiceHeader.setSpacingBefore(5f);
                    qElements.add(choiceHeader);
                }
                // Always give the student a blank to write the answer on. The
                // `___` placeholders inside the question content stay where the
                // teacher put them (rendered as-is by addRichText above); this
                // line below is the dedicated answer slot.
                Paragraph field = new Paragraph("   Cavab: __________________________________________________", oFont);
                field.setSpacingBefore(5f);
                qElements.add(field);
            }

            if (keepTogether) {
                // Image question → one keep-together block: a single-cell table
                // that iText moves to the next page as a unit when it doesn't
                // fit, so the text + image + options never get separated.
                PdfPCell qCell = new PdfPCell();
                qCell.setBorder(Rectangle.NO_BORDER);
                qCell.setPadding(0f);
                for (Element el : qElements) qCell.addElement(el);

                PdfPTable qWrap = new PdfPTable(1);
                qWrap.setWidthPercentage(100);
                qWrap.setKeepTogether(true);
                qWrap.setSpacingBefore(8f);
                qWrap.setSpacingAfter(3f);
                qWrap.addCell(qCell);
                document.add(qWrap);
            } else {
                // Text-only question → stream elements straight to the document
                // so consecutive questions fill the page (no wasted whitespace).
                // The stem may split across a page boundary, which is fine for
                // plain text. Give the stem the inter-question top spacing.
                boolean firstElement = true;
                for (Element el : qElements) {
                    if (firstElement && el instanceof Paragraph p) {
                        p.setSpacingBefore(8f);
                        firstElement = false;
                    }
                    document.add(el);
                }
            }
        }

        // --- Answer Key Page ---
        document.newPage();
        
        Paragraph akHeader = new Paragraph("Düzgün Cavablar", titleFont);
        akHeader.setAlignment(Element.ALIGN_CENTER);
        akHeader.setSpacingAfter(20f);
        document.add(akHeader);

        Table table = new Table(5); // 5 columns for answers
        table.setBorderWidth(1);
        table.setWidth(100);
        table.setPadding(5);

        // Collect open-ended answers to render below the grid (they don't fit narrow cells)
        java.util.List<int[]> openIndices = new java.util.ArrayList<>(); // i -> question index
        java.util.List<String> openAnswers = new java.util.ArrayList<>();

        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            String ans = "-";
            boolean isOpen = q.getQuestionType() == QuestionType.OPEN_AUTO || q.getQuestionType() == QuestionType.OPEN_MANUAL;

            if (q.getQuestionType() == QuestionType.MCQ || q.getQuestionType() == QuestionType.TRUE_FALSE || q.getQuestionType() == QuestionType.MULTI_SELECT) {
                List<Option> options = q.getOptions();
                options.sort(Comparator.comparing(Option::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder())));
                char optionChar = 'A';
                for (Option opt : options) {
                    if (opt.getIsCorrect()) {
                        ans = String.valueOf(optionChar);
                        break;
                    }
                    optionChar++;
                }
            } else if (q.getQuestionType() == QuestionType.MATCHING) {
                List<az.testup.entity.MatchingPair> pairs = q.getMatchingPairs();
                java.util.List<String> lefts = pairs.stream().map(az.testup.entity.MatchingPair::getLeftItem).filter(java.util.Objects::nonNull).distinct().toList();
                java.util.List<String> rights = pairs.stream().map(az.testup.entity.MatchingPair::getRightItem).filter(java.util.Objects::nonNull).distinct().toList();

                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < lefts.size(); j++) {
                    String left = lefts.get(j);
                    // Find which right item matches this left item
                    for (int k = 0; k < rights.size(); k++) {
                        String right = rights.get(k);
                        boolean isMatch = pairs.stream().anyMatch(p -> left.equals(p.getLeftItem()) && right.equals(p.getRightItem()));
                        if (isMatch) {
                            if (sb.length() > 0) sb.append(", ");
                            sb.append((j + 1)).append("-").append((char)('A' + k));
                        }
                    }
                }
                ans = sb.toString();
            } else if (q.getQuestionType() == QuestionType.FILL_IN_THE_BLANK) {
                ans = q.getCorrectAnswer() != null ? q.getCorrectAnswer() : q.getSampleAnswer();
                // sampleAnswer is stored as a JSON array (e.g. ["x^2","$$\\int...$$"]).
                // Old code stripped [ ] " with naive string replace, which left
                // JSON-escaped \\ pairs intact — JLaTeXMath then failed to parse
                // \\int and fell back to printing the whole $$..$$ block as raw
                // text. Parse the JSON properly so backslashes collapse to one.
                if (ans != null && ans.startsWith("[")) {
                    ans = decodeFillInAnswers(ans);
                }
            } else if (isOpen) {
                String raw = q.getCorrectAnswer() != null ? q.getCorrectAnswer() : q.getSampleAnswer();
                if (raw != null && !isContentEffectivelyEmpty(raw)) {
                    openIndices.add(new int[]{ i + 1 });
                    openAnswers.add(raw);
                    ans = "↓"; // pointer to section below
                } else {
                    ans = q.getQuestionType() == QuestionType.OPEN_MANUAL ? "Əl ilə" : "Açıq";
                }
            } else {
                ans = q.getCorrectAnswer() != null ? q.getCorrectAnswer() : (q.getSampleAnswer() != null ? q.getSampleAnswer() : "Açıq");
            }


            Paragraph p = new Paragraph();
            p.add(new Chunk((i + 1) + ": ", oFont));
            addRichText(p, ans, oFont);
            Cell cell = new Cell(p);
            table.addCell(cell);
        }


        int rem = questions.size() % 5;
        if (rem != 0) {
            for (int k = 0; k < (5 - rem); k++) {
                table.addCell(new Cell(""));
            }
        }

        document.add(table);

        // --- Open-ended answers (full width, below the grid) ---
        if (!openAnswers.isEmpty()) {
            Paragraph openHeader = new Paragraph("Açıq sualların cavabları", qFont);
            openHeader.setSpacingBefore(20f);
            openHeader.setSpacingAfter(10f);
            document.add(openHeader);

            for (int idx = 0; idx < openAnswers.size(); idx++) {
                int openQNum = openIndices.get(idx)[0];
                String answer = openAnswers.get(idx);

                Paragraph item = new Paragraph();
                item.add(new Chunk(openQNum + ". ", qFont));
                addRichText(item, answer, oFont);
                item.setSpacingAfter(8f);
                document.add(item);
            }
        }

        document.close();
        return out.toByteArray();
    }

    private String decodeHtmlEntities(String text) {
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
    }

    private Font derivedFont(Font base, boolean bold, boolean italic) {
        int style = Font.NORMAL;
        if (bold && italic) style = Font.BOLDITALIC;
        else if (bold) style = Font.BOLD;
        else if (italic) style = Font.ITALIC;
        if (base.getBaseFont() != null) {
            return new Font(base.getBaseFont(), base.getSize(), style);
        }
        return new Font(base.getFamily(), base.getSize(), style, base.getColor());
    }

    private static final java.util.regex.Pattern HTML_TAG_PATTERN =
        java.util.regex.Pattern.compile("(?i)<(/?)\\s*(strong|b|em|i|u|s|strike|del|br)(?:\\s[^>]*)?>|<[^>]+>");

    /**
     * Replace each `<span data-latex="…">…</span>` block with a sentinel
     * `<latex>` so the downstream renderer (addLineRichText)
     * can spot it and emit a JLaTeXMath bitmap. The inner KaTeX-rendered
     * HTML is discarded — keeping it would just paint mangled glyphs.
     */
    private String preprocessMathNodes(String html) {
        if (html == null || html.indexOf("data-latex") < 0) return html;
        java.util.regex.Matcher m = MATH_NODE_PATTERN.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String latex = decodeHtmlEntities(m.group(1) == null ? "" : m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("" + latex + ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void addRichText(Paragraph paragraph, String html, Font baseFont) {
        if (html == null) return;
        html = preprocessMathNodes(html);
        java.util.regex.Matcher m = HTML_TAG_PATTERN.matcher(html);
        int bold = (baseFont.getStyle() & Font.BOLD) != 0 ? 1 : 0;
        int italic = (baseFont.getStyle() & Font.ITALIC) != 0 ? 1 : 0;
        int underline = 0;
        int strike = 0;
        int lastEnd = 0;
        StringBuilder pending = new StringBuilder();

        while (m.find()) {
            pending.append(html, lastEnd, m.start());
            String closing = m.group(1);
            String tag = m.group(2);

            if (tag != null) {
                String tl = tag.toLowerCase();
                if (tl.equals("br")) {
                    flushText(paragraph, pending.toString(), baseFont, bold, italic, underline, strike);
                    pending.setLength(0);
                    paragraph.add(Chunk.NEWLINE);
                } else {
                    flushText(paragraph, pending.toString(), baseFont, bold, italic, underline, strike);
                    pending.setLength(0);
                    boolean isClose = !closing.isEmpty();
                    if (tl.equals("strong") || tl.equals("b")) bold += isClose ? -1 : 1;
                    else if (tl.equals("em") || tl.equals("i")) italic += isClose ? -1 : 1;
                    else if (tl.equals("u")) underline += isClose ? -1 : 1;
                    else if (tl.equals("s") || tl.equals("strike") || tl.equals("del")) strike += isClose ? -1 : 1;
                }
            }
            // unknown tags: just skip
            lastEnd = m.end();
        }
        pending.append(html, lastEnd, html.length());
        flushText(paragraph, pending.toString(), baseFont, bold, italic, underline, strike);
    }

    private void flushText(Paragraph paragraph, String raw, Font baseFont, int bold, int italic, int underline, int strike) {
        if (raw.isEmpty()) return;
        String text = decodeHtmlEntities(raw);
        String[] lines = text.split("\n", -1);
        Font f = derivedFont(baseFont, bold > 0, italic > 0);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isEmpty()) addLineRichText(paragraph, lines[i], f, bold > 0, underline > 0, strike > 0);
            if (i < lines.length - 1) paragraph.add(Chunk.NEWLINE);
        }
    }

    private Chunk styleChunk(Chunk chunk, Font font, boolean bold, boolean underline, boolean strike) {
        if (bold && font.getBaseFont() != null) {
            float strokeWidth = font.getSize() * 0.04f;
            chunk.setTextRenderMode(PdfContentByte.TEXT_RENDER_MODE_FILL_STROKE, strokeWidth, Color.BLACK);
        }
        if (underline) {
            chunk.setUnderline(Math.max(0.6f, font.getSize() * 0.06f), -font.getSize() * 0.15f);
        }
        if (strike) {
            chunk.setUnderline(Math.max(0.6f, font.getSize() * 0.06f), font.getSize() * 0.3f);
        }
        return chunk;
    }

    private void addLineRichText(Paragraph paragraph, String text, Font font, boolean bold, boolean underline, boolean strike) {
        java.util.regex.Matcher matcher = LATEX_PATTERN.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            String plainText = text.substring(lastEnd, matcher.start());
            if (!plainText.isEmpty()) {
                paragraph.add(styleChunk(new Chunk(plainText, font), font, bold, underline, strike));
            }

            // Group 1: math-node sentinel (inline), Group 2: $$…$$ (display), Group 3: $…$ (inline)
            String latex;
            boolean isDisplay;
            if (matcher.group(1) != null) {
                latex = matcher.group(1).trim();
                isDisplay = false;
            } else if (matcher.group(2) != null) {
                latex = matcher.group(2).trim();
                isDisplay = true;
            } else {
                latex = matcher.group(3) != null ? matcher.group(3).trim() : "";
                isDisplay = false;
            }

            if (!latex.isEmpty()) {
                try {
                    TeXFormula formula = new TeXFormula(sanitizeLatex(latex));
                    int style = isDisplay ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT;

                    // Render at 3x size for better quality, then scale down
                    float renderScale = 3.0f;
                    float renderFontSize = font.getSize() * renderScale;
                    TeXIcon icon = formula.createTeXIcon(style, (int) renderFontSize);

                    // Create high-quality BufferedImage
                    BufferedImage bimg = new BufferedImage(
                        (int)(icon.getIconWidth() * 1.2),
                        (int)(icon.getIconHeight() * 1.2),
                        BufferedImage.TYPE_INT_ARGB
                    );
                    Graphics2D g2 = bimg.createGraphics();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    g2.setColor(new Color(0, 0, 0, 0)); // transparent background
                    g2.fillRect(0, 0, bimg.getWidth(), bimg.getHeight());
                    g2.setColor(Color.BLACK);
                    icon.paintIcon(null, g2, (int)(icon.getIconWidth() * 0.1), (int)(icon.getIconHeight() * 0.1));
                    g2.dispose();

                    Image iTextImage = Image.getInstance(bimg, null);
                    // Scale down to actual size (dividing by renderScale)
                    float displayWidth = icon.getIconWidth() / renderScale;
                    float displayHeight = icon.getIconHeight() / renderScale;

                    // A long formula renders to a bitmap wider than the printable
                    // page. Added as an inline Chunk, iText neither wraps nor
                    // shrinks an over-wide image — it runs off the right margin
                    // and the tail of the formula is clipped. Bound the display
                    // width to the content area, scaling height in proportion so
                    // the whole formula stays on the page (smaller, but complete).
                    if (displayWidth > MAX_FORMULA_WIDTH) {
                        float shrink = MAX_FORMULA_WIDTH / displayWidth;
                        displayWidth *= shrink;
                        displayHeight *= shrink;
                    }
                    iTextImage.scaleAbsolute(displayWidth, displayHeight);

                    float yOffset = -displayHeight * 0.2f;
                    paragraph.add(new Chunk(iTextImage, 0, yOffset, true));
                } catch (Exception e) {
                    paragraph.add(styleChunk(new Chunk(matcher.group(0), font), font, bold, underline, strike));
                }
            }
            lastEnd = matcher.end();
        }
        if (lastEnd < text.length()) {
            paragraph.add(styleChunk(new Chunk(text.substring(lastEnd), font), font, bold, underline, strike));
        }
    }

    /**
     * Render a MATCHING question as a two-column PdfPTable. The legacy
     * com.lowagie.text.Table API was used previously but it doesn't lay out
     * inline LaTeX-image chunks or fitted bitmaps correctly inside cells —
     * left/right columns collided and $..$ formulas printed as raw $ signs.
     * PdfPCell delegates layout to ColumnText so addRichText (which can emit
     * inline image chunks for LaTeX) renders the same way it does in the
     * body flow.
     */
    private PdfPTable buildMatchingTable(Question q, Font oFont) {
        List<az.testup.entity.MatchingPair> pairs = q.getMatchingPairs();
        if (pairs == null || pairs.isEmpty()) return null;

        java.util.List<az.testup.entity.MatchingPair> leftItems = pairs.stream()
                .filter(p -> (p.getLeftItem() != null && !p.getLeftItem().isBlank())
                        || (p.getAttachedImageLeft() != null && !p.getAttachedImageLeft().isEmpty()))
                .toList();
        java.util.List<az.testup.entity.MatchingPair> rightItems = pairs.stream()
                .filter(p -> (p.getRightItem() != null && !p.getRightItem().isBlank())
                        || (p.getAttachedImageRight() != null && !p.getAttachedImageRight().isEmpty()))
                .toList();

        PdfPTable matchingTable = new PdfPTable(2);
        try {
            matchingTable.setWidthPercentage(95f);
            matchingTable.setWidths(new float[]{1f, 1f});
        } catch (Exception ignored) {}
        matchingTable.setSpacingBefore(8f);
        matchingTable.setSpacingAfter(8f);

        Font labelFont = derivedFont(oFont, true, false);
        int maxRows = Math.max(leftItems.size(), rightItems.size());
        for (int m = 0; m < maxRows; m++) {
            matchingTable.addCell(buildMatchingCell(
                    m < leftItems.size() ? leftItems.get(m).getLeftItem() : null,
                    m < leftItems.size() ? leftItems.get(m).getAttachedImageLeft() : null,
                    (m + 1) + ". ", labelFont, oFont));
            matchingTable.addCell(buildMatchingCell(
                    m < rightItems.size() ? rightItems.get(m).getRightItem() : null,
                    m < rightItems.size() ? rightItems.get(m).getAttachedImageRight() : null,
                    ((char) ('A' + m)) + ") ", labelFont, oFont));
        }
        return matchingTable;
    }

    private PdfPCell buildMatchingCell(String content, String imageUrl, String label, Font labelFont, Font oFont) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(6f);

        Paragraph p = new Paragraph();
        p.add(new Chunk(label, labelFont));
        if (content != null && !isContentEffectivelyEmpty(content)) {
            addRichText(p, content, oFont);
        }
        cell.addElement(p);

        if (imageUrl != null && !imageUrl.isBlank()) {
            try {
                Image img = loadImageForCell(imageUrl);
                if (img != null) cell.addElement(img);
            } catch (Exception e) {
                log.warn("Could not embed matching pair image", e);
            }
        }
        return cell;
    }

    /**
     * Build one MCQ option as a self-contained PdfPCell: the label (A) B) …),
     * the option text and, when present, its image — all in a single cell so
     * the layout engine keeps them together and an option's picture is never
     * rendered beside another option's text (BUG-247).
     */
    private PdfPCell buildOptionCell(char optionChar, String content, String imageUrl, Font labelFont, Font oFont) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(4f);

        Paragraph p = new Paragraph();
        p.add(new Chunk("   " + optionChar + ") ", labelFont));
        String optContent = content;
        if (optContent == null || optContent.isBlank() || optContent.replaceAll("<[^>]+>", "").isBlank()) {
            optContent = optionChar + " variantı";
        }
        addRichText(p, optContent, oFont);
        cell.addElement(p);

        if (imageUrl != null && !imageUrl.trim().isEmpty()) {
            try {
                Image img = loadImage(imageUrl, OPT_IMG_MAX_WIDTH, OPT_IMG_MAX_HEIGHT);
                if (img != null) cell.addElement(img);
            } catch (Exception e) {
                log.warn("Could not embed option image in PDF", e);
            }
        }
        return cell;
    }

    /**
     * Build a compact text-only MCQ option cell for the 2-column option layout
     * (label + text, no image). Tight padding so two columns of options stay
     * close together and save vertical space.
     */
    private PdfPCell buildTextOptionCell(char optionChar, String content, Font labelFont, Font oFont) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingTop(1f);
        cell.setPaddingBottom(1f);
        cell.setPaddingLeft(0f);
        cell.setPaddingRight(6f);

        Paragraph p = new Paragraph();
        p.add(new Chunk("   " + optionChar + ") ", labelFont));
        String optContent = content;
        if (optContent == null || optContent.isBlank() || optContent.replaceAll("<[^>]+>", "").isBlank()) {
            optContent = optionChar + " variantı";
        }
        addRichText(p, optContent, oFont);
        cell.addElement(p);
        return cell;
    }

    // Standard cap for any embedded image. Large uploads previously kept their
    // near-full-page width (only width was bounded, height never was), so a
    // tall picture dominated the page and spilled across page breaks. Bound
    // BOTH dimensions to a sensible box so images stay readable but compact.
    private static final float IMG_MAX_WIDTH = 240f;   // pt (~8.5 cm)
    private static final float IMG_MAX_HEIGHT = 240f;  // pt (~8.5 cm)

    // Option images get a much tighter box than the question body — at the
    // full IMG_MAX size a 4-option image question spanned more than a page.
    private static final float OPT_IMG_MAX_WIDTH = 150f;  // pt (~5.3 cm)
    private static final float OPT_IMG_MAX_HEIGHT = 100f; // pt (~3.5 cm)

    // Widest a rendered LaTeX bitmap may be before it's scaled down to fit:
    // the A4 content width (page minus the 50pt left+right margins). Keeps
    // long formulas on the page instead of overflowing the margin and being
    // clipped.
    private static final float MAX_FORMULA_WIDTH = PageSize.A4.getWidth() - 100f;

    /** Scale an image down (never up) to fit within maxWidth × maxHeight, keeping aspect ratio. */
    private void scaleToBox(Image img, float maxWidth, float maxHeight) {
        // Composite PdfPCells stretch an added Image to the FULL cell width
        // (Image.widthPercentage defaults to 100), silently overriding any
        // scalePercent/scaleAbsolute — that's how small option pictures blew
        // up to page width. 0 = render at the image's own (scaled) size.
        img.setWidthPercentage(0f);
        float w = img.getPlainWidth();
        float h = img.getPlainHeight();
        if (w <= 0 || h <= 0) return;
        float scale = Math.min(maxWidth / w, maxHeight / h);
        if (scale < 1f) img.scalePercent(scale * 100f);
    }

    /** Load (base64 data-URL or remote URL) + scale an image into the given box. */
    private Image loadImage(String imageUrl, float maxWidth, float maxHeight) throws Exception {
        if (imageUrl == null || imageUrl.isBlank()) return null;
        Image img;
        if (imageUrl.startsWith("data:image")) {
            String base64Data = imageUrl.substring(imageUrl.indexOf(",") + 1);
            img = Image.getInstance(java.util.Base64.getDecoder().decode(base64Data));
        } else {
            img = Image.getInstance(imageUrl);
        }
        // Bound to the standard box (respecting any tighter caller box).
        scaleToBox(img, Math.min(maxWidth, IMG_MAX_WIDTH), Math.min(maxHeight, IMG_MAX_HEIGHT));
        return img;
    }

    /** Load + scale an image to fit a half-page-wide PdfPCell. */
    private Image loadImageForCell(String imageUrl) throws Exception {
        if (imageUrl == null || imageUrl.isBlank()) return null;
        Image img;
        if (imageUrl.startsWith("data:image")) {
            String base64Data = imageUrl.substring(imageUrl.indexOf(",") + 1);
            img = Image.getInstance(java.util.Base64.getDecoder().decode(base64Data));
        } else {
            img = Image.getInstance(imageUrl);
        }
        float maxWidth = (PageSize.A4.getWidth() - 100) * 0.42f;
        scaleToBox(img, maxWidth, IMG_MAX_HEIGHT);
        return img;
    }

    /**
     * Render the passage header — title, body text (TEXT type) or audio
     * notice (LISTENING type), and an optional attached image — directly
     * into the document. The passage block sits above its child questions
     * so the student has the source material in front of them when
     * answering.
     */
    /**
     * Prints the platform logo centered at the top of the PDF. The image
     * lives in src/main/resources/static/logo.png and is loaded through the
     * classpath so it works whether the app is exploded on disk or running
     * from the fat JAR. Width is capped at ~120pt — large enough to be
     * recognisable, small enough to leave breathing room above the title.
     */
    private void renderLogo(Document document) {
        try (java.io.InputStream in = getClass().getResourceAsStream("/static/logo.png")) {
            if (in == null) return;
            byte[] bytes = in.readAllBytes();
            Image logo = Image.getInstance(bytes);
            scaleToBox(logo, 120f, 60f);
            logo.setAlignment(Image.ALIGN_CENTER);
            logo.setSpacingAfter(8f);
            document.add(logo);
        } catch (Exception e) {
            log.warn("Could not embed platform logo in PDF", e);
        }
    }

    /**
     * Section divider for multi-subject exams: subject name (centered, bold)
     * with the global question range next to it. Compact — one rule below,
     * no flanking empty paragraphs — so it reads as a section break without
     * eating half a page in vertical space. Numbering is intentionally NOT
     * reset here; the answer-key page relies on the same global question
     * order.
     */
    private void renderSubjectHeader(Document document, String subjectName, int[] range, Font qFont, Font metaFont) {
        try {
            Font headerFont = derivedFont(qFont, true, false);
            headerFont.setSize(qFont.getSize() + 1f);
            Paragraph header = new Paragraph();
            header.setAlignment(Element.ALIGN_CENTER);
            header.setSpacingBefore(2f);
            header.setSpacingAfter(6f);
            header.add(new Chunk(subjectName.toUpperCase(), headerFont));
            if (range != null && range[1] >= range[0]) {
                String rangeText = range[0] == range[1]
                        ? "  ·  Sual " + range[0]
                        : "  ·  Suallar " + range[0] + "-" + range[1];
                header.add(new Chunk(rangeText, derivedFont(metaFont, false, true)));
            }
            document.add(header);
        } catch (Exception e) {
            log.warn("Failed to render subject header in PDF", e);
        }
    }

    private void renderPassageHeader(Document document, Passage passage, Font qFont, Font oFont, Font metaFont) {
        try {
            boolean isText = passage.getPassageType() == PassageType.TEXT;
            // Always show a TYPE label first ("MƏTN PARÇASI" / "DİNLƏMƏ MƏTNİ")
            // so the two passage kinds are visually distinct even when the
            // teacher leaves the optional title blank. Previously both fell
            // back to a generic "Mətn parçası"/"Dinləmə mətni" title and the
            // student couldn't tell whether the audio task was actually a
            // reading task by accident.
            String typeLabel = isText ? "MƏTN PARÇASI" : "DİNLƏMƏ MƏTNİ";
            String customTitle = (passage.getTitle() != null && !passage.getTitle().isBlank())
                    ? passage.getTitle().trim() : null;

            Paragraph header = new Paragraph();
            header.setSpacingBefore(18f);
            header.setSpacingAfter(4f);
            header.add(new Chunk(typeLabel, derivedFont(metaFont, true, false)));
            if (customTitle != null) {
                header.add(new Chunk("  ·  ", metaFont));
                header.add(new Chunk(customTitle, qFont));
            }
            document.add(header);

            if (isText) {
                String body = passage.getTextContent();
                if (body != null && !body.isBlank()) {
                    Paragraph bodyPara = new Paragraph();
                    addRichText(bodyPara, body, oFont);
                    bodyPara.setSpacingAfter(6f);
                    document.add(bodyPara);
                }
                if (passage.getAttachedImage() != null && !passage.getAttachedImage().isBlank()) {
                    addImageToDocument(document, passage.getAttachedImage());
                }
            } else {
                // LISTENING: PDF can't play audio. Make the constraint obvious
                // so the student doesn't expect text here.
                Paragraph note = new Paragraph(
                        "🎧 Bu dinləmə tapşırığıdır — audio yalnız onlayn imtahan versiyasında səslənir.",
                        metaFont
                );
                note.setSpacingAfter(4f);
                document.add(note);
            }
        } catch (Exception e) {
            log.warn("Failed to render passage header in PDF", e);
        }
    }

    /**
     * Load an image (DataURL or remote URL) and scale it to fit the page
     * width. Returns null if the image couldn't be loaded — the caller
     * should fall back to a text representation. Extracted from
     * {@link #addImageToDocument} so cells in tightBlock tables can embed
     * the same Image instance without going through document.add().
     */
    private Image loadAndScaleImage(String imageUrl) throws Exception {
        if (imageUrl == null || imageUrl.isBlank()) return null;
        Image img;
        if (imageUrl.startsWith("data:image")) {
            String base64Data = imageUrl.substring(imageUrl.indexOf(",") + 1);
            byte[] decodedBytes = java.util.Base64.getDecoder().decode(base64Data);
            img = Image.getInstance(decodedBytes);
        } else {
            img = Image.getInstance(imageUrl);
        }
        scaleToBox(img, IMG_MAX_WIDTH, IMG_MAX_HEIGHT);
        return img;
    }

    private void addImageToDocument(Document document, String imageUrl) {
        addImageToDocument(document, imageUrl, false);
    }

    private void addImageToDocument(Document document, String imageUrl, boolean tight) {
        try {
            Image img;
            if (imageUrl.startsWith("data:image")) {
                // Handle base64 DataURL
                String base64Data = imageUrl.substring(imageUrl.indexOf(",") + 1);
                byte[] decodedBytes = java.util.Base64.getDecoder().decode(base64Data);
                img = Image.getInstance(decodedBytes);
            } else {
                img = Image.getInstance(imageUrl);
            }

            // Bound to the standard box so large uploads don't dominate the
            // page or get orphaned across a page break.
            scaleToBox(img, IMG_MAX_WIDTH, IMG_MAX_HEIGHT);
            // For image-only questions the label paragraph above already
            // collapses to a single line — start the image flush against it
            // (zero spacingBefore) so the number sits right on top of the
            // picture instead of floating in empty space.
            img.setSpacingBefore(tight ? 0f : 5f);
            img.setSpacingAfter(5f);
            document.add(img);
        } catch (Exception e) {
            log.warn("Could not add image to PDF: " + (imageUrl.length() > 100 ? imageUrl.substring(0, 100) + "..." : imageUrl), e);
        }
    }

    /** True when the question/option content is effectively empty after stripping HTML and whitespace. */
    private boolean isContentEffectivelyEmpty(String html) {
        if (html == null) return true;
        return plainTextLength(html) == 0;
    }

    // Option packing thresholds (plain-text length of the longest option):
    //   ≤ FOUR → 4 per row (tiny maths answers), ≤ TWO → 2 per row, else 1.
    private static final int OPTION_FOUR_COL_MAX_CHARS = 14;
    private static final int OPTION_TWO_COL_MAX_CHARS = 35;

    /** Plain-text length of HTML content after stripping tags/entities and trimming. */
    private int plainTextLength(String html) {
        if (html == null) return 0;
        return html
            .replaceAll("(?i)<br\\s*/?>", " ")
            .replaceAll("<[^>]+>", "")
            .replace("&nbsp;", " ")
            .trim()
            .length();
    }
}

