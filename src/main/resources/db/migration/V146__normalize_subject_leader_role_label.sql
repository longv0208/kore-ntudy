-- Role display text is also read from the database by administration screens.
-- Role identifiers and permission grants are unchanged.
UPDATE roles
SET name = 'Trưởng môn',
    description = REPLACE(REPLACE(description, 'bộ môn', 'môn học'), 'Bộ môn', 'Môn học')
WHERE code = 'LEADER';
