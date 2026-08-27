package com.ksh.common;

/**
 * Project-wide constants shared by controllers (and any other layer that
 * benefits from the same key set). Consumers reference these unqualified via
 * a static import — interface fields are implicitly
 * {@code public static final}, so {@code import static com.ksh.common.IConstant.*}
 * lets a class write {@code ATTR_FORM} directly without {@code IConstant.} prefix.
 *
 * <p><b>Why an interface, not a final class with statics?</b>
 * The interface stays as a namespace for the constants. Consumers pull them
 * in with {@code import static com.ksh.common.IConstant.*}, which keeps the
 * keys as an <em>implementation detail</em> of the consumer rather than
 * leaking through its public type (the classic "constant interface
 * anti-pattern" — Effective Java Item 22). Sub-interfaces can still extend
 * this one to compose specialised key bags if the surface grows.
 *
 * <p><b>What goes here:</b>
 * <ul>
 *   <li>Route prefixes / canonical URLs that span more than one controller.</li>
 *   <li>View names used by SSR templates.</li>
 *   <li>Model attribute keys consumed by Thymeleaf.</li>
 *   <li>Flash message text (Vietnamese — UI surface).</li>
 *   <li>Enum-like discriminators: mode values, tab keys, sub-tab keys.</li>
 *   <li>Pagination / size defaults.</li>
 * </ul>
 *
 * <p><b>What does NOT go here:</b>
 * <ul>
 *   <li>Entity column names — they belong on the entity.</li>
 *   <li>Role / permission strings — see {@code com.ksh.security.Roles}.</li>
 *   <li>One-off literals used by a single method — keep them inline.</li>
 *   <li>Localised UI strings once {@code MessageSource} ships — migrate to
 *       {@code messages.properties}.</li>
 * </ul>
 *
 * <p><b>Backward compatibility:</b> values here are CONTRACT with the
 * Thymeleaf templates. Changing a value (e.g. {@code ATTR_FORM = "form"}
 * → {@code "formData"}) breaks every template that reads {@code ${form}}.
 * Add a new constant for a new key instead of editing an existing one.
 */
public interface IConstant {

    // ───────── Route prefixes / canonical URLs ───────────────────────
    String BASE_LECTURER     = "/lecturer";
    String PATH_CLASSES      = "/classes";
    String URL_CLASSES_LIST  = BASE_LECTURER + PATH_CLASSES;
    String PATH_DASHBOARD    = "/dashboard";
    String URL_LECTURER_DASHBOARD = BASE_LECTURER + PATH_DASHBOARD;
    String PATH_LIBRARY      = "/library";
    String URL_LIBRARY       = BASE_LECTURER + PATH_LIBRARY;

    // ───────── View names ────────────────────────────────────────────
    String VIEW_LECTURER_DASHBOARD      = "lecturer/dashboard";
    String VIEW_LIBRARY                 = "library/index";
    String VIEW_CLASS_MANAGE            = "classes/manage";
    String VIEW_CLASS_FORM              = "classes/form";
    String VIEW_CLASS_DETAIL_BOARD      = "classes/detail-board";
    String VIEW_CLASS_DETAIL_MEMBERS    = "classes/detail-members";
    String VIEW_CLASS_DETAIL_SETTINGS   = "classes/detail-settings";
    String VIEW_CLASS_DETAIL_PLACEHOLDER = "classes/detail-placeholder";
    String VIEW_CLASS_DETAIL_PROGRESS   = "classes/detail-progress";
    String VIEW_CLASS_DETAIL_TESTS      = "classes/detail-tests";
    String VIEW_STUDENT_CLASS_LESSONS   = "student/class-lessons";

    // ───────── Model attribute keys ──────────────────────────────────
    String ATTR_FORM              = "form";
    String ATTR_MODE              = "mode";
    String ATTR_FORM_ACTION       = "formAction";
    String ATTR_CLASS_ID          = "classId";
    String ATTR_CLAZZ             = "clazz";
    String ATTR_ACTIVE_TAB        = "activeTab";
    String ATTR_ACTIVE_DETAIL_TAB = "activeDetailTab";
    String ATTR_FLASH_SUCCESS     = "flashSuccess";
    String ATTR_FLASH_ERROR       = "flashError";
    String ATTR_CLASSES           = "classes";
    String ATTR_CLASSES_PAGE      = "classesPage";
    String ATTR_MEMBERS           = "members";
    String ATTR_MEMBER_TOTAL      = "memberTotal";
    String ATTR_PLACEHOLDER_TAB   = "placeholderTab";
    String ATTR_PLACEHOLDER_LABEL = "placeholderLabel";

    // Lecturer progress dashboard (lecturer-student-progress).
    String ATTR_PROGRESS_SUMMARY = "progressSummary";
    String ATTR_PROGRESS_PAGE    = "progressPage";
    String ATTR_PROGRESS_STATUS  = "progressStatus";
    String ATTR_PROGRESS_QUERY   = "progressQuery";
    String ATTR_PROGRESS_SIZE    = "progressSize";

    // Lecturer teaching dashboard (KSH-9.1).
    String ATTR_TEACHING_STATS      = "teachingStats";
    String ATTR_TEACHING_CLASS_ROWS = "teachingClassRows";
    String ATTR_TEACHING_QUERY      = "teachingQuery";
    String ATTR_TEACHING_SIZE       = "teachingSize";

    // Personal file library (lecturer-file-library).
    String ATTR_LIBRARY_PAGE   = "libraryPage";
    String ATTR_LIBRARY_QUERY  = "libraryQuery";
    String ATTR_LIBRARY_SIZE   = "librarySize";
    String ATTR_LIBRARY_TEMPLATE_COUNT = "libraryTemplateCount";
    String ATTR_LIBRARY_CLASS_OPTIONS  = "libraryClassOptions";

