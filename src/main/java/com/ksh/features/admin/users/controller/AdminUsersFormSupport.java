package com.ksh.features.admin.users.controller;

import com.ksh.features.admin.subjects.dto.SubjectDtos.SubjectOption;
import com.ksh.features.admin.subjects.service.SubjectQueryService;
import com.ksh.security.Role;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;

import static com.ksh.common.IConstant.*;

/**
 * Shared form-rendering helpers for the {@code /admin/users} CRUD and Edit
 * controllers. Loads live subject options from {@link SubjectQueryService}.
 */
@Component
class AdminUsersFormSupport {

    static final String URL_BASE = "/admin/users";

    /** View names — kept here so both controllers reference the same string. */
    static final String VIEW_LIST = "admin/users";
    static final String VIEW_FORM = "admin/users-form";

    // Model attribute keys shared by the create + edit form templates.
    private static final String ATTR_ROLES = "roles";

    private final SubjectQueryService subjectQueryService;

    AdminUsersFormSupport(SubjectQueryService subjectQueryService) {
        this.subjectQueryService = subjectQueryService;
    }

    /** Builds the canonical URL for a single admin user. */
    static String userUrl(Long id) {
        return URL_BASE + "/" + id;
    }

    /**
     * Populates the common form-page model attributes (mode, action URL,
     * roles dropdown, subjects dropdown, active sidebar tab). Used by
     * both the Create and Edit flows.
     */
    void populateFormModel(Model model, String mode, Long userId) {
        populateFormModel(model, mode, userId, null);
    }

    void populateFormModel(Model model, String mode, Long userId, Role currentRole) {
        model.addAttribute(ATTR_MODE, mode);
        model.addAttribute(ATTR_FORM_ACTION,
                MODE_CREATE.equals(mode) ? URL_BASE : userUrl(userId));
        model.addAttribute(ATTR_ROLES, editableRoles(mode, currentRole));
        List<SubjectOption> subjects = subjectQueryService.options();
        model.addAttribute(ATTR_SUBJECTS, subjects);
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_USERS);
    }

    private static List<Role> editableRoles(String mode, Role currentRole) {
        if (MODE_CREATE.equals(mode) || currentRole == null) {
            return List.of(Role.STUDENT, Role.LECTURER, Role.ADMIN);
        }
        if (currentRole == Role.LECTURER || currentRole == Role.LEADER) {
            return List.of(Role.LECTURER, Role.LEADER);
        }
        return List.of(currentRole);
    }
}
