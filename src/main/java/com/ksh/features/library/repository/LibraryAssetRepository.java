package com.ksh.features.library.repository;

import com.ksh.entities.LibraryAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

/**
 * Spring Data repository for {@link LibraryAsset}. Soft-deleted rows are
 * excluded by the entity {@code @SQLRestriction}.
 */
public interface LibraryAssetRepository extends JpaRepository<LibraryAsset, Long> {

    /** Native projection of one exact durable reference to a personal asset. */
    interface AssetUsageProjection {
        String getUsageType();
        Long getClassId();
        String getClassName();
        Long getSectionId();
        String getSectionTitle();
        Long getLessonId();
        String getLessonTitle();
        Long getTemplateId();
        String getTemplateTitle();
        String getChapterTitle();
        String getPlacement();
        LocalDateTime getUpdatedAt();
    }

    /** Owner-scoped lookup; returns empty for other owners or soft-deleted rows. */
    Optional<LibraryAsset> findByIdAndOwnerId(Long id, Long ownerId);

    /** Serializes reference creation against owner-scoped asset deletion. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a FROM LibraryAsset a
            WHERE a.id = :id AND a.ownerId = :ownerId
            """)
    Optional<LibraryAsset> findByIdAndOwnerIdForUpdate(@Param("id") Long id,
                                                       @Param("ownerId") Long ownerId);

    /** Locks a persisted canonical reference whose owner may differ from the template owner. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LibraryAsset a WHERE a.id = :id")
    Optional<LibraryAsset> findByIdForUpdate(@Param("id") Long id);

    /**
     * Lists only the authenticated owner's live assets. There is deliberately
     * no unscoped picker query: shared lesson templates and personal file
     * inventory are separate concepts.
     */
    @Query("""
            SELECT a FROM LibraryAsset a
            WHERE a.ownerId = :ownerId
              AND (:kind IS NULL OR a.kind = :kind)
              AND (
                    :q IS NULL
                    OR LOWER(a.title) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(a.originalFilename) LIKE LOWER(CONCAT('%', :q, '%'))
                  )
            ORDER BY a.updatedAt DESC, a.id DESC
            """)
    Page<LibraryAsset> searchOwned(@Param("ownerId") Long ownerId,
                                   @Param("q") String q,
                                   @Param("kind") String kind,
                                   Pageable pageable);

    @Query("""
            SELECT a FROM LibraryAsset a
            WHERE a.ownerId = :ownerId
              AND a.id IN :assetIds
              AND (:kind IS NULL OR a.kind = :kind)
              AND (
                    :q IS NULL
                    OR LOWER(a.title) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(a.originalFilename) LIKE LOWER(CONCAT('%', :q, '%'))
                  )
            ORDER BY a.updatedAt DESC, a.id DESC
            """)
    Page<LibraryAsset> searchOwnedByIds(@Param("ownerId") Long ownerId,
                                        @Param("assetIds") List<Long> assetIds,
                                        @Param("q") String q,
                                        @Param("kind") String kind,
                                        Pageable pageable);

    @Query("""
            SELECT a FROM LibraryAsset a
            WHERE a.ownerId = :ownerId
              AND a.updatedAt >= :since
              AND (:kind IS NULL OR a.kind = :kind)
              AND (
                    :q IS NULL
                    OR LOWER(a.title) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(a.originalFilename) LIKE LOWER(CONCAT('%', :q, '%'))
                  )
            ORDER BY a.updatedAt DESC, a.id DESC
            """)
    Page<LibraryAsset> searchRecentOwned(@Param("ownerId") Long ownerId,
                                         @Param("since") LocalDateTime since,
                                         @Param("q") String q,
                                         @Param("kind") String kind,
                                         Pageable pageable);

    long countByOwnerId(Long ownerId);

    long countByOwnerIdAndKind(Long ownerId, String kind);

    long countByOwnerIdAndUpdatedAtGreaterThanEqual(Long ownerId, LocalDateTime since);

    /** The five newest owner-private assets for the stable hero activity strip. */
    List<LibraryAsset> findTop5ByOwnerIdOrderByUpdatedAtDescIdDesc(Long ownerId);