    /** Library sidebar rail for lesson templates (not a file kind). */

    /** Default section title auto-created when cloning into an empty class. */
    String DEFAULT_SECTION_TITLE = "Chương 1";

    // Additional cross-cutting model attribute keys (used by ≥2 controllers).
    String ATTR_USER          = "user";
    String ATTR_REQUEST       = "request";
    String ATTR_TOKEN         = "token";
    String ATTR_INVALID       = "invalid";
    String ATTR_FLASH_INFO    = "flashInfo";
    String ATTR_FLASH_WARNING = "flashWarning";

    String ATTR_LESSON          = "lesson";
    String ATTR_LESSONS         = "lessons";
    String ATTR_LESSON_ID       = "lessonId";
    String ATTR_SELECTED_LESSON = "selectedLesson";

    String ATTR_ATTACHMENTS     = "attachments";

    String ATTR_VIEW              = "view";
    String ATTR_ACTIVE_SECTION_ID = "activeSectionId";

    String ATTR_LESSON_DETAIL     = "lessonDetail";

    // Lessons feature — shared cross-controller attrs
    // (used by SectionsController + LessonsController).
    String ATTR_CANCEL_URL    = "cancelUrl";
    String ATTR_EDIT_BASE_URL = "editBaseUrl";
    String ATTR_ACTIVITY_PAGE = "activityPage";
    String ATTR_SECTION       = "section";

    // ───────── Form mode discriminators ──────────────────────────────
    String MODE_CREATE = "create";
    String MODE_EDIT   = "edit";

    // ───────── Class-detail tab keys ─────────────────────────────────
    String TAB_BOARD       = "board";
    String TAB_SCHEDULE    = "schedule";
    String TAB_MEMBERS     = "members";
    String TAB_ROLES       = "roles";
    String TAB_GROUPS      = "groups";
    String TAB_ASSIGNMENTS = "assignments";
    String TAB_SCORES      = "scores";
    String TAB_LESSONS     = "lessons";
    String TAB_MATERIALS   = "materials";
    String TAB_PROGRESS    = "progress";
    String TAB_TESTS       = "tests";
    String TAB_SETTINGS    = "settings";

    // Additional tab keys (used across admin / detail screens).
    String TAB_DASHBOARD = "dashboard";
    String TAB_USERS     = "users";
    String TAB_INFO      = "info";
    String TAB_HISTORY   = "history";
    String TAB_ACTIVITY  = "activity";
    String TAB_MONITOR     = "monitor";
    String TAB_SUBMISSIONS = "submissions";

    // Settings sub-tabs
    String SUBTAB_INFO   = "info";

    // ───────── Flash messages (Vietnamese UI text) ───────────────────
    // Migrate to messages.properties once MessageSource is wired up.
    String MSG_CLASS_CREATED       = "Đã tạo lớp";
    String MSG_CLASS_UPDATED       = "Đã cập nhật lớp";
    String MSG_CLASS_DELETED       = "Đã xoá lớp";
    // Cross-controller membership messages.
    String MSG_JOINED_CLASS        = "Đã tham gia lớp ";
    String MSG_ALREADY_IN_CLASS    = "Bạn đã ở trong lớp ";
    String MSG_JOIN_REQUEST_SENT   = "Đã gửi yêu cầu tham gia lớp ";
    String MSG_JOIN_REQUEST_PENDING_SUFFIX = " — chờ giảng viên duyệt";
    String MSG_JOIN_ALREADY_PENDING = "Yêu cầu tham gia lớp ";
    String MSG_JOIN_ALREADY_PENDING_SUFFIX = " đang chờ duyệt";
    String MSG_JOIN_APPROVED       = "Đã duyệt yêu cầu tham gia của học sinh";
    String MSG_JOIN_REJECTED       = "Đã từ chối yêu cầu tham gia";
    String MSG_JOIN_APPROVE_FAILED = "Không thể duyệt yêu cầu: ";
    String MSG_JOIN_REJECT_FAILED  = "Không thể từ chối yêu cầu: ";
    String MSG_JOIN_CLASS_FULL     = "Lớp đã đầy, không thể duyệt thêm thành viên";

    // Members tab — pending join requests
    String ATTR_PENDING_MEMBERS = "pendingMembers";
    String ATTR_PENDING_TOTAL   = "pendingTotal";
    String ATTR_PENDING_ROWS    = "pendingRows";

    // Cross-controller admin-settings session guard
    // (used by EmailSettingsController + OauthSettingsController + StorageSettingsController).
    String MSG_OAUTH_SESSION_UNSUPPORTED =
            "Phiên đăng nhập của bạn không hỗ trợ thao tác này. Vui lòng đăng nhập lại bằng email và mật khẩu.";

    // ───────── Object storage (Cloudflare R2) ─────────────────────────
    String STORAGE_PROVIDER_LOCAL = "local";
    String STORAGE_PROVIDER_R2    = "r2";

