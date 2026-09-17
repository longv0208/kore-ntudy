---
title: "Assignment service test hardening"
description: "Add persistence-focused integration coverage for one-shot assignment submissions without changing production code."
status: pending
priority: P2
effort: 2h
branch: main
tags: [tests, assignments, submissions, spring-integration]
created: 2026-09-17
---

# Scope

Test-only changes, centered on `AssignmentServiceTest`; no production or migration edits. Existing tests use `@SpringBootTest`, real repositories, `@Transactional`, seeded lecturer/student users, and a fresh class plus ACTIVE enrollment per test.

# Findings

- `StudentAssignmentService.submit` trims/validates content, locks the assignment then submission, and inserts exactly one `SUB_SUBMITTED` row.
- `assignment_submissions` has a database unique key on `(assignment_id, user_id)`; `AssignmentSubmissionRepository` exposes lookup/count methods.
- Existing coverage checks visible DTOs and exceptions, but does not directly assert persisted row count/content or database uniqueness.
- The working tree already adds `AssignmentSubmissionRepository` injection to `AssignmentServiceTest`; use it rather than adding another fixture abstraction.

# TODOs

- [ ] Keep the repository injection ordered with other assignment imports and use it in persistence assertions.
- [ ] Extend the first-submit test to flush/query the real repository and assert one row, assignment/student identity, trimmed content, `SUB_SUBMITTED`, non-late default, and timestamps.
- [ ] Strengthen duplicate-submit coverage: submit once, assert the second call fails before persistence, then flush/query and verify exactly one row with original content/status.
- [ ] Strengthen blank-content coverage for whitespace input: assert rejection leaves repository lookup empty (no consumed attempt).
- [ ] Add a late-allowed persistence assertion that `isLate=true`; retain the existing DTO-level check for end-to-end behavior.
- [ ] Avoid direct entity construction except where needed to verify repository constraints; do not mock repositories or alter production code.
- [ ] Run focused `AssignmentServiceTest`, then the assignment test slice (`AssignmentServiceTest`, `AssignmentsIntegrationTest`, `AssignmentConcurrencyContractTest`, `AssignmentCatalogUiContractTest`), then full Maven tests.

# Fixture/testing strategy

- Reuse `setUp`, `createAndPublish`, `saveClass`, and `ensureUser`; unique class code remains `ASGNIT` plus a time-derived suffix.
- Use `submissionRepository.findByAssignmentIdAndUserId(... )` for identity/content assertions and `countByAssignmentId(...)` only after `saveAndFlush` or a query-triggered flush.
- Assert observable persistence state, not Hibernate implementation details; use AssertJ and existing exception/message conventions.
- Keep tests isolated through the class-level transaction and fresh class/enrollment; do not rely on migration demo submissions.
- If testing the DB unique key directly, create two rows with the same pair and assert the repository/database rejects the second insert, while clearing the persistence context as needed to avoid false positives.

# Success criteria

- Tests prove one-shot semantics and no persistence on validation failure.
- Tests prove late flag and normalized content are stored correctly.
- No production files changed; existing assignment and full test suites pass.

# Unresolved questions

- Is the intended request limited to `AssignmentServiceTest`, or should repository-level unique-key coverage be a separate test class?
- Should the concurrent submit race be exercised with real threads, or remain covered by the existing lock-order contract test?