    @Query(value = """
            SELECT DISTINCT live_refs.asset_id
            FROM (
                SELECT la.library_asset_id AS asset_id
                FROM lesson_attachments la
                JOIN classes c ON c.id = la.class_id AND c.is_deleted = 0
                WHERE la.class_id IS NOT NULL AND la.library_asset_id IS NOT NULL

                UNION ALL

                SELECT la.library_asset_id
                FROM lesson_attachments la
                JOIN lessons l ON l.id = la.lesson_id AND l.is_deleted = 0
                JOIN sections s ON s.id = l.section_id AND s.is_deleted = 0
                JOIN classes c ON c.id = s.class_id AND c.is_deleted = 0
                WHERE la.lesson_id IS NOT NULL AND la.library_asset_id IS NOT NULL

                UNION ALL

                SELECT l.video_library_asset_id
                FROM lessons l
                JOIN sections s ON s.id = l.section_id AND s.is_deleted = 0
                JOIN classes c ON c.id = s.class_id AND c.is_deleted = 0
                WHERE l.is_deleted = 0 AND l.video_library_asset_id IS NOT NULL

                UNION ALL

                SELECT lta.library_asset_id
                FROM lesson_template_attachments lta
                JOIN lesson_templates lt ON lt.id = lta.template_id AND lt.is_deleted = 0
                WHERE lta.library_asset_id IS NOT NULL

                UNION ALL

                SELECT lt.pdf_library_asset_id
                FROM lesson_templates lt
                WHERE lt.is_deleted = 0 AND lt.pdf_library_asset_id IS NOT NULL

                UNION ALL

                SELECT lt.video_library_asset_id
                FROM lesson_templates lt
                WHERE lt.is_deleted = 0 AND lt.video_library_asset_id IS NOT NULL
            ) live_refs
            JOIN library_assets a
              ON a.id = live_refs.asset_id
             AND a.owner_id = :ownerId
             AND a.is_deleted = 0
            ORDER BY live_refs.asset_id
            """, nativeQuery = true)
    List<Long> findReferencedAssetIdsByOwnerId(@Param("ownerId") Long ownerId);

    /**
     * Resolves every live class/lesson/template location that references one
     * owned asset. The owner predicate is repeated at the database boundary;
     * a guessed id from another account therefore returns no metadata.
     */
    @Query(value = """
            SELECT reference_rows.usageType AS usageType,
                   reference_rows.classId AS classId,
                   reference_rows.className AS className,
                   reference_rows.sectionId AS sectionId,
                   reference_rows.sectionTitle AS sectionTitle,
                   reference_rows.lessonId AS lessonId,
                   reference_rows.lessonTitle AS lessonTitle,
                   reference_rows.templateId AS templateId,
                   reference_rows.templateTitle AS templateTitle,
                   reference_rows.chapterTitle AS chapterTitle,
                   reference_rows.placement AS placement,
                   reference_rows.updatedAt AS updatedAt
            FROM (
                SELECT la.library_asset_id AS assetId,
                       'CLASS_MATERIAL' AS usageType,
                       c.id AS classId,
                       c.name AS className,
                       CAST(NULL AS SIGNED) AS sectionId,
                       CAST(NULL AS CHAR(200)) AS sectionTitle,
                       CAST(NULL AS SIGNED) AS lessonId,
                       CAST(NULL AS CHAR(300)) AS lessonTitle,
                       CAST(NULL AS SIGNED) AS templateId,
                       CAST(NULL AS CHAR(300)) AS templateTitle,
                       CAST(NULL AS CHAR(200)) AS chapterTitle,
                       'Tài liệu lớp' AS placement,
                       la.uploaded_at AS updatedAt
                FROM lesson_attachments la
                JOIN classes c ON c.id = la.class_id AND c.is_deleted = 0
                WHERE la.class_id IS NOT NULL

                UNION ALL

                SELECT la.library_asset_id,
                       'LESSON_ATTACHMENT',
                       c.id,
                       c.name,
                       s.id,
                       s.title,
                       l.id,
                       l.title,
                       CAST(NULL AS SIGNED),
                       CAST(NULL AS CHAR(300)),
                       CAST(NULL AS CHAR(200)),
                       CASE WHEN l.pdf_attachment_id = la.id
                            THEN 'PDF chính' ELSE 'Tài liệu đính kèm' END,
                       la.uploaded_at
                FROM lesson_attachments la
                JOIN lessons l ON l.id = la.lesson_id AND l.is_deleted = 0
                JOIN sections s ON s.id = l.section_id AND s.is_deleted = 0
                JOIN classes c ON c.id = s.class_id AND c.is_deleted = 0
                WHERE la.lesson_id IS NOT NULL

                UNION ALL

                SELECT l.video_library_asset_id,
                       'LESSON_VIDEO',
                       c.id,
                       c.name,
                       s.id,
                       s.title,
                       l.id,
                       l.title,
                       CAST(NULL AS SIGNED),
                       CAST(NULL AS CHAR(300)),
                       CAST(NULL AS CHAR(200)),
                       'Video bài học',
                       l.updated_at
                FROM lessons l
                JOIN sections s ON s.id = l.section_id AND s.is_deleted = 0
                JOIN classes c ON c.id = s.class_id AND c.is_deleted = 0
                WHERE l.is_deleted = 0 AND l.video_library_asset_id IS NOT NULL

                UNION ALL

                SELECT lta.library_asset_id,
                       'TEMPLATE_ATTACHMENT',
                       CAST(NULL AS SIGNED),
                       CAST(NULL AS CHAR(300)),
                       CAST(NULL AS SIGNED),
                       CAST(NULL AS CHAR(200)),
                       CAST(NULL AS SIGNED),
                       CAST(NULL AS CHAR(300)),
                       lt.id,
                       lt.title,
                       lt.chapter_title,
                       'Tài liệu bổ sung',
                       lt.updated_at
                FROM lesson_template_attachments lta
                JOIN lesson_templates lt ON lt.id = lta.template_id AND lt.is_deleted = 0

                UNION ALL

                SELECT lt.pdf_library_asset_id,
                       'TEMPLATE_PDF',
                       CAST(NULL AS SIGNED),
                       CAST(NULL AS CHAR(300)),
                       CAST(NULL AS SIGNED),
                       CAST(NULL AS CHAR(200)),
                       CAST(NULL AS SIGNED),
                       CAST(NULL AS CHAR(300)),
                       lt.id,
                       lt.title,
                       lt.chapter_title,
                       'PDF chính',
                       lt.updated_at
                FROM lesson_templates lt
                WHERE lt.is_deleted = 0 AND lt.pdf_library_asset_id IS NOT NULL

                UNION ALL

                SELECT lt.video_library_asset_id,
                       'TEMPLATE_VIDEO',
                       CAST(NULL AS SIGNED),
                       CAST(NULL AS CHAR(300)),
                       CAST(NULL AS SIGNED),
                       CAST(NULL AS CHAR(200)),
                       CAST(NULL AS SIGNED),
                       CAST(NULL AS CHAR(300)),
                       lt.id,
                       lt.title,
                       lt.chapter_title,
                       'Video chính',
                       lt.updated_at
                FROM lesson_templates lt
                WHERE lt.is_deleted = 0 AND lt.video_library_asset_id IS NOT NULL
            ) reference_rows
            JOIN library_assets owned_asset
              ON owned_asset.id = reference_rows.assetId
             AND owned_asset.owner_id = :ownerId
             AND owned_asset.is_deleted = 0
            WHERE reference_rows.assetId = :assetId
            ORDER BY reference_rows.updatedAt DESC,
                     reference_rows.usageType ASC,
                     reference_rows.classId ASC,
                     reference_rows.lessonId ASC,
                     reference_rows.templateId ASC
            """, nativeQuery = true)
    List<AssetUsageProjection> findUsagesByOwnerIdAndAssetId(
            @Param("ownerId") Long ownerId,
            @Param("assetId") Long assetId);