    String MSG_STORAGE_SETTINGS_SAVED =
            "Đã lưu cấu hình lưu trữ.";
    String MSG_STORAGE_R2_NOT_CONFIGURED =
            "Cloudflare R2 chưa được cấu hình đầy đủ. Vui lòng kiểm tra Cài đặt > Lưu trữ.";
    String MSG_STORAGE_PROVIDER_INVALID =
            "Nhà cung cấp lưu trữ không hợp lệ (chọn local hoặc r2).";
    String MSG_STORAGE_R2_FIELDS_REQUIRED =
            "Khi chọn R2, Access Key, Secret, Bucket và Endpoint là bắt buộc.";
    String MSG_STORAGE_UPLOAD_FAILED =
            "Không lưu được tệp lên kho lưu trữ. Vui lòng thử lại.";
    String MSG_STORAGE_PROFILE_UNAVAILABLE =
            "Kho lưu trữ toàn hệ thống chưa sẵn sàng. Quản trị viên cần kiểm tra Cài đặt hệ thống > Lưu trữ toàn hệ thống.";
    String MSG_STORAGE_R2_CONNECT_FAILED =
            "Không kết nối được tới Cloudflare R2";

    // ───────── Lesson status discriminators ─────────────────────────
    String LESSON_STATUS_DRAFT     = "DRAFT";
    String LESSON_STATUS_PUBLISHED = "PUBLISHED";

    // ───────── Lesson content-type discriminators ──────────────────
    String CONTENT_TYPE_RICHTEXT = "RICHTEXT";
    String CONTENT_TYPE_PDF      = "PDF";
    String CONTENT_TYPE_VIDEO    = "VIDEO";

    // YOUTUBE / VIMEO point at an external embed; UPLOAD points at a
    // server-relative MP4 path.
    String VIDEO_PROVIDER_YOUTUBE = "YOUTUBE";
    String VIDEO_PROVIDER_VIMEO   = "VIMEO";
    String VIDEO_PROVIDER_UPLOAD  = "UPLOAD";

    // Hard cap for uploaded MP4 size: 200 MB.
    long MAX_VIDEO_SIZE_BYTES = 200L * 1024L * 1024L;

    // ───────── Lesson flash messages (Vietnamese UI text) ────────────
    String MSG_LESSON_CREATED      = "Đã tạo bài giảng";
    String MSG_LESSON_UPDATED      = "Đã cập nhật bài giảng";
    String MSG_LESSON_DELETED      = "Đã xoá bài giảng";
    String MSG_LESSON_PUBLISHED    = "Đã xuất bản bài giảng";
    String MSG_LESSON_UNPUBLISHED  = "Đã chuyển bài giảng về nháp";
    String MSG_LESSON_NOT_FOUND    = "Bài giảng không tồn tại";

    // Lessons feature — shared cross-controller messages
    // (used by SectionsController + LessonsController).
    String MSG_SECTION_NOT_FOUND   = "Chương không tồn tại";
    String MSG_FORBIDDEN_FOR_CLASS = "Bạn không có quyền thao tác với lớp này.";
    String MSG_GENERIC_RETRY       = "Có lỗi xảy ra, vui lòng thử lại.";

    // ───────── Attachment messages (Vietnamese UI text) ──────────────
    String MSG_ATTACHMENT_UPLOADED       = "Đã tải lên tệp đính kèm";
    String MSG_ATTACHMENT_DELETED        = "Đã xoá tệp đính kèm";
    String MSG_ATTACHMENT_INVALID        = "Tệp đính kèm không hợp lệ";
    String MSG_ATTACHMENT_TOO_LARGE      = "Tệp đính kèm vượt quá giới hạn 20MB";
    String MSG_ATTACHMENT_EXT_NOT_ALLOWED =
            "Chỉ chấp nhận tệp PDF, DOCX, PPTX, XLSX hoặc ZIP";
    String MSG_ATTACHMENT_MAGIC_FAIL     = "Định dạng tệp không hợp lệ";
    String MSG_ATTACHMENT_NOT_FOUND      = "Không tìm thấy tệp đính kèm";
    String MSG_ATTACHMENT_EMPTY          = "Tệp đính kèm rỗng";

    // ───────── Content-type messages (Vietnamese UI text) ────────────
    String MSG_LESSON_CONTENT_TYPE_REQUIRED = "Loại nội dung không hợp lệ";
    String MSG_LESSON_PDF_NOT_UPLOADED      = "PDF chưa được tải lên";
    String MSG_LESSON_VIDEO_NOT_CONFIGURED  = "Chưa cấu hình video";
    String MSG_VIDEO_URL_INVALID            = "URL video không hợp lệ";
    String MSG_VIDEO_FILE_TOO_LARGE         = "Video vượt quá giới hạn 200MB";
    String MSG_VIDEO_FILE_NOT_MP4           = "Chỉ chấp nhận tệp MP4";

    // ───────── Student-facing messages (Vietnamese UI text) ──────────
    String MSG_STUDENT_LESSONS_EMPTY_SECTION = "Chương này chưa có bài giảng";
    String MSG_STUDENT_LESSONS_EMPTY_CLASS   = "Lớp này chưa có chương";

    // ───────── Learning-progress flash messages (KSH-4.5) ────────────
    String MSG_PROGRESS_MARKED_COMPLETE   = "Đã đánh dấu hoàn thành bài giảng";
    String MSG_PROGRESS_MARKED_INCOMPLETE = "Đã bỏ đánh dấu hoàn thành";
    String MSG_PROGRESS_CHECKLIST_REQUIRED =
            "Hãy hoàn thành đủ thời gian ở các mục nội dung, video và tài liệu đang có";

    // ───────── Lecturer progress dashboard (Vietnamese UI text) ───────
    String MSG_STUDENT_NOT_IN_CLASS = "Sinh viên không thuộc lớp này";

    // ───────── Flashcards (KSH-5.x) ──────────────────────────────────
    // Route prefixes / canonical URLs.
    String BASE_FLASHCARDS      = "/my/flashcards";
    String PATH_PUBLIC_DECK     = "/s";
    String API_FLASHCARDS       = "/api/flashcards";
    String SUBPATH_AI_GENERATE  = "/ai-generate";

