package com.ksh.features.admin.users.imports.validator;

import com.ksh.entities.Subject;
import com.ksh.entities.User;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.admin.users.imports.dto.UserImportRow;
import com.ksh.features.admin.users.imports.dto.UserImportRowStatus;
import com.ksh.features.admin.users.imports.parser.UserRosterParser;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.security.Role;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates rows without writing and applies current admin role invariants. */
@Component
public class UserRosterRowValidator {
    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Set<Role> IMPORTABLE_ROLES =
            Set.of(Role.STUDENT, Role.LECTURER, Role.ADMIN);

    private final UserRepository userRepository;
    private final SubjectRepository subjectRepository;

    public UserRosterRowValidator(UserRepository userRepository,
                                  SubjectRepository subjectRepository) {
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
    }

    public List<UserImportRow> validate(List<UserRosterParser.RawRosterRow> rawRows) {
        List<UserImportRow> result = new ArrayList<>();
        Set<String> seenEmails = new HashSet<>();
        Map<String, Subject> subjects = loadSubjects();
        for (UserRosterParser.RawRosterRow raw : rawRows) {
            UserImportRow row = new UserImportRow(raw.rowNumber(), raw.email(), raw.fullName(),
                    raw.role(), raw.subject(), raw.phone());
            result.add(row);

            String email = text(raw.email());
            if (email == null) {
                row.mark(UserImportRowStatus.MISSING_EMAIL);
                continue;
            }
            String emailKey = email.toLowerCase(Locale.ROOT);
            if (!seenEmails.add(emailKey)) {
                row.mark(UserImportRowStatus.DUPLICATE_IN_FILE);
                continue;
            }
            if (email.length() > 255 || !EMAIL.matcher(email).matches()) {
                row.mark(UserImportRowStatus.INVALID_EMAIL);
                continue;
            }
            if (text(raw.fullName()) != null && text(raw.fullName()).length() > 150) {
                row.mark(UserImportRowStatus.INVALID_FULL_NAME, "Họ tên tối đa 150 ký tự");
                continue;
            }
            if (text(raw.phone()) != null && text(raw.phone()).length() > 20) {
                row.mark(UserImportRowStatus.INVALID_PHONE, "Số điện thoại tối đa 20 ký tự");
                continue;
            }

            boolean defaulted = text(raw.role()) == null;
            Role role = resolveRole(raw.role());
            if (role == null || !IMPORTABLE_ROLES.contains(role)) {
                row.mark(UserImportRowStatus.INVALID_ROLE,
                        "Chấp nhận STUDENT, LECTURER hoặc ADMIN; LEADER phải được gán tại màn Môn học");
                continue;
            }

            Long subjectId = null;
            String subjectValue = text(raw.subject());
            if (subjectValue != null) {
                Subject subject = subjects.get(UserRosterParser.normalize(subjectValue));
                if (subject == null) {
                    row.mark(UserImportRowStatus.UNKNOWN_SUBJECT, subjectValue);
                    continue;
                }
                subjectId = subject.getId();
            }
            row.resolve(role, subjectId, defaulted);

            User existing = userRepository.findByEmailIncludingDeleted(emailKey).orElse(null);
            if (existing != null) {
                attachExisting(row, existing);
            } else {
                row.mark(UserImportRowStatus.CREATABLE,
                        defaulted ? "Cột vai trò trống nên dùng STUDENT" : null);
            }
        }
        return result;
    }

    public static void attachExisting(UserImportRow row, User existing) {
        String label;
        if (existing.isDeleted()) label = "DELETED";
        else if (existing.isLocked()) label = "LOCKED";
        else if (existing.isPendingActivation()) label = "PENDING";
        else if (!existing.isActive()) label = "INACTIVE";
        else label = "ACTIVE";
        row.attachExistingStatusLabel(label);
        row.mark(UserImportRowStatus.ALREADY_EXISTS);
    }

    private Map<String, Subject> loadSubjects() {
        Map<String, Subject> result = new HashMap<>();
        for (Subject subject : subjectRepository.findAllByOrderByNameAsc()) {
            result.putIfAbsent(UserRosterParser.normalize(subject.getCode()), subject);
            result.putIfAbsent(UserRosterParser.normalize(subject.getName()), subject);
        }
        return result;
    }

    private static Role resolveRole(String raw) {
        String value = text(raw);
        if (value == null) return Role.STUDENT;
        try {
            return Role.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
