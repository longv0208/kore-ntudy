-- New classes no longer require subject-leader approval.
-- Preserve historical review statuses and reviewer evidence unchanged.
ALTER TABLE classes ALTER COLUMN status SET DEFAULT 'ACTIVE';