    // View names.
    String VIEW_FLASHCARD_LIST   = "flashcards/list";
    String VIEW_FLASHCARD_FORM   = "flashcards/deck-form";
    String VIEW_FLASHCARD_DETAIL = "flashcards/deck-detail";
    String VIEW_PUBLIC_DECK      = "flashcards/public-deck";
    String VIEW_FLASHCARD_FLIP   = "flashcards/flashcard-flip";
    String VIEW_FLASHCARD_REVIEW = "flashcards/flashcard-review";
    String VIEW_FLASHCARD_LEARNING = "flashcards/flashcard-learning";

    // Model attribute keys.
    String ATTR_DECK          = "deck";
    String ATTR_DECKS_OWN_PAGE = "ownDecksPage";
    String ATTR_DECKS_SHARED  = "sharedDecks";
    String ATTR_CARDS         = "cards";
    String ATTR_CARDS_JSON    = "cardsJson";
    String ATTR_DUE_COUNT     = "dueCount";
    String ATTR_SHARE_CLASSES = "shareClasses";

    // Flash / error messages (Vietnamese UI text).
    String MSG_DECK_CREATED    = "Đã tạo bộ thẻ";
    String MSG_DECK_UPDATED    = "Đã lưu bộ thẻ";
    String MSG_DECK_DELETED    = "Đã xoá bộ thẻ";
    String MSG_DECK_SHARED     = "Đã chia sẻ bộ thẻ cho lớp";
    String MSG_DECK_UNSHARED   = "Đã chuyển bộ thẻ về riêng tư";
    String MSG_DECK_PUBLIC_LINK_ENABLED = "Đã bật link công khai";
    String MSG_DECK_PUBLIC_LINK_DISABLED = "Đã tắt link công khai";
    String MSG_DECK_PUBLIC_LINK_REGENERATED = "Đã tạo link mới; link cũ không còn hiệu lực";
    String MSG_DECK_TITLE_BLANK = "Tiêu đề không được để trống";
    String MSG_CARD_SIDE_BLANK = "Mỗi thẻ phải có cả mặt trước và mặt sau";
    String MSG_CARD_NOT_FOUND  = "Không tìm thấy thẻ";
    String MSG_DECK_NOT_FOUND  = "Không tìm thấy bộ thẻ";
    String MSG_SHARE_CLASS_INVALID = "Bạn không thuộc lớp này";
    String MSG_AI_NO_MATERIAL = "Vui lòng tải lên tài liệu hoặc dán nội dung để sinh thẻ";

    // SM-2 rating → quality map (Không nhớ / Khó / Tốt / Dễ).
    int QUALITY_FORGOT = 1;
    int QUALITY_HARD   = 3;
    int QUALITY_GOOD   = 4;
    int QUALITY_EASY   = 5;

    // ───────── MCQ online exams (Epic #11) ───────────────────────────
    // Route prefixes / canonical URLs.
    String BASE_MY_TESTS       = "/my/tests";
    String API_TESTS           = "/api/tests";
    String BASE_LECTURER_TESTS = BASE_LECTURER + "/tests";

    // View names.
    String VIEW_TEST_LIST          = "tests/list";
    String VIEW_TEST_DETAIL        = "tests/detail";
    String VIEW_TEST_TAKE          = "tests/take";
    String VIEW_TEST_RESULT        = "tests/result";
    String VIEW_TEST_REVIEW        = "tests/review";
    String VIEW_TEST_PRACTICE_NEW  = "tests/practice-new";
    String VIEW_TEST_READINESS     = "tests/readiness";
    String VIEW_TEST_LECTURER_LIST = "tests/lecturer-list";
    String VIEW_TEST_LECTURER_FORM = "tests/lecturer-form";
    String VIEW_TEST_LECTURER_PREVIEW = "tests/lecturer-preview";
    String VIEW_STUDENT_CLASS_TESTS = "student/class-tests";

    // Model attribute keys.
    String ATTR_EXAMS_PAGE   = "examsPage";
    String ATTR_DETAIL       = "detail";
    String ATTR_TAKE         = "take";
    String ATTR_RESULT       = "result";
    String ATTR_REVIEW       = "review";
    String ATTR_READINESS    = "readiness";
    String ATTR_PRACTICE     = "practice";
    String ATTR_EXAM_FORM    = "examForm";
    String ATTR_TEST         = "test";
    String ATTR_LED_CLASSES  = "ledClasses";
    String ATTR_MONITOR      = "monitor";
    String ATTR_SUBMISSIONS  = "submissions";
    String ATTR_TEST_ACTIVITIES_PAGE = "activitiesPage";
    String ATTR_PREVIEW      = "preview";

    // Readiness band labels (Vietnamese UI text).
    String BAND_NOT_READY = "Chưa sẵn sàng";
    String BAND_OK        = "Khá";
    String BAND_READY     = "Sẵn sàng";

