package com.ksh.features.student.service;

import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.student.dto.StudentClassesDtos.EnrolledClassRow;
import com.ksh.features.student.dto.StudentClassesDtos.CatalogClassRow;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.entities.Subject;
import com.ksh.features.classes.semester.AcademicSemester;
import com.ksh.features.classes.dto.ClassOverview;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read service that powers {@code GET /my/classes}.
 *
 * <p>ACTIVE enrollments appear as full class rows; PENDING enrollments appear
 * as "đang chờ duyệt" without content entry links.
 */
@Service
public class StudentClassesService {

    private static final String[][] AVATAR_GRADIENTS = {
            {"#5E92F3", "#1E88E5"},
            {"#EC407A", "#D81B60"},
            {"#26A69A", "#00897B"},
            {"#FFA726", "#FB8C00"},
            {"#7E57C2", "#5E35B1"}
    };

    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final SubjectRepository subjectRepository;

    public StudentClassesService(EnrollmentRepository enrollmentRepository,
                                 ClassRepository classRepository,
                                 UserRepository userRepository,
                                 SubjectRepository subjectRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
    }

    /** ACTIVE enrolled classes, most recent join first. Soft-deleted classes hidden. */
    @Transactional(readOnly = true)
    public List<EnrolledClassRow> listEnrolledClasses(Long userId) {
        return listEnrolledClasses(userId, "", "", "");
    }

    public List<EnrolledClassRow> listEnrolledClasses(Long userId, String query,
                                                       String semester, String subjectCode) {
        return mapRows(enrollmentRepository.findAllByUserIdAndStatusOrderByJoinedAtDesc(
                userId, Enrollment.STATUS_ACTIVE), query, semester, subjectCode);
    }

    /** All current requests/enrollments and historical classes, excluding removed membership. */
    @Transactional(readOnly = true)
    public List<EnrolledClassRow> listWorkspaceClasses(Long userId, String query,
                                                       String semester, String subjectCode) {
        return mapRows(enrollmentRepository.findAllByUserId(userId).stream()
                .filter(e -> !Enrollment.STATUS_REMOVED.equals(e.getStatus())).toList(),
                query, semester, subjectCode, true);
    }

    @Transactional(readOnly = true)
    public ClassOverview workspaceOverview(List<EnrolledClassRow> rows) {
        if (rows.isEmpty()) return ClassOverview.empty();
        List<Long> ids = rows.stream().map(EnrolledClassRow::classId).distinct().toList();
        return new ClassOverview(ids.size(), rows.stream().filter(r -> !r.archived()
                && Enrollment.STATUS_ACTIVE.equals(r.status())).count(),
                rows.stream().filter(EnrolledClassRow::archived).count(),
                enrollmentRepository.countDistinctStudentsInClasses(ids),
                classRepository.countDistinctTeachingUsers(ids));
    }

    @Transactional(readOnly = true)
    public ClassOverview catalogOverview(String query, String semester, String subjectCode) {
        List<ClassEntity> classes = classRepository.searchActiveCatalogFiltered(
                ClassEntity.STATUS_ACTIVE, query == null ? "" : query.trim(),
                normalizeSemester(semester), normalizeSubjectCode(subjectCode),
                org.springframework.data.domain.Pageable.unpaged()).getContent();
        if (classes.isEmpty()) return ClassOverview.empty();
        List<Long> ids = classes.stream().map(ClassEntity::getId).toList();
        return new ClassOverview(ids.size(), ids.size(), 0,
                enrollmentRepository.countDistinctStudentsInClasses(ids),
                classRepository.countDistinctTeachingUsers(ids));
    }

    /** PENDING join requests for the student (awaiting owner approval). */
    @Transactional(readOnly = true)
    public List<EnrolledClassRow> listPendingClasses(Long userId) {
        return listPendingClasses(userId, "", "", "");
    }

    public List<EnrolledClassRow> listPendingClasses(Long userId, String query,
                                                      String semester, String subjectCode) {
        return mapRows(enrollmentRepository.findAllByUserIdAndStatusOrderByJoinedAtDesc(
                userId, Enrollment.STATUS_PENDING), query, semester, subjectCode);
    }

    /** All leader-approved ACTIVE classes, optionally filtered by name/subject code. */
    @Transactional(readOnly = true)
    public Page<CatalogClassRow> listActiveCatalog(Long userId, String query, int page, int size) {
        return listActiveCatalog(userId, query, "", "", page, size);
    }

