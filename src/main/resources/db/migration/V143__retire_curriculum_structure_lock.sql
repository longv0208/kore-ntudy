-- Structure/content authoring is permanently owned by the Subject Leader.
-- Keep the legacy column for backward-compatible schema rollout, but clear
-- every stale value because the only active lock now governs lecturer
-- resource additions (departments.library_locked).
UPDATE subjects
SET curriculum_structure_locked = 0
WHERE curriculum_structure_locked <> 0;
