# Phase 1: ClassesServiceTest

## Context links

- Source: `src/main/java/com/ksh/features/classes/service/ClassesService.java`
- Existing test: `src/test/java/com/ksh/features/classes/service/ClassesServiceTest.java`
- Contracts: `docs/audit/workflows/core/CLASS_MANAGEMENT_AND_ENROLLMENT.md`

## Overview

Priority P2; unit-only Mockito coverage. Extend the existing setup/builders; do not introduce Spring context or production changes.

## Test additions

1. **Role and empty branches**
   - `listForUser`: leader with no assigned subjects and unsupported/student role return an empty page without broad repository queries.
   - `listForUserByStatuses`: empty/null statuses return empty; exercise LEADER and ADMIN repository routes; unsupported role returns empty.
   - `countForUserByStatuses`: empty input returns zero; verify leader/admin/unsupported routing.
2. **Search/filter contract**
   - `listForUserByStatusesAndFilters` sends trimmed subject/query values and a valid case-insensitive semester code to the lecturer query.
   - Invalid or blank semester normalizes to `""`; cover leader and admin query methods and empty leader-subject scope.
3. **Overview and badges**
   - Return total/active/archived/student/teacher values from a mixed search result and verify the two aggregate repository calls.
   - Empty search result returns `ClassOverview.empty()` and `ClassStatusCounts.empty()` without aggregate calls.
   - Mixed statuses produce exact `ClassStatusCounts` values.
4. **Access/row edges**
   - `getEditableForUpdate` uses `findByIdForUpdate`, returns authorized entity, and throws 403 for denied access; missing row remains 404.
   - `getViewable` delegates to the read authorization path; `isEditableBy` returns false for null role.
   - Non-empty mapping includes subject code/lecturer label and preserves page total; empty mapping avoids aggregate queries.
5. **Defensive semester input**
   - Null/blank persisted semester codes are filtered while valid codes retain newest-first ordering.

## Implementation notes

Use `ArgumentCaptor`/`verify` for repository method and normalized argument assertions. Keep existing activity and mutation tests unchanged. Avoid testing repository query semantics here; those belong to repository/integration tests.

## Success criteria

All new tests are deterministic, isolated mocks, and pass with `ClassesServiceTest` under JDK 17.
