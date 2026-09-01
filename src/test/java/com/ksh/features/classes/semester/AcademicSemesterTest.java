package com.ksh.features.classes.semester;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcademicSemesterTest {

    @Test
    void derivesEachTermFromTheRowsOwnYear() {
        assertThat(AcademicSemester.from(LocalDate.of(2023, 2, 1)).code()).isEqualTo("SP23");
        assertThat(AcademicSemester.from(LocalDate.of(2024, 7, 1)).code()).isEqualTo("SU24");
        assertThat(AcademicSemester.from(LocalDate.of(2025, 11, 1)).code()).isEqualTo("FA25");
    }

    @Test
    void comparesChronologicallyAcrossYearsAndTerms() {
        assertThat(AcademicSemester.parse("FA25"))
                .isLessThan(AcademicSemester.parse("SP26"));
        assertThat(AcademicSemester.parse("SP26"))
                .isLessThan(AcademicSemester.parse("SU26"));
    }

    @Test
    void rejectsUnsupportedCodes() {
        assertThatThrownBy(() -> AcademicSemester.parse("SEM1-2026"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void advancesEveryTermAndRollsFallIntoNextYearSpring() {
        assertThat(AcademicSemester.parse("SP26").next().code()).isEqualTo("SU26");
        assertThat(AcademicSemester.parse("SU26").next().code()).isEqualTo("FA26");
        assertThat(AcademicSemester.parse("FA26").next().code()).isEqualTo("SP27");
    }
}
