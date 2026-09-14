package com.ksh.entities;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassEntityApprovalTest {
    @Test
    void directHistoricalFixtureDerivesSemesterFromItsOwnStartDate() {
        ClassEntity clazz = new ClassEntity("Lớp cũ", 7L, 7L,
                null, LocalDate.of(2022, 6, 15), null, 30);

        assertThat(clazz.getSemester()).isEqualTo("SU22");
    }

    @Test
    void newClassIsActiveWithoutReviewerEvidence() {
        ClassEntity clazz = new ClassEntity("Lớp mới", 7L, 7L,
                null, null, null, 30);
        LocalDateTime reviewedAt = LocalDateTime.of(2026, 7, 31, 0, 30);

        assertThat(clazz.getStatus()).isEqualTo(ClassEntity.STATUS_ACTIVE);
        assertThat(clazz.getApprovedBy()).isNull();
        assertThat(clazz.getApprovedAt()).isNull();
        assertThatThrownBy(() -> clazz.approve(11L, reviewedAt))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectionStoresTrimmedOptionalNoteAndBecomesRejected() {
        ClassEntity clazz = new ClassEntity("Lớp mới", 7L, 7L,
                null, null, null, 30);

        org.springframework.test.util.ReflectionTestUtils.setField(clazz, "status", ClassEntity.STATUS_PENDING);
        clazz.reject(11L, "  Thiếu thông tin  ", LocalDateTime.now());

        assertThat(clazz.getStatus()).isEqualTo(ClassEntity.STATUS_REJECTED);
        assertThat(clazz.getRejectionNote()).isEqualTo("Thiếu thông tin");
    }

    @Test
    void rejectedClassMustBeExplicitlyResubmittedBeforeAnotherDecision() {
        ClassEntity clazz = new ClassEntity("Lớp mới", 7L, 7L,
                null, null, null, 30);
        LocalDateTime reviewedAt = LocalDateTime.of(2026, 8, 11, 10, 0);
        org.springframework.test.util.ReflectionTestUtils.setField(clazz, "status", ClassEntity.STATUS_PENDING);
        clazz.reject(11L, "Cần bổ sung lịch học", reviewedAt);

        assertThatThrownBy(() -> clazz.approve(11L, reviewedAt))
                .isInstanceOf(IllegalStateException.class);
        assertThat(clazz.resubmitForReview()).isTrue();
        assertThat(clazz.getStatus()).isEqualTo(ClassEntity.STATUS_PENDING);
        assertThat(clazz.getApprovedBy()).isNull();
        assertThat(clazz.getApprovedAt()).isNull();
        assertThat(clazz.getRejectionNote()).isNull();
        assertThat(clazz.resubmitForReview()).isFalse();
    }
}
