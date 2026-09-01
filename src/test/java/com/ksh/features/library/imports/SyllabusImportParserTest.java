package com.ksh.features.library.imports;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

class SyllabusImportParserTest {

    @Test
    void generatedTemplateRoundTripsThroughParser() throws Exception {
        byte[] workbook = new SyllabusImportTemplate().build();
        MockMultipartFile upload = new MockMultipartFile(
                "file", "syllabus.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                workbook);

        var rows = new SyllabusImportParser().parse(upload);

        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).chapterNumber()).isEqualTo(1);
        assertThat(rows.get(0).lessonNumber()).isEqualTo(1);
        assertThat(rows.get(3).chapterNumber()).isEqualTo(2);
        assertThat(rows.get(3).lessonNumber()).isEqualTo(4);
    }
}