    // Flash / validation messages (Vietnamese UI text).
    String MSG_EXAM_SAVED            = "Đã lưu bài test";
    String MSG_EXAM_DELETED          = "Đã xoá bài test";
    String MSG_EXAM_SUBMITTED        = "Đã nộp bài";
    String MSG_PRACTICE_CREATED      = "Đã tạo bài luyện tập";
    String MSG_EXAM_TITLE_BLANK      = "Tiêu đề bài test không được để trống";
    String MSG_EXAM_TITLE_TOO_LONG   = "Tiêu đề bài test không được vượt quá 300 ký tự";
    String MSG_EXAM_NEEDS_CLASS      = "Vui lòng chọn lớp cho bài test";
    String MSG_EXAM_NEEDS_SUBJECT    = "Vui lòng chọn môn học cho bài test";
    String MSG_EXAM_NEEDS_QUESTIONS  = "Bài test phải có ít nhất một câu hỏi";
    String MSG_EXAM_TYPE_INVALID     = "Loại bài test không hợp lệ";
    String MSG_EXAM_STATUS_INVALID   = "Trạng thái bài test không hợp lệ";
    String MSG_EXAM_TIME_MODE_INVALID = "Cách tính giờ không hợp lệ";
    String MSG_EXAM_TIME_RANGE_INVALID = "Thời gian kết thúc phải sau thời gian bắt đầu";
    String MSG_EXAM_FIXED_WINDOW_REQUIRED =
            "Bài test theo khung giờ chung phải có thời gian bắt đầu và kết thúc";
    String MSG_EXAM_DURATION_REQUIRED =
            "Bài test tính giờ theo học sinh phải có thời lượng làm bài";
    String MSG_EXAM_DURATION_INVALID = "Thời lượng làm bài phải từ 1 đến 600 phút";
    String MSG_EXAM_MEDIA_URL_REQUIRED = "Vui lòng nhập URL media khi đã chọn loại media";
    String MSG_EXAM_MEDIA_TYPE_REQUIRED = "Vui lòng chọn loại media khi đã nhập URL";
    String MSG_EXAM_MEDIA_YOUTUBE_INVALID = "URL YouTube không hợp lệ";
    String MSG_EXAM_MEDIA_URL_SCHEME = "URL media phải bắt đầu bằng http:// hoặc https://";
    String MSG_EXAM_MEDIA_TYPE_INVALID = "Loại media không hợp lệ";
    String MSG_EXAM_IMAGE_EMPTY = "File ảnh rỗng";
    String MSG_EXAM_IMAGE_TOO_LARGE = "Ảnh vượt quá giới hạn 2MB";
    String MSG_EXAM_IMAGE_TYPE = "Chỉ chấp nhận ảnh JPEG, PNG hoặc WebP";
    String MSG_EXAM_IMAGE_INVALID = "Nội dung file không phải ảnh hợp lệ";
    String MSG_EXAM_IMAGE_STAGED_INVALID = "Ảnh tải lên không hợp lệ hoặc đã hết hạn";
    String MSG_QUESTION_CONTENT_BLANK = "Nội dung câu hỏi không được để trống";
    String MSG_QUESTION_TYPE_INVALID = "Loại câu hỏi không hợp lệ";
    String MSG_QUESTION_POINTS_INVALID =
            "Điểm mỗi câu phải lớn hơn 0, tối đa 999.99 và có không quá 2 chữ số thập phân";
    String MSG_EXAM_TOTAL_POINTS_INVALID =
            "Tổng điểm bài test không được vượt quá 9999.99";
    String MSG_EXAM_PASSING_SCORE_INVALID =
            "Điểm đạt phải từ 0 đến tổng điểm, tối đa 999.99 và có không quá 2 chữ số thập phân";
    String MSG_OPTION_CONTENT_BLANK = "Nội dung đáp án không được để trống";
    String MSG_QUESTION_NEEDS_OPTIONS = "Mỗi câu hỏi phải có ít nhất hai lựa chọn";
    String MSG_QUESTION_NEEDS_CORRECT = "Mỗi câu hỏi phải có ít nhất một đáp án đúng";
    String MSG_MCQ_ONE_CORRECT       = "Câu hỏi một đáp án (MCQ) phải có đúng một đáp án đúng";
    String MSG_EXAM_QUESTION_BANK_LOCKED =
            "Bài test đã có lượt làm: nội dung, đáp án, lớp và cấu hình thời gian đã được khóa để bảo toàn lịch sử chấm điểm.";
    String MSG_EXAM_CONTENT_TOO_LARGE =
            "Nội dung câu hỏi/đáp án quá lớn. Hãy chèn ảnh bằng nút ảnh (không dán base64).";
    String MSG_PRACTICE_EMPTY_POOL   = "Không có câu hỏi phù hợp để tạo bài luyện tập";
    String MSG_PRACTICE_INVALID_SOURCE = "Nguồn câu hỏi không hợp lệ";

    // Exam list / submissions page sizes (default + upper bound for ?size).
    int DEFAULT_EXAM_PAGE_SIZE        = 12;
    int DEFAULT_SUBMISSIONS_PAGE_SIZE = 20;

    // ───────── Assignments (Sprint 6, #70) ──────────────────────────
    // Route prefixes / canonical URLs.
    String PATH_ASSIGNMENTS              = "/assignments";
    String URL_LECTURER_ASSIGNMENTS      = BASE_LECTURER + PATH_CLASSES + "/{classId}" + PATH_ASSIGNMENTS;

    // View names.
    String VIEW_ASSIGNMENT_LIST          = "assignments/lecturer-list";
    String VIEW_ASSIGNMENT_FORM          = "assignments/lecturer-form";
    String VIEW_ASSIGNMENT_SUBMISSIONS   = "assignments/lecturer-submissions";
    String VIEW_ASSIGNMENT_GRADE         = "assignments/lecturer-grade";
    String VIEW_STUDENT_ASSIGNMENT_LIST  = "assignments/student-list";
    String VIEW_STUDENT_ASSIGNMENT_DETAIL = "assignments/student-detail";

