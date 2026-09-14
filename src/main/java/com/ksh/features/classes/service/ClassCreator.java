package com.ksh.features.classes.service;

import com.ksh.entities.ClassActivity;
import com.ksh.entities.ClassEntity;
import com.ksh.entities.Subject;
import com.ksh.features.admin.subjects.repository.SubjectRepository;
import com.ksh.features.classes.dto.ClassesDtos.ClassForm;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.semester.AcademicSemesterService;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Encapsulates immediate class activation, subject binding, and audit.
 *
 * <p>Plain package-private helper instantiated by {@link ClassesService}
 * rather than a separate Spring bean.
 *
 * <p>The {@code @Transactional} boundary lives on {@link ClassesService#create}
 * so the entity and audit row commit atomically.
 */
final class ClassCreator {

    private final ClassRepository classRepository;
    private final ClassActivityWriter activityWriter;
    private final SubjectRepository subjectRepository;
    private final AcademicSemesterService semesterService;

    ClassCreator(ClassRepository classRepository,
                 ClassActivityWriter activityWriter,
                 SubjectRepository subjectRepository,
                 ApplicationEventPublisher eventPublisher,
                 AcademicSemesterService semesterService) {
        this.classRepository = classRepository;
        this.activityWriter = activityWriter;
        this.subjectRepository = subjectRepository;
        this.semesterService = semesterService;
    }

    ClassEntity create(ClassForm form, Long userId) {
        Subject subject = subjectRepository.findById(form.subjectId())
                .filter(Subject::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Mã môn không tồn tại hoặc đã ngừng sử dụng"));
        ClassEntity entity = new ClassEntity(
                form.name(), userId, userId,
                form.description(), form.startDate(), form.endDate(),
                form.maxStudents());
        entity.setSubjectId(subject.getId());
        entity.assignSemester(semesterService.currentCode());
        ClassEntity saved = classRepository.saveAndFlush(entity);
        activityWriter.write(saved.getId(), ClassActivity.TYPE_CREATED,
                "Tạo lớp " + saved.getName(), userId);
        return saved;
    }

}
