ALTER TABLE flashcard_decks
    ADD COLUMN shared_class_ids JSON NULL AFTER class_id;
