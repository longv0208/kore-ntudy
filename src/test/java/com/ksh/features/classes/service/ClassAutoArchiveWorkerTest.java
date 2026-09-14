package com.ksh.features.classes.service;

import com.ksh.entities.ClassEntity;
import com.ksh.features.classes.repository.ClassRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClassAutoArchiveWorkerTest {

    @Test
    void archivesActiveClassesWhoseEndDateIsDue() {
        ClassRepository classes = mock(ClassRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);
        ClassEntity due = activeClass(LocalDate.of(2026, 8, 3));
        when(classes.findAllByStatusAndEndDateLessThanEqual(
                ClassEntity.STATUS_ACTIVE, LocalDate.of(2026, 8, 3)))
                .thenReturn(List.of(due));

        new ClassAutoArchiveWorker(classes, clock).archiveDueClasses();

        assertThat(due.getStatus()).isEqualTo(ClassEntity.STATUS_ARCHIVED);
    }

    private static ClassEntity activeClass(LocalDate endDate) {
        ClassEntity clazz = new ClassEntity(
                "KOR311-A", 2L, 2L, null,
                LocalDate.of(2026, 8, 1), endDate, 30);

        return clazz;
    }
}
