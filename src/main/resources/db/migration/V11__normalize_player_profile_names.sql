UPDATE player_profile
SET profile_name = UPPER(LEFT(LOWER(profile_name), 1))
                   || SUBSTRING(LOWER(profile_name) FROM 2);

UPDATE tracked_character
SET realm = UPPER(LEFT(LOWER(realm), 1))
            || SUBSTRING(LOWER(realm) FROM 2),
    character_name = UPPER(LEFT(LOWER(character_name), 1))
                     || SUBSTRING(LOWER(character_name) FROM 2);
