-- A library document shared with one class belongs to the class Materials tab,
-- not to an arbitrary lesson. Reuse lesson_attachments as the durable reference
-- table so no extra entity/table is introduced.
ALTER TABLE lesson_attachments
    MODIFY COLUMN lesson_id BIGINT NULL,
    ADD COLUMN class_id BIGINT NULL AFTER lesson_id,
    ADD INDEX idx_lesson_attachments_class (class_id, uploaded_at, id),
    ADD UNIQUE KEY uq_lesson_attachments_class_asset (class_id, library_asset_id),
    ADD CONSTRAINT fk_lesson_attachments_class
        FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE CASCADE,
    ADD CONSTRAINT chk_lesson_attachments_exact_parent
        CHECK ((lesson_id IS NOT NULL AND class_id IS NULL)
            OR (lesson_id IS NULL AND class_id IS NOT NULL)),
    ADD CONSTRAINT chk_lesson_attachments_class_library
        CHECK (class_id IS NULL OR library_asset_id IS NOT NULL),
    ADD CONSTRAINT chk_lesson_attachments_canonical_lesson
        CHECK (origin_scope <> 'CANONICAL_TEMPLATE' OR lesson_id IS NOT NULL);
