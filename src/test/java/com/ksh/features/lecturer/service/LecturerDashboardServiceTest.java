package com.ksh.features.lecturer.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Lesson;
import com.ksh.entities.Section;
import com.ksh.entities.User;
import com.ksh.features.lecturer.dto.LecturerDashboardDtos.ClassDashboardRow;
import com.ksh.features.lecturer.dto.LecturerDashboardDtos.TeachingDashboardView;
import com.ksh.features.lecturer.dto.LecturerDashboardDtos.TeachingStats;
import com.ksh.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration tests for {@link LecturerDashboardService} aggregates. */
@SpringBootTest
@Transactional
class LecturerDashboardServiceTest {

    @Autowired private LecturerDashboardService service;
    @Autowired private LecturerDashboardServiceTestSupport fixtures;

    private User lecturer;
    private User otherLecturer;

    @BeforeEach
    void setUp() {
        lecturer = fixtures.requireUser("lecturer@ksh.edu.vn");
        otherLecturer = fixtures.ensureUser(
                "dash-other-lec@ksh.edu.vn", "Other Lecturer", Role.LECTURER);
    }

    @Test
    void empty_scope_returns_zero_stats() {
        User lonely = fixtures.ensureUser(
                "dash-lonely@ksh.edu.vn", "Lonely Lecturer", Role.LECTURER);

        TeachingDashboardView view = service.getDashboard(lonely.getId(), Role.LECTURER, "", 0, 20);

        assertThat(view.stats()).isEqualTo(TeachingStats.empty());
        assertThat(view.classes().getTotalElements()).isZero();
        assertThat(view.classes().getContent()).isEmpty();
        assertThat(view.classes().getSize()).isEqualTo(20);
    }

    @Test
    void progress_math_and_kpis_for_owned_classes() {
        ClassEntity active = fixtures.saveClass(lecturer, "Dash Active", "DASHA1");
        fixtures.markStatus(active, "ACTIVE");
        ClassEntity upcoming = fixtures.saveClass(lecturer, "Dash Upcoming", "DASHU1");

        Section section = fixtures.persistSection(
                active.getId(), "Chương 1", (short) 0, lecturer.getId());
        Lesson l1 = fixtures.persistLesson(
                section.getId(), "Bài 1", (short) 0, true, lecturer.getId());
        Lesson l2 = fixtures.persistLesson(
                section.getId(), "Bài 2", (short) 1, true, lecturer.getId());
        // DRAFT lesson must not affect denominator.
        fixtures.persistLesson(section.getId(), "Nháp", (short) 2, false, lecturer.getId());

        User s100 = fixtures.enroll(active, "dash-s100@ksh.edu.vn", "Full Done");
        User s50 = fixtures.enroll(active, "dash-s50@ksh.edu.vn", "Half Way");
        fixtures.enroll(active, "dash-s0@ksh.edu.vn", "Not Yet");
        fixtures.complete(s100, List.of(l1, l2)); // 100%
        fixtures.complete(s50, List.of(l1));      // 50%

        TeachingDashboardView view = service.getDashboard(
                lecturer.getId(), Role.LECTURER, "", 0, 100);

        TeachingStats stats = view.stats();
        assertThat(stats.totalClasses()).isGreaterThanOrEqualTo(2);
        assertThat(stats.totalStudents()).isGreaterThanOrEqualTo(3);
        assertThat(stats.activeClasses()).isGreaterThanOrEqualTo(1);

        ClassDashboardRow activeRow = view.classes().getContent().stream()
                .filter(r -> r.id().equals(active.getId())).findFirst().orElseThrow();
        assertThat(activeRow.studentCount()).isEqualTo(3);
        assertThat(activeRow.avgPercent()).isEqualTo(50);
        assertThat(activeRow.displayStatus()).isEqualTo("Đang hoạt động");

        ClassDashboardRow upcomingRow = view.classes().getContent().stream()
                .filter(r -> r.id().equals(upcoming.getId())).findFirst().orElseThrow();
        assertThat(upcomingRow.studentCount()).isZero();
        assertThat(upcomingRow.avgPercent()).isZero();
    }

    @Test
    void lecturer_scope_excludes_other_lecturers_classes() {
        ClassEntity mine = fixtures.saveClass(lecturer, "Mine Only", "DASHM1");
        ClassEntity theirs = fixtures.saveClass(otherLecturer, "Theirs Only", "DASHT1");

        TeachingDashboardView view = service.getDashboard(
                lecturer.getId(), Role.LECTURER, "", 0, 100);

        assertThat(view.classes().getContent()).extracting(ClassDashboardRow::id)
                .contains(mine.getId())
                .doesNotContain(theirs.getId());
    }

