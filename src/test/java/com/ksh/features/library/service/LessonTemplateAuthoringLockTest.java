package com.ksh.features.library.service;

import com.ksh.entities.Department;
import com.ksh.entities.Lesson;
import com.ksh.entities.LibraryAsset;
import com.ksh.entities.User;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.library.dto.LessonTemplateForm;
import com.ksh.features.library.repository.LessonTemplateAttachmentRepository;
import com.ksh.features.library.repository.LessonTemplateRepository;
import com.ksh.features.library.repository.LibraryAssetRepository;
import com.ksh.security.Role;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contracts for leader-owned syllabus authoring and lecturer resource contribution. */
@SpringBootTest
@Transactional
class LessonTemplateAuthoringLockTest {

    @Autowired private LessonTemplateService templateService;
    @Autowired private LessonTemplateRepository templateRepository;
    @Autowired private LessonTemplateAttachmentRepository attachmentRepository;
    @Autowired private LibraryAssetRepository assetRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private User lecturer;
    private User leader;
    private Department subject;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ksh.edu.vn").orElseThrow();
        subject = departmentRepository.findById(lecturer.getSubjectId()).orElseThrow();
        leader = userRepository.findById(subject.getLeaderUserId()).orElseThrow();
        subject.setLibraryLocked(false);
        subject.setCurriculumStructureLocked(false);
        departmentRepository.saveAndFlush(subject);
    }

    @Test
    void onlyLeaderMayCreateRenameAndEditLessonContent() {
        var created = templateService.saveForm(
                leader.getId(), Role.LEADER, uniqueLessonForm());

        assertThatThrownBy(() -> templateService.saveForm(
                lecturer.getId(), Role.LECTURER, uniqueLessonForm()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("trưởng bộ môn");
        assertThatThrownBy(() -> templateService.renameLesson(
                lecturer.getId(), Role.LECTURER, created.id(), "Không được đổi"))
                .isInstanceOf(AccessDeniedException.class);

        templateService.renameLesson(leader.getId(), Role.LEADER,
                created.id(), "Leader đổi tiêu đề");
        LessonTemplateForm edit = templateService.loadForm(
                leader.getId(), Role.LEADER, created.id(), subject.getId());
        edit.setContentRichtext("<p>Nội dung do leader cập nhật.</p>");
        templateService.saveForm(leader.getId(), Role.LEADER, edit);

        var saved = templateRepository.findById(created.id()).orElseThrow();
        assertThat(saved.getTitle()).contains("Leader đổi tiêu đề");
        assertThat(saved.getContentRichtext()).contains("leader cập nhật");
    }

    @Test
    void lecturerCanOnlyAppendOwnResourcesWhenLeaderAllowsIt() {
        var created = templateService.saveForm(
                leader.getId(), Role.LEADER, uniqueLessonForm());
        LibraryAsset lecturerAsset = assetRepository.saveAndFlush(new LibraryAsset(
                lecturer.getId(), "Tài nguyên kiểm thử", "tai-nguyen.pdf",
                "library/" + lecturer.getId() + "/tai-nguyen.pdf",
                "application/pdf", 128L, LibraryAsset.KIND_DOCUMENT));

        LessonTemplateForm contribution = templateService.loadForm(
                lecturer.getId(), Role.LECTURER, created.id(), subject.getId());
        contribution.setTitle("Request giả mạo đổi tiêu đề");
        contribution.setContentRichtext("<p>Request giả mạo đổi nội dung.</p>");
        contribution.setMaterialAssetIds(List.of(lecturerAsset.getId()));
        templateService.saveForm(lecturer.getId(), Role.LECTURER, contribution);

        var unchanged = templateRepository.findById(created.id()).orElseThrow();
        assertThat(unchanged.getTitle()).doesNotContain("giả mạo");
        assertThat(unchanged.getContentRichtext()).doesNotContain("giả mạo");
        assertThat(attachmentRepository.findByTemplateIdAndLibraryAssetId(
                created.id(), lecturerAsset.getId())).isPresent();

        templateService.setSubjectLibraryLocked(
                leader.getId(), Role.LEADER, subject.getId(), true);
        assertThatThrownBy(() -> templateService.loadForm(
                lecturer.getId(), Role.LECTURER, created.id(), subject.getId()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("không cho phép");
        assertThatThrownBy(() -> templateService.saveForm(
                lecturer.getId(), Role.LECTURER, contribution))
                .isInstanceOf(AccessDeniedException.class);

        // Leader editing must preserve resources contributed by other uploaders.
        LessonTemplateForm leaderEdit = templateService.loadForm(
                leader.getId(), Role.LEADER, created.id(), subject.getId());
        assertThat(leaderEdit.getMaterialAssetIds()).contains(lecturerAsset.getId());
        assertThat(templateService.materialOptions(leader.getId(), Role.LEADER, created.id()))
                .anySatisfy(option -> assertThat(option.id()).isEqualTo(lecturerAsset.getId()));
        leaderEdit.setContentRichtext("<p>Leader giữ tài nguyên khi sửa.</p>");
        templateService.saveForm(leader.getId(), Role.LEADER, leaderEdit);
        assertThat(attachmentRepository.findByTemplateIdAndLibraryAssetId(
                created.id(), lecturerAsset.getId())).isPresent();
    }

    @Test
    void onlyLeaderCanMoveLessonsBetweenChaptersEvenWhileLocked() {
        var first = templateService.saveForm(leader.getId(), Role.LEADER, uniqueLessonForm());
        LessonTemplateForm otherChapter = uniqueLessonForm();
        otherChapter.setChapterNumber(1000);
        otherChapter.setChapterTitle("Chương đích");
        var second = templateService.saveForm(leader.getId(), Role.LEADER, otherChapter);
        int targetChapter = templateRepository.findById(second.id()).orElseThrow().getChapterOrder();
        assertThatThrownBy(() -> templateService.moveLesson(
                lecturer.getId(), Role.LECTURER, first.id(), targetChapter, second.id()))
                .isInstanceOf(AccessDeniedException.class);
        templateService.setSubjectLibraryLocked(leader.getId(), Role.LEADER, subject.getId(), true);
        templateService.moveLesson(leader.getId(), Role.LEADER, first.id(), targetChapter, second.id());
        var moved = templateRepository.findById(first.id()).orElseThrow();
        var target = templateRepository.findById(second.id()).orElseThrow();
        assertThat(moved.getChapterOrder()).isEqualTo(target.getChapterOrder());
        assertThat(moved.getDisplayOrder()).isLessThan(target.getDisplayOrder());
    }

    @Test
    void resourceLockNeverBlocksLeaderSyllabusCrud() {
        assertThat(templateService.setSubjectLibraryLocked(
                leader.getId(), Role.LEADER, subject.getId(), true)).isTrue();
        entityManager.flush();
        entityManager.clear();

        var created = templateService.saveForm(
                leader.getId(), Role.LEADER, uniqueLessonForm());
        templateService.renameLesson(
                leader.getId(), Role.LEADER, created.id(), "Vẫn biên tập khi khóa");

        var leaderView = templateService.list(leader.getId(), Role.LEADER,
                subject.getId(), "Vẫn biên tập", 0, 20);
        assertThat(leaderView.libraryLocked()).isTrue();
        assertThat(leaderView.page().getContent()).singleElement().satisfies(row -> {
            assertThat(row.canManage()).isTrue();
            assertThat(row.canManageStructure()).isTrue();
            assertThat(row.canAddResources()).isTrue();
        });

        var lecturerView = templateService.list(lecturer.getId(), Role.LECTURER,
                subject.getId(), "Vẫn biên tập", 0, 20);
        assertThat(lecturerView.page().getContent()).singleElement().satisfies(row -> {
            assertThat(row.canManage()).isFalse();
            assertThat(row.canManageStructure()).isFalse();
            assertThat(row.canAddResources()).isFalse();
        });
    }

    @Test
    void scopedLeaderMayManageTemplatesOwnedByAnotherUploader() {
        User scopedLeader = userRepository.findByEmailIgnoreCase("kor_leader@ksh.edu.vn")
                .orElseThrow();
        var view = templateService.list(scopedLeader.getId(), Role.LEADER,
                subject.getId(), null, 0, 20);
        var foreign = view.page().getContent().stream()
                .filter(row -> !scopedLeader.getId().equals(row.uploaderUserId()))
                .findFirst().orElseThrow();

        templateService.renameLesson(scopedLeader.getId(), Role.LEADER,
                foreign.id(), "Leader theo scope cập nhật");
        assertThat(templateRepository.findById(foreign.id()).orElseThrow().getTitle())
                .contains("Leader theo scope cập nhật");
    }

    private LessonTemplateForm uniqueLessonForm() {
        LessonTemplateForm form = new LessonTemplateForm();
        form.setSubjectId(subject.getId());
        form.setChapterNumber(999);
        form.setChapterTitle("Kiểm thử quyền biên soạn");
        form.setTitle("Bài kiểm thử quyền " + System.nanoTime());
        form.setContentType(Lesson.CONTENT_TYPE_RICHTEXT);
        form.setContentRichtext("<p>Nội dung kiểm thử.</p>");
        return form;
    }
}
