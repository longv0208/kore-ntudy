-- One flag on the existing subject row is sufficient: locking is a current
-- authoring policy, not a versioned domain object and therefore needs no table.
ALTER TABLE subjects
    ADD COLUMN library_locked TINYINT(1) NOT NULL DEFAULT 0 AFTER leader_user_id;
