-- Correct the demo catalog assignment without granting access by email at runtime.
UPDATE subjects s
JOIN users old_leader ON old_leader.id = s.leader_user_id
JOIN users intended_leader ON intended_leader.email = 'kor_leader@ksh.edu.vn'
SET s.leader_user_id = intended_leader.id
WHERE old_leader.email = 'kor_lecturer@ksh.edu.vn'
  AND intended_leader.role = 'LEADER';
