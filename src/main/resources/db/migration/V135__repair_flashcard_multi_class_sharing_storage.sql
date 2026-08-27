-- Repair databases that briefly received the join-table implementation of V134.
-- The final model keeps multi-class sharing inside flashcard_decks as JSON so no
-- additional domain table is required.

SET @shared_class_ids_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'flashcard_decks'
      AND column_name = 'shared_class_ids'
);

SET @add_shared_class_ids_sql = IF(
    @shared_class_ids_column_exists = 0,
    'ALTER TABLE flashcard_decks ADD COLUMN shared_class_ids JSON NULL AFTER class_id',
    'SELECT 1'
);

PREPARE add_shared_class_ids_statement FROM @add_shared_class_ids_sql;
EXECUTE add_shared_class_ids_statement;
DEALLOCATE PREPARE add_shared_class_ids_statement;

SET @legacy_share_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'flashcard_deck_class_shares'
);

SET @migrate_legacy_shares_sql = IF(
    @legacy_share_table_exists > 0,
    'UPDATE flashcard_decks d JOIN (SELECT deck_id, JSON_ARRAYAGG(class_id) AS class_ids FROM flashcard_deck_class_shares GROUP BY deck_id) s ON s.deck_id = d.id SET d.shared_class_ids = s.class_ids',
    'SELECT 1'
);

PREPARE migrate_legacy_shares_statement FROM @migrate_legacy_shares_sql;
EXECUTE migrate_legacy_shares_statement;
DEALLOCATE PREPARE migrate_legacy_shares_statement;

DROP TABLE IF EXISTS flashcard_deck_class_shares;
