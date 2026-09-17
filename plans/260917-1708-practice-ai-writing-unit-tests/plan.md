---
title: "Practice AI Writing unit-test hardening"
description: "Prioritized unit-test plan for changed Practice AI/Writing paths and complex re-evaluation behavior."
status: pending
priority: P1
effort: 6h
branch: main
tags: [tests, practice-ai, writing, spring, junit5]
created: 2026-09-17
---

# Scope and current test setup

- Java 17, Spring Boot 3.5.16, Maven wrapper (`mvnw.cmd`), JUnit 5/Mockito/AssertJ via `spring-boot-starter-test`; Spring Security test also available.
- JaCoCo 0.8.12 runs during `test`; report is generated under `target/site/jacoco`.
- Existing Writing coverage is substantial: client, normalizer, cache, parser, rule/scoring policies, task resolver, and contract fixtures.
- Existing PracticeService coverage includes full/per-question re-evaluation, aggregation, unavailable/invalid responses, version gates, and optimistic-lock paths.
- Existing control-plane service/contract tests cover capability and persistence rules; controller tests are sparse.

# Highest-risk behavior to cover

1. `WritingEvaluationClient.unifiedSchema(taskType)` and `findingSchema(taskType)` are task-specific and now emit strict `anyOf` branches. Verify Q51/Q52/Q53/Q54 schemas do not permit cross-task criteria, enforce polarity/operation/evidence cardinality, and retain bounded metadata.
2. `WritingEvaluationClient.evaluate(...)` is a fail-closed pipeline: deterministic invalid short-circuit, cache read/rehydration/delete, provider availability, contract/HTTP/transport/unexpected failures, interruption propagation, normalization, metrics, and cache write. Preserve ordering and no-provider-call guarantees.
3. `WritingEvidenceLedgerVerifier.rubrics(...)` now derives authoritative evidence/finding/requirement IDs and permits zero anchors without false contradiction. Test maximum/zero/intermediate anchors, contradictory evidence, duplicate/overlapping spans, and upgrade rewrite authority.
4. `WritingEvaluationNormalizer.normalize(...)` must convert verifier rejection to contract failure without leaking provider payloads; cache trust/sanitization/rehydration must reject stale identity, wrong task, score mismatch, and unsanitized payload.
5. `PracticeService.requestReEvaluation(...)` now promotes a per-question request to full attempt when current analysis is not succeeded or feedback is blank. Test operation/questionId/fingerprint and queue persistence for both branches.
6. `PracticeService.gradeWritingSnapshot(...)` and `gradeWritingQuestionSnapshot(...)` preserve leading/trailing answer whitespace (no `.trim()`), while retaining score aggregation and target-only replacement semantics.
7. `PracticeAiControlPlaneController.saveBinding(...)` must preserve submitted form and `BindingResult` on validation/service errors so Thymeleaf fields/errors survive redisplay. Add focused direct-controller tests; template hidden defaults need static contract coverage.

# TODO implementation sequence

- [ ] Extend `WritingEvaluationClientTest` with parameterized schema assertions for Q51/Q52/Q53/Q54 and explicit payload capture for task-specific criteria.
- [ ] Add client branch tests for retryable HTTP statuses (429/5xx), non-retryable 4xx, provider contract failure, interruption, malformed cache cleanup, cache-write failure, and metrics outcome; keep existing no-secret-log assertions.
- [ ] Extend `WritingEvaluationNormalizerTest` with zero-anchor acceptance, derived rubric ownership IDs, non-empty upgrade content without rewrites (must normalize to empty), stale/wrong task cache rejection, and trusted-finding metadata rejection.
- [ ] Add a dedicated `WritingEvidenceLedgerVerifierTest` (or keep fixture-driven tests in the normalizer suite) for exact offset/occurrence/overlap/one-to-one finding invariants and maximum/zero/intermediate anchor contradictions.
- [ ] Add `PracticeServiceTest` cases around `requestReEvaluation`: per-question + succeeded/nonblank feedback remains `QUESTION_REEVALUATE`; per-question + queued analysis/blank feedback becomes `FULL_REEVALUATE`; verify inserted/saved job fields and fingerprint identity.
- [ ] Add `PracticeServiceTest` whitespace regressions for full and per-question Writing grading; answer passed to `WritingEvaluationClient` must retain surrounding whitespace and score/result persistence remains unchanged.
- [ ] Add `PracticeAiControlPlaneControllerTest` for purpose mismatch and service exception: returned view, original form, `BindingResult.MODEL_KEY_PREFIX + "form"`, profiles, purpose, required capabilities, and active tab all present.
- [ ] Add/extend static template test to assert hidden `directAudioInput=false` and `pdfImageInput=false` inputs for non-applicable purposes, without duplicating browser tests.
- [ ] Run focused tests first, then complete Practice test slice, then full Maven test with JaCoCo; fix failures before review.

# Validation commands (PowerShell)

```powershell
.\mvnw.cmd -Dtest=WritingEvaluationClientTest,WritingEvaluationNormalizerTest test
.\mvnw.cmd -Dtest=WritingEvaluationCacheServiceTest,PracticeServiceTest test
.\mvnw.cmd -Dtest=PracticeAiControlPlaneControllerTest test
.\mvnw.cmd test
```

# Test design constraints

- Use production-shaped `WritingContractTestFixtures`; do not bypass verifier with fake score fields or provider mocks that hide contract behavior.
- Prefer parameterized tests for task types and small branch-specific fixtures; retain Mockito only at repository/provider boundaries.
- Assert externally observable outcomes, cache calls, provider call counts, persisted job fields, and metrics rather than private implementation details.
- Keep tests deterministic: fixed answers/offsets, no wall-clock assertions beyond ordering, and no network/database dependency for unit scope.

# Dependencies and risks

- `PracticeService.requestReEvaluation` tests require a mocked `PracticeAttemptEvaluationJobRepository` in the production constructor; existing lightweight constructor cannot exercise this path.
- Controller tests may need `BindingResult`, `ModelMap`, and a mocked authenticated `KshUserDetails`; no Spring MVC slice is necessary unless binding behavior itself is under test.
- Template hidden-input assertions are static-contract tests; they cannot prove browser checkbox submission order.

# Unresolved questions

- Should verifier-focused tests be a new class or remain in `WritingEvaluationNormalizerTest` to minimize fixture duplication?
- Is the desired coverage gate numeric, or only regression-focused JaCoCo visibility? 
