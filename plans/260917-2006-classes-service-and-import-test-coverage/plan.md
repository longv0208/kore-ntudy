---
title: "Classes and student-import test coverage"
description: "Add focused unit and integration tests to the three requested existing test classes without changing production code or fixtures."
status: pending
priority: P2
effort: 5h
branch: main
tags: [tests, classes, materials, student-import, junit, spring-boot]
created: 2026-09-17
---

## Scope

Modify only these existing test files:

- `src/test/java/com/ksh/features/classes/service/ClassesServiceTest.java`
- `src/test/java/com/ksh/features/classes/service/ClassMaterialsServiceTest.java`
- `src/test/java/com/ksh/features/classes/imports/service/ImportStudentsServiceIntegrationTest.java`

Do not change production code, migrations, seed fixtures, or add new test files. Reuse Mockito builders in unit tests and the existing `@SpringBootTest @Transactional` Excel builder in the integration test.

## Baseline and contracts

- Current unit baseline: `ClassesServiceTest` 24/24 and `ClassMaterialsServiceTest` 3/3 pass with JDK 17.
- `ClassesService` contracts: role-scoped lists/searches, filter normalization, aggregate overview/badges, owner/admin mutation boundaries, lock-based editable access, 404/403 behavior.
- `ClassMaterialsService` contracts: active owner-only sharing of DOCUMENT library assets, no-copy class references, active-enrollment student gate, teaching-view gate, remove/download failure paths, row size labels.
- Import contracts: V5 users (`lecturer`, `leader`, `admin`, `student`), V8 students `sv01`–`sv08`, preview status classification, owner/class-pinned session claim, reactivation/duplicate/completed handling, capacity lock, activity summary.

## Phases

1. [Phase 1: ClassesServiceTest](phase-01-classes-service.md) — cover untested role/filter/aggregate/access branches and retain interaction assertions.
2. [Phase 2: ClassMaterialsServiceTest](phase-02-class-materials-service.md) — cover list/remove/download gates and pure row formatting boundaries.
3. [Phase 3: ImportStudentsServiceIntegrationTest](phase-03-import-students-integration.md) — cover replay/security and mutable enrollment/user states against real MySQL/Flyway data.

## Validation

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd "-Dtest=ClassesServiceTest,ClassMaterialsServiceTest" test
.\mvnw.cmd "-Dtest=ImportStudentsServiceIntegrationTest" test
.\mvnw.cmd "-Dtest=ClassesServiceTest,ClassMaterialsServiceTest,ImportStudentsServiceIntegrationTest" test
```

The integration command requires an isolated disposable `TEST_DB_URL`, `TEST_DB_USERNAME`, and `TEST_DB_PASSWORD`; never point it at a shared development database. Review Surefire results and the JaCoCo report under `target/site/jacoco`.

## Definition of done

- Only the three listed test files are modified.
- Tests assert observable service/API contracts, not implementation trivia.
- Unit tests pass on JDK 17; integration tests pass with the required disposable DB.
- No mocks replace the real database in the import integration test.
