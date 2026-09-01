package com.ksh.features.classes.semester;

import com.ksh.entities.SystemSetting;
import com.ksh.features.admin.settings.repository.SystemSettingsRepository;
import com.ksh.features.classes.repository.ClassRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * One active semester; historical periods remain immutable. Metadata is kept in
 * the existing system_settings table. The current-semester row is a database
 * mutex shared by scheduling, manual transitions and class creation.
 */
@Service
public class SemesterCatalogService {
    static final String PREFIX = "academic.semester.";
    private static final String GROUP = "ACADEMIC";
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final SystemSettingsRepository settings;
    private final ClassRepository classes;
    private final EntityManager entityManager;
    private final Clock clock;

    @Autowired
    public SemesterCatalogService(SystemSettingsRepository settings, ClassRepository classes,
                                  EntityManager entityManager) {
        this(settings, classes, entityManager, Clock.system(ZONE));
    }

    SemesterCatalogService(SystemSettingsRepository settings, ClassRepository classes,
                           EntityManager entityManager, Clock clock) {
        this.settings = settings;
        this.classes = classes;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public record Entry(String code, String name, LocalDateTime startAt, LocalDateTime endAt,
                        boolean latest, long revision) {
        public String status() { return latest ? "Đang diễn ra" : "Đã kết thúc"; }
        public String nextCode() { return AcademicSemester.parse(code).next().code(); }
        public String season() { return AcademicSemester.parse(code).term().name().toLowerCase(Locale.ROOT); }
        public LocalDate start() { return startAt == null ? null : startAt.toLocalDate(); }
        public LocalDate end() { return endAt == null ? null : endAt.toLocalDate(); }
    }

    private record Period(LocalDateTime start, LocalDateTime end, long revision, boolean lifecycle) {
        String encode() {
            return "v2|" + (start == null ? "" : start) + "|" + (end == null ? "" : end) + "|" + revision;
        }
    }

    /** Conflict is non-rollback so a due automatic transition stays committed. */
    public static class StaleSemesterException extends IllegalArgumentException {
        public StaleSemesterException() {
            super("Học kỳ hoặc lịch kết thúc đã thay đổi. Hãy tải lại trang trước khi thao tác.");
        }
    }

    @Transactional
    public Entry current() {
        SystemSetting anchor = lockCurrent();
        advanceDueLocked(anchor, now());
        return entry(AcademicSemester.parse(anchor.getSettingValue()), period(anchor.getSettingValue()), true);
    }

    @Transactional
    public List<Entry> list() {
        SystemSetting anchor = lockCurrent();
        advanceDueLocked(anchor, now());
        AcademicSemester active = AcademicSemester.parse(anchor.getSettingValue());
        Map<String, SystemSetting> stored = new HashMap<>();
        settings.findBySettingGroupForUpdate(GROUP).stream()
                .filter(row -> row.getSettingKey().startsWith(PREFIX))
                .forEach(row -> {
                    entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
                    stored.put(row.getSettingKey().substring(PREFIX.length()), row);
                });
        int firstYear = active.year();
        Set<String> evidence = new HashSet<>(classes.findDistinctSemesterCodes());
        evidence.addAll(stored.keySet());
        for (String code : evidence) {
            try {
                AcademicSemester term = AcademicSemester.parse(code);
                if (term.compareTo(active) <= 0) firstYear = Math.min(firstYear, term.year());
            } catch (IllegalArgumentException ignored) { /* Not an academic catalog key. */ }
        }

        List<Entry> result = new ArrayList<>();
        // Fill missing historical codes, not dates. Do not activate/create future terms.
        for (AcademicSemester term = new AcademicSemester(AcademicSemester.Term.SP, firstYear);
             term.compareTo(active) <= 0;) {
            SystemSetting row = stored.get(term.code());
            if (row == null) {
                row = settings.save(new SystemSetting(PREFIX + term.code(), "", GROUP));
            }
            result.add(entry(term, decode(row.getSettingValue()), term.equals(active)));
            if (term.equals(active)) break;
            term = term.next();
        }
        Collections.reverse(result);
        return result;
    }

    /** Schedule (or cancel) the only current period's automatic end. */
    @Transactional(noRollbackFor = StaleSemesterException.class)
    public Entry schedule(String expectedCurrent, long expectedRevision, LocalDateTime endAt, Long actor) {
        SystemSetting anchor = lockCurrent();
        LocalDateTime now = now();
        validateFutureEnd(endAt, now);
        advanceDueLocked(anchor, now);
        Period current = checkedPeriod(anchor, expectedCurrent, expectedRevision);
        Period changed = new Period(current.start, endAt, current.revision + 1, true);
        writePeriod(anchor.getSettingValue(), changed, actor);
        return entry(AcademicSemester.parse(anchor.getSettingValue()), changed, true);
    }

    /** End at server now and start exactly the next term at the same instant. */
    @Transactional(noRollbackFor = StaleSemesterException.class)
    public Entry transition(String expectedCurrent, long expectedRevision, LocalDateTime nextEndAt, Long actor) {
        SystemSetting anchor = lockCurrent();
        LocalDateTime now = now();
        validateFutureEnd(nextEndAt, now);
        advanceDueLocked(anchor, now);
        Period current = checkedPeriod(anchor, expectedCurrent, expectedRevision);
        if (!now.isAfter(current.start)) {
            throw new IllegalArgumentException("Kỳ mới phải bắt đầu sau thời điểm bắt đầu kỳ hiện hành.");
        }
        return transitionLocked(anchor, current, now, nextEndAt, actor);
    }

    @Transactional
    public void advanceIfDue() {
        advanceDueLocked(lockCurrent(), now());
    }

    private SystemSetting lockCurrent() {
        SystemSetting anchor = settings.findBySettingKeyForUpdate(AcademicSemesterService.SETTING_KEY)
                .orElseThrow(() -> new IllegalStateException("Thiếu cấu hình học kỳ hiện hành. Hãy chạy migration V141."));
        // An earlier caller in the same transaction may already have read this entity.
        entityManager.refresh(anchor, LockModeType.PESSIMISTIC_WRITE);
        AcademicSemester.parse(anchor.getSettingValue()); // Corruption must not silently activate another period.
        Period metadata = period(anchor.getSettingValue());
        if (!metadata.lifecycle) {
            // Start tracking the existing active period now; never invent historical dates.
            writePeriod(anchor.getSettingValue(), new Period(now(), null, 1, true), null);
        }
        return anchor;
    }

    private void advanceDueLocked(SystemSetting anchor, LocalDateTime now) {
        Period active = period(anchor.getSettingValue());
        if (active.end != null && !now.isBefore(active.end)) {
            transitionLocked(anchor, active, active.end, null, null);
        }
    }

    private Entry transitionLocked(SystemSetting anchor, Period active, LocalDateTime boundary,
                                   LocalDateTime nextEndAt, Long actor) {
        AcademicSemester current = AcademicSemester.parse(anchor.getSettingValue());
        AcademicSemester next = current.next();
        Period nextPeriod = period(next.code());
        if (nextPeriod.lifecycle && nextPeriod.start != null) {
            throw new IllegalStateException("Học kỳ kế tiếp đã có lịch sử. Không thể ghi đè dữ liệu học kỳ.");
        }
        writePeriod(current.code(), new Period(active.start, boundary, active.revision + 1, true), actor);
        Period started = new Period(boundary, nextEndAt, 1, true);
        writePeriod(next.code(), started, actor);
        anchor.setSettingValue(next.code());
        anchor.setUpdatedBy(actor);
        settings.saveAndFlush(anchor);
        return entry(next, started, true);
    }

    private Period checkedPeriod(SystemSetting anchor, String expected, long revision) {
        Period current = period(anchor.getSettingValue());
        if (!anchor.getSettingValue().equals(expected) || current.revision != revision) {
            throw new StaleSemesterException();
        }
        return current;
    }

    private Period period(String code) {
        return settings.findBySettingKeyForUpdate(PREFIX + code).map(row -> {
            entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
            return decode(row.getSettingValue());
        }).orElse(new Period(null, null, 0, false));
    }

    private void writePeriod(String code, Period period, Long actor) {
        SystemSetting row = settings.findBySettingKeyForUpdate(PREFIX + code)
                .orElseGet(() -> new SystemSetting(PREFIX + code, "", GROUP));
        row.setSettingValue(period.encode());
        row.setUpdatedBy(actor);
        settings.saveAndFlush(row);
    }

    private Period decode(String raw) {
        if (raw == null || raw.isBlank()) return new Period(null, null, 0, false);
        String[] parts = raw.split("\\|", -1);
        if (parts.length == 4 && parts[0].equals("v2")) {
            return new Period(parts[1].isBlank() ? null : LocalDateTime.parse(parts[1]),
                    parts[2].isBlank() ? null : LocalDateTime.parse(parts[2]), Long.parseLong(parts[3]), true);
        }
        if (parts.length == 2) {
            return new Period(parts[0].isBlank() ? null : LocalDate.parse(parts[0]).atStartOfDay(),
                    parts[1].isBlank() ? null : LocalDate.parse(parts[1]).atStartOfDay(), 0, false);
        }
        throw new IllegalStateException("Dữ liệu thời gian học kỳ không hợp lệ; không tự ghi đè lịch sử.");
    }

    private static Entry entry(AcademicSemester semester, Period period, boolean current) {
        return new Entry(semester.code(), semester.displayName(), period.start, period.end, current, period.revision);
    }

    private static void validateFutureEnd(LocalDateTime end, LocalDateTime now) {
        if (end != null && !end.isAfter(now)) {
            throw new IllegalArgumentException("Lịch kết thúc phải sau thời điểm hiện tại (giờ Việt Nam).");
        }
    }

    private LocalDateTime now() { return LocalDateTime.now(clock).withNano(0); }
}
