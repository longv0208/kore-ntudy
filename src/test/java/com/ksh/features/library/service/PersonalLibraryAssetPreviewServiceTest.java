package com.ksh.features.library.service;

import com.ksh.features.library.dto.LibraryDtos.LibraryAssetDetail;
import com.ksh.features.library.service.LibraryService.OwnedAssetContent;
import com.ksh.features.storage.ObjectStorage;
import com.ksh.features.storage.StoredObject;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalLibraryAssetPreviewServiceTest {

    @Mock private LibraryService libraryService;
    @Mock private ObjectStorage objectStorage;

    private PersonalLibraryAssetPreviewService service;

    @BeforeEach
    void setUp() {
        service = new PersonalLibraryAssetPreviewService(libraryService, objectStorage);
    }

    @Test
    void pdf_preview_is_browser_inline_and_never_reads_the_whole_object() throws Exception {
        LibraryAssetDetail detail = detail(
                "outline.pdf", "application/pdf", "PDF", "pdf", 512L);
        when(libraryService.detail(7L, 11L)).thenReturn(detail);

        var preview = service.load(7L, 11L);

        assertThat(preview.previewKind()).isEqualTo("PDF");
        assertThat(preview.contentUrl()).isEqualTo(
                "/lecturer/library/assets/11/content");
        verify(objectStorage, never()).open("library/7/outline.pdf");
    }

    @Test
    void xlsx_preview_reads_a_bounded_first_sheet_internally() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Điểm lớp K21");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Họ tên");
            header.createCell(1).setCellValue("Điểm");
            var student = sheet.createRow(1);
            student.createCell(0).setCellValue("Nguyễn Văn A");
            student.createCell(1).setCellValue(9.5);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        LibraryAssetDetail detail = detail(
                "scores.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "XLSX", "excel", bytes.length);
        when(libraryService.detail(7L, 11L)).thenReturn(detail);
        when(libraryService.contentHandle(7L, 11L)).thenReturn(
                new OwnedAssetContent("library/7/scores.xlsx", "scores.xlsx",
                        detail.mimeType(), bytes.length));
        when(objectStorage.exists("library/7/scores.xlsx")).thenReturn(true);
        when(objectStorage.open("library/7/scores.xlsx")).thenReturn(
                new StoredObject(new ByteArrayInputStream(bytes), bytes.length, detail.mimeType()));

        var preview = service.load(7L, 11L);

        assertThat(preview.previewKind()).isEqualTo("SPREADSHEET");
        assertThat(preview.sheetName()).isEqualTo("Điểm lớp K21");
        assertThat(preview.tableRows()).containsExactly(
                List.of("Họ tên", "Điểm"), List.of("Nguyễn Văn A", "9,5"));
        verify(objectStorage).open("library/7/scores.xlsx");
    }

    @Test
    void preview_rejects_an_object_whose_reported_length_exceeds_the_safe_limit() throws Exception {
        long tooLarge = 10L * 1024L * 1024L + 1L;
        LibraryAssetDetail detail = detail(
                "scores.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "XLSX", "excel", 1024L);
        when(libraryService.detail(7L, 11L)).thenReturn(detail);
        when(libraryService.contentHandle(7L, 11L)).thenReturn(
                new OwnedAssetContent("library/7/scores.xlsx", "scores.xlsx",
                        detail.mimeType(), 1024L));
        when(objectStorage.exists("library/7/scores.xlsx")).thenReturn(true);
        when(objectStorage.open("library/7/scores.xlsx")).thenReturn(
                new StoredObject(new ByteArrayInputStream(new byte[0]), tooLarge, detail.mimeType()));

        var preview = service.load(7L, 11L);

        assertThat(preview.message()).contains("quá lớn");
    }

    @Test
    void preview_rejects_a_mismatched_stored_object_length() throws Exception {
        LibraryAssetDetail detail = detail(
                "scores.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "XLSX", "excel", 1024L);
        when(libraryService.detail(7L, 11L)).thenReturn(detail);
        when(libraryService.contentHandle(7L, 11L)).thenReturn(
                new OwnedAssetContent("library/7/scores.xlsx", "scores.xlsx",
                        detail.mimeType(), 1024L));
        when(objectStorage.exists("library/7/scores.xlsx")).thenReturn(true);
        when(objectStorage.open("library/7/scores.xlsx")).thenReturn(
                new StoredObject(new ByteArrayInputStream(new byte[0]), 2048L, detail.mimeType()));

        var preview = service.load(7L, 11L);

        assertThat(preview.message()).contains("Kích thước tệp đã thay đổi");
    }

    private static LibraryAssetDetail detail(String filename, String mime,
                                             String format, String formatClass,
                                             long size) {
        return new LibraryAssetDetail(
                11L, filename, filename, "DOCUMENT", mime, format, formatClass,
                size, null, null,
                "/lecturer/library/assets/11/preview",
                "/lecturer/library/assets/11/content",
                "/lecturer/library/assets/11/content?download=true", List.of());
    }
}
