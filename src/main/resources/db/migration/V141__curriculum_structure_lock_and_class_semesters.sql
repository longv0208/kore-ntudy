-- Keep the canonical subject structure independently lockable from lesson
-- resources, without introducing another persistence table.
ALTER TABLE subjects
    ADD COLUMN curriculum_structure_locked TINYINT(1) NOT NULL DEFAULT 0
        AFTER library_locked;

-- Every class keeps the semester it was created for. Historical rows are
-- derived from their own dates so old seed/catalog data is not collapsed into
-- the current year. SP = Jan-Apr, SU = May-Aug, FA = Sep-Dec.
ALTER TABLE classes
    ADD COLUMN semester VARCHAR(4) NULL AFTER subject_id;

UPDATE classes
SET semester = CONCAT(
        CASE
            WHEN MONTH(COALESCE(start_date, DATE(created_at), CURRENT_DATE())) BETWEEN 1 AND 4 THEN 'SP'
            WHEN MONTH(COALESCE(start_date, DATE(created_at), CURRENT_DATE())) BETWEEN 5 AND 8 THEN 'SU'
            ELSE 'FA'
        END,
        DATE_FORMAT(COALESCE(start_date, DATE(created_at), CURRENT_DATE()), '%y')
    )
WHERE semester IS NULL OR semester = '';

ALTER TABLE classes
    MODIFY COLUMN semester VARCHAR(4) NOT NULL,
    ADD CONSTRAINT chk_classes_semester_code
        CHECK (semester REGEXP '^(SP|SU|FA)[0-9]{2}$'),
    ADD INDEX idx_classes_semester_subject_status (semester, subject_id, status);

-- A semester is "created" by advancing this existing key/value setting. Class
-- history stays on classes.semester, so no semester table is needed.
INSERT IGNORE INTO system_settings
    (setting_key, setting_value, setting_group, description, is_encrypted)
VALUES
    ('academic.current_semester',
     CONCAT(
        CASE
            WHEN MONTH(CURRENT_DATE()) BETWEEN 1 AND 4 THEN 'SP'
            WHEN MONTH(CURRENT_DATE()) BETWEEN 5 AND 8 THEN 'SU'
            ELSE 'FA'
        END,
        DATE_FORMAT(CURRENT_DATE(), '%y')
     ),
     'GENERAL',
     'Học kỳ hiện hành dùng tự động khi tạo lớp (SPyy/SUyy/FAyy)',
     0);
