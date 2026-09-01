package com.ksh.features.classes.service;

import com.ksh.entities.ClassActivity;
import com.ksh.entities.ClassEntity;
import com.ksh.entities.Department;
import com.ksh.features.admin.departments.repository.DepartmentRepository;
import com.ksh.features.classes.dto.ClassesDtos.ClassForm;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.service.approval.ClassPendingReviewEvent;
import com.ksh.features.classes.semester.AcademicSemesterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Encapsulates class creation, subject binding, audit, and leader notification.
 *
 * <p>Plain package-private helper instantiated by {@link ClassesService}
 * rather than a separate Spring bean.
 *
 * <p>The {@code @Transactional} boundary lives on {@link ClassesService#create}
 * so the entity and audit row commit atomically.
 */
final class ClassCreator {

    private static final Logger log = LoggerFactory.getLogger(ClassCreator.class);
    private final ClassRepository classRepository;
    private final ClassActivityWriter activityWriter;
    private final DepartmentRepository subjectRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AcademicSemesterService semesterService;

    ClassCreator(ClassRepository classRepository,
                 ClassActivityWriter activityWriter,
                 DepartmentRepository subjectRepository,
                 ApplicationEventPublisher eventPublisher,
                 AcademicSemesterService semesterService) {
        this.classRepository = classRepository;
        this.activityWriter = activityWriter;
        this.subjectRepository = subjectRepository;
        this.eventPublisher = eventPublisher;
        this.semesterService = semesterService;
    }

    ClassEntity create(ClassForm form, Long userId) {
        Department subject = subjectRepository.findById(form.subjectId())
                .filter(Department::isActive)
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
        publishPendingReview(saved, subject.getCode());
        return saved;
    }

    /** Emits the after-commit leader notification for a new or resubmitted class. */
    void publishPendingReview(ClassEntity clazz, String subjectCode) {
        try {
            eventPublisher.publishEvent(new ClassPendingReviewEvent(
                    clazz.getId(), clazz.getSubjectId(), clazz.getLecturerId(),
                    clazz.getName(), subjectCode));
        } catch (RuntimeException exception) {
            log.warn("Không đăng ký được thông báo chờ duyệt cho lớp {}",
                    clazz.getId(), exception);
        }
    }
}
