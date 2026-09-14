package com.ksh.features.admin.subjects.controller;

import com.ksh.features.admin.subjects.dto.SubjectDtos.SubjectFilter;
import com.ksh.features.admin.subjects.dto.SubjectDtos.SubjectForm;
import com.ksh.features.admin.subjects.service.SubjectQueryService;
import com.ksh.features.admin.subjects.service.SubjectService;
import com.ksh.features.admin.subjects.service.SubjectValidationException;
import com.ksh.security.KshUserDetails;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;

import static com.ksh.common.IConstant.*;

/**
 * MVC controller for the legacy {@code /admin/subjects} URL. The page now
 * manages the subject catalog and subject-leader assignments.
 */
@Controller
@RequestMapping(URL_ADMIN_SUBJECTS)
@PreAuthorize("hasAuthority('PERM_subject.manage')")
public class AdminSubjectsController {

    private static final String REDIRECT_BASE = "redirect:" + URL_ADMIN_SUBJECTS;
    private static final String ATTR_FILTER = "filter";
    private static final int HISTORY_PAGE_SIZE = 20;
    private static final Set<String> VALID_DETAIL_TABS = Set.of(TAB_INFO, TAB_HISTORY);

    private final SubjectQueryService queryService;
    private final SubjectService subjectService;

    public AdminSubjectsController(SubjectQueryService queryService,
                                      SubjectService subjectService) {
        this.queryService = queryService;
        this.subjectService = subjectService;
    }

    /** Lists subjects with optional search, status, and sort filters. */
    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String sort,
                       Model model) {
        SubjectFilter filter = new SubjectFilter(q, status, sort);
        model.addAttribute(ATTR_SUBJECTS, queryService.list(filter));
        model.addAttribute(ATTR_FILTER, filter);
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SUBJECTS);
        return VIEW_ADMIN_SUBJECTS;
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, SubjectForm.empty());
        }
        populateFormModel(model, MODE_CREATE, null, TAB_INFO);
        return VIEW_ADMIN_SUBJECTS_FORM;
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") SubjectForm form,
                         BindingResult result,
                         @AuthenticationPrincipal KshUserDetails actor,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateFormModel(model, MODE_CREATE, null, TAB_INFO);
            return VIEW_ADMIN_SUBJECTS_FORM;
        }
        try {
            String savedName = subjectService.create(form, actorId(actor));
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SUBJECT_CREATED + savedName);
            return REDIRECT_BASE;
        } catch (SubjectValidationException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateFormModel(model, MODE_CREATE, null, TAB_INFO);
            return VIEW_ADMIN_SUBJECTS_FORM;
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @RequestParam(name = "tab", required = false, defaultValue = TAB_INFO) String tab,
                           @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                           Model model,
                           RedirectAttributes ra) {
        SubjectForm existing = queryService.loadForm(id);
        if (existing == null) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_SUBJECT_NOT_FOUND);
            return REDIRECT_BASE;
        }
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, existing);
        }
        // Invalid tab values silently fall back to info.
        String activeTab = VALID_DETAIL_TABS.contains(tab) ? tab : TAB_INFO;
        populateFormModel(model, MODE_EDIT, id, activeTab);
        if (TAB_HISTORY.equals(activeTab)) {
            int safePage = Math.max(0, page);
            model.addAttribute(ATTR_ACTIVITIES_PAGE,
                    queryService.listActivities(id, PageRequest.of(safePage, HISTORY_PAGE_SIZE)));
        }
        return VIEW_ADMIN_SUBJECTS_FORM;
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") SubjectForm form,
                         BindingResult result,
                         @AuthenticationPrincipal KshUserDetails actor,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateFormModel(model, MODE_EDIT, id, TAB_INFO);
            return VIEW_ADMIN_SUBJECTS_FORM;
        }
        try {
            subjectService.update(id, form, actorId(actor));
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SUBJECT_UPDATED);
            return "redirect:" + editUrl(id) + "?tab=" + TAB_INFO;
        } catch (SubjectValidationException ex) {
            model.addAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            populateFormModel(model, MODE_EDIT, id, TAB_INFO);
            return VIEW_ADMIN_SUBJECTS_FORM;
        }
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id,
                         @AuthenticationPrincipal KshUserDetails actor,
                         RedirectAttributes ra) {
        try {
            boolean nowActive = subjectService.toggleActive(id, actorId(actor));
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS,
                    nowActive ? MSG_SUBJECT_ACTIVATED : MSG_SUBJECT_DEACTIVATED);
        } catch (SubjectValidationException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        }
        return REDIRECT_BASE;
    }

    private void populateFormModel(Model model, String mode, Long targetId, String detailTab) {
        model.addAttribute(ATTR_MODE, mode);
        model.addAttribute(ATTR_TARGET_ID, targetId);
        model.addAttribute(ATTR_LEADER_CANDIDATES, queryService.leaderCandidates());
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SUBJECTS);
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, detailTab);
    }

    /** Builds the canonical edit URL for a subject. */
    private static String editUrl(Long id) {
        return URL_ADMIN_SUBJECTS + "/" + id + "/edit";
    }

    private static Long actorId(KshUserDetails actor) {
        return actor == null ? null : actor.getId();
    }
}
