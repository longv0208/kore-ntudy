package com.ksh.features.tests.controller;

import com.ksh.entities.ClassEntity;
import com.ksh.features.classes.controller.support.ClassDetailModelSupport;
import com.ksh.features.classes.service.ClassesService;
import com.ksh.features.tests.dto.LecturerTestDtos.ClassOption;
import com.ksh.features.tests.dto.LecturerTestDtos.ExamForm;
import com.ksh.features.tests.dto.LecturerTestDtos.ExamHeader;
import com.ksh.features.tests.dto.LecturerTestDtos.LecturerExamRow;
import com.ksh.features.tests.dto.LecturerTestDtos.ExamFilter;
import com.ksh.features.tests.dto.LecturerTestDtos.SaveResult;
import com.ksh.features.tests.dto.LecturerTestDtos.TestDistributionResult;
import com.ksh.features.tests.dto.LecturerTestDtos.TestDistributionView;
import com.ksh.features.tests.dto.TestDtos.PreviewView;
import com.ksh.features.tests.service.ExamMonitorService;
import com.ksh.features.tests.service.ExamQuestionBankPickerService;
import com.ksh.features.tests.service.LecturerExamService;
import com.ksh.features.storage.StorageNotConfiguredException;
import com.ksh.features.upload.ExamImageStorageService;
import com.ksh.security.Roles;
import com.ksh.security.KshUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ksh.common.IConstant.ATTR_ACTIVE_DETAIL_TAB;
import static com.ksh.common.IConstant.ATTR_EXAMS_PAGE;
import static com.ksh.common.IConstant.ATTR_EXAM_FORM;
import static com.ksh.common.IConstant.ATTR_FLASH_ERROR;
import static com.ksh.common.IConstant.ATTR_FLASH_SUCCESS;
import static com.ksh.common.IConstant.ATTR_LED_CLASSES;
import static com.ksh.common.IConstant.ATTR_MODE;
import static com.ksh.common.IConstant.ATTR_MONITOR;
import static com.ksh.common.IConstant.ATTR_PAGER_PARAMS;
import static com.ksh.common.IConstant.ATTR_PREVIEW;
import static com.ksh.common.IConstant.ATTR_SUBMISSIONS;
import static com.ksh.common.IConstant.ATTR_TEST;
import static com.ksh.common.IConstant.ATTR_TEST_ACTIVITIES_PAGE;
import static com.ksh.common.IConstant.BASE_LECTURER_TESTS;
import static com.ksh.common.IConstant.MODE_CREATE;
import static com.ksh.common.IConstant.MODE_EDIT;
import static com.ksh.common.IConstant.MSG_STORAGE_R2_NOT_CONFIGURED;
import static com.ksh.common.IConstant.MSG_STORAGE_UPLOAD_FAILED;
import static com.ksh.common.IConstant.TAB_HISTORY;
import static com.ksh.common.IConstant.TAB_INFO;
import static com.ksh.common.IConstant.TAB_MONITOR;
import static com.ksh.common.IConstant.TAB_SUBMISSIONS;
import static com.ksh.common.IConstant.VIEW_TEST_LECTURER_FORM;
import static com.ksh.common.IConstant.VIEW_TEST_LECTURER_LIST;
import static com.ksh.common.IConstant.VIEW_TEST_LECTURER_PREVIEW;
import static com.ksh.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ksh.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ksh.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ksh.features.lessons.controller.support.AjaxResponses.notFound;
import static com.ksh.features.lessons.dto.SectionDtos.AjaxResult;

/**
 * Lecturer-facing SSR controller for exam authoring under {@code /lecturer/tests}:
 * list owned exams, render the create/edit form (question builder), and a JSON
 * save endpoint. Ownership is enforced in the service via {@code TestAccessResolver}.
 */
