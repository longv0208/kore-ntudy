package com.ksh.features.classes.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Department;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.assignments.repository.AssignmentRepository;
import com.ksh.features.classes.dto.ClassOverview;
import com.ksh.features.classes.dto.ClassStatusCounts;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.classes.semester.AcademicSemesterService;
import com.ksh.features.lessons.repository.LessonAttachmentRepository;
import com.ksh.features.lessons.repository.LessonRepository;
import com.ksh.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Regression coverage for filter-scoped statistics outside the current result page. */
class ClassWorkspaceOverviewTest {

    private static final List<String> ALL_STATES = List.of(
            ClassEntity.STATUS_PENDING, ClassEntity.STATUS_REJECTED,
            ClassEntity.STATUS_ACTIVE, ClassEntity.STATUS_ARCHIVED);

    private final ClassRepository classes = mock(ClassRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final DepartmentRepository subjects = mock(DepartmentRepository.class);
    private final ClassRoleAccessPolicy access = mock(ClassRoleAccessPolicy.class);
    private ClassesService service;

    @BeforeEach
    void setUp() {
        service = new ClassesService(classes, mock(ClassActivityWriter.class), subjects,
                access, enrollments, mock(LessonRepository.class),
                mock(AssignmentRepository.class), mock(LessonAttachmentRepository.class),
                mock(ApplicationEventPublisher.class), mock(AcademicSemesterService.class));
    }

    @Test
    void lecturer_overview_uses_only_authorized_filtered_classes_and_distinct_people() {
        List<ClassEntity> matches = List.of(
                clazz(1L, ClassEntity.STATUS_ACTIVE), clazz(2L, ClassEntity.STATUS_ARCHIVED),
                clazz(3L, ClassEntity.STATUS_PENDING), clazz(4L, ClassEntity.STATUS_REJECTED));
        when(classes.searchAccessibleToLecturer(42L, ALL_STATES, "SU26", "KOR311", "Kim",
                Pageable.unpaged())).thenReturn(new PageImpl<>(matches));
        when(enrollments.countDistinctStudentsInClasses(List.of(1L, 2L, 3L, 4L))).thenReturn(17L);
        when(classes.countDistinctTeachingUsers(List.of(1L, 2L, 3L, 4L))).thenReturn(3L);

        assertThat(service.overview(42L, Role.LECTURER, " su26 ", " KOR311 ", " Kim "))
                .isEqualTo(new ClassOverview(4, 1, 1, 17, 3));

        verify(classes).searchAccessibleToLecturer(42L, ALL_STATES, "SU26", "KOR311", "Kim",
                Pageable.unpaged());
        verify(classes, never()).searchAdministrativeClasses(any(), any(), any(), any(), any());
        verify(enrollments, never()).countActiveGroupedByClassIds(any());
    }

    @Test
    void leader_overview_is_limited_to_all_assigned_subjects() {
        when(access.leaderSubjectIds(7L)).thenReturn(List.of(6L, 12L));
        when(classes.searchLeaderClasses(List.of(6L, 12L), ALL_STATES, "", "", "",
                Pageable.unpaged())).thenReturn(new PageImpl<>(List.of(clazz(9L, ClassEntity.STATUS_ACTIVE))));
        when(enrollments.countDistinctStudentsInClasses(List.of(9L))).thenReturn(4L);
        when(classes.countDistinctTeachingUsers(List.of(9L))).thenReturn(2L);

        assertThat(service.overview(7L, Role.LEADER, null, null, null))
                .isEqualTo(new ClassOverview(1, 1, 0, 4, 2));

        verify(classes, never()).searchAdministrativeClasses(any(), any(), any(), any(), any());
        verify(classes, never()).searchAccessibleToLecturer(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unassigned_leader_and_student_cannot_obtain_administrative_statistics() {
        when(access.leaderSubjectIds(7L)).thenReturn(List.of());

        assertThat(service.overview(7L, Role.LEADER, "", "", "")).isEqualTo(ClassOverview.empty());
        assertThat(service.overview(99L, Role.STUDENT, "", "", "")).isEqualTo(ClassOverview.empty());

        verifyNoInteractions(classes, enrollments);
    }

    @Test
    void empty_admin_result_does_not_issue_aggregate_queries_with_empty_ids() {
        when(classes.searchAdministrativeClasses(ALL_STATES, "FA25", "", "missing",
                Pageable.unpaged())).thenReturn(Page.empty());

        assertThat(service.overview(1L, Role.ADMIN, "FA25", "", "missing"))
                .isEqualTo(ClassOverview.empty());

        verifyNoInteractions(enrollments);
        verify(classes, never()).countDistinctTeachingUsers(any());
    }

    @Test
    void overview_is_not_limited_by_paginated_class_rows() {
        Pageable page = PageRequest.of(0, 1);
        ClassEntity first = clazz(1L, ClassEntity.STATUS_ACTIVE);
        when(classes.searchAdministrativeClasses(eq(ALL_STATES), eq(""), eq(""), eq(""), eq(page)))
                .thenReturn(new PageImpl<>(List.of(first), page, 3));
        when(classes.searchAdministrativeClasses(ALL_STATES, "", "", "", Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(first,
                        clazz(2L, ClassEntity.STATUS_ACTIVE), clazz(3L, ClassEntity.STATUS_ARCHIVED))));
        when(enrollments.countDistinctStudentsInClasses(List.of(1L, 2L, 3L))).thenReturn(8L);
        when(classes.countDistinctTeachingUsers(List.of(1L, 2L, 3L))).thenReturn(2L);

        assertThat(service.listForUserByStatusesAndFilters(1L, Role.ADMIN, ALL_STATES,
                "", "", "", page).getContent()).hasSize(1);
        assertThat(service.overview(1L, Role.ADMIN, "", "", ""))
                .isEqualTo(new ClassOverview(3, 2, 1, 8, 2));
    }

    @Test
    void lifecycle_badges_use_real_filter_scoped_statuses() {
        List<ClassEntity> matches = List.of(
                clazz(1L, ClassEntity.STATUS_ACTIVE),
                clazz(2L, ClassEntity.STATUS_ACTIVE),
                clazz(3L, ClassEntity.STATUS_PENDING),
                clazz(4L, ClassEntity.STATUS_REJECTED),
                clazz(5L, ClassEntity.STATUS_ARCHIVED));
        when(classes.searchAccessibleToLecturer(42L, ALL_STATES, "SP26", "KOR311", "Toan",
                Pageable.unpaged())).thenReturn(new PageImpl<>(matches));

        assertThat(service.statusCounts(42L, Role.LECTURER, "sp26", " KOR311 ", " Toan "))
                .isEqualTo(new ClassStatusCounts(2, 1, 1, 1));

        verify(classes).searchAccessibleToLecturer(
                42L, ALL_STATES, "SP26", "KOR311", "Toan", Pageable.unpaged());
        verifyNoInteractions(enrollments);
    }

    @Test
    void lifecycle_badges_are_empty_outside_an_authorized_role() {
        assertThat(service.statusCounts(42L, Role.STUDENT, "", "", ""))
                .isEqualTo(ClassStatusCounts.empty());
        verifyNoInteractions(classes, enrollments);
    }

    @Test
    void row_projects_bulk_lecturer_label_and_updated_date() {
        ClassEntity clazz = clazz(10L, ClassEntity.STATUS_ACTIVE);
        ReflectionTestUtils.setField(clazz, "updatedAt", LocalDateTime.of(2026, 8, 31, 8, 15));
        Department subject = new Department("Korean", "KOR311", null, true);
        ReflectionTestUtils.setField(subject, "id", 6L);
        when(subjects.findAllById(List.of(6L))).thenReturn(List.of(subject));
        ClassRepository.LecturerLabel label = mock(ClassRepository.LecturerLabel.class);
        when(label.getClassId()).thenReturn(10L);
        when(label.getLecturerName()).thenReturn("Kim Giảng Viên");
        when(classes.findLecturerLabels(List.of(10L))).thenReturn(List.of(label));
        when(classes.findAllAccessibleToLecturer(eq(42L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(clazz)));

        assertThat(service.listForUser(42L, Role.LECTURER, Pageable.unpaged()).getContent())
                .singleElement().satisfies(row -> {
                    assertThat(row.lecturerName()).isEqualTo("Kim Giảng Viên");
                    assertThat(row.updatedAtLabel()).isEqualTo("31/08/2026");
                    assertThat(row.code()).isEqualTo("KOR311");
                });
        verify(classes).findLecturerLabels(List.of(10L));
    }

    private static ClassEntity clazz(long id, String status) {
        ClassEntity clazz = new ClassEntity("Korean " + id, 42L, 42L, "",
                LocalDate.of(2026, 6, 1), null, 30);
        clazz.setSubjectId(6L);
        ReflectionTestUtils.setField(clazz, "id", id);
        ReflectionTestUtils.setField(clazz, "status", status);
        return clazz;
    }
}
