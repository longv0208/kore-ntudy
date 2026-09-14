package com.ksh.features.student.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Lesson;
import com.ksh.entities.Section;
import com.ksh.entities.User;
import com.ksh.features.auth.repository.UserRepository;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.service.support.ProgressMath;
import com.ksh.features.lessons.repository.LessonRepository;
import com.ksh.features.lessons.repository.SectionRepository;
import com.ksh.features.lessons.support.ClassAccessPolicy;
import com.ksh.features.progress.repository.LearningProgressRepository;
import com.ksh.features.student.dto.StudentLessonsDtos.ClassLessonsView;
import com.ksh.features.student.dto.StudentLessonsDtos.SectionWithLessons;
import com.ksh.features.student.dto.StudentLessonsDtos.StudentLessonRow;
import com.ksh.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.ksh.common.IConstant.CONTENT_TYPE_RICHTEXT;

/**
 * Read service backing {@code GET /my/classes/{classId}/lessons}.
 *
 * <p>Enforces these authz rules:
 * <ol>
 *   <li>The class itself must be live (not soft-deleted). The repo's
 *       {@code @SQLRestriction} filters soft-deleted rows
 *       transparently — a missing row maps to 404.</li>
 *   <li>The caller must be admitted: an ACTIVE-enrolled student, OR the
 *       owning lecturer, OR an ADMIN / in-subject LEADER
 *       (bypasses enrollment for in-scope inspection). Any other
 *       caller (REMOVED / COMPLETED / non-enrolled non-privileged) → 404
 *       to avoid leaking class existence (see design D5, D6).</li>
 * </ol>
 *
 * <p>Visibility filter (design D3): only {@link Lesson#STATUS_PUBLISHED}
 * lessons reach the view model. DRAFT lessons are stripped at this
 * layer so any future consumer (mobile API, etc.) inherits the rule.
 */
@Service
public class StudentLessonsService {

    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final LessonRepository lessonRepository;
    private final UserRepository userRepository;
    private final LearningProgressRepository progressRepository;
    private final ClassAccessPolicy accessPolicy;
    private final SubjectRepository subjectRepository;

    public StudentLessonsService(ClassRepository classRepository,
                                 SectionRepository sectionRepository,
                                 LessonRepository lessonRepository,
                                 UserRepository userRepository,
                                 LearningProgressRepository progressRepository,
                                 ClassAccessPolicy accessPolicy,
                                 SubjectRepository subjectRepository) {
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.lessonRepository = lessonRepository;
        this.userRepository = userRepository;
        this.progressRepository = progressRepository;
        this.accessPolicy = accessPolicy;
        this.subjectRepository = subjectRepository;
    }

    /**
     * Returns the lesson view for the given class, admitting an
     * ACTIVE-enrolled student, the owning lecturer, or an ADMIN /
     * in-subject LEADER
     * privileged viewer.
     *
     * @param classId target class id
     * @param userId  authenticated user id
     * @param role    the caller's role; ADMIN and in-subject LEADER bypass enrollment
     * @return populated {@link ClassLessonsView}; sections list is empty
     *         when the class has none, individual section lesson lists
     *         may be empty when nothing is PUBLISHED yet
     * @throws EntityNotFoundException when the caller is not admitted or
     *         the class is soft-deleted / missing
     */
    @Transactional(readOnly = true)
    public ClassLessonsView listClassLessons(Long classId, Long userId, Role role) {
        // Gate 1: class must be live. @SQLRestriction filters soft-deletes.
        // Loaded first so the lecturer check below can read lecturerId.
        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Class not found or not accessible"));
        if (role == Role.STUDENT && !ClassEntity.STATUS_ACTIVE.equals(clazz.getStatus())) {
            throw new EntityNotFoundException("Class not found or not accessible");
        }

        // Gate 2: admit the caller. ADMIN and in-subject LEADER bypass enrollment for
        // in-scope inspection; the owning lecturer passes
        // too; otherwise an ACTIVE enrollment is required. Any other caller
        // (REMOVED/COMPLETED/non-enrolled non-privileged) → no-leak 404.
        accessPolicy.requireViewAccess(clazz, userId, role);

        List<Section> sections = sectionRepository
                .findByClassIdOrderByDisplayOrderAsc(classId);

        // First pass: collect each section's PUBLISHED lessons so we know the
        // full published-id set before a single progress query.
        List<Section> orderedSections = new ArrayList<>(sections);
        List<List<Lesson>> publishedPerSection = new ArrayList<>(sections.size());
        List<Long> publishedIds = new ArrayList<>();
        for (Section section : orderedSections) {
            List<Lesson> published = publishedLessonsOf(section);
            publishedPerSection.add(published);
            for (Lesson l : published) {
                publishedIds.add(l.getId());
            }
        }

        // Single progress query for the whole page — avoids N+1 (design D4).
        Set<Long> completedIds = publishedIds.isEmpty()
                ? Set.of()
                : new HashSet<>(progressRepository
                        .findCompletedLessonIds(userId, publishedIds));

        List<SectionWithLessons> sectionRows = new ArrayList<>(orderedSections.size());
        int completedTotal = 0;
        for (int i = 0; i < orderedSections.size(); i++) {
            SectionWithLessons row = buildSectionRow(
                    orderedSections.get(i), publishedPerSection.get(i), completedIds);
            completedTotal += row.completedCount();
            sectionRows.add(row);
        }

        int publishedTotal = publishedIds.size();
        // Single source of the percent formula (shared with the lecturer view).
        int percent = ProgressMath.percent(completedTotal, publishedTotal);

        // Resolve lecturer name for the sidebar; a deleted lecturer maps to
        // null so the template renders a graceful fallback.
        String lecturerName = userRepository.findById(clazz.getLecturerId())
                .map(User::getFullName)
                .orElse(null);
        String subjectCode = clazz.getSubjectId() == null ? "—"
                : subjectRepository.findById(clazz.getSubjectId())
                        .map(com.ksh.entities.Subject::getCode).orElse("—");

        return new ClassLessonsView(clazz.getId(), clazz.getName(),
                subjectCode, lecturerName, sectionRows,
                completedTotal, publishedTotal, percent);
    }

    /** Returns a section's live, PUBLISHED lessons in display order. */
    private List<Lesson> publishedLessonsOf(Section section) {
        return lessonRepository.findBySectionIdAndStatusOrderByDisplayOrderAsc(
                section.getId(), Lesson.STATUS_PUBLISHED);
    }

    /** Builds a sidebar row + its PUBLISHED-only lesson list with progress flags. */
    private SectionWithLessons buildSectionRow(Section section,
                                               List<Lesson> published,
                                               Set<Long> completedIds) {
        List<StudentLessonRow> lessons = new ArrayList<>(published.size());
        int completedCount = 0;
        for (Lesson l : published) {
            // Legacy rows pre-V16 may carry null content_type — default
            // to RICHTEXT so the right-rail card still picks an icon.
            String type = l.getContentType() == null
                    ? CONTENT_TYPE_RICHTEXT : l.getContentType();
            boolean completed = completedIds.contains(l.getId());
            if (completed) completedCount++;
            lessons.add(new StudentLessonRow(
                    l.getId(), l.getTitle(),
                    l.getSectionId(), l.getPublishedAt(), type, completed));
        }

        short order = section.getDisplayOrder() == null
                ? 0 : section.getDisplayOrder();
        return new SectionWithLessons(
                section.getId(), section.getTitle(), order, lessons, completedCount);
    }
}