    @Transactional(readOnly = true)
    public Page<CatalogClassRow> listActiveCatalog(Long userId, String query,
                                                   String semester, String subjectCode,
                                                   int page, int size) {
        String normalizedQuery = query == null ? "" : query.trim();
        Page<ClassEntity> classPage = classRepository.searchActiveCatalogFiltered(
                ClassEntity.STATUS_ACTIVE, normalizedQuery,
                normalizeSemester(semester), normalizeSubjectCode(subjectCode),
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50))));
        List<ClassEntity> classes = classPage.getContent();
        Map<Long, Subject> subjects = new HashMap<>();
        for (Subject subject : subjectRepository.findAllById(classes.stream()
                .map(ClassEntity::getSubjectId).filter(java.util.Objects::nonNull).distinct().toList())) {
            subjects.put(subject.getId(), subject);
        }
        Map<Long, LecturerContact> lecturers = new HashMap<>();
        for (User lecturer : userRepository.findAllById(classes.stream()
                .map(ClassEntity::getLecturerId).distinct().toList())) {
            lecturers.put(lecturer.getId(), new LecturerContact(
                    lecturer.getFullName(), lecturer.getEmail()));
        }
        Map<Long, String> enrollmentStatuses = new HashMap<>();
        for (Enrollment enrollment : enrollmentRepository.findAllByUserId(userId)) {
            enrollmentStatuses.put(enrollment.getClassId(), enrollment.getStatus());
        }
        List<CatalogClassRow> rows = new ArrayList<>();
        for (ClassEntity clazz : classes) {
            Subject subject = subjects.get(clazz.getSubjectId());
            String code = subject == null ? "—" : subject.getCode();
            String subjectName = subject == null ? "—" : subject.getName();
            String status = enrollmentStatuses.get(clazz.getId());
            LecturerContact lecturer = lecturers.getOrDefault(
                    clazz.getLecturerId(), new LecturerContact("—", "—"));
            rows.add(new CatalogClassRow(
                    clazz.getId(), clazz.getName(), code, subjectName,
                    lecturer.name(), lecturer.email(),
                    Enrollment.STATUS_PENDING.equals(status),
                    Enrollment.STATUS_ACTIVE.equals(status),
                    clazz.getSemester()));
        }
        return new PageImpl<>(rows, classPage.getPageable(), classPage.getTotalElements());
    }

    private List<EnrolledClassRow> mapRows(List<Enrollment> enrollments, String query,
                                           String semester, String subjectCode) {
        return mapRows(enrollments, query, semester, subjectCode, false);
    }

    private List<EnrolledClassRow> mapRows(List<Enrollment> enrollments, String query,
                                           String semester, String subjectCode, boolean includeArchived) {
        if (enrollments.isEmpty()) {
            return List.of();
        }

        List<Long> classIds = enrollments.stream().map(Enrollment::getClassId).distinct().toList();
        Map<Long, ClassEntity> classById = new HashMap<>();
        for (ClassEntity c : classRepository.findAllById(classIds)) {
            classById.put(c.getId(), c);
        }

        List<Long> lecturerIds = classById.values().stream()
                .map(ClassEntity::getLecturerId).distinct().toList();
        Map<Long, String> lecturerNames = new HashMap<>();
        for (User u : userRepository.findAllById(lecturerIds)) {
            lecturerNames.put(u.getId(), u.getFullName());
        }
        Map<Long, String> subjectCodes = new HashMap<>();
        for (Subject subject : subjectRepository.findAllById(classById.values().stream()
                .map(ClassEntity::getSubjectId).filter(java.util.Objects::nonNull)
                .distinct().toList())) {
            subjectCodes.put(subject.getId(), subject.getCode());
        }

        List<EnrolledClassRow> rows = new ArrayList<>(enrollments.size());
        String queryFilter = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        String semesterFilter = normalizeSemester(semester);
        String subjectFilter = normalizeSubjectCode(subjectCode);
        int idx = 0;
        for (Enrollment e : enrollments) {
            ClassEntity c = classById.get(e.getClassId());
            // Soft-deleted class → hide row.
            if (c == null) continue;
            boolean archived = ClassEntity.STATUS_ARCHIVED.equals(c.getStatus());
            if (!ClassEntity.STATUS_ACTIVE.equals(c.getStatus()) && !(includeArchived && archived)) continue;
            if (archived && !Enrollment.STATUS_ACTIVE.equals(e.getStatus())
                    && !Enrollment.STATUS_COMPLETED.equals(e.getStatus())) continue;
            String code = subjectCodes.getOrDefault(c.getSubjectId(), "—");
            if (!semesterFilter.isEmpty() && !semesterFilter.equals(c.getSemester())) continue;
            if (!subjectFilter.isEmpty() && !subjectFilter.equalsIgnoreCase(code)) continue;
            if (!queryFilter.isEmpty()
                    && !c.getName().toLowerCase(java.util.Locale.ROOT).contains(queryFilter)
                    && !code.toLowerCase(java.util.Locale.ROOT).contains(queryFilter)
                    && !lecturerNames.getOrDefault(c.getLecturerId(), "—")
                        .toLowerCase(java.util.Locale.ROOT).contains(queryFilter)) continue;
            String lecName = lecturerNames.getOrDefault(c.getLecturerId(), "—");
            String gradient = gradientFor(idx++);
            rows.add(new EnrolledClassRow(
                    c.getId(),
                    c.getName(),
                    code,
                    lecName,
                    e.getJoinedAt(),
                    gradient,
                    c.getSemester(),
                    e.getStatus(),
                    archived
            ));
        }
        return rows;
    }

    private static String gradientFor(int index) {
        String[] colors = AVATAR_GRADIENTS[Math.floorMod(index, AVATAR_GRADIENTS.length)];
        return "linear-gradient(135deg," + colors[0] + "," + colors[1] + ")";
    }

    @Transactional(readOnly = true)
    public List<String> semesterOptions() {
        return classRepository.findDistinctSemesterCodes().stream()
                .filter(value -> value != null && !value.isBlank())
                .sorted((left, right) -> Integer.compare(
                        AcademicSemester.parse(right).orderKey(),
                        AcademicSemester.parse(left).orderKey()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Subject> subjectOptions() {
        return subjectRepository.findByActiveTrueOrderByNameAsc();
    }

    private static String normalizeSemester(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return AcademicSemester.parse(value).code();
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static String normalizeSubjectCode(String value) {
        return value == null ? "" : value.trim();
    }

    private record LecturerContact(String name, String email) {
    }
}