    // Model attribute keys.
    String ATTR_ASSIGNMENT          = "assignment";
    String ATTR_ASSIGNMENTS         = "assignments";
    String ATTR_ASSIGNMENT_FORM     = "assignmentForm";
    String ATTR_SUBMISSION          = "submission";
    String ATTR_SUBMISSION_FORM     = "submitForm";
    String ATTR_GRADE_FORM          = "gradeForm";

    // Flash messages (Vietnamese UI text).
    String MSG_ASSIGNMENT_CREATED    = "Đã tạo bài tập";
    String MSG_ASSIGNMENT_UPDATED    = "Đã cập nhật bài tập";
    String MSG_ASSIGNMENT_PUBLISHED  = "Đã xuất bản bài tập";
    String MSG_ASSIGNMENT_CLOSED     = "Đã đóng bài tập";
    String MSG_ASSIGNMENT_NOT_FOUND  = "Không tìm thấy bài tập";
    String MSG_SUBMIT_SUCCESS        = "Đã nộp bài thành công";
    String MSG_SUBMIT_LATE           = "Bài tập đã quá hạn, không thể nộp trễ";
    String MSG_SUBMIT_AFTER_GRADED   = "Mỗi bài tập chỉ được nộp một lần; bài của bạn đã được khóa";
    String MSG_SUBMIT_CONTENT_REQUIRED = "Nội dung bài nộp không được để trống";
    String MSG_GRADE_SUCCESS         = "Đã chấm điểm thành công";
    String MSG_GRADE_SCORE_INVALID   = "Điểm phải nằm trong khoảng 0 đến điểm tối đa";
    String MSG_ASSIGNMENT_INVALID_TRANSITION = "Không thể thực hiện thao tác này với trạng thái hiện tại";
    String MSG_NOT_ENROLLED          = "Bạn không thuộc lớp này";
    String MSG_ASSIGNMENT_TITLE_BLANK = "Tiêu đề không được để trống";
    String MSG_ASSIGNMENT_TITLE_TOO_LONG = "Tiêu đề tối đa 300 ký tự";
    String MSG_ASSIGNMENT_MAX_SCORE_NEGATIVE = "Điểm tối đa không được âm";
    String MSG_ASSIGNMENT_MAX_SCORE_INVALID = "Điểm tối đa không vượt quá 999,99 và chỉ có tối đa 2 chữ số thập phân";
    String MSG_GRADE_SCORE_FORMAT_INVALID = "Điểm chỉ có tối đa 2 chữ số thập phân và không vượt quá 999,99";

    // Notification titles/bodies for assignment events (lecturer/student services).
    String MSG_NOTIF_ASSIGNMENT_PUBLISHED_TITLE = "Bài tập mới được xuất bản";
    String MSG_NOTIF_ASSIGNMENT_PUBLISHED_BODY_PREFIX = "Bài tập \"";
    String MSG_NOTIF_ASSIGNMENT_PUBLISHED_BODY_SUFFIX = "\" vừa được xuất bản.";
    String MSG_NOTIF_ASSIGNMENT_GRADED_TITLE = "Bài tập đã được chấm điểm";
    String MSG_NOTIF_ASSIGNMENT_GRADED_BODY_PREFIX = "Bài tập \"";
    String MSG_NOTIF_ASSIGNMENT_GRADED_BODY_MID = "\" của bạn đã được chấm. Điểm: ";

    // ───────── Notifications (Sprint 5, #63/#64) ─────────────────────
    // Route prefix / canonical URL.
    String BASE_MY_NOTIFICATIONS = "/my/notifications";

    // View names.
    String VIEW_NOTIFICATIONS_INDEX = "notifications/index";

    // Model attribute keys.
    String ATTR_NOTIFICATIONS   = "notifications";
    String ATTR_NOTIF_UNREAD    = "notifUnreadCount";

    // Flash messages (Vietnamese UI text).
    String MSG_NOTIF_READ = "Đã đánh dấu đã đọc";

    // ───────── Direct messaging (Epic #13, KSH-8.3 + KSH-8.4) ────────
    // Route prefix / canonical URL.
    String BASE_MY_MESSAGES = "/my/messages";

    // View names.
    String VIEW_MESSAGING_INDEX        = "messaging/index";
    String VIEW_MESSAGING_CONVERSATION = "messaging/conversation";
    String VIEW_STUDENT_CLASS_MESSAGES = "student/class-messages";

    // Model attribute keys.
    String ATTR_CONVERSATIONS = "conversations";
    String ATTR_CONVERSATION  = "conversation";
    String ATTR_MSG_UNREAD    = "msgUnreadCount";
    String ATTR_RECIPIENTS     = "recipients";
    String ATTR_COMPOSE        = "compose";
    String ATTR_COMPOSE_QUERY  = "composeQuery";

    // Pagination.
    int DEFAULT_PAGE_SIZE = 20;

    // Lessons feature — shared paging (history tab page size).
    int DEFAULT_HISTORY_PAGE_SIZE = 20;

    // Lecturer progress dashboard — table page size (default + upper bound).
    int DEFAULT_PROGRESS_PAGE_SIZE = 10;
    int MAX_PROGRESS_PAGE_SIZE = 100;

    // Lecturer teaching dashboard — class table page size (default + upper bound).
    int DEFAULT_TEACHING_PAGE_SIZE = 10;
    int MAX_TEACHING_PAGE_SIZE = 100;

    // Personal file library — SSR / picker page size.
    int DEFAULT_LIBRARY_PAGE_SIZE = 12;
    int MAX_LIBRARY_PAGE_SIZE = 50;

    // Flashcards — own decks per SSR numbered-pager page.
    int DEFAULT_DECK_PAGE_SIZE = 12;