@Controller
@RequestMapping(BASE_LECTURER_TESTS)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LecturerTestController {

    private static final Logger log = LoggerFactory.getLogger(LecturerTestController.class);

    /** Page size for the "Lịch sử" tab (mirrors the admin user-detail history tab). */
    private static final int HISTORY_PAGE_SIZE = 20;

    private static final String ATTR_SELECTED_CLASS_ID = "selectedClassId";
    private static final String ATTR_SELECTED_SUBJECT_ID = "selectedSubjectId";
    private static final String ATTR_TEST_SUBJECTS = "testSubjects";
    private static final String ATTR_TEST_RETURN_URL = "testReturnUrl";

    /** Whitelist of valid {@code tab} values; anything else falls back to {@code info}. */
    private static final Set<String> VALID_TABS =
            Set.of(TAB_INFO, TAB_MONITOR, TAB_SUBMISSIONS, TAB_HISTORY);

    private final LecturerExamService examService;
    private final ExamMonitorService monitorService;
    private final ClassesService classesService;
    private final ClassDetailModelSupport classDetailSupport;
    private final ExamImageStorageService examImageStorage;
    private final ExamQuestionBankPickerService questionBankPickerService;

    public LecturerTestController(LecturerExamService examService,
                                  ExamMonitorService monitorService,
                                  ClassesService classesService,
                                  ClassDetailModelSupport classDetailSupport,
                                  ExamImageStorageService examImageStorage,
                                  ExamQuestionBankPickerService questionBankPickerService) {
        this.examService = examService;
        this.monitorService = monitorService;
        this.classesService = classesService;
        this.classDetailSupport = classDetailSupport;
        this.examImageStorage = examImageStorage;
        this.questionBankPickerService = questionBankPickerService;
    }

    /** Lists exams the lecturer owns (SSR numbered pager). */
    @GetMapping
    public String list(@RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "q", required = false) String q,
                       @RequestParam(name = "status", required = false) String status,
                       @RequestParam(name = "type", required = false) String type,
                       @RequestParam(name = "classId", required = false) Long classId,
                       @AuthenticationPrincipal KshUserDetails user, Model model) {
        List<ClassOption> classes = examService.ledClasses(user.getId());
        ExamFilter filter = ExamFilter.of(q, status, type, classId, classes);
        Page<LecturerExamRow> exams = examService.listOwned(user.getId(), page, filter);
        model.addAttribute(ATTR_EXAMS_PAGE, exams);
        model.addAttribute("testMetrics", examService.metricsFor(user.getId()));
        model.addAttribute("examFilter", filter);
        model.addAttribute(ATTR_LED_CLASSES, classes);
        model.addAttribute("examPagerParams", java.util.Map.of(
                "q", filter.keyword(), "status", filter.status() == null ? "" : filter.status(),
                "type", filter.type() == null ? "" : filter.type(),
                "classId", filter.classId() == null ? "" : filter.classId()));
        return VIEW_TEST_LECTURER_LIST;
    }

    /** Renders the create form with a blank question builder. */
    @GetMapping("/new")
    public String newForm(@RequestParam(name = "classId", required = false) Long classId,
                          @AuthenticationPrincipal KshUserDetails user, Model model) {
        List<ClassOption> ledClasses = examService.ledClasses(user.getId());
        Long selectedClassId = ledClasses.stream()
                .map(ClassOption::id)
                .filter(id -> id.equals(classId))
                .findFirst()
                .orElse(null);

        model.addAttribute(ATTR_EXAM_FORM, null);
        model.addAttribute(ATTR_LED_CLASSES, ledClasses);
        model.addAttribute(ATTR_TEST_SUBJECTS, examService.subjectOptions(user.getId()));
        model.addAttribute(ATTR_MODE, MODE_CREATE);
        model.addAttribute(ATTR_SELECTED_CLASS_ID, selectedClassId);
        model.addAttribute(ATTR_SELECTED_SUBJECT_ID, ledClasses.stream()
                .filter(option -> option.id().equals(selectedClassId))
                .map(ClassOption::subjectId)
                .findFirst().orElse(null));
        model.addAttribute(ATTR_TEST_RETURN_URL, returnUrlForClass(selectedClassId));
        populateClassSidebarIfViewable(selectedClassId, user, model);
        return VIEW_TEST_LECTURER_FORM;
    }

    /** Shows ACTIVE same-subject classes eligible for an atomic test snapshot. */
    @GetMapping("/{id}/distribute")
    public String distributionForm(@PathVariable Long id,
                                   @AuthenticationPrincipal KshUserDetails user,
                                   Model model) {
        TestDistributionView distribution = examService.distributionView(
                user.getId(), user.getRole(), id);
        model.addAttribute("distribution", distribution);
        return "tests/lecturer-distribute";
    }

    /** Copies one finished test to all selected same-subject classes. */
    @PostMapping("/{id}/distribute")
    public String distribute(@PathVariable Long id,
                             @RequestParam(name = "classIds", required = false) List<Long> classIds,
                             @AuthenticationPrincipal KshUserDetails user,
                             RedirectAttributes ra) {
        try {
            TestDistributionResult result = examService.distributePublished(
                    user.getId(), user.getRole(), id, classIds);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS,
                    "Đã phân phối bài test tới " + result.distributedCount() + " lớp");
            return "redirect:" + BASE_LECTURER_TESTS;
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return "redirect:" + BASE_LECTURER_TESTS + "/" + id + "/distribute";
        } catch (AccessDeniedException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, "Bạn không có quyền phân phối bài test này");
            return "redirect:" + BASE_LECTURER_TESTS;
        } catch (EntityNotFoundException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return "redirect:" + BASE_LECTURER_TESTS;
        }
    }

    /**
     * Student-style preview of an owned exam. Read-only: no attempt is started
     * and answers cannot be submitted. Used from the edit toolbar "Xem trước".
     */
    @GetMapping("/{id}/preview")
    public String preview(@PathVariable Long id,
                          @AuthenticationPrincipal KshUserDetails user, Model model) {
        PreviewView preview = examService.previewAsStudent(id, user.getId(), user.getRole());
        model.addAttribute(ATTR_PREVIEW, preview);
        return VIEW_TEST_LECTURER_PREVIEW;
    }

    /**
     * Renders the exam detail page — a single screen with four tabs
     * (thông tin / theo dõi / bài nộp / lịch sử), routed via {@code ?tab=}.
     * Mirrors the admin user-detail pattern: the info tab hosts the question
     * builder form; the other tabs are loaded lazily per request. Invalid
     * {@code tab} values fall back to {@code info}.
     */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @RequestParam(name = "tab", required = false, defaultValue = TAB_INFO) String tab,
                           @RequestParam(name = "q", required = false) String q,
                           @RequestParam(name = "page", defaultValue = "0") int page,
                           @AuthenticationPrincipal KshUserDetails user, Model model) {
        Long userId = user.getId();
        String activeTab = VALID_TABS.contains(tab) ? tab : TAB_INFO;

        // Header + info-form are always needed (page chrome + tab #1 content).
        ExamForm form = examService.getForEdit(id, userId);
        model.addAttribute(ATTR_EXAM_FORM, form);
        model.addAttribute(ATTR_LED_CLASSES, examService.ledClasses(userId));
        model.addAttribute(ATTR_TEST_SUBJECTS, examService.subjectOptions(userId));
        model.addAttribute(ATTR_SELECTED_SUBJECT_ID, form.subjectId());
        model.addAttribute(ATTR_SELECTED_CLASS_ID, form.classId());
        model.addAttribute(ATTR_MODE, MODE_EDIT);
        model.addAttribute(ATTR_TEST, monitorService.header(id, userId));
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, activeTab);
        model.addAttribute(ATTR_TEST_RETURN_URL, BASE_LECTURER_TESTS);

        populateClassSidebarIfViewable(form.classId(), user, model);

        // Lazy per-tab data — only query what the active tab renders.
        if (TAB_MONITOR.equals(activeTab)) {
            model.addAttribute(ATTR_MONITOR, monitorService.snapshotFor(id, userId));
        } else if (TAB_SUBMISSIONS.equals(activeTab)) {
            model.addAttribute(ATTR_SUBMISSIONS, monitorService.submissionsFor(id, userId, q, page));
            model.addAttribute(ATTR_PAGER_PARAMS, Map.of("tab", TAB_SUBMISSIONS, "q", q == null ? "" : q));
        } else if (TAB_HISTORY.equals(activeTab)) {
            model.addAttribute(ATTR_TEST_ACTIVITIES_PAGE,
                    monitorService.historyFor(id, userId, PageRequest.of(Math.max(0, page), HISTORY_PAGE_SIZE)));
        }
        return VIEW_TEST_LECTURER_FORM;
    }

    /**
     * Adds optional class chrome without making class ownership a second,
     * stricter gate for an already-authorized exam page.
     *
     * <p>An exam creator remains allowed to manage their exam even if they no
     * longer lead the attached class. In that case the test access resolver has
     * already authorized the page, while {@link ClassesService#getViewable}
     * correctly refuses the unrelated class navigation. The page therefore
     * falls back to its full-width layout instead of turning that optional
     * sidebar denial into a 403 for the whole exam.</p>
     */
    private void populateClassSidebarIfViewable(Long classId,
                                                KshUserDetails user,
                                                Model model) {
        if (classId == null) {
            return;
        }
        try {
            ClassEntity clazz = classesService.getViewable(classId, user.getId(), user.getRole());
            classDetailSupport.populateDetail(model, clazz, TAB_INFO, user.getId(), user.getRole());
        } catch (AccessDeniedException ignored) {
            // The test itself is manageable; only the optional class chrome is not.
        }
    }

    private static String returnUrlForClass(Long classId) {
        return classId == null
                ? BASE_LECTURER_TESTS
                : "/lecturer/classes/" + classId + "/tests";
    }

    /**
     * Creates or updates an exam from the JSON question-builder payload; field
     * validation + ownership map onto the shared {@link AjaxResult} envelope.
     */
    @PostMapping(value = "/save", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> save(@RequestBody ExamForm form,
                                  @AuthenticationPrincipal KshUserDetails user) {
        try {
            Long id = examService.save(user.getId(), form);
            return ResponseEntity.ok(AjaxResult.success(new SaveResult(id)));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to save exam", ex);
            return internalError();
        }
    }

    /**
     * Uploads an image for embedding in question HTML (Quill toolbar).
     * Returns {@code { url: "/uploads/exams/..." }} inside the AjaxResult data.
     */
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file,
                                         @AuthenticationPrincipal KshUserDetails user) {
        try {
            String url = examImageStorage.store(user.getId(), file);
            return ResponseEntity.ok(AjaxResult.success(Map.of("url", url)));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (StorageNotConfiguredException ex) {
            return badRequest(MSG_STORAGE_R2_NOT_CONFIGURED);
        } catch (IOException ex) {
            log.error("Failed to upload exam image", ex);
            return badRequest(MSG_STORAGE_UPLOAD_FAILED);
        } catch (Exception ex) {
            log.error("Failed to upload exam image", ex);
            return internalError();
        }
    }
}
