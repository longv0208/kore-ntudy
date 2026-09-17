# Phase 1: CardServiceTest coverage

## Context links

- `src/test/java/com/ksh/features/flashcards/service/CardServiceTest.java`
- `src/main/java/com/ksh/features/flashcards/service/CardService.java`
- `src/main/java/com/ksh/features/flashcards/support/DeckAccessResolver.java`
- `src/main/java/com/ksh/features/flashcards/dto/FlashcardDtos.java`

## Overview

- Priority: P2
- Status: pending
- Add focused integration tests without changing `CardService`.

## Key insights

- `replaceCards` treats `null` as an empty full replacement, trims required text, and maps blank optional media/alternatives to `null`.
- Existing-card updates preserve ids and therefore should preserve any attached review schedule; owner authorization is required for editor reads and mutations.
- `setImage` accepts only `front` or `back`, updates one side, and resolves ownership from the card's deck.
- Null replacement is an empty full replacement; null elements, duplicate ids, and cross-deck ids have no explicit service-level contract and should not be silently assumed valid.

## Implementation steps

1. Add a rich-card replacement test with surrounding whitespace and optional image/alternatives values; assert trimmed required fields, trimmed nonblank optionals, and blank optional values becoming null in `CardView`.
2. Add a null-list replacement test: persist a card, call `replaceCards(deckId, ownerId, null)`, assert the deck editor contains no cards.
3. Add `setImage` happy-path coverage for both sides, asserting the untouched side remains unchanged and persisted editor output reflects each update.
4. Add invalid-side rejection coverage, asserting `IllegalArgumentException` and no card mutation.
5. Add direct non-owner editor-read or image-mutation authorization coverage; assert `AccessDeniedException` and unchanged persisted data.
6. Add review-state preservation/cascade coverage if the review repository is practical in this integration fixture: retain a rated card and assert its review row survives an edit; remove a rated card and assert its review row cascades away.
7. Treat null card elements and duplicate/foreign ids as unresolved contracts; only add rejection assertions after confirming the intended API behavior rather than encoding current accidental behavior.

## Related files

- Modify: `src/test/java/com/ksh/features/flashcards/service/CardServiceTest.java`
- Create/delete: none

## Success criteria

Card optional-field normalization, null replacement semantics, image side validation, and owner-only access are all asserted through persisted integration state.

## Risks and security

- Do not use a card id from another deck as a proxy for authorization; `CardService` intentionally treats unknown ids as new rows.
- Keep tests isolated with the existing per-test deck setup.
