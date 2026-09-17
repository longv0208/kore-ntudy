# Phase 3: ImportStudentsServiceIntegrationTest

## Context links

- Source: `src/main/java/com/ksh/features/classes/imports/service/ImportStudentsService.java`
- Row processor: `src/main/java/com/ksh/features/classes/imports/service/ImportRowProcessor.java`
- Existing test: `src/test/java/com/ksh/features/classes/imports/service/ImportStudentsServiceIntegrationTest.java`
- Workflow: `docs/audit/workflows/core/CLASS_STUDENT_IMPORT_EXCEL.md`
- Seeds: `V5__seed_test_users.sql`, `V8__seed_fake_students.sql`

## Overview

Priority P1 for security/state coverage; real Spring Boot + Flyway + disposable MySQL. Keep `@Transactional`, create fresh classes through the existing helper, and use only seeded users (`lecturer`, `leader`, `admin`, `sv01`–`sv08`).

## Test additions

1. **Preview/confirm state machine**
   - Existing ACTIVE enrollment previews as `DUPLICATE_IN_CLASS` and confirm skips it without changing active count.
   - COMPLETED enrollment previews as `ENROLLMENT_COMPLETED`; confirm reports `skippedError` and never reactivates it.
   - A valid preview followed by deactivating/locking the student is marked `PERSISTENCE_FAILED` on confirm and does not create an ACTIVE enrollment.
2. **Session ownership and replay protection**
   - Wrong class id restores the claimed session and throws `InvalidFileException`.
   - Wrong lecturer/role cannot preview or confirm the owner’s class/session (`AccessDeniedException`); do not rely on controller-only security.
   - Successful confirmation consumes the session; a second confirmation fails as expired/missing and creates no second enrollment.
3. **Audit/result contract**
   - Confirm writes one `UPDATED` class activity with filename and exact imported/reactivated/skipped/failed counts; verify `JOINED_VIA_IMPORT` on created rows.
   - Retain existing mixed valid/error, skip-errors=false, re-enroll, and capacity-lock tests as regression coverage.

## Implementation notes

Use `ClassActivityRepository` only if needed to assert the persisted summary. Mutate seeded users/enrollments inside the transactional test so rollback isolates methods. Avoid adding test migrations or fake users. If an exception path restores a session, assert it remains retrievable through `sessionStore.get` before the transaction ends.

## Success criteria

Tests exercise real parser/validator/session/repository/transaction behavior, prove no unauthorized or replay import, and pass against an explicitly disposable MySQL database.