    @Test
    void admin_scope_includes_all_non_deleted_classes() {
        ClassEntity mine = fixtures.saveClass(lecturer, "Admin Sees Mine", "DASHAD1");
        ClassEntity theirs = fixtures.saveClass(otherLecturer, "Admin Sees Theirs", "DASHAD2");
        User admin = fixtures.requireUser("admin@ksh.edu.vn");

        TeachingDashboardView view = service.getDashboard(admin.getId(), Role.ADMIN, "", 0, 100);

        assertThat(view.classes().getContent()).extracting(ClassDashboardRow::id)
                .contains(mine.getId(), theirs.getId());
    }

    @Test
    void search_filters_table_but_keeps_full_scope_kpis() {
        User owner = fixtures.ensureUser(
                "dash-search-owner@ksh.edu.vn", "Search Owner", Role.LECTURER);
        ClassEntity alpha = fixtures.saveClass(owner, "Alpha Search Class", "ALPH01");
        ClassEntity beta = fixtures.saveClass(owner, "Beta Other Class", "BETA01");
        fixtures.enroll(alpha, "dash-search-s1@ksh.edu.vn", "Search Student");

        TeachingDashboardView all = service.getDashboard(owner.getId(), Role.LECTURER, "", 0, 20);
        TeachingDashboardView filtered =
                service.getDashboard(owner.getId(), Role.LECTURER, "alpha", 0, 20);

        assertThat(all.stats().totalClasses()).isEqualTo(2);
        assertThat(all.stats().totalStudents()).isEqualTo(1);
        // KPI must ignore the search needle.
        assertThat(filtered.stats().totalClasses()).isEqualTo(2);
        assertThat(filtered.stats().totalStudents()).isEqualTo(1);
        assertThat(filtered.classes().getTotalElements()).isEqualTo(1);
        assertThat(filtered.classes().getContent()).extracting(ClassDashboardRow::id)
                .containsExactly(alpha.getId())
                .doesNotContain(beta.getId());
    }

    @Test
    void pagination_returns_window_without_changing_total() {
        User owner = fixtures.ensureUser(
                "dash-page-owner@ksh.edu.vn", "Page Owner", Role.LECTURER);
        ClassEntity c1 = fixtures.saveClass(owner, "Page Class One", "PAGE01");
        ClassEntity c2 = fixtures.saveClass(owner, "Page Class Two", "PAGE02");
        ClassEntity c3 = fixtures.saveClass(owner, "Page Class Three", "PAGE03");

        TeachingDashboardView page0 = service.getDashboard(owner.getId(), Role.LECTURER, "", 0, 2);
        TeachingDashboardView page1 = service.getDashboard(owner.getId(), Role.LECTURER, "", 1, 2);

        assertThat(page0.stats().totalClasses()).isEqualTo(3);
        assertThat(page0.classes().getTotalElements()).isEqualTo(3);
        assertThat(page0.classes().getContent()).hasSize(2);
        assertThat(page1.classes().getContent()).hasSize(1);
        assertThat(page0.classes().getContent()).extracting(ClassDashboardRow::id)
                .doesNotContainAnyElementsOf(
                        page1.classes().getContent().stream().map(ClassDashboardRow::id).toList());
        assertThat(page0.classes().getContent()).extracting(ClassDashboardRow::id)
                .containsAnyOf(c1.getId(), c2.getId(), c3.getId());
    }

    @Test
    void active_classes_are_listed_before_archived_classes() {
        User owner = fixtures.ensureUser(
                "dash-order-owner@ksh.edu.vn", "Order Owner", Role.LECTURER);
        ClassEntity archived = fixtures.saveClass(owner, "Archived First By Creation", "DASHO1");
        fixtures.markStatus(archived, ClassEntity.STATUS_ARCHIVED);
        ClassEntity active = fixtures.saveClass(owner, "Active Teaching", "DASHO2");
        fixtures.markStatus(active, ClassEntity.STATUS_ACTIVE);

        TeachingDashboardView view = service.getDashboard(owner.getId(), Role.LECTURER, "", 0, 20);

        assertThat(view.classes().getContent()).extracting(ClassDashboardRow::id)
                .startsWith(active.getId())
                .endsWith(archived.getId());
        ClassDashboardRow archivedRow = view.classes().getContent().stream()
                .filter(row -> row.id().equals(archived.getId())).findFirst().orElseThrow();
        assertThat(archivedRow.displayStatus()).isEqualTo("Đã lưu trữ");
    }
}
