-- The full Korean catalog seeder originally marked demo submissions GRADED
-- without inserting the corresponding authoritative feedback row.  Repair only
-- those exact generated demo submissions; real submissions are not touched.
INSERT INTO assignment_feedback
    (submission_id, graded_by, score, feedback, is_ai_generated, created_at, updated_at)
SELECT submission_row.id,
       assignment_row.created_by,
       9.00,
       'Bài làm đáp ứng yêu cầu; cần diễn đạt tự nhiên hơn.',
       0,
       COALESCE(submission_row.updated_at, submission_row.submitted_at, CURRENT_TIMESTAMP),
       COALESCE(submission_row.updated_at, submission_row.submitted_at, CURRENT_TIMESTAMP)
FROM assignment_submissions submission_row
JOIN assignments assignment_row
  ON assignment_row.id = submission_row.assignment_id
LEFT JOIN assignment_feedback feedback_row
  ON feedback_row.submission_id = submission_row.id
WHERE submission_row.status = 'GRADED'
  AND submission_row.content = 'Em đã hoàn thành bài tập đầy đủ theo hướng dẫn của giảng viên.'
  AND feedback_row.id IS NULL;
