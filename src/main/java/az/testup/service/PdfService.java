package az.testup.service;

import az.testup.entity.Exam;
import az.testup.entity.Option;
import az.testup.entity.Question;
import az.testup.enums.QuestionType;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.pdf.BaseFont;
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

        List<Question> questions = exam.getQuestions();
        questions.sort(Comparator.comparing(Question::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder())));

        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            
            // Question Content
            Paragraph qPara = new Paragraph();
            qPara.add(new Chunk((i + 1) + ". ", qFont));
            addRichText(qPara, q.getContent(), qFont);
            qPara.setSpacingBefore(15f);
            qPara.setSpacingAfter(5f);
            document.add(qPara);

            // Question Image
            if (q.getAttachedImage() != null && !q.getAttachedImage().trim().isEmpty()) {
                addImageToDocument(document, q.getAttachedImage());
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
        
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            String ans = "-";
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
        java.util.regex.Pattern.compile("(?i)<(/?)\\s*(strong|b|em|i|u|br)(?:\\s[^>]*)?>|<[^>]+>");

    private void addRichText(Paragraph paragraph, String html, Font baseFont) {
        if (html == null) return;
        java.util.regex.Matcher m = HTML_TAG_PATTERN.matcher(html);
        int bold = (baseFont.getStyle() & Font.BOLD) != 0 ? 1 : 0;
        int italic = (baseFont.getStyle() & Font.ITALIC) != 0 ? 1 : 0;
        int lastEnd = 0;
        StringBuilder pending = new StringBuilder();

        while (m.find()) {
            pending.append(html, lastEnd, m.start());
            String closing = m.group(1);
            String tag = m.group(2);

            if (tag != null) {
                String tl = tag.toLowerCase();
                if (tl.equals("br")) {
                    flushText(paragraph, pending.toString(), baseFont, bold, italic);
                    pending.setLength(0);
                    paragraph.add(Chunk.NEWLINE);
                } else {
                    flushText(paragraph, pending.toString(), baseFont, bold, italic);
                    pending.setLength(0);
                    boolean isClose = !closing.isEmpty();
                    if (tl.equals("strong") || tl.equals("b")) bold += isClose ? -1 : 1;
                    else if (tl.equals("em") || tl.equals("i")) italic += isClose ? -1 : 1;
                }
            }
            // unknown tags: just skip
            lastEnd = m.end();
        }
        pending.append(html, lastEnd, html.length());
        flushText(paragraph, pending.toString(), baseFont, bold, italic);
    }

    private void flushText(Paragraph paragraph, String raw, Font baseFont, int bold, int italic) {
        if (raw.isEmpty()) return;
        String text = decodeHtmlEntities(raw);
        String[] lines = text.split("\n", -1);
        Font f = derivedFont(baseFont, bold > 0, italic > 0);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isEmpty()) addLineRichText(paragraph, lines[i], f);
            if (i < lines.length - 1) paragraph.add(Chunk.NEWLINE);
        }
    }

    private void addLineRichText(Paragraph paragraph, String text, Font font) {
        java.util.regex.Matcher matcher = LATEX_PATTERN.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            String plainText = text.substring(lastEnd, matcher.start());
            if (!plainText.isEmpty()) {
                paragraph.add(new Chunk(plainText, font));
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
                    paragraph.add(new Chunk(matcher.group(0), font));
                }
            }
            lastEnd = matcher.end();
        }
        if (lastEnd < text.length()) {
            paragraph.add(new Chunk(text.substring(lastEnd), font));
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

    private void addImageToDocument(Document document, String imageUrl) {


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
            } else if (img.getPlainWidth() < 100) {
                // If the image is too small, maybe scale it up a bit? No, keep natural or cap it.
            }
            img.setSpacingBefore(5f);
            img.setSpacingAfter(5f);
            document.add(img);
        } catch (Exception e) {
            log.warn("Could not add image to PDF: " + (imageUrl.length() > 100 ? imageUrl.substring(0, 100) + "..." : imageUrl), e);
        }
    }
}

