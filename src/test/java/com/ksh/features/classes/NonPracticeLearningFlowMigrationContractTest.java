package com.ksh.features.classes;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NonPracticeLearningFlowMigrationContractTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    void subject_seed_and_class_lifecycle_are_explicit() throws IOException {
        String sql = read("V88__subject_catalog_and_class_lifecycle.sql");

        assertThat(sql).contains(
                "'KOR311'", "'KOR321'", "'KOR411'",
                "'KRL112'", "'KRL122'", "'KRL212'", "'KRL222'",
                "Chương trình Kỹ sư cầu nối Hàn Quốc",
                "Chương trình Ngôn ngữ Hàn",
                "ON DUPLICATE KEY UPDATE",
                "'lecturer@ksh.edu.vn', 'leader@ksh.edu.vn'");
        assertThat(sql).contains("'DRAFT','ACTIVE','ARCHIVED'");
        assertThat(sql).doesNotContain("CREATE TABLE");
    }

    @Test
    void class_approval_uses_only_pending_rejected_active_and_archived() throws IOException {
        String migration = read("V124__class_four_state_approval_lifecycle.sql");
        String entity = Files.readString(Path.of("src/main/java/com/ksh/entities/ClassEntity.java"));
        String approvalService = Files.readString(Path.of(
                "src/main/java/com/ksh/features/leader/service/LeaderClassApprovalService.java"));

        assertThat(migration).contains(
                "CHECK (status IN ('PENDING','REJECTED','ACTIVE','ARCHIVED'))",
                "DEFAULT 'PENDING'",
                "WHEN status = 'DRAFT'");
        assertThat(entity).contains(
                "STATUS_PENDING", "STATUS_REJECTED", "STATUS_ACTIVE", "STATUS_ARCHIVED",
                "resubmitForReview()")
                .doesNotContain("STATUS_DRAFT", "STATUS_UPCOMING", "STATUS_COMPLETED", "STATUS_CANCELLED");
        assertThat(approvalService).contains("ClassEntity.STATUS_PENDING");
    }

    @Test
    void compaction_adds_only_the_approved_co_lecturer_table() throws IOException {
        List<String> files = List.of(
                "V88__subject_catalog_and_class_lifecycle.sql",
                "V89__add_class_co_lecturers.sql",
                "V90__remove_class_invites.sql",
                "V91__subject_activity_audit.sql",
                "V92__remove_courses_and_general_categories.sql",
                "V93__scope_question_bank_by_subject.sql",
                "V94__remove_lesson_comments.sql",
                "V95__subject_library_hierarchy.sql",
                "V96__remove_unused_activity_tables.sql",
                "V97__remove_random_class_code.sql",
                "V98__remove_legacy_invite_provenance.sql",
                "V99__pluralize_subjects_activity_table.sql");
        String combined = files.stream().map(this::readUnchecked).reduce("", String::concat);

        assertThat(count(combined, "CREATE TABLE")).isEqualTo(1);
        assertThat(combined).contains("CREATE TABLE class_co_lecturers");
        assertThat(combined).doesNotContain("CREATE TABLE subjects");
        assertThat(combined).doesNotContain("practice_");
    }

    @Test
    void library_hierarchy_reuses_templates_assets_sections_and_lessons() throws IOException {
        String sql = read("V95__subject_library_hierarchy.sql");

        assertThat(sql).contains(
                "ALTER TABLE lesson_templates",
                "ADD COLUMN subject_id",
                "ADD COLUMN chapter_title",
                "REFERENCES subjects(id)");
        assertThat(sql).doesNotContain("CREATE TABLE", "practice_");
    }

    @Test
    void unused_activity_tables_are_removed_but_live_audit_streams_are_retained()
            throws IOException {
        String migration = read("V96__remove_unused_activity_tables.sql");

        assertThat(migration).contains(
                "DROP TABLE activity_enrollments",
                "DROP TABLE activity_assignments",
                "DROP TABLE activity_submissions",
                "DROP TABLE activity_users",
                "DROP TABLE activity_content_versions",
                "DROP TABLE activity_flashcard_decks");
        assertThat(migration).doesNotContain(
                "DROP TABLE activity_classes",
                "DROP TABLE activity_sections",
                "DROP TABLE activity_lessons",
                "DROP TABLE activity_tests",
                "DROP TABLE user_activities",
                "DROP TABLE permission_activities",
                "DROP TABLE subjects_activities");
    }

    @Test
    void random_class_code_is_removed_after_invite_retirement() throws IOException {
        String migration = read("V97__remove_random_class_code.sql");
        String creator = Files.readString(Path.of(
                "src/main/java/com/ksh/features/classes/service/ClassCreator.java"));
        String templates = Files.readString(Path.of(
                "src/main/resources/templates/classes/manage.html"))
                + Files.readString(Path.of(
                "src/main/resources/templates/student/my-classes.html"));

        assertThat(migration).contains(
                "DROP INDEX uk_classes_code",
                "DROP COLUMN code");
        assertThat(creator).doesNotContain(
                "ClassCodeGenerator", "countAnyByCode", "setCode(");
        assertThat(templates).doesNotContain("Mã mời", "Mã lớp");
        assertThat(templates).contains("Mã môn");
    }

    @Test
    void legacy_invite_provenance_is_normalized_out_of_enrollments() throws IOException {
        String migration = read("V98__remove_legacy_invite_provenance.sql");

        assertThat(migration).contains(
                "SET joined_via = 'REQUEST'",
                "CHECK (joined_via IN ('IMPORT','MANUAL','REQUEST'))");
        assertThat(migration).doesNotContain("CREATE TABLE", "practice_");
    }

    @Test
    void live_subject_audit_reaches_the_requested_plural_physical_name()
            throws IOException {
        String migration = read("V99__pluralize_subjects_activity_table.sql");
        String entity = Files.readString(Path.of(
                "src/main/java/com/ksh/entities/SubjectActivity.java"));

        assertThat(migration).contains(
                "subjects_activities has existed since V41");
        assertThat(migration).doesNotContain("CREATE TABLE", "DROP TABLE", "practice_");
        assertThat(entity).contains("@Table(name = \"subjects_activities\")");
    }

    @Test
    void subject_catalog_routes_render_subject_vocabulary() throws IOException {
        String list = Files.readString(Path.of(
                "src/main/resources/templates/admin/subjects.html"));
        String form = Files.readString(Path.of(
                "src/main/resources/templates/admin/subjects-form.html"));
        String users = Files.readString(Path.of(
                "src/main/resources/templates/admin/users-form.html"));

        assertThat(list).contains(
                "Danh mục môn học", "Thêm mã môn", "Trưởng môn phụ trách");
        assertThat(form).contains(
                "Tên môn học", "Mã môn", "Lịch sử cập nhật môn học",
                "<select id=\"leaderSelect\"");
        assertThat(users).contains("Mã môn phụ trách");
        assertThat(list + form + users).doesNotContain(">Khoa<");
    }

    @Test
    void course_and_question_bank_categories_are_removed_without_touching_practice()
            throws IOException {
        String courseCatalog = read("V92__remove_courses_and_general_categories.sql");
        String questionBankCreation = read("V46__subject_question_bank.sql");
        String questionBank = read("V93__scope_question_bank_by_subject.sql");

        assertThat(courseCatalog).contains(
                "DROP TABLE activity_courses",
                "DROP TABLE course_categories",
                "DROP TABLE courses",
                "DROP TABLE categories");
        assertThat(questionBankCreation).contains(
                "subject_id BIGINT NOT NULL",
                "REFERENCES subjects(id)");
        assertThat(questionBank).contains(
                "DROP COLUMN category_id",
                "DROP TABLE question_bank_categories");
        assertThat(questionBankCreation + questionBank)
                .doesNotContain("subject_id", "subjects");
        assertThat(courseCatalog + questionBank).doesNotContain("practice_");
    }

    @Test
    void removed_course_table_has_no_dashboard_reader() throws IOException {
        String adminDashboard = Files.readString(Path.of(
                "src/main/java/com/ksh/features/admin/service/AdminDashboardService.java"));
        String leaderDashboard = Files.readString(Path.of(
                "src/main/java/com/ksh/features/leader/service/LeaderDashboardService.java"));

        assertThat(adminDashboard + leaderDashboard).doesNotContain("FROM courses", "JOIN courses");
        assertThat(adminDashboard).contains("status = 'ACTIVE'");
        assertThat(leaderDashboard).contains("FROM question_bank_items", "subject_id = ?");
    }

    @Test
    void lesson_comment_aggregate_and_permissions_are_removed() throws IOException {
        String sql = read("V94__remove_lesson_comments.sql");

        assertThat(sql).contains(
                "DROP TABLE activity_comments",
                "DROP TABLE comment_moderation",
                "DROP TABLE comments",
                "feature_key LIKE 'comment.%'",
                "feature_key LIKE 'moderation.comment_%'");
        assertThat(sql).doesNotContain("practice_", "CREATE TABLE");
    }

    @Test
    void invite_storage_and_only_the_unused_duplicate_subject_activity_table_are_removed()
            throws IOException {
        String invites = read("V90__remove_class_invites.sql");
        String activities = read("V91__subject_activity_audit.sql");

        assertThat(invites).contains("DROP COLUMN invite_code_id", "DROP TABLE class_invite_codes");
        assertThat(activities).contains("DROP TABLE activity_subjects");
        assertThat(activities).doesNotContain("DROP TABLE subjects_activities", "subject");
    }

    @Test
    void fresh_schema_uses_subject_vocabulary_from_v1_without_transitional_rename()
            throws IOException {
        String init = read("V1__init_schema.sql");
        String classes = read("V40__classes_subject_id.sql");
        String audit = read("V41__subjects_activities.sql");
        String marker = read("V102__canonical_subject_catalog_schema.sql");

        assertThat(init).contains("CREATE TABLE subjects", "subject_id BIGINT NULL");
        assertThat(classes).contains("ADD COLUMN subject_id", "REFERENCES subjects(id)");
        assertThat(audit).contains("CREATE TABLE subjects_activities", "subject_id BIGINT NOT NULL");
        assertThat(marker).contains("no transitional rename is required");

        try (var paths = Files.list(MIGRATIONS)) {
            String allMigrations = paths
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .map(this::readPathUnchecked)
                    .reduce("", String::concat);
            assertThat(allMigrations.toLowerCase()).doesNotContain("subject");
        }
    }

    private String read(String file) throws IOException {
        return Files.readString(MIGRATIONS.resolve(file));
    }

    private String readUnchecked(String file) {
        try {
            return read(file);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private String readPathUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private long count(String value, String token) {
        return value.lines().filter(line -> line.contains(token)).count();
    }
}
