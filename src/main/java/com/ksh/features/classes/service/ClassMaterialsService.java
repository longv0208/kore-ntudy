package com.ksh.features.classes.service;

import com.ksh.entities.ClassEntity;
import com.ksh.entities.Enrollment;
import com.ksh.entities.LessonAttachment;
import com.ksh.entities.LibraryAsset;
import com.ksh.features.classes.repository.ClassRepository;
import com.ksh.features.classes.repository.EnrollmentRepository;
import com.ksh.features.lessons.repository.LessonAttachmentRepository;
import com.ksh.features.library.repository.LibraryAssetRepository;
import com.ksh.features.library.service.LibraryService;
import com.ksh.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ksh.entities.LibraryAsset.KIND_DOCUMENT;

/** Durable, no-copy documents shown in a class's standalone Materials tab. */
@Service
public class ClassMaterialsService {

    private static final String MSG_CLASS_NOT_LIVE =
            "Chỉ có thể chia sẻ tài liệu vào lớp đang hoạt động";
    private static final String MSG_DUPLICATE =
            "Tài liệu này đã có trong tab Tài liệu của lớp";

    private final ClassesService classesService;
    private final ClassRepository classRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final LessonAttachmentRepository attachmentRepository;
    private final LibraryAssetRepository assetRepository;
    private final LibraryService libraryService;

    public ClassMaterialsService(ClassesService classesService,
                                 ClassRepository classRepository,
                                 EnrollmentRepository enrollmentRepository,
                                 LessonAttachmentRepository attachmentRepository,
                                 LibraryAssetRepository assetRepository,
                                 LibraryService libraryService) {
        this.classesService = classesService;
        this.classRepository = classRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.attachmentRepository = attachmentRepository;
        this.assetRepository = assetRepository;
        this.libraryService = libraryService;
    }

    /** Shares one owned DOCUMENT with one owned live class without copying bytes. */
    @Transactional
    public ClassMaterialRow shareFromLibrary(Long classId, Long assetId,
                                             Long actorId, Role role) {
        ClassEntity clazz = classesService.getOwnerManaged(classId, actorId, role);
        if (!ClassEntity.STATUS_ACTIVE.equals(clazz.getStatus())) {
            throw new IllegalStateException(MSG_CLASS_NOT_LIVE);
        }
        LibraryAsset asset = libraryService.getOwnedAssetForUpdate(actorId, assetId);
        if (!KIND_DOCUMENT.equals(asset.getKind())) {
            throw new IllegalArgumentException("Chỉ tài liệu DOCUMENT mới có thể chia sẻ vào lớp");
        }
        String storageKey = libraryService.requireOwnedStorageKey(actorId, asset);
        if (attachmentRepository.existsByClassIdAndLibraryAssetId(classId, assetId)) {
            throw new IllegalStateException(MSG_DUPLICATE);
        }
        LessonAttachment saved = attachmentRepository.save(
                LessonAttachment.forClassMaterial(
                        classId, asset.getOriginalFilename(), storageKey,
                        asset.getMimeType(), asset.getSizeBytes(), actorId, assetId));
        return toRow(saved, asset.getTitle());
    }

    /** Lecturer/leader/admin class view. */
    @Transactional(readOnly = true)
    public List<ClassMaterialRow> listForTeaching(Long classId, Long actorId, Role role) {
        classesService.getViewable(classId, actorId, role);
        return mapRows(classId);
    }

    /** ACTIVE-enrolled student class view. */
    @Transactional(readOnly = true)
    public List<ClassMaterialRow> listForStudent(Long classId, Long studentId) {
        requireStudentAccess(classId, studentId);
        return mapRows(classId);
    }

    /** Removes only the class reference; the personal-library object remains intact. */
    @Transactional
    public void remove(Long classId, Long materialId, Long actorId, Role role) {
        classesService.getOwnerManaged(classId, actorId, role);
        LessonAttachment attachment = attachmentRepository.findByIdAndClassId(materialId, classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy tài liệu lớp"));
        attachmentRepository.delete(attachment);
    }

    /** Resolves an authorized storage handle for either a student or elevated viewer. */
    @Transactional(readOnly = true)
    public DownloadHandle download(Long classId, Long materialId,
                                   Long actorId, Role role) {
        if (role == Role.STUDENT) {
            requireStudentAccess(classId, actorId);
        } else {
            classesService.getViewable(classId, actorId, role);
        }
        LessonAttachment attachment = attachmentRepository.findByIdAndClassId(materialId, classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy tài liệu lớp"));
        if (!attachment.isLibraryBacked()) {
            throw new EntityNotFoundException("Không tìm thấy tài liệu lớp");
        }
        String storageKey = libraryService.requireReferencedStorageKey(
                attachment.getLibraryAssetId(), attachment.getStoredPath());
        return new DownloadHandle(storageKey, attachment.getOriginalFilename(),
                attachment.getMimeType(), attachment.getSizeBytes());
    }

    private void requireStudentAccess(Long classId, Long studentId) {
        classRepository.findById(classId)
                .filter(clazz -> ClassEntity.STATUS_ACTIVE.equals(clazz.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException("Lớp không tồn tại hoặc đã lưu trữ"));
        enrollmentRepository.findByUserIdAndClassId(studentId, classId)
                .filter(enrollment -> Enrollment.STATUS_ACTIVE.equals(enrollment.getStatus()))
                .orElseThrow(() -> new AccessDeniedException("Bạn chưa tham gia lớp này"));
    }

    private List<ClassMaterialRow> mapRows(Long classId) {
        List<LessonAttachment> attachments =
                attachmentRepository.findByClassIdOrderByUploadedAtDescIdDesc(classId);
        Map<Long, String> titles = new HashMap<>();
        assetRepository.findAllById(attachments.stream()
                        .map(LessonAttachment::getLibraryAssetId).distinct().toList())
                .forEach(asset -> titles.put(asset.getId(), asset.getTitle()));
        return attachments.stream()
                .map(attachment -> toRow(attachment,
                        titles.getOrDefault(attachment.getLibraryAssetId(),
                                attachment.getOriginalFilename())))
                .toList();
    }

    private static ClassMaterialRow toRow(LessonAttachment attachment, String title) {
        return new ClassMaterialRow(
                attachment.getId(), title, attachment.getOriginalFilename(),
                attachment.getMimeType(), attachment.getSizeBytes(),
                attachment.getUploadedAt(),
                "/api/classes/" + attachment.getClassId()
                        + "/materials/" + attachment.getId() + "/download");
    }

    public record ClassMaterialRow(Long id, String title, String originalFilename,
                                   String mimeType, long sizeBytes,
                                   LocalDateTime sharedAt, String downloadUrl) {
        public String sizeLabel() {
            if (sizeBytes < 1024) return sizeBytes + " B";
            if (sizeBytes < 1024 * 1024) return String.format("%.1f KB", sizeBytes / 1024.0);
            return String.format("%.1f MB", sizeBytes / 1048576.0);
        }
    }

    public record DownloadHandle(String storageKey, String originalFilename,
                                 String mimeType, long sizeBytes) {
    }
}
