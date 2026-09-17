# Phase 2: LessonTemplateServiceTest coverage

## Context links

- `src/test/java/com/ksh/features/library/service/LessonTemplateServiceTest.java`
- `src/main/java/com/ksh/features/library/service/LessonTemplateService.java`
- `src/main/java/com/ksh/entities/LessonTemplate.java`
- Existing related coverage: `LessonTemplateAuthoringLockTest`, `LessonTemplateStorageVideoEditTest`, `LessonTemplatePrivateShareRefreshTest`

## Overview

- Priority: P2
- Status: pending
- Extend the existing subject-scoped integration fixture; avoid duplicating already-covered lock/storage/provenance scenarios.

## Key insights

- Leader-only form loading has separate `startNewChapter` and `requestedChapterNumber` branches; numbering is global and titles are canonicalized.
- Resource references are durable join rows; detaching must not delete the underlying `LibraryAsset`.
- Distribution accepts only active same-subject classes, refreshes exact provenance, and rejects title collisions without provenance; PDF/VIDEO body mapping remains untested here.
- Soft deletion must hide rows while closing lesson/chapter numbering gaps; `distributeSubject` delegates every canonical template to each target.
- Null-only/foreign target lists need atomicity assertions so failed distribution cannot leave sections, lessons, or partial refreshes behind.

## Implementation steps

1. Cover `loadForm(null, ..., startNewChapter=true)` and `requestedChapterNumber`, asserting next chapter/lesson numbers and stripped chapter labels.
2. Cover successful `renameChapter`, asserting every lesson in the chapter receives the canonical renamed chapter title while lesson ordering remains stable.
3. Create a template with a supplementary document asset, call `detachResource`, and assert the join row is gone while the library asset remains.
4. Add duplicate-title distribution rejection for a directly authored class lesson; assert no source-template snapshot is created.
5. Add canonical PDF and VIDEO distribution cases using real owned `LibraryAsset` rows; assert content type, canonical attachment origin/FK, provider, URL, summary, publication, and provenance.
6. Add `distributeSubject` coverage with multiple templates/chapters and one active same-subject class; assert one published snapshot per template and correct provenance, then assert a second call reuses ids.
8. Add invalid target/atomicity coverage (null-only ids and a foreign-subject class), asserting the expected exception and no partial section/lesson creation or refresh.
9. Add `softDelete` and `softDeleteChapter` coverage, asserting list exclusion and dense global lesson/chapter renumbering of remaining rows.
10. Add one invalid body/resource test (PDF without an owned PDF asset or invalid video source), asserting a client-facing `IllegalArgumentException` before partial template mutation.
11. If the existing Excel helper is available, add `importSyllabus` coverage for sorted upsert count and preservation of existing bodies/resources; otherwise leave as a follow-up.

## Related files

- Modify: `src/test/java/com/ksh/features/library/service/LessonTemplateServiceTest.java`
- Create/delete: none

## Success criteria

Form defaults, structure edits, resource lifecycle, distribution guards, subject distribution, and soft-delete compaction are covered with repository-backed assertions.

## Risks and security

- Use leader identity resolved for the seeded subject; do not assume admin may author structure.
- Use unique chapter numbers/title suffixes to avoid any non-transactional concurrency residue.
- Reuse existing asset constructors and assert ownership boundaries; never bypass `LibraryService` checks with mocks.
