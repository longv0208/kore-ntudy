package com.ksh.features.library.service;

import com.ksh.entities.LibraryAsset;
import com.ksh.features.library.dto.LibraryDtos.LibraryAssetPageView;
import com.ksh.features.library.dto.LibraryDtos.LibraryAssetDetail;
import com.ksh.features.library.dto.LibraryDtos.LibraryAssetPickerItem;
import com.ksh.features.library.dto.LibraryDtos.LibraryAssetPickerPage;
import com.ksh.features.library.dto.LibraryDtos.LibraryAssetRow;
import com.ksh.features.library.dto.LibraryDtos.LibraryAssetUsage;
import com.ksh.features.library.repository.LibraryAssetRepository;
import com.ksh.features.library.repository.LibraryAssetRepository.AssetUsageProjection;
import com.ksh.features.storage.StorageTransactionLifecycle;
import com.ksh.features.upload.LibraryStorageService;
import com.ksh.features.upload.LibraryStorageService.StoredLibraryFile;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.ksh.common.IConstant.DEFAULT_LIBRARY_PAGE_SIZE;
import static com.ksh.common.IConstant.MAX_LIBRARY_PAGE_SIZE;
import static com.ksh.common.IConstant.MSG_LIBRARY_ASSET_NOT_FOUND;
import static com.ksh.entities.LibraryAsset.KIND_DOCUMENT;
import static com.ksh.entities.LibraryAsset.KIND_VIDEO;

/** Owner-scoped reusable material storage for lecturer authoring workflows. */
@Service
public class LibraryService {

    private static final String MSG_ASSET_IN_USE =
            "Không thể xoá vì tài liệu đang được dùng trong bài giảng hoặc lớp học";
    private static final String MSG_TITLE_BLANK = "Tên hiển thị không được để trống";
    private static final String MSG_TITLE_TOO_LONG = "Tên hiển thị tối đa 255 ký tự";

    private final LibraryAssetRepository assetRepository;
    private final LibraryStorageService storage;

    public LibraryService(LibraryAssetRepository assetRepository,
                          LibraryStorageService storage) {
        this.assetRepository = assetRepository;
        this.storage = storage;
    }

    /** Owner-private SSR inventory with optional search/kind filters. */
    @Transactional(readOnly = true)
    public LibraryAssetPageView list(Long ownerId, String q, String kind,
                                     int page, int size) {
        return list(ownerId, q, kind, "ALL", page, size);
    }

    /** Owner-private SSR inventory with real in-use and recent views. */
    @Transactional(readOnly = true)
    public LibraryAssetPageView list(Long ownerId, String q, String kind, String view,
                                     int page, int size) {
        Long scopedOwnerId = requireOwnerId(ownerId);
        String normalizedQuery = normalizeQuery(q);
        String normalizedKind = normalizeKind(kind);
        String normalizedView = normalizeView(view);
        LocalDateTime recentSince = LocalDateTime.now().minusDays(30);
        List<Long> referencedAssetIds = assetRepository.findReferencedAssetIdsByOwnerId(scopedOwnerId);
        if (referencedAssetIds == null) referencedAssetIds = List.of();
        Set<Long> referencedAssetIdSet = new HashSet<>(referencedAssetIds);
        PageRequest pageable = pageRequest(page, size);
        Page<LibraryAsset> assets;
        if ("USED".equals(normalizedView)) {
            assets = referencedAssetIds.isEmpty()
                    ? Page.empty(pageable)
                    : assetRepository.searchOwnedByIds(scopedOwnerId, referencedAssetIds,
                            normalizedQuery, normalizedKind, pageable);
        } else if ("RECENT".equals(normalizedView)) {
            assets = assetRepository.searchRecentOwned(scopedOwnerId, recentSince,
                    normalizedQuery, normalizedKind, pageable);
        } else {
            assets = assetRepository.searchOwned(
                    scopedOwnerId, normalizedQuery, normalizedKind, pageable);
        }
        List<LibraryAsset> recentAssets = assetRepository
                .findTop5ByOwnerIdOrderByUpdatedAtDesc(scopedOwnerId);
        if (recentAssets == null) recentAssets = List.of();
        return new LibraryAssetPageView(
                assets.map(asset -> toRow(asset, referencedAssetIdSet.contains(asset.getId()))),
                recentAssets.stream()
                        .map(asset -> toRow(asset, referencedAssetIdSet.contains(asset.getId())))
                        .toList(),
                normalizedQuery == null ? "" : normalizedQuery,
                normalizedKind == null ? "" : normalizedKind,
                normalizedView,
                assetRepository.countByOwnerId(scopedOwnerId),
                assetRepository.countByOwnerIdAndKind(scopedOwnerId, KIND_DOCUMENT),
                assetRepository.countByOwnerIdAndKind(scopedOwnerId, KIND_VIDEO),
                referencedAssetIds.size(),
                assetRepository.countByOwnerIdAndUpdatedAtGreaterThanEqual(
                        scopedOwnerId, recentSince));
    }

