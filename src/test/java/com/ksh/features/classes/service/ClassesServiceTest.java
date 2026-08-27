package com.ksh.features.classes.service;

import com.ksh.security.Role;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.classes.dto.ClassesDtos.ClassForm;
import com.ksh.features.classes.dto.ClassesDtos.ClassRow;
import com.ksh.entities.ClassActivity;
import com.ksh.entities.ClassEntity;
import com.ksh.entities.Department;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.lessons.repository.LessonRepository;
import com.ksh.features.lessons.repository.LessonAttachmentRepository;
import com.ksh.features.assignments.repository.AssignmentRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test cho {@link ClassesService}. Mock repositories and access policy.
 * Bao phu: list-by-role, owner check, required subject binding,
 * activity write tren moi mutation, edge case 404/403.
 *
 * <p>Wave 2 refactor (perf-services-cache-and-principal): service no longer
 * resolves the caller via {@code Principal}; tests pass {@code (userId, role)}
 * directly. Subject lookup is mocked to enforce the required active subject.
 */
class ClassesServiceTest {

    private static final Long LECTURER_ID = 42L;
    private static final Long OTHER_LECTURER_ID = 99L;
    private static final Long LEADER_ID = 7L;
    private static final Long ADMIN_ID = 1L;

    private ClassRepository classRepository;
    private ClassActivityWriter activityWriter;
    private DepartmentRepository subjectRepository;
    private ClassRoleAccessPolicy accessPolicy;
    private ApplicationEventPublisher eventPublisher;
    private EnrollmentRepository enrollmentRepository;
    private LessonRepository lessonRepository;
    private AssignmentRepository assignmentRepository;
    private LessonAttachmentRepository attachmentRepository;
    private ClassesService service;

