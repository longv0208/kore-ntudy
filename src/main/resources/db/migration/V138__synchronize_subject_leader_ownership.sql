-- A LEADER account's assigned subject and subjects.leader_user_id must describe
-- the same ownership boundary. V131 assigned several lecturer accounts to the
-- leader field, leaving the seeded KOR311 leader unable to operate leader-only
-- curriculum controls. Repair only subjects that have an explicit LEADER user.
UPDATE subjects subject_row
JOIN users leader_row
  ON leader_row.subject_id = subject_row.id
 AND leader_row.role = 'LEADER'
 AND leader_row.is_deleted = 0
SET subject_row.leader_user_id = leader_row.id,
    subject_row.updated_at = CURRENT_TIMESTAMP;
