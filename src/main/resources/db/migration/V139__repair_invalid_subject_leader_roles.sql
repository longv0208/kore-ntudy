-- V131 populated leader_user_id with the lecturer who teaches each demo class.
-- That makes the subject impossible to manage through LEADER-only workflows.
-- Keep the teaching assignment on classes.  Assign only those invalid subject
-- ownership rows to the canonical Korean-program leader instead.
UPDATE subjects subject_row
JOIN users current_owner
  ON current_owner.id = subject_row.leader_user_id
JOIN users canonical_leader
  ON canonical_leader.email = 'kor_leader@ksh.edu.vn'
 AND canonical_leader.role = 'LEADER'
 AND canonical_leader.is_deleted = 0
SET subject_row.leader_user_id = canonical_leader.id,
    subject_row.updated_at = CURRENT_TIMESTAMP
WHERE current_owner.role <> 'LEADER';
