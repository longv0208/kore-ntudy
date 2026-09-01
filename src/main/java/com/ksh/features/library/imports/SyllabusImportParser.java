package com.ksh.features.library.imports;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses a compact chapter/lesson Excel syllabus without persisting a new table. */
@Component
public class SyllabusImportParser {

    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_ROWS = 500;

    public List<SyllabusRow> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn file Excel syllabus");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("File syllabus tối đa 5 MB");
        }
        String filename = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx") && !filename.endsWith(".xls")) {
            throw new IllegalArgumentException("Syllabus phải là file .xlsx hoặc .xls");
        }
        try {
            byte[] bytes = file.getBytes();
            ZipSecureFile.setMinInflateRatio(0.01d);
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                if (workbook.getNumberOfSheets() == 0) {
                    throw new IllegalArgumentException("File Excel không có sheet syllabus");
                }
                return parseSheet(workbook.getSheetAt(0));
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Không đọc được file Excel syllabus", exception);
        }
    }

    private static List<SyllabusRow> parseSheet(Sheet sheet) {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        List<SyllabusRow> rows = new ArrayList<>();
        Set<Integer> lessonNumbers = new HashSet<>();
        int lastRow = Math.min(sheet.getLastRowNum(), MAX_ROWS + 1);
        for (int index = 1; index <= lastRow; index++) {
            Row row = sheet.getRow(index);
            if (row == null) continue;
            String chapterNoText = cell(formatter, row, 0);
            String chapterTitle = cell(formatter, row, 1);
            String lessonNoText = cell(formatter, row, 2);
            String lessonTitle = cell(formatter, row, 3);
            if (chapterNoText.isBlank() && chapterTitle.isBlank()
                    && lessonNoText.isBlank() && lessonTitle.isBlank()) continue;
            int excelRow = index + 1;
            int chapterNumber = positiveInt(chapterNoText, "Số chương", excelRow);
            int lessonNumber = positiveInt(lessonNoText, "Số bài", excelRow);
            if (chapterTitle.isBlank() || lessonTitle.isBlank()) {
                throw new IllegalArgumentException(
                        "Dòng " + excelRow + " phải có đủ tên chương và tên bài học");
            }
            if (chapterTitle.length() > 180 || lessonTitle.length() > 280) {
                throw new IllegalArgumentException("Tên chương/bài ở dòng " + excelRow + " quá dài");
            }
            if (!lessonNumbers.add(lessonNumber)) {
                throw new IllegalArgumentException("Số bài " + lessonNumber + " bị lặp trong file");
            }
            rows.add(new SyllabusRow(chapterNumber, chapterTitle.trim(),
                    lessonNumber, lessonTitle.trim()));
        }
        if (sheet.getLastRowNum() > MAX_ROWS + 1) {
            throw new IllegalArgumentException("Syllabus tối đa " + MAX_ROWS + " bài học");
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "Không có dữ liệu. Cột A-D lần lượt là: Số chương, Tên chương, Số bài, Tên bài học");
        }
        rows.sort(java.util.Comparator.comparingInt(SyllabusRow::lessonNumber));
        return List.copyOf(rows);
    }

    private static String cell(DataFormatter formatter, Row row, int column) {
        return row.getCell(column) == null ? "" : formatter.formatCellValue(row.getCell(column)).trim();
    }

    private static int positiveInt(String value, String label, int row) {
        try {
            int parsed = Integer.parseInt(value.replaceFirst("\\.0+$", ""));
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " ở dòng " + row + " phải là số nguyên dương");
        }
    }

    public record SyllabusRow(int chapterNumber, String chapterTitle,
                              int lessonNumber, String lessonTitle) {
    }
}