    /** Owner-private JSON inventory for class and lesson-library pickers. */
    @Transactional(readOnly = true)
    public LibraryAssetPickerPage listForPicker(Long ownerId, String q, String kind,
                                                int page, int size) {
        Long scopedOwnerId = requireOwnerId(ownerId);
        Page<LibraryAsset> assets = assetRepository.searchOwned(
                scopedOwnerId, normalizeQuery(q), normalizeKind(kind),
                pageRequest(page, size));
        List<LibraryAssetPickerItem> items = assets.getContent().stream()
                .map(asset -> new LibraryAssetPickerItem(
                        asset.getId(), asset.getTitle(), asset.getOriginalFilename(),
                        asset.getKind(), asset.getMimeType(), asset.getSizeBytes()))
                .toList();
        return new LibraryAssetPickerPage(
                items, assets.getNumber(), assets.getSize(),
                assets.getTotalPages(), assets.getTotalElements());
    }

    /**
     * Owner-scoped metadata plus exact durable usage locations for the detail
     * drawer. Cross-owner ids fail before any reference query is exposed.
     */
    @Transactional(readOnly = true)
    public LibraryAssetDetail detail(Long ownerId, Long assetId) {
        Long scopedOwnerId = requireOwnerId(ownerId);
        LibraryAsset asset = getOwnedAsset(scopedOwnerId, assetId);
        LibraryAssetRow row = toRow(asset, false);
        List<AssetUsageProjection> projections =
                assetRepository.findUsagesByOwnerIdAndAssetId(scopedOwnerId, asset.getId());
        List<LibraryAssetUsage> usages = projections == null ? List.of()
                : projections.stream().map(LibraryService::toUsage).toList();
        String base = "/lecturer/library/assets/" + asset.getId();
        return new LibraryAssetDetail(
                asset.getId(), asset.getTitle(), asset.getOriginalFilename(),
                asset.getKind(), asset.getMimeType(), row.formatLabel(), row.formatClass(),
                asset.getSizeBytes(), asset.getCreatedAt(), asset.getUpdatedAt(),
                base + "/preview", base + "/content", base + "/content?download=true",
                usages);
    }

    @Transactional
    public LibraryAssetRow upload(Long ownerId, MultipartFile file, String kind) throws IOException {
        Long scopedOwnerId = requireOwnerId(ownerId);
        StoredLibraryFile stored = storage.store(file, scopedOwnerId, kind);
        StorageTransactionLifecycle.deleteOnRollback(
                () -> storage.delete(stored.storedPath()));
        LibraryAsset asset = new LibraryAsset(
                scopedOwnerId, stored.originalFilename(), stored.originalFilename(),
                stored.storedPath(), stored.mimeType(), stored.sizeBytes(), stored.kind());
        return toRow(assetRepository.save(asset));
    }

    /**
     * Renames only an owned asset; cross-owner ids resolve as not found. The
     * write lock is shared with delete so a stale rename cannot race a soft
     * delete and restore metadata on the deleted row.
     */
    @Transactional
    public LibraryAssetRow rename(Long ownerId, Long assetId, String title) {
        String normalizedTitle = normalizeTitle(title);
        LibraryAsset asset = getOwnedAssetForUpdate(ownerId, assetId);
        asset.rename(normalizedTitle);
        return toRow(assetRepository.save(asset));
    }

