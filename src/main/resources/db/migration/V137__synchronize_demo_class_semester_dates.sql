-- V131 seeded a complete class catalog with an end date of 2026-06-30.
-- Once that date passed, the nightly auto-archive worker correctly archived
-- every demo class, even though their ACTIVE enrollments were still present.
-- Move only the named canonical demo catalog into the current semester so the
-- student catalog, My Classes view and lecturer member roster agree again.
UPDATE classes
SET status = 'ACTIVE',
    start_date = '2026-08-17',
    end_date = '2026-12-31',
    updated_at = CURRENT_TIMESTAMP
WHERE is_deleted = 0
  AND name IN (
      'KOR111 - chinhcd2',
      'KOR111 - ThaoNT',
      'KOR122 - nhungdtp2',
      'KOR122 - ThaoNT',
      'KOR211 - chinhcd2',
      'KOR211 - nhungdtp2',
      'KOR222 - MinhNV',
      'KOR222 - ParkSJ',
      'KOR311 - LeTT',
      'KOR311 - ChoA',
      'KOR322 - LeTT',
      'KOR322 - KimDY',
      'KOR411 - PhuongHTH2',
      'KOR411 - LeeHM',
      'KOR422 - PhuongHTH2',
      'TOP301 - ChoA',
      'TOP301 - LeeHM',
      'TOP401 - ChoA',
      'TOP401 - KimDY',
      'KOR_BIZ - PhuongHTH2',
      'KOR_TRAN - LinhND'
  );
