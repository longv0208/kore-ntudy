package com.ksh.features.student.service;

import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.student.dto.StudentClassesDtos.EnrolledClassRow;
import com.ksh.features.student.dto.StudentClassesDtos.CatalogClassRow;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.entities.Department;
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
    private final DepartmentRepository subjectRepository;

    public StudentClassesService(EnrollmentRepository enrollmentRepository,
                                 ClassRepository classRepository,
                                 UserRepository userRepository,
                                 DepartmentRepository subjectRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
    }

    /** ACTIVE enrolled classes, most recent join first. Soft-deleted classes hidden. */
    @Transactional(readOnly = true)
    public List<EnrolledClassRow> listEnrolledClasses(Long userId) {
        return mapRows(enrollmentRepository
                .findAllByUserIdAndStatusOrderByJoinedAtDesc(userId, Enrollment.STATUS_ACTIVE));
    }

    /** PENDING join requests for the student (awaiting owner approval). */
    @Transactional(readOnly = true)
    public List<EnrolledClassRow> listPendingClasses(Long userId) {
        return mapRows(enrollmentRepository
                .findAllByUserIdAndStatusOrderByJoinedAtDesc(userId, Enrollment.STATUS_PENDING));
    }

    /** All leader-approved ACTIVE classes, optionally filtered by name/subject code. */
    @Transactional(readOnly = true)
    public Page<CatalogClassRow> listActiveCatalog(Long userId, String query, int page, int size) {
        String normalizedQuery = query == null ? "" : query.trim();
        Page<ClassEntity> classPage = classRepository.searchActiveCatalog(
                ClassEntity.STATUS_ACTIVE, normalizedQuery,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50))));
        List<ClassEntity> classes = classPage.getContent();
        Map<Long, Department> subjects = new HashMap<>();
        for (Department subject : subjectRepository.findAllById(classes.stream()
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
            Department subject = subjects.get(clazz.getSubjectId());
            String code = subject == null ? "—" : subject.getCode();
            String subjectName = subject == null ? "—" : subject.getName();
            String status = enrollmentStatuses.get(clazz.getId());
            LecturerContact lecturer = lecturers.getOrDefault(
                    clazz.getLecturerId(), new LecturerContact("—", "—"));
            rows.add(new CatalogClassRow(
                    clazz.getId(), clazz.getName(), code, subjectName,
                    lecturer.name(), lecturer.email(),
                    Enrollment.STATUS_PENDING.equals(status),
                    Enrollment.STATUS_ACTIVE.equals(status)));
        }
        return new PageImpl<>(rows, classPage.getPageable(), classPage.getTotalElements());
    }

    private List<EnrolledClassRow> mapRows(List<Enrollment> enrollments) {
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
        for (Department subject : subjectRepository.findAllById(classById.values().stream()
                .map(ClassEntity::getSubjectId).filter(java.util.Objects::nonNull)
                .distinct().toList())) {
            subjectCodes.put(subject.getId(), subject.getCode());
        }

        List<EnrolledClassRow> rows = new ArrayList<>(enrollments.size());
        int idx = 0;
        for (Enrollment e : enrollments) {
            ClassEntity c = classById.get(e.getClassId());
            // Soft-deleted class → hide row.
            if (c == null || !ClassEntity.STATUS_ACTIVE.equals(c.getStatus())) continue;
            String lecName = lecturerNames.getOrDefault(c.getLecturerId(), "—");
            String gradient = gradientFor(idx++);
            rows.add(new EnrolledClassRow(
                    c.getId(),
                    c.getName(),
                    subjectCodes.getOrDefault(c.getSubjectId(), "—"),
                    lecName,
                    e.getJoinedAt(),
                    gradient
            ));
        }
        return rows;
    }

    private static String gradientFor(int index) {
        String[] colors = AVATAR_GRADIENTS[Math.floorMod(index, AVATAR_GRADIENTS.length)];
        return "linear-gradient(135deg," + colors[0] + "," + colors[1] + ")";
    }

    private record LecturerContact(String name, String email) {
    }
}