    @BeforeEach
    void setUp() {
        classRepository = mock(ClassRepository.class);
        activityWriter = mock(ClassActivityWriter.class);
        subjectRepository = mock(DepartmentRepository.class);
        accessPolicy = mock(ClassRoleAccessPolicy.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        enrollmentRepository = mock(EnrollmentRepository.class);
        lessonRepository = mock(LessonRepository.class);
        assignmentRepository = mock(AssignmentRepository.class);
        attachmentRepository = mock(LessonAttachmentRepository.class);
        when(enrollmentRepository.countActiveGroupedByClassIds(any())).thenReturn(List.of());
        when(lessonRepository.countLiveGroupedByClassIds(any())).thenReturn(List.of());
        when(assignmentRepository.countLiveGroupedByClassIds(any())).thenReturn(List.of());
        when(attachmentRepository.countGroupedByClassIds(any())).thenReturn(List.of());
        Department subject = new Department("Tiếng Hàn 3.1.1", "KOR311", null, true);
        ReflectionTestUtils.setField(subject, "id", 12L);
        when(subjectRepository.findById(12L)).thenReturn(Optional.of(subject));
        when(subjectRepository.findAllById(any())).thenReturn(List.of(subject));
        when(accessPolicy.canAccess(any(), any(), any())).thenAnswer(invocation -> {
            ClassEntity clazz = invocation.getArgument(0);
            Long userId = invocation.getArgument(1);
            Role role = invocation.getArgument(2);
            return role == Role.ADMIN
                    || (role == Role.LECTURER && userId.equals(clazz.getLecturerId()))
                    || role == Role.LEADER;
        });
        when(accessPolicy.canManageClass(any(), any(), any())).thenAnswer(invocation -> {
            ClassEntity clazz = invocation.getArgument(0);
            Long userId = invocation.getArgument(1);
            Role role = invocation.getArgument(2);
            return role == Role.ADMIN || userId.equals(clazz.getLecturerId());
        });
        service = new ClassesService(classRepository, activityWriter,
                subjectRepository, accessPolicy, enrollmentRepository,
                lessonRepository, assignmentRepository, attachmentRepository,
                eventPublisher);
    }

    // ───────────────── List by role ─────────────────

    @Test
    void list_for_lecturer_filters_to_own_classes() {
        Pageable pageable = PageRequest.of(0, 20);
        when(classRepository.findAllAccessibleToLecturer(eq(LECTURER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(buildClass(1L, "Java", LECTURER_ID)), pageable, 1));

        Page<ClassRow> rows = service.listForUser(LECTURER_ID, Role.LECTURER, pageable);

        assertThat(rows.getContent()).hasSize(1);
        assertThat(rows.getContent().get(0).name()).isEqualTo("Java");
        verify(classRepository, never()).findAllBy(any(Pageable.class));
    }

    @Test
    void list_for_leader_returns_all_assigned_subjects() {
        Pageable pageable = PageRequest.of(0, 20);
        when(accessPolicy.leaderSubjectIds(LEADER_ID)).thenReturn(List.of(12L, 13L));
        when(classRepository.findAllBySubjectIdIn(eq(List.of(12L, 13L)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(buildClass(1L, "A", LECTURER_ID), buildClass(2L, "B", OTHER_LECTURER_ID)),
                        pageable, 2));

        Page<ClassRow> rows = service.listForUser(LEADER_ID, Role.LEADER, pageable);

        assertThat(rows.getContent()).hasSize(2);
        verify(classRepository, never()).findAllByLecturerId(any(), any(Pageable.class));
        verify(classRepository, never()).findAllBy(any(Pageable.class));
    }

    @Test
    void list_for_admin_returns_all() {
        Pageable pageable = PageRequest.of(0, 20);
        when(classRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(buildClass(1L, "A", LECTURER_ID)), pageable, 1));

        Page<ClassRow> rows = service.listForUser(ADMIN_ID, Role.ADMIN, pageable);

        assertThat(rows.getContent()).hasSize(1);
    }

    @Test
    void list_current_tab_filters_out_archived_classes_for_lecturer() {
        Pageable pageable = PageRequest.of(0, 20);
        List<String> currentStatuses = List.of(
                ClassEntity.STATUS_PENDING,
                ClassEntity.STATUS_REJECTED,
                ClassEntity.STATUS_ACTIVE);
        when(classRepository.findAllAccessibleToLecturerByStatuses(
                eq(LECTURER_ID), eq(currentStatuses), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(buildClass(1L, "Current", LECTURER_ID)), pageable, 1));

        Page<ClassRow> rows = service.listForUserByStatuses(
                LECTURER_ID, Role.LECTURER, currentStatuses, pageable);

        assertThat(rows.getContent()).extracting(ClassRow::name).containsExactly("Current");
        verify(classRepository).findAllAccessibleToLecturerByStatuses(
                eq(LECTURER_ID), eq(currentStatuses), any(Pageable.class));
    }

    @Test
    void archived_tab_uses_archived_only_and_reports_its_badge_count() {
        Pageable pageable = PageRequest.of(0, 20);
        List<String> archivedOnly = List.of(ClassEntity.STATUS_ARCHIVED);
        ClassEntity archived = buildClass(2L, "Archived", LECTURER_ID);
        archived.approve(LEADER_ID, java.time.LocalDateTime.now());
        archived.archive();
        when(classRepository.findAllAccessibleToLecturerByStatuses(
                eq(LECTURER_ID), eq(archivedOnly), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(archived), pageable, 1));
        when(classRepository.countAccessibleToLecturerByStatuses(
                LECTURER_ID, archivedOnly)).thenReturn(1L);

        Page<ClassRow> rows = service.listForUserByStatuses(
                LECTURER_ID, Role.LECTURER, archivedOnly, pageable);

        assertThat(rows.getContent()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(ClassEntity.STATUS_ARCHIVED);
            assertThat(row.reviewStateLabel()).isEqualTo("Đã lưu trữ");
        });
        assertThat(service.countForUserByStatuses(
                LECTURER_ID, Role.LECTURER, archivedOnly)).isEqualTo(1L);
    }

    @Test
    void list_returns_zero_stat_columns() {
        Pageable pageable = PageRequest.of(0, 20);
        when(classRepository.findAllAccessibleToLecturer(eq(LECTURER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(buildClass(1L, "X", LECTURER_ID)), pageable, 1));

        ClassRow row = service.listForUser(LECTURER_ID, Role.LECTURER, pageable).getContent().get(0);

        assertThat(row.studentCount()).isZero();
        assertThat(row.lectureCount()).isZero();
        assertThat(row.assignmentCount()).isZero();
        assertThat(row.materialCount()).isZero();
    }

    @Test
    void list_uses_live_repository_aggregates_for_stat_columns() {
        Pageable pageable = PageRequest.of(0, 20);
        when(classRepository.findAllAccessibleToLecturer(eq(LECTURER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(buildClass(1L, "KOR311", LECTURER_ID)), pageable, 1));

        EnrollmentRepository.ClassCount students = mock(EnrollmentRepository.ClassCount.class);
        LessonRepository.ClassCount lessons = mock(LessonRepository.ClassCount.class);
        AssignmentRepository.ClassCount assignments = mock(AssignmentRepository.ClassCount.class);
        LessonAttachmentRepository.ClassCount materials = mock(LessonAttachmentRepository.ClassCount.class);
        when(students.getClassId()).thenReturn(1L);
        when(students.getCnt()).thenReturn(3L);
        when(lessons.getClassId()).thenReturn(1L);
        when(lessons.getCnt()).thenReturn(4L);
        when(assignments.getClassId()).thenReturn(1L);
        when(assignments.getCnt()).thenReturn(5L);
        when(materials.getClassId()).thenReturn(1L);
        when(materials.getCnt()).thenReturn(6L);
        when(enrollmentRepository.countActiveGroupedByClassIds(List.of(1L))).thenReturn(List.of(students));
        when(lessonRepository.countLiveGroupedByClassIds(List.of(1L))).thenReturn(List.of(lessons));
        when(assignmentRepository.countLiveGroupedByClassIds(List.of(1L))).thenReturn(List.of(assignments));
        when(attachmentRepository.countGroupedByClassIds(List.of(1L))).thenReturn(List.of(materials));

        ClassRow row = service.listForUser(LECTURER_ID, Role.LECTURER, pageable).getContent().get(0);

        assertThat(row.studentCount()).isEqualTo(3);
        assertThat(row.lectureCount()).isEqualTo(4);
        assertThat(row.assignmentCount()).isEqualTo(5);
        assertThat(row.materialCount()).isEqualTo(6);
    }

    // ───────────────── Create ─────────────────

    @Test
    void create_persists_and_writes_created_activity() {
        when(classRepository.saveAndFlush(any(ClassEntity.class)))
                .thenAnswer(inv -> {
                    ClassEntity e = inv.getArgument(0);
                    ReflectionTestUtils.setField(e, "id", 100L);
                    return e;
                });

        ClassForm form = new ClassForm("Java", "Khoá nhập môn",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31), 50, 12L);
        ClassEntity saved = service.create(form, LECTURER_ID);

        assertThat(saved.getSubjectId()).isEqualTo(12L);
        assertThat(saved.getLecturerId()).isEqualTo(LECTURER_ID);
        assertThat(saved.getStatus()).isEqualTo(ClassEntity.STATUS_PENDING);

        verify(activityWriter).write(eq(100L), eq(ClassActivity.TYPE_CREATED),
                eq("Tạo lớp Java"), eq(LECTURER_ID));

    }

    @Test
    void create_rejects_unknown_or_inactive_subject_before_persisting() {
        when(subjectRepository.findById(99L)).thenReturn(Optional.empty());
        ClassForm form = new ClassForm("Tiếng Hàn", null, null, null, 50, 99L);

        assertThatThrownBy(() -> service.create(form, LECTURER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mã môn");

        verify(classRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_rethrows_non_code_collision_without_retry() {
        DataIntegrityViolationException other = new DataIntegrityViolationException(
                "Some other constraint",
                new RuntimeException("Cannot be null: classes.name"));
        when(classRepository.saveAndFlush(any(ClassEntity.class))).thenThrow(other);

        ClassForm form = new ClassForm("Java", "x", null, null, 100, 12L);

        assertThatThrownBy(() -> service.create(form, LECTURER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(classRepository, times(1)).saveAndFlush(any(ClassEntity.class));
        verify(activityWriter, never()).write(any(), any(), any(), any());
        verify(activityWriter, never()).write(any(), any(), any(), any(), any());
    }

    // ───────────────── Authz: owner check ─────────────────

    @Test
    void update_by_owning_lecturer_succeeds_and_writes_updated_activity() {
        ClassEntity entity = buildClass(9L, "Old name", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));
        when(classRepository.save(any(ClassEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ClassForm form = new ClassForm("New name", "desc", null, null, 50, 12L);
        service.update(9L, form, LECTURER_ID, Role.LECTURER);

        assertThat(entity.getName()).isEqualTo("New name");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCap =
                ArgumentCaptor.forClass(Map.class);
        verify(activityWriter).write(eq(9L), eq(ClassActivity.TYPE_UPDATED),
                eq("Cập nhật lớp New name"), metadataCap.capture(), eq(LECTURER_ID));

        Map<String, Object> diff = metadataCap.getValue();
        assertThat(diff).containsKey("old").containsKey("new");
        @SuppressWarnings("unchecked")
        Map<String, Object> oldState = (Map<String, Object>) diff.get("old");
        @SuppressWarnings("unchecked")
        Map<String, Object> newState = (Map<String, Object>) diff.get("new");
        assertThat(oldState).containsEntry("name", "Old name");
        assertThat(newState).containsEntry("name", "New name");
    }

    @Test
    void update_by_non_owning_lecturer_throws_403() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID); // owned by lecturer id=42
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        ClassForm form = new ClassForm("Y", "", null, null, 50, 12L);

        assertThatThrownBy(() -> service.update(9L, form, OTHER_LECTURER_ID, Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);

        verify(classRepository, never()).save(any(ClassEntity.class));
        verify(activityWriter, never()).write(any(), any(), any(), any());
        verify(activityWriter, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void update_by_leader_is_rejected_without_ownership_transfer() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        ClassForm form = new ClassForm("Y", "", null, null, 50, 12L);

        assertThatThrownBy(() -> service.update(9L, form, LEADER_ID, Role.LEADER))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(entity.getName()).isEqualTo("X");
        verify(classRepository, never()).save(any(ClassEntity.class));
    }

    @Test
    void update_by_admin_succeeds_for_any_class() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));
        when(classRepository.save(any(ClassEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ClassForm form = new ClassForm("Y", "", null, null, 50, 12L);
        service.update(9L, form, ADMIN_ID, Role.ADMIN);

        assertThat(entity.getName()).isEqualTo("Y");
    }

    @Test
    void update_throws_entity_not_found_when_missing() {
        when(classRepository.findById(999L)).thenReturn(Optional.empty());

        ClassForm form = new ClassForm("X", "", null, null, 50, 12L);
        assertThatThrownBy(() -> service.update(999L, form, LECTURER_ID, Role.LECTURER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ───────────────── Soft-delete ─────────────────

    @Test
    void rejected_class_is_resubmitted_only_by_an_explicit_owner_action() {
        ClassEntity entity = buildClass(9L, "Needs correction", LECTURER_ID);
        ReflectionTestUtils.setField(entity, "subjectId", 12L);
        entity.reject(LEADER_ID, "Thiếu lịch học", java.time.LocalDateTime.now());
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));
        when(classRepository.save(any(ClassEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ClassEntity saved = service.resubmitForReview(9L, LECTURER_ID, Role.LECTURER);

        assertThat(saved.getStatus()).isEqualTo(ClassEntity.STATUS_PENDING);
        assertThat(saved.getRejectionNote()).isNull();
        verify(activityWriter).write(eq(9L), eq(ClassActivity.TYPE_UPDATED),
                eq("Gửi duyệt lại lớp Needs correction"), eq(LECTURER_ID));
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void active_class_cannot_be_resubmitted_for_review() {
        ClassEntity entity = buildClass(9L, "Active", LECTURER_ID);
        entity.approve(LEADER_ID, java.time.LocalDateTime.now());
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.resubmitForReview(9L, LECTURER_ID, Role.LECTURER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bị từ chối");
        verify(classRepository, never()).save(any(ClassEntity.class));
    }

    @Test
    void soft_delete_by_owner_marks_deleted_and_writes_activity() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));
        when(classRepository.save(any(ClassEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.softDelete(9L, LECTURER_ID, Role.LECTURER);

        assertThat(entity.isDeleted()).isTrue();

        verify(activityWriter).write(eq(9L), eq(ClassActivity.TYPE_DELETED),
                eq("Xoá lớp X"), eq(LECTURER_ID));
    }

    @Test
    void soft_delete_by_non_owner_throws_403() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.softDelete(9L, OTHER_LECTURER_ID, Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);

        verify(classRepository, never()).save(any(ClassEntity.class));
        verify(activityWriter, never()).write(any(), any(), any(), any());
        verify(activityWriter, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void soft_delete_throws_entity_not_found_when_missing() {
        when(classRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.softDelete(999L, LECTURER_ID, Role.LECTURER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ───────────────── getEditable ─────────────────

    @Test
    void get_editable_returns_entity_for_owner() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        ClassEntity result = service.getEditable(9L, LECTURER_ID, Role.LECTURER);

        assertThat(result).isSameAs(entity);
    }

    @Test
    void get_editable_throws_403_for_non_owner() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.getEditable(9L, OTHER_LECTURER_ID, Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void get_editable_throws_not_found_for_missing() {
        when(classRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEditable(999L, LECTURER_ID, Role.LECTURER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ───────────────── Helpers ─────────────────

    private static ClassEntity buildClass(long id, String name, long lecturerId) {
        ClassEntity e = new ClassEntity(name, lecturerId, lecturerId, null, null, null, 100);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }
}
