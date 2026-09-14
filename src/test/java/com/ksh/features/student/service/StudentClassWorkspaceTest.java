package com.ksh.features.student.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Subject;
import com.ksh.entities.Enrollment;
import com.ksh.entities.User;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.dto.ClassOverview;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.student.dto.StudentClassesDtos.EnrolledClassRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Student workspace must preserve history without reviving rejected/removed access. */
class StudentClassWorkspaceTest {

    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final ClassRepository classes = mock(ClassRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SubjectRepository subjects = mock(SubjectRepository.class);
    private StudentClassesService service;

    @BeforeEach
    void setUp() {
        service = new StudentClassesService(enrollments, classes, users, subjects);
    }

    @Test
    void historical_classes_require_active_or_completed_membership() {
        when(enrollments.findAllByUserId(99L)).thenReturn(List.of(
                enrollment(1L, Enrollment.STATUS_ACTIVE), enrollment(2L, Enrollment.STATUS_COMPLETED),
                enrollment(3L, Enrollment.STATUS_REMOVED), enrollment(4L, Enrollment.STATUS_PENDING),
                enrollment(5L, Enrollment.STATUS_REJECTED), enrollment(6L, Enrollment.STATUS_ACTIVE),
                enrollment(7L, Enrollment.STATUS_ACTIVE)));
        when(classes.findAllById(any())).thenReturn(List.of(
                clazz(1L, ClassEntity.STATUS_ARCHIVED), clazz(2L, ClassEntity.STATUS_ARCHIVED),
                clazz(3L, ClassEntity.STATUS_ARCHIVED), clazz(4L, ClassEntity.STATUS_ARCHIVED),
                clazz(5L, ClassEntity.STATUS_ARCHIVED), clazz(6L, ClassEntity.STATUS_ACTIVE)));

        List<EnrolledClassRow> rows = service.listWorkspaceClasses(99L, "", "", "");

        assertThat(rows).extracting(EnrolledClassRow::classId).containsExactly(1L, 2L, 6L);
        assertThat(rows).extracting(EnrolledClassRow::archived).containsExactly(true, true, false);
        verify(classes).findAllById(List.of(1L, 2L, 4L, 5L, 6L, 7L));
    }

    @Test
    void operational_classes_keep_pending_and_rejected_requests_but_hide_unapproved_classes() {
        when(enrollments.findAllByUserId(99L)).thenReturn(List.of(
                enrollment(1L, Enrollment.STATUS_PENDING), enrollment(2L, Enrollment.STATUS_REJECTED),
                enrollment(3L, Enrollment.STATUS_ACTIVE), enrollment(4L, Enrollment.STATUS_ACTIVE)));
        when(classes.findAllById(any())).thenReturn(List.of(
                clazz(1L, ClassEntity.STATUS_ACTIVE), clazz(2L, ClassEntity.STATUS_ACTIVE),
                clazz(3L, ClassEntity.STATUS_PENDING), clazz(4L, ClassEntity.STATUS_REJECTED)));

        assertThat(service.listWorkspaceClasses(99L, "", "", ""))
                .extracting(EnrolledClassRow::status)
                .containsExactly(Enrollment.STATUS_PENDING, Enrollment.STATUS_REJECTED);
    }

    @Test
    void teacher_name_search_combines_with_semester_and_subject_filters() {
        when(enrollments.findAllByUserId(99L)).thenReturn(List.of(
                enrollment(1L, Enrollment.STATUS_ACTIVE), enrollment(2L, Enrollment.STATUS_COMPLETED)));
        ClassEntity current = clazz(1L, ClassEntity.STATUS_ACTIVE);
        ClassEntity historical = clazz(2L, ClassEntity.STATUS_ARCHIVED);
        ReflectionTestUtils.setField(historical, "semester", "FA25");
        when(classes.findAllById(any())).thenReturn(List.of(current, historical));
        User lecturer = mock(User.class);
        when(lecturer.getId()).thenReturn(42L);
        when(lecturer.getFullName()).thenReturn("Kim Giảng Viên");
        when(users.findAllById(List.of(42L))).thenReturn(List.of(lecturer));
        Subject subject = new Subject("Korean", "KOR311", null, true);
        ReflectionTestUtils.setField(subject, "id", 6L);
        when(subjects.findAllById(List.of(6L))).thenReturn(List.of(subject));

        assertThat(service.listWorkspaceClasses(99L, " kIM ", " su26 ", " kor311 "))
                .singleElement().satisfies(row -> {
                    assertThat(row.classId()).isEqualTo(1L);
                    assertThat(row.lecturerName()).isEqualTo("Kim Giảng Viên");
                    assertThat(row.classCode()).isEqualTo("KOR311");
                });
        assertThat(service.listWorkspaceClasses(99L, "Kim", "FA25", "KOR311"))
                .singleElement().satisfies(row -> assertThat(row.archived()).isTrue());
        assertThat(service.listWorkspaceClasses(99L, "Kim", "SU26", "OTHER")).isEmpty();
    }

    @Test
    void workspace_totals_use_distinct_people_in_visible_classes() {
        List<EnrolledClassRow> rows = List.of(row(1L, Enrollment.STATUS_ACTIVE, false),
                row(2L, Enrollment.STATUS_PENDING, false), row(3L, Enrollment.STATUS_REJECTED, false),
                row(4L, Enrollment.STATUS_COMPLETED, true));
        when(enrollments.countDistinctStudentsInClasses(List.of(1L, 2L, 3L, 4L))).thenReturn(31L);
        when(classes.countDistinctTeachingUsers(List.of(1L, 2L, 3L, 4L))).thenReturn(3L);

        assertThat(service.workspaceOverview(rows)).isEqualTo(new ClassOverview(4, 1, 1, 31, 3));
        verify(enrollments, never()).countActiveGroupedByClassIds(any());
    }

    @Test
    void catalog_totals_query_all_filtered_results_not_a_single_page() {
        when(classes.searchActiveCatalogFiltered(ClassEntity.STATUS_ACTIVE, "Kim", "SU26", "KOR311",
                Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(
                        clazz(1L, ClassEntity.STATUS_ACTIVE), clazz(2L, ClassEntity.STATUS_ACTIVE))));
        when(enrollments.countDistinctStudentsInClasses(List.of(1L, 2L))).thenReturn(28L);
        when(classes.countDistinctTeachingUsers(List.of(1L, 2L))).thenReturn(2L);

        assertThat(service.catalogOverview(" Kim ", " su26 ", " KOR311 "))
                .isEqualTo(new ClassOverview(2, 2, 0, 28, 2));
    }

    @Test
    void empty_scopes_skip_person_aggregate_queries() {
        when(classes.searchActiveCatalogFiltered(ClassEntity.STATUS_ACTIVE, "", "", "",
                Pageable.unpaged())).thenReturn(Page.empty());

        assertThat(service.workspaceOverview(List.of())).isEqualTo(ClassOverview.empty());
        assertThat(service.catalogOverview(null, null, null)).isEqualTo(ClassOverview.empty());
        verifyNoInteractions(enrollments);
        verify(classes, never()).countDistinctTeachingUsers(any());
    }

    private static Enrollment enrollment(long classId, String status) {
        Enrollment enrollment = Enrollment.createFor(mock(User.class), classId, Enrollment.JoinedVia.MANUAL, null);
        ReflectionTestUtils.setField(enrollment, "status", status);
        ReflectionTestUtils.setField(enrollment, "joinedAt", LocalDateTime.of(2026, 6, 1, 9, 0));
        return enrollment;
    }

    private static ClassEntity clazz(long id, String status) {
        ClassEntity clazz = new ClassEntity("Korean " + id, 42L, 42L, "",
                LocalDate.of(2026, 6, 1), null, 30);
        clazz.setSubjectId(6L);
        ReflectionTestUtils.setField(clazz, "id", id);
        ReflectionTestUtils.setField(clazz, "status", status);
        return clazz;
    }

    private static EnrolledClassRow row(long id, String status, boolean archived) {
        return new EnrolledClassRow(id, "Korean " + id, "KOR311", "Kim", null, "", "SU26", status, archived);
    }
}
