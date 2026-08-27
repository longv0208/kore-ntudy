package com.ksh.features.classes.imports.parser;

import com.ksh.features.classes.imports.controller.ImportStudentsController;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Builds the .xlsx template streamed by
 * {@link ImportStudentsController#downloadTemplate(Long)}.
 *
 * <p>The file contains a single sheet ("Sinh viên") with a bold header row,
 * 25 realistic sample data rows, and column widths sized for comfortable reading.
 *
 * <p>Extracted from the controller so the POI plumbing lives in one place and
 * keeps the controller method down to a handful of lines.
 */
@Component
public class ExcelTemplateBuilder {

    private static final String SHEET_NAME = "Sinh viên";

    private static final String[] HEADERS = {
            "Email", "MSSV", "Họ và tên", "Số điện thoại"
    };

    private static final String[][] SAMPLE_ROWS = {
            {"minh.anh.nguyen@example.com", "HE180001", "Nguyễn Minh Anh", "0901000001"},
            {"bao.chau.tran@example.com", "HE180002", "Trần Bảo Châu", "0901000002"},
            {"gia.han.le@example.com", "HE180003", "Lê Gia Hân", "0901000003"},
            {"duc.huy.pham@example.com", "HE180004", "Phạm Đức Huy", "0901000004"},
            {"khanh.linh.hoang@example.com", "HE180005", "Hoàng Khánh Linh", "0901000005"},
            {"quang.minh.vo@example.com", "HE180006", "Võ Quang Minh", "0901000006"},
            {"ngoc.anh.dang@example.com", "HE180007", "Đặng Ngọc Anh", "0901000007"},
            {"tuan.kiet.bui@example.com", "HE180008", "Bùi Tuấn Kiệt", "0901000008"},
            {"thu.ha.do@example.com", "HE180009", "Đỗ Thu Hà", "0901000009"},
            {"hoang.long.ngo@example.com", "HE180010", "Ngô Hoàng Long", "0901000010"},
            {"mai.phuong.duong@example.com", "HE180011", "Dương Mai Phương", "0901000011"},
            {"nhat.nam.ly@example.com", "HE180012", "Lý Nhật Nam", "0901000012"},
            {"thanh.thao.vu@example.com", "HE180013", "Vũ Thanh Thảo", "0901000013"},
            {"viet.anh.truong@example.com", "HE180014", "Trương Việt Anh", "0901000014"},
            {"yen.nhi.dinh@example.com", "HE180015", "Đinh Yến Nhi", "0901000015"},
            {"hai.dang.nguyen@example.com", "HE180016", "Nguyễn Hải Đăng", "0901000016"},
            {"kim.ngan.tran@example.com", "HE180017", "Trần Kim Ngân", "0901000017"},
            {"manh.hung.le@example.com", "HE180018", "Lê Mạnh Hùng", "0901000018"},
            {"phuong.thao.pham@example.com", "HE180019", "Phạm Phương Thảo", "0901000019"},
            {"quoc.bao.hoang@example.com", "HE180020", "Hoàng Quốc Bảo", "0901000020"},
            {"thuy.duong.vo@example.com", "HE180021", "Võ Thùy Dương", "0901000021"},
            {"trung.kien.dang@example.com", "HE180022", "Đặng Trung Kiên", "0901000022"},
            {"uyen.nhi.bui@example.com", "HE180023", "Bùi Uyên Nhi", "0901000023"},
            {"xuan.bach.do@example.com", "HE180024", "Đỗ Xuân Bách", "0901000024"},
            {"yen.linh.ngo@example.com", "HE180025", "Ngô Yến Linh", "0901000025"},
    };

    /** Approximate cell width in POI units (256 = one character at default font). */
    private static final int COLUMN_WIDTH = 256 * 24;

    /**
     * Builds the .xlsx workbook and returns it as a byte array. Callers are
     * expected to forward the result through an HTTP response without further
     * processing.
     *
     * @throws IOException if the workbook fails to serialize
     */
    public byte[] build() throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(SHEET_NAME);

            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
            }

            for (int r = 0; r < SAMPLE_ROWS.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < SAMPLE_ROWS[r].length; c++) {
                    row.createCell(c).setCellValue(SAMPLE_ROWS[r][c]);
                }
            }

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.setColumnWidth(i, COLUMN_WIDTH);
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }
}