    // Shared pager fragment — Map of query params to preserve across pages
    // (status/q/size/…). Consumed by templates/fragments/pager.html.
    // Numbered-button window size lives in com.ksh.common.PageWindow.
    String ATTR_PAGER_PARAMS = "params";

    // ───────── Admin departments + LEADER shell ────────────────────────
    String URL_ADMIN_DEPARTMENTS = "/admin/departments";
    String VIEW_ADMIN_DEPARTMENTS      = "admin/departments";
    String VIEW_ADMIN_DEPARTMENTS_FORM = "admin/departments-form";
    String TAB_DEPARTMENTS = "departments";

    String ATTR_DEPARTMENTS     = "departments";
    String ATTR_LEADER_CANDIDATES = "leaderCandidates";
    String ATTR_TARGET_ID = "targetId";
    // Shared key "activitiesPage" — same string as users edit history tab.
    String ATTR_ACTIVITIES_PAGE = "activitiesPage";

    String MSG_DEPARTMENT_CREATED     = "Đã thêm môn học ";
    String MSG_DEPARTMENT_UPDATED     = "Đã cập nhật môn học";
    String MSG_DEPARTMENT_ACTIVATED   = "Đã hiện môn học";
    String MSG_DEPARTMENT_DEACTIVATED = "Đã ẩn môn học";
    String MSG_DEPARTMENT_NOT_FOUND   = "Không tìm thấy môn học";

    // LEADER product area.
    String BASE_LEADER              = "/leader";
    String URL_LEADER_DASHBOARD     = BASE_LEADER;
    String URL_LEADER_ASSIGN        = BASE_LEADER + "/assign";
    String URL_LEADER_REPORT        = BASE_LEADER + "/report";
    String VIEW_LEADER_DASHBOARD    = "leader/dashboard";
    String VIEW_LEADER_ASSIGN       = "leader/assign";
    String VIEW_LEADER_REPORT       = "leader/report";
    String VIEW_LEADER_QB_TESTS      = "leader/question-bank-tests";
    String ATTR_LEADER_QB_TESTS      = "qbTests";
    String ATTR_LEADER_DEPARTMENT   = "leaderDepartment";
    String ATTR_LEADER_KPIS         = "kpis";
    String ATTR_LEADER_RECENT       = "recentClasses";
    String ATTR_LEADER_CLASS_ROWS   = "classRows";
    String ATTR_LEADER_LECTURERS    = "lecturers";
    String ATTR_LEADER_REPORT_ROWS  = "reportRows";
    String ATTR_LEADER_EMPTY        = "emptyDepartment";
    String ATTR_QB_ITEMS          = "items";
    String ATTR_QB_SELECTED_STATUS = "selectedStatus";
    String ATTR_QB_QUERY          = "query";
    String ATTR_QB_EMPTY_DEPARTMENT = "emptyDepartment";
    String ATTR_QB_DETAIL         = "detail";
    String MSG_LEADER_REASSIGNED    = "Đã phân công giảng viên cho lớp ";
    String MSG_LEADER_REASSIGN_FAIL = "Không thể phân công giảng viên";
    String MSG_QB_DRAFT_SAVED     = "Đã lưu câu hỏi nháp";
    String MSG_QB_SUBMITTED       = "Đã gửi câu hỏi lên hàng chờ duyệt";
    String MSG_QB_UPDATED         = "Đã cập nhật câu hỏi";
    String MSG_QB_RESUBMITTED     = "Đã cập nhật và gửi lại hàng chờ duyệt";
    String MSG_QB_APPROVED        = "Đã duyệt câu hỏi";
    String MSG_QB_REJECTED        = "Đã trả lại câu hỏi cho giảng viên";
    String MSG_QB_ARCHIVED        = "Đã lưu trữ câu hỏi";
    String MSG_QB_UNARCHIVED      = "Đã bỏ lưu trữ câu hỏi";

    // ───────── Shared subject-scoped question bank ────────────────────
    // Route prefixes / canonical URLs.
    String BASE_LECTURER_QUESTION_BANK = BASE_LECTURER + "/question-bank";
    String URL_LECTURER_QUESTION_BANK  = BASE_LECTURER_QUESTION_BANK;
    String BASE_LEADER_QUESTION_BANK     = BASE_LEADER + "/question-bank";
    String URL_LEADER_QUESTION_BANK_MANAGE = BASE_LEADER_QUESTION_BANK;

    // View names.
    String VIEW_QB_LIST   = "questionbank/list";
    String VIEW_QB_DETAIL = "questionbank/detail";
    String VIEW_QB_FORM   = "questionbank/form";
    // Model attribute keys.
    String ATTR_QB_SELECTED_CONTRIBUTOR_ID = "selectedContributorId";

    // LEADER subject review with question detail + bulk actions.
    String VIEW_QB_SUBJECT_REVIEW      = "questionbank/subject-review";
    String PARAM_QB_ITEM_IDS           = "itemIds";
    String MSG_QB_BULK_EMPTY           = "Vui lòng chọn ít nhất một câu hỏi";
    String MSG_QB_BULK_APPROVED_PREFIX = "Đã duyệt ";
    String MSG_QB_BULK_REJECTED_PREFIX = "Đã trả lại ";
    String MSG_QB_BULK_ARCHIVED_PREFIX = "Đã lưu trữ ";
    String MSG_QB_BULK_UNARCHIVED_PREFIX = "Đã bỏ lưu trữ ";
    String MSG_QB_BULK_SUFFIX_ITEMS    = " câu";
    String MSG_QB_BULK_SKIPPED_PREFIX  = ", bỏ qua ";
    String MSG_QB_BULK_SKIPPED_SUFFIX  = " câu không hợp lệ";

