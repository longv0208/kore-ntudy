package com.ksh.features.leader.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.ClassCoLecturer;
import com.ksh.entities.Subject;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.ClassCoLecturerRepository;
import com.ksh.features.leader.dto.LeaderDtos.AssignClassRow;
import com.ksh.features.leader.dto.LeaderDtos.AssignView;
import com.ksh.features.leader.dto.LeaderDtos.SubjectSummary;
import com.ksh.features.leader.dto.LeaderDtos.LecturerOption;
import com.ksh.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lists subject classes and adds co-lecturers without transferring ownership.
 */
@Service
public class LeaderLecturerAssignmentService {

    private static final Set<Role> ELIGIBLE = Set.of(Role.LECTURER, Role.LEADER);

    private final LeaderSubjectResolver resolver;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final ClassCoLecturerRepository coLecturerRepository;

    public LeaderLecturerAssignmentService(LeaderSubjectResolver resolver,
                                         ClassRepository classRepository,
                                         UserRepository userRepository,
                                         ClassCoLecturerRepository coLecturerRepository) {
        this.resolver = resolver;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.coLecturerRepository = coLecturerRepository;
    }

    @Transactional(readOnly = true)
    public AssignView load(Long leaderUserId) {
        List<Subject> subjects = resolver.resolveAll(leaderUserId);
        if (subjects.isEmpty()) {
            return new AssignView(null, List.of(), List.of(), true);
        }
        Map<Long, String> subjectCodes = new HashMap<>();
        List<ClassEntity> classes = new ArrayList<>();
        for (Subject subject : subjects) {
            subjectCodes.put(subject.getId(), subject.getCode());
            classes.addAll(classRepository.findAllBySubjectIdOrderByCreatedAtDesc(subject.getId()));
        }
        classes.sort(java.util.Comparator.comparing(ClassEntity::getCreatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        Map<Long, String> names = loadNames(classes);
        Map<Long, List<Long>> coLecturerIds = loadCoLecturerIds(classes);
        Map<Long, List<String>> coLecturerNames = loadCoLecturerNames(classes);
        List<AssignClassRow> rows = new ArrayList<>(classes.size());
        for (ClassEntity c : classes) {
            rows.add(new AssignClassRow(
                    c.getId(), c.getName(), subjectCodes.get(c.getSubjectId()), c.getSubjectId(),
                    c.getLecturerId(),
                    names.getOrDefault(c.getLecturerId(), "—"),
                    coLecturerIds.getOrDefault(c.getId(), List.of()),
                    coLecturerNames.getOrDefault(c.getId(), List.of())));
        }
        List<LecturerOption> lecturers = activeLecturers();
        Subject first = subjects.get(0);
        return new AssignView(
                subjects.size() == 1
                        ? new SubjectSummary(first.getId(), first.getCode(), first.getName())
                        : new SubjectSummary(first.getId(), subjects.size() + " mã môn",
                                "Môn học tiếng Hàn"),
                rows, lecturers, false);
    }

    /**
     * Adds a co-lecturer without changing the owning lecturer or creator.
     *
     * @return class display name for success toast
     */
    @Transactional
    public String assignCoLecturer(Long leaderUserId, Long classId, Long newLecturerId) {
        List<Long> selected = new ArrayList<>(coLecturerRepository.findAllByClassId(classId)
                .stream().map(ClassCoLecturer::getLecturerId).toList());
        selected.add(newLecturerId);
        return updateCoLecturers(leaderUserId, classId, selected);
    }

    /** Replaces the co-lecturer set selected by the leader. Ownership and
     * creator fields on the class are deliberately never touched. */
    @Transactional
    public String updateCoLecturers(Long leaderUserId, Long classId,
                                    List<Long> selectedLecturerIds) {
        List<Subject> subjects = resolver.resolveAll(leaderUserId);
        if (subjects.isEmpty()) throw new AccessDeniedException("Không có môn học");
        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy lớp"));
        Subject subject = subjects.stream()
                .filter(item -> item.getId().equals(clazz.getSubjectId()))
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException(
                        "Lớp không thuộc môn học của bạn"));
        if (clazz.getSubjectId() == null) {
            throw new AccessDeniedException("Lớp không thuộc môn học của bạn");
        }
        Set<Long> selected = selectedLecturerIds == null ? Set.of()
                : new java.util.LinkedHashSet<>(selectedLecturerIds);
        for (Long lecturerId : selected) {
            User lecturer = userRepository.findById(lecturerId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Không tìm thấy giảng viên"));
            if (!lecturer.isActive() || lecturer.isDeleted()
                    || !ELIGIBLE.contains(lecturer.getRole())) {
                throw new IllegalArgumentException(
                        "Giảng viên đồng giảng phải đang hoạt động");
            }
            if (lecturerId.equals(clazz.getLecturerId())) {
                throw new IllegalArgumentException("Giảng viên này đang là chủ lớp");
            }
        }
        List<ClassCoLecturer> current = coLecturerRepository.findAllByClassId(classId);
        coLecturerRepository.deleteAll(current.stream()
                .filter(row -> !selected.contains(row.getLecturerId())).toList());
        Set<Long> existing = current.stream().map(ClassCoLecturer::getLecturerId)
                .filter(selected::contains).collect(java.util.stream.Collectors.toSet());
        for (Long lecturerId : selected) {
            if (!existing.contains(lecturerId)) {
                coLecturerRepository.save(new ClassCoLecturer(classId, lecturerId, leaderUserId));
            }
        }
        return clazz.getName();
    }

    private Map<Long, List<Long>> loadCoLecturerIds(List<ClassEntity> classes) {
        if (classes.isEmpty()) return Map.of();
        Map<Long, List<Long>> result = new HashMap<>();
        for (ClassCoLecturer assignment : coLecturerRepository.findAllByClassIdIn(
                classes.stream().map(ClassEntity::getId).toList())) {
            result.computeIfAbsent(assignment.getClassId(), ignored -> new ArrayList<>())
                    .add(assignment.getLecturerId());
        }
        return result;
    }

    private Map<Long, List<String>> loadCoLecturerNames(List<ClassEntity> classes) {
        if (classes.isEmpty()) {
            return Map.of();
        }
        List<ClassCoLecturer> assignments = coLecturerRepository.findAllByClassIdIn(
                classes.stream().map(ClassEntity::getId).toList());
        Map<Long, String> userNames = new HashMap<>();
        for (User user : userRepository.findAllById(
                assignments.stream().map(ClassCoLecturer::getLecturerId).distinct().toList())) {
            userNames.put(user.getId(), user.getFullName());
        }
        Map<Long, List<String>> result = new HashMap<>();
        for (ClassCoLecturer assignment : assignments) {
            result.computeIfAbsent(assignment.getClassId(), ignored -> new ArrayList<>())
                    .add(userNames.getOrDefault(assignment.getLecturerId(), "—"));
        }
        return result;
    }

    private List<LecturerOption> activeLecturers() {
        return userRepository.findAll().stream()
                .filter(u -> u.isActive() && !u.isDeleted())
                .filter(u -> ELIGIBLE.contains(u.getRole()))
                .sorted((a, b) -> a.getFullName().compareToIgnoreCase(b.getFullName()))
                .map(u -> new LecturerOption(
                        u.getId(), u.getFullName(), u.getEmail()))
                .toList();
    }

    private Map<Long, String> loadNames(List<ClassEntity> classes) {
        Map<Long, String> names = new HashMap<>();
        for (ClassEntity c : classes) {
            if (c.getLecturerId() != null && !names.containsKey(c.getLecturerId())) {
                userRepository.findById(c.getLecturerId())
                        .ifPresent(u -> names.put(u.getId(), u.getFullName()));
            }
        }
        return names;
    }
}
