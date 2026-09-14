package com.ksh.features.student.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.entities.User;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassCoLecturerRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read model for the student class board and member directory. */
@Service
public class StudentClassDetailService {

    private final ClassRepository classRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassCoLecturerRepository coLecturerRepository;
    private final UserRepository userRepository;
    private final SubjectRepository subjectRepository;

    public StudentClassDetailService(ClassRepository classRepository,
                                     EnrollmentRepository enrollmentRepository,
                                     ClassCoLecturerRepository coLecturerRepository,
                                     UserRepository userRepository,
                                     SubjectRepository subjectRepository) {
        this.classRepository = classRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.coLecturerRepository = coLecturerRepository;
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
    }

    @Transactional(readOnly = true)
    public StudentClassDetailView get(Long classId, Long studentId) {
        enrollmentRepository.findByUserIdAndClassId(studentId, classId)
                .filter(row -> Enrollment.STATUS_ACTIVE.equals(row.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException("Lớp không tồn tại hoặc bạn chưa tham gia"));
        ClassEntity clazz = classRepository.findById(classId)
                .filter(row -> ClassEntity.STATUS_ACTIVE.equals(row.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException("Lớp không tồn tại hoặc đã lưu trữ"));

        User owner = userRepository.findById(clazz.getLecturerId())
                .orElseThrow(() -> new EntityNotFoundException("Giảng viên chủ lớp không tồn tại"));
        String subjectCode = clazz.getSubjectId() == null ? "—"
                : subjectRepository.findById(clazz.getSubjectId())
                        .map(subject -> subject.getCode()).orElse("—");

        Map<Long, MemberRow> members = new LinkedHashMap<>();
        putMember(members, owner, "Giảng viên chủ lớp", studentId);
        List<Long> coLecturerIds = coLecturerRepository.findAllByClassId(classId).stream()
                .map(row -> row.getLecturerId()).distinct().toList();
        for (User coLecturer : userRepository.findAllById(coLecturerIds)) {
            putMember(members, coLecturer, "Giảng viên đồng giảng", studentId);
        }
        for (Enrollment enrollment : enrollmentRepository
                .findAllByClassIdAndStatusOrderByJoinedAtDesc(classId, Enrollment.STATUS_ACTIVE)) {
            putMember(members, enrollment.getUser(), "Sinh viên", studentId);
        }

        return new StudentClassDetailView(clazz.getId(), clazz.getName(), subjectCode,
                owner.getFullName(), new ArrayList<>(members.values()));
    }

    private static void putMember(Map<Long, MemberRow> members, User user,
                                  String roleLabel, Long currentUserId) {
        if (user == null || user.getId() == null) return;
        String name = user.getFullName() == null || user.getFullName().isBlank()
                ? user.getEmail() : user.getFullName();
        String initial = name == null || name.isBlank() ? "?"
                : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        members.putIfAbsent(user.getId(), new MemberRow(user.getId(), name,
                user.getEmail(), roleLabel, initial, !user.getId().equals(currentUserId)));
    }

    public record StudentClassDetailView(Long classId, String className, String classCode,
                                         String lecturerName, List<MemberRow> members) {}

    public record MemberRow(Long userId, String name, String email, String roleLabel,
                            String initial, boolean canMessage) {}
}
