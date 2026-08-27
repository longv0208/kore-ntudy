package com.ksh.features.classes.imports.parser;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelTemplateBuilderTest {

    @Test
    void templateContainsHeaderAndTwentyFiveCompleteStudents() throws Exception {
        byte[] bytes = new ExcelTemplateBuilder().build();

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("Sinh viên");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(26);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Email");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("HE180001");
            assertThat(sheet.getRow(25).getCell(1).getStringCellValue()).isEqualTo("HE180025");
            for (int row = 1; row <= 25; row++) {
                for (int column = 0; column < 4; column++) {
                    assertThat(sheet.getRow(row).getCell(column).getStringCellValue()).isNotBlank();
                }
            }
        }
    }
}
