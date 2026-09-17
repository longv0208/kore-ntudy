# Phase 3: LessonsServiceTest coverage

## Context links

- `src/test/java/com/ksh/features/lessons/service/LessonsServiceTest.java`
- `src/main/java/com/ksh/features/lessons/service/LessonsService.java`
- `src/main/java/com/ksh/features/lessons/service/LessonsUpdateHelper.java`
- `src/main/java/com/ksh/features/lessons/service/LessonsReorderService.java`
- `src/main/java/com/ksh/features/lessons/service/LessonContentTypeSwitcher.java`

## Overview

- Priority: P2
- Status: pending
- Extend the existing fresh class/section fixture; keep media assertions storage-safe and persistence-based.

## Key insights

- `update` owns title/body/type changes and delegates status transitions, while publish-service tests do not cover update-form status changes.
- Form-based create has a separate external-video/publish-on-create branch not covered by the current tests.
- Every CRUD/media path verifies class ownership, section-to-class binding, and lesson-to-section binding; missing/deleted lessons should fail closed.
- Library-backed video references must survive class-lesson deletion as library assets/blobs, while the lesson reference is cleared.
- Direct external/upload/library video methods write their own audit rows and have canonical-snapshot guards; storage replacement/rollback behavior remains largely untested.

## Implementation steps

1. Add form-based create coverage for a valid external-video form and for `PUBLISHED`; assert VIDEO fields, null rich text, `publishedAt`, CREATED/PUBLISHED audit rows, and no controller-only URL validation assumptions.
2. Add update-form status transition tests for DRAFT→PUBLISHED and PUBLISHED→DRAFT; assert status/timestamp and audit order (`UPDATED` before the transition row), plus no transition row when status is unchanged.
3. Add a cross-class, missing, or soft-deleted section/lesson lookup test for `listForSection` or `getEditableLesson`; assert `EntityNotFoundException` and no mutation.
4. Add duplicate-id and null-list reorder coverage, asserting `IllegalArgumentException` and unchanged display orders; optionally add a concurrent create test if the environment supports two transactions.
5. Add direct `setExternalVideo` coverage, asserting provider/url persistence and `VIDEO_SET` activity; retain controller-level URL regex validation out of scope.
6. Add successful `bindVideoFromLibrary` coverage with an owned `KIND_VIDEO` asset; assert VIDEO type, UPLOAD provider, asset FK, stored path, and summary. Add wrong-kind/foreign-owner rejection if fixture cost is low.
7. Add library-video delete cleanup coverage; assert the soft-deleted lesson no longer lists, its library FK is released, and the library asset/blob remains available. Cover uploaded-video replacement/delete-after-commit only if real storage lifecycle can be isolated.
8. Optionally add direct `getEditableLesson` success coverage if hierarchy lookup is not covered by step 3.

## Related files

- Modify: `src/test/java/com/ksh/features/lessons/service/LessonsServiceTest.java`
- Create/delete: none

## Success criteria

Update status transitions, hierarchy fail-closed behavior, reorder validation, direct media APIs, and library-video lifecycle are verified against database state and audit rows.

## Risks and security

- Reuse `LibraryService.upload`/`ObjectStorage` for a real owned asset; do not fabricate storage records.
- Clear the persistence context before asserting soft-delete visibility or released FKs where the existing tests document first-level-cache behavior.
- Keep canonical override conflict coverage from `CanonicalLessonVideoOverrideServiceTest` intact rather than duplicating it here.
