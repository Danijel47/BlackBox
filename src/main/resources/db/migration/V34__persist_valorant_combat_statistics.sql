ALTER TABLE valorant_profile
    ADD COLUMN combat_kills BIGINT CHECK (combat_kills >= 0),
    ADD COLUMN combat_deaths BIGINT CHECK (combat_deaths >= 0),
    ADD COLUMN combat_assists BIGINT CHECK (combat_assists >= 0),
    ADD COLUMN combat_damage_dealt BIGINT CHECK (combat_damage_dealt >= 0),
    ADD COLUMN combat_damage_received BIGINT CHECK (combat_damage_received >= 0),
    ADD COLUMN combat_score BIGINT CHECK (combat_score >= 0),
    ADD COLUMN combat_rounds BIGINT CHECK (combat_rounds >= 0),
    ADD COLUMN combat_wins INTEGER CHECK (combat_wins BETWEEN 0 AND 20),
    ADD COLUMN combat_losses INTEGER CHECK (combat_losses BETWEEN 0 AND 20),
    ADD COLUMN combat_draws INTEGER CHECK (combat_draws BETWEEN 0 AND 20);
