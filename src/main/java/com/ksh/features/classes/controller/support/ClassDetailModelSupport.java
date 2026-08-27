package com.ksh.features.classes.controller.support;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.ClassCoLecturer;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.classes.repository.ClassCoLecturerRepository;
import com.ksh.security.Role;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import com.ksh.features.admin.departments.repository.DepartmentRepository;

import static com.ksh.common.IConstant.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared model-population + URL/label helpers for the class detail screens.
 * Extracted from {@code ClassesController} during the file-size refactor so
 * the controllers stay focused on request mapping.
 *
 * <p>{@link #populateDetail} centralises the attributes consumed by the
 * detail layout across lessons, members, materials, settings and placeholder tabs.
 */
@Component
public class ClassDetailModelSupport {

    private final ClassCoLecturerRepository coLecturerRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository subjectRepository;

    public ClassDetailModelSupport(ClassCoLecturerRepository coLecturerRepository,
                                   UserRepository userRepository,
                                   DepartmentRepository subjectRepository) {
        this.coLecturerRepository = coLecturerRepository;
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
    }

    /**
     * Populates common model attributes required by the class-detail layout.
     *
     * <p>The invite system is retired; only common class and tab state remains.
     */
    public void populateDetail(Model model, ClassEntity clazz, String activeTab,
                               Long userId, Role role) {
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_ACTIVE_TAB, activeTab);
        TeachingTeam teachingTeam = loadTeachingTeam(clazz);
        boolean primaryOwner = clazz.getLecturerId().equals(userId);
        boolean coLecturer = teachingTeam.coLecturers().stream()
                .anyMatch(member -> member.id().equals(userId));
        model.addAttribute("classTeachingTeam", teachingTeam);
        model.addAttribute("isPrimaryClassOwner", primaryOwner);

        // Provide subject code and lecturer name for sidebar rendering
        String subjectCode = clazz.getSubjectId() == null ? null
                : subjectRepository.findById(clazz.getSubjectId()).map(s -> s.getCode()).orElse(null);
        model.addAttribute("classCode", subjectCode);
        model.addAttribute("lecturerName", teachingTeam.owner().name());

        model.addAttribute("classViewerRole",
                primaryOwner ? "GV chủ lớp"
                        : coLecturer ? "Giảng viên đồng giảng"
                        : role == Role.LEADER ? "Trưởng bộ môn"
                        : role == Role.ADMIN ? "Quản trị viên" : "Người xem");
    }

    private TeachingTeam loadTeachingTeam(ClassEntity clazz) {
        List<ClassCoLecturer> assignments = coLecturerRepository.findAllByClassId(clazz.getId());
        List<Long> userIds = new java.util.ArrayList<>();
        userIds.add(clazz.getLecturerId());
        userIds.addAll(assignments.stream().map(ClassCoLecturer::getLecturerId).toList());
        Map<Long, User> users = new LinkedHashMap<>();
        userRepository.findAllById(userIds).forEach(user -> users.put(user.getId(), user));

        TeachingMember owner = toMember(users.get(clazz.getLecturerId()), clazz.getLecturerId());
        List<TeachingMember> coLecturers = assignments.stream()
                .map(ClassCoLecturer::getLecturerId)
                .map(id -> toMember(users.get(id), id))
                .toList();
        return new TeachingTeam(owner, coLecturers);
    }

    private static TeachingMember toMember(User user, Long fallbackId) {
        if (user == null) return new TeachingMember(fallbackId, "Không tìm thấy tài khoản", "");
        return new TeachingMember(user.getId(), user.getFullName(), user.getEmail());
    }

    public record TeachingMember(Long id, String name, String email) {}
    public record TeachingTeam(TeachingMember owner, List<TeachingMember> coLecturers) {}

    /** Builds the canonical URL for a single class — used by redirects and form actions. */
    public static String classUrl(Long id) {
        return URL_CLASSES_LIST + "/" + id;
    }

    /** Maps a tab key to its Vietnamese sidebar label; unknown keys pass through. */
    public static String labelFor(String tab) {
        return switch (tab) {
            case TAB_BOARD       -> "Bảng tin";
            case TAB_SCHEDULE    -> "Lịch học";
            case TAB_MEMBERS     -> "Thành viên";
            case TAB_ROLES       -> "Vai trò lớp";
            case TAB_GROUPS      -> "Nhóm học tập";
            case TAB_ASSIGNMENTS -> "Bài tập";
            case TAB_SCORES      -> "Bảng điểm";
            case TAB_PROGRESS    -> "Tiến độ";
            case TAB_LESSONS     -> "Bài giảng";
            case TAB_MATERIALS   -> "Tài liệu";
            case TAB_SETTINGS    -> "Cài đặt lớp học";
            // Fallback so future tabs render their raw key until labelled.
            default -> tab;
        };
    }
}
