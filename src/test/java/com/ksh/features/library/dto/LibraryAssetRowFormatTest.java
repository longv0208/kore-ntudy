package com.ksh.features.library.dto;

import com.ksh.features.library.dto.LibraryDtos.LibraryAssetRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryAssetRowFormatTest {

    @Test
    void office_and_pdf_extensions_have_recognizable_product_icons() {
        assertFormat("bang-diem.xlsx", "application/octet-stream", "XLSX", "excel", "X");
        assertFormat("huong-dan.docx", "application/octet-stream", "DOCX", "word", "W");
        assertFormat("bai-giang.pptx", "application/octet-stream", "PPTX", "powerpoint", "P");
        assertFormat("de-cuong.pdf", "application/octet-stream", "PDF", "pdf", "PDF");
        assertFormat("video.mp4", "application/octet-stream", "MP4", "video", "\u25b6");
    }

    @Test
    void mime_type_is_a_safe_fallback_when_legacy_filename_has_no_extension() {
        assertFormat("de-cuong", "application/pdf", "PDF", "pdf", "PDF");
        assertFormat("bang-diem", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "XLSX", "excel", "X");
        assertFormat("huong-dan", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "DOCX", "word", "W");
    }

    private static void assertFormat(String filename, String mimeType, String label,
                                     String cssClass, String icon) {
        LibraryAssetRow row = new LibraryAssetRow(
                1L, filename, filename, "DOCUMENT", mimeType, 1L,
                null, null, false);
        assertThat(row.formatLabel()).isEqualTo(label);
        assertThat(row.formatClass()).isEqualTo(cssClass);
        assertThat(row.iconLabel()).isEqualTo(icon);
    }
}