    // Insert-from-bank into test authoring (Vietnamese UI text).
    String MSG_QB_INSERTED_PREFIX = "Đã chèn ";
    String MSG_QB_INSERTED_SUFFIX = " câu hỏi đã duyệt vào bài test";
    String MSG_QB_INSERT_LOCKED   =
            "Bài test đã có bài nộp nên không thể thêm câu hỏi mới từ ngân hàng";
    String MSG_QB_INSERT_EMPTY    = "Vui lòng chọn ít nhất một câu hỏi đã duyệt để chèn";

    // ───────── Personal file library (Vietnamese UI text) ────────────
    String MSG_LIBRARY_ASSET_NOT_FOUND = "Không tìm thấy học liệu";
    String MSG_LIBRARY_BIND_INVALID_KIND =
            "Loại học liệu không phù hợp với thao tác này";
    String MSG_LIBRARY_BIND_NOT_PDF    = "Chỉ có thể chọn tệp PDF làm nội dung chính";

    // ───────── Lesson templates (Vietnamese UI text) ─────────────────
    String MSG_TEMPLATE_DELETED        = "Đã xoá mẫu bài giảng";
    String MSG_TEMPLATE_NOT_FOUND      = "Không tìm thấy mẫu bài giảng";
    String MSG_TEMPLATE_BODY_INCOMPLETE =
            "Bài giảng chưa đủ nội dung để lưu mẫu (thiếu PDF/video/nội dung)";

    // Admin permission management.
    String URL_ADMIN_PERMISSIONS           = "/admin/permissions";
    String URL_ADMIN_PERMISSIONS_OVERRIDES = URL_ADMIN_PERMISSIONS + "/overrides";

    String VIEW_ADMIN_PERMISSIONS_ROLES     = "admin/permissions-roles";
    String VIEW_ADMIN_PERMISSIONS_OVERRIDES = "admin/permissions-overrides";

    String TAB_PERMISSIONS = "permissions";

    String ATTR_PERMISSION_MATRIX    = "matrix";
    String ATTR_PERMISSION_OVERRIDES = "overrides";
    String ATTR_PERMISSION_CATALOG   = "permissionCatalog";
    String ATTR_OVERRIDE_USERS       = "overrideUsers";
    String ATTR_FLASH_REASON_ERROR   = "flashReasonError";
    String ATTR_OVERRIDE_FORM        = "overrideForm";

    String MSG_PERMISSION_ATTACHED  = "Đã cấp quyền cho vai trò";
    String MSG_PERMISSION_DETACHED  = "Đã gỡ quyền khỏi vai trò";
    String MSG_OVERRIDE_SAVED       = "Đã lưu ngoại lệ quyền";
    String MSG_OVERRIDE_DEACTIVATED = "Đã huỷ ngoại lệ quyền";
    String MSG_PERMISSION_NOT_FOUND = "Không tìm thấy quyền";

    // Admin AI provider settings.
    String URL_SETTINGS_AI       = "/admin/settings/ai";
    String VIEW_SETTINGS_AI      = "admin/settings-ai";
    String VIEW_SETTINGS_AI_FORM = "admin/settings-ai-form";

    String ATTR_AI_PROVIDERS    = "providers";
    String ATTR_AI_PROVIDER     = "provider";
    String ATTR_AI_ALL_DISABLED = "allDisabled";

    String MSG_AI_PROVIDER_CREATED   = "Đã thêm AI provider";
    String MSG_AI_PROVIDER_UPDATED   = "Đã cập nhật AI provider";
    String MSG_AI_PROVIDER_DELETED   = "Đã xoá AI provider";
    String MSG_AI_PROVIDER_ENABLED   = "Đã bật AI provider";
    String MSG_AI_PROVIDER_DISABLED  = "Đã tắt AI provider";
    String MSG_AI_PROVIDER_NOT_FOUND = "Không tìm thấy AI provider";
    String MSG_AI_NAME_DUPLICATE     = "Tên provider đã tồn tại";
    String MSG_AI_KEY_REQUIRED       = "API key là bắt buộc khi thêm provider mới";

    // ───────── AI system prompts ─────────────────────────────────────
    String URL_SETTINGS_AI_PROMPTS  = "/admin/settings/ai/prompts";
    String VIEW_SETTINGS_AI_PROMPTS = "admin/settings-ai-prompts";

    String ATTR_AI_PROMPTS = "prompts";

    // ───────── AI system prompts (Vietnamese UI text) ────────────────
    String MSG_AI_PROMPT_CREATED       = "Đã thêm system prompt";
    String MSG_AI_PROMPT_UPDATED       = "Đã cập nhật system prompt";
    String MSG_AI_PROMPT_DELETED       = "Đã xoá system prompt";
    String MSG_AI_PROMPT_ENABLED       = "Đã bật system prompt";
    String MSG_AI_PROMPT_DISABLED      = "Đã tắt system prompt";
    String MSG_AI_PROMPT_NOT_FOUND     = "Không tìm thấy system prompt";
    String MSG_AI_PROMPT_NAME_DUPLICATE = "Tên prompt đã tồn tại";

    // ───────── AI request logs ───────────────────────────────────────
    String URL_SETTINGS_AI_LOGS  = "/admin/settings/ai/logs";
    String VIEW_SETTINGS_AI_LOGS = "admin/settings-ai-logs";

    String ATTR_AI_LOGS_PAGE      = "page";
    String ATTR_AI_LOGS_FILTER    = "filter";
    String ATTR_AI_LOGS_TOTALS    = "totals";
    String ATTR_AI_LOGS_PROVIDERS = "providerNames";
}
