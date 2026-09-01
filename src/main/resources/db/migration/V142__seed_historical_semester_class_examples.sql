-- Keep a small archived history in demo environments so semester filtering is
-- exercised across years. These rows reuse existing lecturers/subjects and do
-- not introduce any new persistence structure.
INSERT INTO classes
    (name, lecturer_id, subject_id, semester, start_date, end_date,
     max_students, status, description, created_by, approved_by, approved_at)
SELECT 'KOR111 - FA25 (đã lưu trữ)', lecturer_id, subject_id, 'FA25',
       '2025-09-01', '2025-12-20', max_students, 'ARCHIVED',
       'Lớp dữ liệu lịch sử dùng để kiểm tra bộ lọc học kỳ.',
       created_by, approved_by, '2025-08-25 08:00:00'
FROM classes current_row
WHERE current_row.name = 'KOR111 - chinhcd2'
  AND NOT EXISTS (SELECT 1 FROM classes WHERE name = 'KOR111 - FA25 (đã lưu trữ)')
LIMIT 1;

INSERT INTO classes
    (name, lecturer_id, subject_id, semester, start_date, end_date,
     max_students, status, description, created_by, approved_by, approved_at)
SELECT 'KOR122 - SU25 (đã lưu trữ)', lecturer_id, subject_id, 'SU25',
       '2025-05-05', '2025-08-15', max_students, 'ARCHIVED',
       'Lớp dữ liệu lịch sử dùng để kiểm tra bộ lọc học kỳ.',
       created_by, approved_by, '2025-04-28 08:00:00'
FROM classes current_row
WHERE current_row.name = 'KOR122 - ThaoNT'
  AND NOT EXISTS (SELECT 1 FROM classes WHERE name = 'KOR122 - SU25 (đã lưu trữ)')
LIMIT 1;

INSERT INTO classes
    (name, lecturer_id, subject_id, semester, start_date, end_date,
     max_students, status, description, created_by, approved_by, approved_at)
SELECT 'KOR211 - SP25 (đã lưu trữ)', lecturer_id, subject_id, 'SP25',
       '2025-01-06', '2025-04-25', max_students, 'ARCHIVED',
       'Lớp dữ liệu lịch sử dùng để kiểm tra bộ lọc học kỳ.',
       created_by, approved_by, '2024-12-20 08:00:00'
FROM classes current_row
WHERE current_row.name = 'KOR211 - chinhcd2'
  AND NOT EXISTS (SELECT 1 FROM classes WHERE name = 'KOR211 - SP25 (đã lưu trữ)')
LIMIT 1;

INSERT INTO classes
    (name, lecturer_id, subject_id, semester, start_date, end_date,
     max_students, status, description, created_by, approved_by, approved_at)
SELECT 'KOR311 - FA24 (đã lưu trữ)', lecturer_id, subject_id, 'FA24',
       '2024-09-02', '2024-12-20', max_students, 'ARCHIVED',
       'Lớp dữ liệu lịch sử dùng để kiểm tra bộ lọc học kỳ.',
       created_by, approved_by, '2024-08-26 08:00:00'
FROM classes current_row
WHERE current_row.name = 'KOR311 - LeTT'
  AND NOT EXISTS (SELECT 1 FROM classes WHERE name = 'KOR311 - FA24 (đã lưu trữ)')
LIMIT 1;

INSERT INTO classes
    (name, lecturer_id, subject_id, semester, start_date, end_date,
     max_students, status, description, created_by, approved_by, approved_at)
SELECT 'TOP301 - SU24 (đã lưu trữ)', lecturer_id, subject_id, 'SU24',
       '2024-05-06', '2024-08-16', max_students, 'ARCHIVED',
       'Lớp dữ liệu lịch sử dùng để kiểm tra bộ lọc học kỳ.',
       created_by, approved_by, '2024-04-29 08:00:00'
FROM classes current_row
WHERE current_row.name = 'TOP301 - ChoA'
  AND NOT EXISTS (SELECT 1 FROM classes WHERE name = 'TOP301 - SU24 (đã lưu trữ)')
LIMIT 1;

INSERT INTO classes
    (name, lecturer_id, subject_id, semester, start_date, end_date,
     max_students, status, description, created_by, approved_by, approved_at)
SELECT 'TOP401 - SP24 (đã lưu trữ)', lecturer_id, subject_id, 'SP24',
       '2024-01-08', '2024-04-26', max_students, 'ARCHIVED',
       'Lớp dữ liệu lịch sử dùng để kiểm tra bộ lọc học kỳ.',
       created_by, approved_by, '2023-12-22 08:00:00'
FROM classes current_row
WHERE current_row.name = 'TOP401 - KimDY'
  AND NOT EXISTS (SELECT 1 FROM classes WHERE name = 'TOP401 - SP24 (đã lưu trữ)')
LIMIT 1;
