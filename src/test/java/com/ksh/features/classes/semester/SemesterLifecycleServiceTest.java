package com.ksh.features.classes.semester;

import com.ksh.entities.SystemSetting;
import com.ksh.features.admin.settings.repository.SystemSettingsRepository;
import com.ksh.features.classes.repository.ClassRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SemesterLifecycleServiceTest {
    private final Map<String, SystemSetting> rows = new HashMap<>();
    private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.parse("2026-08-31T05:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return SemesterCatalogService.ZONE; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant.get(); }
    };
    private SemesterCatalogService service;

    @BeforeEach void setUp() {
        var settings = mock(SystemSettingsRepository.class);
        var classes = mock(ClassRepository.class);
        when(settings.findBySettingKeyForUpdate(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        when(settings.findBySettingGroupForUpdate(anyString())).thenAnswer(call -> rows.values().stream()
                .filter(row -> row.getSettingGroup().equals(call.getArgument(0))).toList());
        when(settings.save(any(SystemSetting.class))).thenAnswer(call -> put(call.getArgument(0)));
        when(settings.saveAndFlush(any(SystemSetting.class))).thenAnswer(call -> put(call.getArgument(0)));
        when(classes.findDistinctSemesterCodes()).thenReturn(List.of("FA24", "SU25", "SU26"));
        put(new SystemSetting(AcademicSemesterService.SETTING_KEY, "SU26", "GENERAL"));
        put(new SystemSetting(SemesterCatalogService.PREFIX + "SU26", "v2|2026-05-01T00:00||1", "ACADEMIC"));
        service = new SemesterCatalogService(settings, classes, mock(EntityManager.class), clock);
    }

    private SystemSetting put(SystemSetting row) { rows.put(row.getSettingKey(), row); return row; }
    private LocalDateTime now() { return LocalDateTime.now(clock); }

    @Test void manualTransitionHasOneBoundaryAndOnlyOneCurrent() {
        var next = service.transition("SU26", 1, null, 1L);
        assertThat(next.code()).isEqualTo("FA26");
        assertThat(next.startAt()).isEqualTo(now());
        assertThat(next.endAt()).isNull();
        assertThat(service.list()).filteredOn(SemesterCatalogService.Entry::latest).singleElement()
                .extracting(SemesterCatalogService.Entry::code).isEqualTo("FA26");
        assertThat(service.list()).filteredOn(e -> e.code().equals("SU26")).singleElement()
                .extracting(SemesterCatalogService.Entry::endAt).isEqualTo(next.startAt());
    }

    @Test void doubleClickOrWrongCodeCannotSkipSemester() {
        service.transition("SU26", 1, null, 1L);
        assertThatThrownBy(() -> service.transition("SU26", 1, null, 1L))
                .isInstanceOf(SemesterCatalogService.StaleSemesterException.class);
        assertThatThrownBy(() -> service.transition("FA29", 1, null, 1L))
                .isInstanceOf(SemesterCatalogService.StaleSemesterException.class);
        assertThat(service.current().code()).isEqualTo("FA26");
    }

    @Test void fallTransitionsToNextYearsSpring() {
        rows.get(AcademicSemesterService.SETTING_KEY).setSettingValue("FA26");
        put(new SystemSetting(SemesterCatalogService.PREFIX + "FA26", "v2|2026-08-30T00:00||1", "ACADEMIC"));
        assertThat(service.transition("FA26", 1, null, 1L).code()).isEqualTo("SP27");
    }

    @Test void lateSchedulerUsesScheduledBoundaryAndDoesNotAdvanceTwice() {
        LocalDateTime boundary = now().plusHours(2);
        service.schedule("SU26", 1, boundary, 1L);
        instant.set(instant.get().plusSeconds(3 * 3600));
        service.advanceIfDue();
        service.advanceIfDue();
        assertThat(service.current().code()).isEqualTo("FA26");
        assertThat(service.current().startAt()).isEqualTo(boundary);
        assertThat(service.current().endAt()).isNull();
    }

    @Test void currentCodeReadProcessesDueScheduleBeforeClassCreation() {
        service.schedule("SU26", 1, now().plusSeconds(5), 1L);
        instant.set(instant.get().plusSeconds(5));
        assertThat(new AcademicSemesterService(service).currentCode()).isEqualTo("FA26");
    }

    @Test void noEndMeansNoAutomaticTransitionEvenAfterCalendarYearChanges() {
        instant.set(Instant.parse("2027-01-02T05:00:00Z"));
        service.advanceIfDue();
        assertThat(service.current().code()).isEqualTo("SU26");
    }

    @Test void cancellationAndRevisionPreventOverwritingNewerSchedule() {
        var scheduled = service.schedule("SU26", 1, now().plusDays(1), 1L);
        assertThatThrownBy(() -> service.schedule("SU26", 1, now().plusDays(2), 1L))
                .isInstanceOf(SemesterCatalogService.StaleSemesterException.class);
        var cancelled = service.schedule("SU26", scheduled.revision(), null, 1L);
        assertThat(cancelled.endAt()).isNull();
        instant.set(instant.get().plusSeconds(3 * 86400));
        service.advanceIfDue();
        assertThat(service.current().code()).isEqualTo("SU26");
    }

    @Test void staleManualActionAtDueBoundaryDoesNotAdvanceTwoTerms() {
        var scheduled = service.schedule("SU26", 1, now().plusMinutes(1), 1L);
        instant.set(instant.get().plusSeconds(60));
        assertThatThrownBy(() -> service.transition("SU26", scheduled.revision(), null, 1L))
                .isInstanceOf(SemesterCatalogService.StaleSemesterException.class);
        assertThat(service.current().code()).isEqualTo("FA26");
    }

    @Test void rejectsPastOrEqualEndAndDoesNotMutateTimeline() {
        assertThatThrownBy(() -> service.schedule("SU26", 1, now(), 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.transition("SU26", 1, now().minusMinutes(1), 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.current().code()).isEqualTo("SU26");
        assertThat(service.current().endAt()).isNull();
    }

    @Test void fillsHistoricalGapsButNeverInventsHistoricalDatesOrFutureActivePeriods() {
        var catalog = service.list();
        assertThat(catalog).extracting(SemesterCatalogService.Entry::code)
                .containsExactly("SU26", "SP26", "FA25", "SU25", "SP25", "FA24", "SU24", "SP24");
        assertThat(catalog).filteredOn(e -> !e.latest()).allSatisfy(e -> {
            assertThat(e.startAt()).isNull(); assertThat(e.endAt()).isNull();
            assertThat(e.status()).isEqualTo("Đã kết thúc");
        });
    }

    @Test void legacyHistoricalDatesRemainUntouched() {
        String legacy = "2024-01-01|2024-04-30";
        put(new SystemSetting(SemesterCatalogService.PREFIX + "SP24", legacy, "ACADEMIC"));
        assertThat(service.list()).filteredOn(e -> e.code().equals("SP24")).singleElement().satisfies(e -> {
            assertThat(e.start()).isEqualTo(LocalDate.of(2024,1,1));
            assertThat(e.end()).isEqualTo(LocalDate.of(2024,4,30));
        });
        assertThat(rows.get(SemesterCatalogService.PREFIX + "SP24").getSettingValue()).isEqualTo(legacy);
    }
}
