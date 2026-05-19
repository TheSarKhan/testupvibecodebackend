package az.testup.service;

import az.testup.entity.Exam;
import az.testup.entity.Option;
import az.testup.entity.Passage;
import az.testup.entity.Question;
import az.testup.enums.PassageType;
import az.testup.enums.QuestionType;
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

    // Match both $$...$$ (display) and $...$ (inline) — display first to avoid matching single $ in $$
    private static final java.util.regex.Pattern LATEX_PATTERN = java.util.regex.Pattern.compile("\\$\\$([^$]+)\\$\\$|\\$([^\\n$]+?)\\$");

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


            titleFont = new Font(bf, 20, Font.BOLD);
            metaFont = new Font(bf, 10, Font.ITALIC);
            qFont = new Font(bf, 12, Font.BOLD);
            oFont = new Font(bf, 11, Font.NORMAL);
        } catch (Exception e) {
            log.warn("Could not load any Unicode font, falling back to Helvetica.", e);
            titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20);
            metaFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Font.ITALIC);
            qFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
            oFont = FontFactory.getFont(FontFactory.HELVETICA, 11);
        }

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
        // (q.getPassage() == null) interleaved with passages (each passage
        // owns its own sub-list of questions). Sorting by orderIndex puts
        // them in the order the teacher arranged in the editor. Passage
        // text/audio is then rendered as a header above its questions —
        // previously the loop only walked exam.getQuestions() and skipped
        // passage content entirely, so passage-based questions appeared in
        // the PDF without the prose they were meant to be answered against.
        List<Question> allQuestions = exam.getQuestions();
        List<Question> standaloneQuestions = allQuestions.stream()
                .filter(q -> q.getPassage() == null)
                .sorted(Comparator.comparing(Question::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<Passage> passagesByOrder = exam.getPassages() == null
                ? List.<Passage>of()
                : exam.getPassages().stream()
                    .sorted(Comparator.comparing(Passage::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

        // Merge them, sorted by orderIndex. We tag each item with its order so
        // the final sort is stable for items that share an index.
        java.util.List<Object> ordered = new java.util.ArrayList<>();
        ordered.addAll(standaloneQuestions);
        ordered.addAll(passagesByOrder);
        ordered.sort((a, b) -> {
            int oa = (a instanceof Question) ? java.util.Objects.requireNonNullElse(((Question) a).getOrderIndex(), Integer.MAX_VALUE)
                                              : java.util.Objects.requireNonNullElse(((Passage) a).getOrderIndex(), Integer.MAX_VALUE);
            int ob = (b instanceof Question) ? java.util.Objects.requireNonNullElse(((Question) b).getOrderIndex(), Integer.MAX_VALUE)
                                              : java.util.Objects.requireNonNullElse(((Passage) b).getOrderIndex(), Integer.MAX_VALUE);
            return Integer.compare(oa, ob);
        });

        // Final flat list of (Question, displayNumber) pairs in render order,
        // produced by expanding each Passage into its child questions. The
        // answer-key page later iterates this same flat list so numbering
        // stays consistent.
        java.util.List<Question> renderOrder = new java.util.ArrayList<>();
        for (Object item : ordered) {
            if (item instanceof Passage p) {
                renderPassageHeader(document, p, qFont, oFont, metaFont);
                allQuestions.stream()
                        .filter(q -> q.getPassage() != null && q.getPassage().getId().equals(p.getId()))
                        .sorted(Comparator.comparing(Question::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder())))
                        .forEach(renderOrder::add);
            } else if (item instanceof Question q) {
                renderOrder.add(q);
            }
        }

        // Use renderOrder as the iteration list for the rest of the method.
        List<Question> questions = renderOrder;

        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);

            boolean contentBlank = isContentEffectivelyEmpty(q.getContent());
            boolean hasImage = q.getAttachedImage() != null && !q.getAttachedImage().trim().isEmpty();

            // Image-only question: render the number and image inside a
            // single-cell table with `setKeepTogether(true)`. This prevents
            // iText from page-breaking after the "5." line and pushing the
            // image to the next page (which produced a huge visual gap), and
            // also keeps padding tight so the number sits flush above the
            // image with no surrounding whitespace.
            if (contentBlank && hasImage) {
                PdfPTable tightBlock = new PdfPTable(1);
                tightBlock.setWidthPercentage(100);
                tightBlock.setSpacingBefore(15f);
                tightBlock.setSpacingAfter(5f);
                tightBlock.setKeepTogether(true);

                PdfPCell numberCell = new PdfPCell(new Phrase((i + 1) + ".", qFont));
                numberCell.setBorder(Rectangle.NO_BORDER);
                numberCell.setPaddingTop(0f);
                numberCell.setPaddingBottom(0f);
                numberCell.setPaddingLeft(0f);
                tightBlock.addCell(numberCell);

                try {
                    Image inlineImg = loadAndScaleImage(q.getAttachedImage());
                    if (inlineImg != null) {
                        PdfPCell imgCell = new PdfPCell();
                        imgCell.setBorder(Rectangle.NO_BORDER);
                        imgCell.setPaddingTop(0f);
                        imgCell.setPaddingBottom(0f);
                        imgCell.setPaddingLeft(0f);
                        imgCell.addElement(inlineImg);
                        tightBlock.addCell(imgCell);
                    }
                } catch (Exception e) {
                    log.warn("Could not embed image for image-only question", e);
                }
                document.add(tightBlock);
            } else {
                Paragraph qPara = new Paragraph();
                qPara.add(new Chunk((i + 1) + ". ", qFont));
                addRichText(qPara, q.getContent(), qFont);
                qPara.setSpacingBefore(15f);
                qPara.setSpacingAfter(5f);
                document.add(qPara);

                if (hasImage) {
                    addImageToDocument(document, q.getAttachedImage(), false);
                }
            }

            // Question Type Specifics
            if (q.getQuestionType() == QuestionType.MCQ || q.getQuestionType() == QuestionType.TRUE_FALSE || q.getQuestionType() == QuestionType.MULTI_SELECT) {
                List<Option> options = q.getOptions();
                options.sort(Comparator.comparing(Option::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder())));
                
                Font oLabelFont = derivedFont(oFont, true, false);
                char optionChar = 'A';
                for (Option opt : options) {
                    Paragraph oPara = new Paragraph();
                    oPara.add(new Chunk("   " + optionChar + ") ", oLabelFont));
                    String optContent = opt.getContent();
                    if (optContent == null || optContent.isBlank() || optContent.replaceAll("<[^>]+>", "").isBlank()) {
                        optContent = optionChar + " variantı";
                    }
                    addRichText(oPara, optContent, oFont);
                    oPara.setSpacingAfter(2f);
                    document.add(oPara);
                    
                    if (opt.getAttachedImage() != null && !opt.getAttachedImage().trim().isEmpty()) {
                        addImageToDocument(document, opt.getAttachedImage());
                    }
                    optionChar++;
                }
            } else if (q.getQuestionType() == QuestionType.OPEN_AUTO || q.getQuestionType() == QuestionType.OPEN_MANUAL) {
                Paragraph field = new Paragraph("   Cavab: __________________________________________________", oFont);
                field.setSpacingBefore(5f);
                document.add(field);
            } else if (q.getQuestionType() == QuestionType.MATCHING) {
                List<az.testup.entity.MatchingPair> pairs = q.getMatchingPairs();
                
                // Collect unique left items and right items
                java.util.List<az.testup.entity.MatchingPair> leftItems = pairs.stream()
                        .filter(p -> p.getLeftItem() != null || (p.getAttachedImageLeft() != null && !p.getAttachedImageLeft().isEmpty()))
                        .toList();
                java.util.List<az.testup.entity.MatchingPair> rightItems = pairs.stream()
                        .filter(p -> p.getRightItem() != null || (p.getAttachedImageRight() != null && !p.getAttachedImageRight().isEmpty()))
                        .toList();

                Table matchingTable = new Table(2);
                matchingTable.setBorder(Table.NO_BORDER);
                matchingTable.setWidth(100);
                matchingTable.setPadding(5);
                
                int maxRows = Math.max(leftItems.size(), rightItems.size());
                for (int m = 0; m < maxRows; m++) {
                    // Left Cell
                    Cell leftCell = new Cell();
                    leftCell.setBorder(Cell.NO_BORDER);
                    if (m < leftItems.size()) {
                        az.testup.entity.MatchingPair lp = leftItems.get(m);
                        Paragraph p = new Paragraph();
                        p.add(new Chunk((m + 1) + ". ", oFont));
                        addRichText(p, lp.getLeftItem(), oFont);
                        leftCell.add(p);
                        if (lp.getAttachedImageLeft() != null && !lp.getAttachedImageLeft().isEmpty()) {
                            addImageToCell(leftCell, lp.getAttachedImageLeft());
                        }
                    }
                    matchingTable.addCell(leftCell);

                    // Right Cell
                    Cell rightCell = new Cell();
                    rightCell.setBorder(Cell.NO_BORDER);
                    if (m < rightItems.size()) {
                        az.testup.entity.MatchingPair rp = rightItems.get(m);
                        Paragraph p = new Paragraph();
                        p.add(new Chunk((char)('A' + m) + ") ", oFont));
                        addRichText(p, rp.getRightItem(), oFont);
                        rightCell.add(p);
                        if (rp.getAttachedImageRight() != null && !rp.getAttachedImageRight().isEmpty()) {
                            addImageToCell(rightCell, rp.getAttachedImageRight());
                        }
                    }
                    matchingTable.addCell(rightCell);
                }
                document.add(matchingTable);
            } else if (q.getQuestionType() == QuestionType.FILL_IN_THE_BLANK) {


                // For fill-in-the-blank, we often show a list of choices
                List<Option> options = q.getOptions();
                if (options != null && !options.isEmpty()) {
                    Paragraph choiceHeader = new Paragraph("   Seçimlər: ", metaFont);
                    StringBuilder sb = new StringBuilder();
                    for (Option opt : options) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(opt.getContent());
                    }
                    choiceHeader.add(new Chunk(sb.toString(), oFont));
                    choiceHeader.setSpacingBefore(5f);
                    document.add(choiceHeader);
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
                if (ans != null && ans.startsWith("[")) {
                     // Try to format JSON array to simple comma separated string
                     try {
                         ans = ans.replace("[", "").replace("]", "").replace("\"", "").replace(",", ", ");
                     } catch (Exception e) {}
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
                int qNum = openIndices.get(idx)[0];
                String answer = openAnswers.get(idx);

                Paragraph item = new Paragraph();
                item.add(new Chunk(qNum + ". ", qFont));
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

    private void addRichText(Paragraph paragraph, String html, Font baseFont) {
        if (html == null) return;
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

            // Group 1: $$...$$ (display), Group 2: $...$ (inline)
            String latex = matcher.group(1) != null ? matcher.group(1).trim() : (matcher.group(2) != null ? matcher.group(2).trim() : "");
            boolean isDisplay = matcher.group(1) != null;

            if (!latex.isEmpty()) {
                try {
                    TeXFormula formula = new TeXFormula(latex);
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

    private void addImageToCell(Cell cell, String imageUrl) {
        try {
            Image img;
            if (imageUrl.startsWith("data:image")) {
                String base64Data = imageUrl.substring(imageUrl.indexOf(",") + 1);
                byte[] decodedBytes = java.util.Base64.getDecoder().decode(base64Data);
                img = Image.getInstance(decodedBytes);
            } else {
                img = Image.getInstance(imageUrl);
            }
            img.scaleToFit(maxWidthForCell(), 150);
            cell.add(img);
        } catch (Exception e) {
            log.warn("Could not add image to cell", e);
        }
    }

    private float maxWidthForCell() {
        return (PageSize.A4.getWidth() - 100) / 2;
    }

    /**
     * Render the passage header — title, body text (TEXT type) or audio
     * notice (LISTENING type), and an optional attached image — directly
     * into the document. The passage block sits above its child questions
     * so the student has the source material in front of them when
     * answering.
     */
    private void renderPassageHeader(Document document, Passage passage, Font qFont, Font oFont, Font metaFont) {
        try {
            boolean isText = passage.getPassageType() == PassageType.TEXT;
            String title = passage.getTitle();
            if (title == null || title.isBlank()) {
                title = isText ? "Mətn parçası" : "Dinləmə mətni";
            }
            Paragraph header = new Paragraph();
            header.add(new Chunk(title, qFont));
            header.setSpacingBefore(18f);
            header.setSpacingAfter(4f);
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
                Paragraph note = new Paragraph(
                        "[Dinləmə tapşırığı — audio onlayn imtahan versiyasında mövcuddur.]",
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
        float maxWidth = PageSize.A4.getWidth() - 100;
        if (img.getPlainWidth() > maxWidth) {
            float percentage = (maxWidth / img.getPlainWidth()) * 100;
            img.scalePercent(percentage);
        }
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

            // Scaled image to fit the page width
            float maxWidth = PageSize.A4.getWidth() - 100; // margin
            if (img.getPlainWidth() > maxWidth) {
                float percentage = (maxWidth / img.getPlainWidth()) * 100;
                img.scalePercent(percentage);
            }
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
        String stripped = html
            .replaceAll("(?i)<br\\s*/?>", "")
            .replaceAll("<[^>]+>", "")
            .replace("&nbsp;", " ")
            .trim();
        return stripped.isEmpty();
    }
}

