---
title: "Service test coverage hardening"
description: "Add focused integration coverage for uncovered Card, Library lesson-template, and class lesson service contracts."
status: pending
priority: P2
effort: 10h
branch: main
tags: [tests, integration, flashcards, library, lessons]
created: 2026-09-17
---

## Scope

Add tests only to:
- `src/test/java/com/ksh/features/flashcards/service/CardServiceTest.java`
- `src/test/java/com/ksh/features/library/service/LessonTemplateServiceTest.java`
- `src/test/java/com/ksh/features/lessons/service/LessonsServiceTest.java`

No production changes, new test classes, migrations, or fixture changes.

## Current gaps

- Card tests cover bulk replacement basics, but not rich-field normalization, null-list clearing, editor read authorization, `setImage`, or review-row retention/cascade.
- Template tests cover core rich-text distribution and concurrency, but not form-default branches, PDF/VIDEO distribution, invalid-target atomicity, detach/delete/resequence operations, subject-wide distribution, or syllabus import.
- Lesson tests cover CRUD, reorder, content switches, and ownership, but not form-based create/publish, update-driven status transitions, cross-hierarchy/missing-row rejection, library-video lifecycle, storage cleanup, or direct video APIs.

## Phases

1. [Card service tests](phase-01-card-service-tests.md) — rich fields, null replacement, image mutation/authz.
2. [Lesson template tests](phase-02-lesson-template-service-tests.md) — form/structure/resource/distribution lifecycle gaps.
3. [Lessons service tests](phase-03-lessons-service-tests.md) — status, hierarchy guards, direct media APIs, library cleanup.
4. Run the three focused Maven test classes, then the full test suite; keep assertions persistence-based and avoid mocks/fake data.

## Dependencies and constraints

- Preserve `@SpringBootTest` + `@Transactional` integration style and seeded users.
- Reuse existing `richtextForm`, `activeClass`, `saveClass`, and `ensureExtraLecturer` helpers where applicable.
- Use unique class codes/chapter numbers/assets per test; clear the persistence context only when verifying soft-delete or post-commit state.
- Assert public behavior, persisted rows, audit records, and authorization exceptions—not private helpers or implementation calls.

## Success criteria

All planned tests compile and pass in isolation and together; only the three named test files change; no production behavior is altered.

## Unresolved questions

- Confirm whether the requested change expects all uncovered public methods or only a minimal regression subset before implementation.
- Confirm CI database/storage profile supports concurrent and post-commit tests already used by these classes.
- Confirm intended behavior for duplicate/foreign CardItem ids and null elements, null-only distribution targets, canonical snapshot edits, and update-form publish notifications before encoding new assertions.