    /**
     * Soft-deletes an unreferenced owned row and removes its object only after
     * the database transaction commits. All lesson and canonical-template
     * reference families participate in the guard.
     */
    @Transactional
    public void delete(Long ownerId, Long assetId) {
        Long scopedOwnerId = requireOwnerId(ownerId);
        Long scopedAssetId = requireAssetId(assetId);
        LibraryAsset asset = assetRepository
                .findByIdAndOwnerIdForUpdate(scopedAssetId, scopedOwnerId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_LIBRARY_ASSET_NOT_FOUND));
        if (countReferences(scopedAssetId) > 0) {
            throw new IllegalStateException(MSG_ASSET_IN_USE);
        }
        String ownedKey = requireOwnedStorageKey(scopedOwnerId, asset);
        asset.markDeleted();
        assetRepository.save(asset);
        StorageTransactionLifecycle.deleteAfterCommit(() -> storage.delete(ownedKey));
    }

    /** Owner-scoped lookup; never falls back to an unscoped id query. */
    @Transactional(readOnly = true)
    public LibraryAsset getOwnedAsset(Long ownerId, Long assetId) {
        return assetRepository.findByIdAndOwnerId(
                        requireAssetId(assetId), requireOwnerId(ownerId))
                .orElseThrow(() -> new EntityNotFoundException(MSG_LIBRARY_ASSET_NOT_FOUND));
    }

    /**
     * Metadata required to stream/download a personal object. The returned key
     * is additionally constrained to {@code library/{ownerId}/}.
     */
    @Transactional(readOnly = true)
    public OwnedAssetContent contentHandle(Long ownerId, Long assetId) {
        Long scopedOwnerId = requireOwnerId(ownerId);
        LibraryAsset asset = getOwnedAsset(scopedOwnerId, assetId);
        return new OwnedAssetContent(
                requireOwnedStorageKey(scopedOwnerId, asset),
                asset.getOriginalFilename(), asset.getMimeType(), asset.getSizeBytes());
    }

    /**
     * Revalidates both row ownership and deterministic object-key ownership.
     * A malformed/tampered row is reported as not-found instead of exposing a
     * storage key from another lecturer or upload family.
     */
    public String requireOwnedStorageKey(Long ownerId, LibraryAsset asset) {
        Long scopedOwnerId = requireOwnerId(ownerId);
        if (asset == null || !scopedOwnerId.equals(asset.getOwnerId())) {
            throw new EntityNotFoundException(MSG_LIBRARY_ASSET_NOT_FOUND);
        }
        try {
            return storage.requireOwnedKey(scopedOwnerId, asset.getStoredPath());
        } catch (IllegalArgumentException ex) {
            throw new EntityNotFoundException(MSG_LIBRARY_ASSET_NOT_FOUND);
        }
    }

    /**
     * Resolves a library-backed lesson reference after lesson-level access has
     * already been authorized. Both the asset's own prefix and the duplicated
     * attachment path must match the canonical asset row.
     */
    @Transactional(readOnly = true)
    public String requireReferencedStorageKey(Long assetId, String attachmentStoredPath) {
        LibraryAsset asset = assetRepository.findById(requireAssetId(assetId))
                .orElseThrow(() -> new EntityNotFoundException(MSG_LIBRARY_ASSET_NOT_FOUND));
        String assetKey = requireOwnedStorageKey(asset.getOwnerId(), asset);
        final String attachmentKey;
        try {
            attachmentKey = storage.requireSafeKey(attachmentStoredPath);
        } catch (IllegalArgumentException ex) {
            throw new EntityNotFoundException(MSG_LIBRARY_ASSET_NOT_FOUND);
        }
        if (!assetKey.equals(attachmentKey)) {
            throw new EntityNotFoundException(MSG_LIBRARY_ASSET_NOT_FOUND);
        }
        return assetKey;
    }

    /** Locks an owned material while a Library lesson creates a durable reference. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LibraryAsset getOwnedAssetForUpdate(Long ownerId, Long assetId) {
        return assetRepository.findByIdAndOwnerIdForUpdate(
                        requireAssetId(assetId), requireOwnerId(ownerId))
                .orElseThrow(() -> new EntityNotFoundException(MSG_LIBRARY_ASSET_NOT_FOUND));
    }

    /** Counts every durable class/template reference that keeps the object live. */
    @Transactional(readOnly = true)
    public long countReferences(Long assetId) {
        return assetRepository.countLiveReferences(requireAssetId(assetId));
    }

    private static PageRequest pageRequest(int page, int size) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = size <= 0 ? DEFAULT_LIBRARY_PAGE_SIZE
                : Math.min(size, MAX_LIBRARY_PAGE_SIZE);
        return PageRequest.of(normalizedPage, normalizedSize);
    }

    private static String normalizeQuery(String q) {
        if (q == null) return null;
        String normalized = q.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) return null;
        String normalized = kind.trim().toUpperCase(Locale.ROOT);
        if (KIND_DOCUMENT.equals(normalized) || KIND_VIDEO.equals(normalized)) {
            return normalized;
        }
        // Invalid filters must not accidentally broaden into an unfiltered list.
        return "__INVALID_KIND__";
    }

    private static String normalizeView(String view) {
        if (view == null || view.isBlank()) return "ALL";
        String normalized = view.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "USED", "RECENT" -> normalized;
            default -> "ALL";
        };
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException(MSG_TITLE_BLANK);
        }
        String normalized = title.trim();
        if (normalized.length() > 255) {
            throw new IllegalArgumentException(MSG_TITLE_TOO_LONG);
        }
        return normalized;
    }

    private static Long requireOwnerId(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new IllegalArgumentException(MSG_LIBRARY_ASSET_NOT_FOUND);
        }
        return ownerId;
    }

    private static Long requireAssetId(Long assetId) {
        if (assetId == null || assetId <= 0) {
            throw new EntityNotFoundException(MSG_LIBRARY_ASSET_NOT_FOUND);
        }
        return assetId;
    }

    private static LibraryAssetRow toRow(LibraryAsset asset) {
        return toRow(asset, false);
    }

    private static LibraryAssetRow toRow(LibraryAsset asset, boolean inUse) {
        return new LibraryAssetRow(
                asset.getId(), asset.getTitle(), asset.getOriginalFilename(),
                asset.getKind(), asset.getMimeType(), asset.getSizeBytes(),
                asset.getCreatedAt(), asset.getUpdatedAt(), inUse);
    }

    private static LibraryAssetUsage toUsage(AssetUsageProjection usage) {
        String type = blankTo(usage.getUsageType(), "UNKNOWN");
        if (type.startsWith("TEMPLATE_")) {
            String title = blankTo(usage.getTemplateTitle(), "Bài giảng trong syllabus");
            String chapter = blankTo(usage.getChapterTitle(), "Kho bài giảng");
            String url = usage.getTemplateId() == null ? "/lecturer/library/templates"
                    : "/lecturer/library/templates/" + usage.getTemplateId() + "/edit";
            return new LibraryAssetUsage(type, title,
                    chapter + " · Kho bài giảng", url,
                    blankTo(usage.getPlacement(), "Tài liệu bài giảng"),
                    usage.getUpdatedAt());
        }
        if ("CLASS_MATERIAL".equals(type)) {
            String className = blankTo(usage.getClassName(), "Lớp học");
            String url = usage.getClassId() == null ? "/lecturer/classes"
                    : "/lecturer/classes/" + usage.getClassId() + "/materials";
            return new LibraryAssetUsage(type, className,
                    "Kho tài liệu dùng chung của lớp", url,
                    blankTo(usage.getPlacement(), "Tài liệu lớp"),
                    usage.getUpdatedAt());
        }
        String lessonTitle = blankTo(usage.getLessonTitle(), "Bài học");
        String className = blankTo(usage.getClassName(), "Lớp học");
        String sectionTitle = blankTo(usage.getSectionTitle(), "Chương học");
        String url = usage.getClassId() == null ? "/lecturer/classes"
                : "/lecturer/classes/" + usage.getClassId() + "/lessons"
                + (usage.getSectionId() == null ? "" : "?section=" + usage.getSectionId()
                + (usage.getLessonId() == null ? "" : "&lesson=" + usage.getLessonId()));
        return new LibraryAssetUsage(type, lessonTitle,
                className + " · " + sectionTitle, url,
                blankTo(usage.getPlacement(), "Tài liệu bài học"),
                usage.getUpdatedAt());
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record OwnedAssetContent(
            String storageKey,
            String originalFilename,
            String mimeType,
            long sizeBytes
    ) {
    }
}
