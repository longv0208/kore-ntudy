package com.ksh.features.library.imports;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Generates the stable four-column workbook accepted by {@link SyllabusImportParser}. */
@Component
public class SyllabusImportTemplate {

    private static final String[] HEADERS = {
            "Số chương", "Tên chương", "Số bài", "Tên bài học"
    };
    private static final Object[][] EXAMPLES = {
            {1, "Nhập môn", 1, "Giới thiệu môn học"},
            {1, "Nhập môn", 2, "Kiến thức nền tảng"},
            {2, "Vận dụng", 3, "Thực hành có hướng dẫn"},
            {2, "Vận dụng", 4, "Bài luyện tập tổng hợp"}
    };

    public byte[] build() throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Syllabus");
            CellStyle headerStyle = headerStyle(workbook);
            Row header = sheet.createRow(0);
            for (int column = 0; column < HEADERS.length; column++) {
                header.createCell(column).setCellValue(HEADERS[column]);
                header.getCell(column).setCellStyle(headerStyle);
            }
            for (int rowIndex = 0; rowIndex < EXAMPLES.length; rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                row.createCell(0).setCellValue((Integer) EXAMPLES[rowIndex][0]);
                row.createCell(1).setCellValue((String) EXAMPLES[rowIndex][1]);
                row.createCell(2).setCellValue((Integer) EXAMPLES[rowIndex][2]);
                row.createCell(3).setCellValue((String) EXAMPLES[rowIndex][3]);
            }
            sheet.createFreezePane(0, 1);
            sheet.setColumnWidth(0, 14 * 256);
            sheet.setColumnWidth(1, 34 * 256);
            sheet.setColumnWidth(2, 14 * 256);
            sheet.setColumnWidth(3, 46 * 256);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }
}
