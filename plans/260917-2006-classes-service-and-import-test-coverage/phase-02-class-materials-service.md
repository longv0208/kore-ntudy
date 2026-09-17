# Phase 2: ClassMaterialsServiceTest

## Context links

- Source: `src/main/java/com/ksh/features/classes/service/ClassMaterialsService.java`
- Existing test: `src/test/java/com/ksh/features/classes/service/ClassMaterialsServiceTest.java`
- Contract: class materials are library-backed references; student reads require ACTIVE class and ACTIVE enrollment.

## Overview

Priority P2; unit-only Mockito coverage. Reuse the current mocked collaborators and `LessonAttachment.forClassMaterial` fixtures.

## Test additions

1. **Share validation**
   - Inactive/archived class rejects sharing before attachment save.
   - Non-DOCUMENT asset rejects sharing; duplicate behavior remains covered.
2. **Listing and mapping**
   - Teaching viewer list calls `getViewable`, maps newest attachments, uses LibraryAsset titles, and falls back to original filename when an asset title is absent.
   - Student list succeeds for an active enrollment and returns the same download URL/metadata; inactive enrollment or archived class is denied/not found.
   - Empty attachment list returns an empty result and does not rely on a blob copy.
3. **Remove and download failures**
   - Owner removes an existing class reference and only calls attachment delete.
   - Missing material throws `EntityNotFoundException` for remove/download.
   - Elevated viewer can download a library-backed reference; a non-library attachment is rejected as not found.
4. **Presentation boundary**
   - `ClassMaterialRow.sizeLabel()` covers bytes, kilobytes, and megabytes at threshold values.

## Implementation notes

Assert authorization collaborator calls and storage-key resolution. Do not test actual object storage; `LibraryService` is the external boundary and remains mocked in this unit class.

## Success criteria

New tests cover authorization, no-copy reference invariants, mapping fallback, failure paths, and size formatting while remaining fast and deterministic.