    /**
     * Counts only references whose enclosing class/lesson/section/template is
     * still live. This uses the same parent predicates as the detail drawer so
     * a soft-deleted location never leaves an asset falsely marked in use.
     */
    @Query(value = """
            SELECT COUNT(*)
            FROM (
                SELECT la.library_asset_id AS asset_id
                FROM lesson_attachments la
                JOIN classes c ON c.id = la.class_id AND c.is_deleted = 0
                WHERE la.class_id IS NOT NULL AND la.library_asset_id IS NOT NULL

                UNION ALL

                SELECT la.library_asset_id
                FROM lesson_attachments la
                JOIN lessons l ON l.id = la.lesson_id AND l.is_deleted = 0
                JOIN sections s ON s.id = l.section_id AND s.is_deleted = 0
                JOIN classes c ON c.id = s.class_id AND c.is_deleted = 0
                WHERE la.lesson_id IS NOT NULL AND la.library_asset_id IS NOT NULL

                UNION ALL

                SELECT l.video_library_asset_id
                FROM lessons l
                JOIN sections s ON s.id = l.section_id AND s.is_deleted = 0
                JOIN classes c ON c.id = s.class_id AND c.is_deleted = 0
                WHERE l.is_deleted = 0 AND l.video_library_asset_id IS NOT NULL

                UNION ALL

                SELECT lta.library_asset_id
                FROM lesson_template_attachments lta
                JOIN lesson_templates lt ON lt.id = lta.template_id AND lt.is_deleted = 0
                WHERE lta.library_asset_id IS NOT NULL

                UNION ALL

                SELECT lt.pdf_library_asset_id
                FROM lesson_templates lt
                WHERE lt.is_deleted = 0 AND lt.pdf_library_asset_id IS NOT NULL

                UNION ALL

                SELECT lt.video_library_asset_id
                FROM lesson_templates lt
                WHERE lt.is_deleted = 0 AND lt.video_library_asset_id IS NOT NULL
            ) live_refs
            WHERE live_refs.asset_id = :assetId
            """, nativeQuery = true)
    long countLiveReferences(@Param("assetId") Long assetId);

    List<LibraryAsset> findByOwnerIdOrderByTitleAsc(Long ownerId);
}
