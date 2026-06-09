package az.testup.service;

import az.testup.dto.response.BankOptionResponse;
import az.testup.dto.response.BankQuestionResponse;
import az.testup.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BankExcelExportService {

    private static final String[] HEADERS = {
            "#", "Sual", "Tip", "Çətinlik", "Mövzu", "Sinif", "Bal",
            "Variantlar (düzgün ✓)", "Düzgün cavab", "Etiketlər", "Yaradılma tarixi"
    };

    public byte[] export(List<BankQuestionResponse> questions) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sual Bazası");

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.INDIGO.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle wrapStyle = wb.createCellStyle();
            wrapStyle.setWrapText(true);
            wrapStyle.setVerticalAlignment(VerticalAlignment.TOP);

            Row header = sheet.createRow(0);
            header.setHeightInPoints(22f);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (BankQuestionResponse q : questions) {
                Row row = sheet.createRow(rowIdx++);
                row.setHeightInPoints(40f);
                int col = 0;
                row.createCell(col++).setCellValue(rowIdx - 1);

                Cell content = row.createCell(col++);
                content.setCellValue(safe(q.content()));
                content.setCellStyle(wrapStyle);

                row.createCell(col++).setCellValue(q.questionType() == null ? "" : q.questionType().name());
                row.createCell(col++).setCellValue(q.difficulty() == null ? "" : q.difficulty().name());
                row.createCell(col++).setCellValue(safe(q.topic()));
                row.createCell(col++).setCellValue(safe(q.gradeLevel()));
                row.createCell(col++).setCellValue(q.points() == null ? 0 : q.points());

                Cell opts = row.createCell(col++);
                String optionsText = q.options() == null ? "" : q.options().stream()
                        .map(this::formatOption)
                        .collect(Collectors.joining(" | "));
                opts.setCellValue(optionsText);
                opts.setCellStyle(wrapStyle);

                row.createCell(col++).setCellValue(safe(q.correctAnswer()));
                row.createCell(col++).setCellValue(
                        q.tags() == null ? "" : String.join(", ", q.tags()));
                row.createCell(col).setCellValue(safe(q.createdAt()));
            }

            sheet.createFreezePane(0, 1);
            int[] widths = { 5, 60, 14, 12, 18, 12, 8, 50, 25, 22, 22 };
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            if (rowIdx > 1) {
                sheet.setAutoFilter(new CellRangeAddress(0, rowIdx - 1, 0, HEADERS.length - 1));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Question bank Excel export failed", e);
            throw new ServiceUnavailableException("Excel faylı yaradıla bilmədi. Yenidən cəhd edin.", e);
        }
    }

    private String formatOption(BankOptionResponse o) {
        String mark = Boolean.TRUE.equals(o.isCorrect()) ? "✓ " : "";
        return mark + safe(o.content());
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
