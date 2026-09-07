package com.blackbox.wow.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class CombatKeyLevelRepository {

    private final JdbcClient jdbc;

    public CombatKeyLevelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Integer> findLevel() {
        return jdbc.sql("""
                SELECT minimum_keystone_level FROM wow_combat_key_level_setting WHERE setting_id = 1
                """).query(Integer.class).optional();
    }

    public void saveLevel(int level, long adminUserId) {
        int updated = jdbc.sql("""
                INSERT INTO wow_combat_key_level_setting (setting_id, minimum_keystone_level, updated_by)
                VALUES (1, :level, :adminUserId)
                ON CONFLICT (setting_id) DO UPDATE SET
                    minimum_keystone_level = EXCLUDED.minimum_keystone_level,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = CURRENT_TIMESTAMP
                """).param("level", level).param("adminUserId", adminUserId).update();
        if (updated != 1) {
            throw new IllegalStateException("The combat key level could not be saved.");
        }
    }
}
