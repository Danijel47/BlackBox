package com.blackbox.wow.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.LocalDate;

@Repository
public class TelegramDailyPromptRepository {

    private static final String PARAM_PROMPT_KEY = "promptKey";
    private static final String PARAM_DELIVERED_ON = "deliveredOn";

    private final JdbcClient jdbc;

    public TelegramDailyPromptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean claimDelivery(String promptKey, LocalDate deliveredOn) {
        int updatedRows = jdbc.sql("""
                        INSERT INTO telegram_daily_prompt_delivery (
                            prompt_key, delivered_on, updated_at
                        ) VALUES (:promptKey, :deliveredOn, CURRENT_TIMESTAMP)
                        ON CONFLICT (prompt_key) DO UPDATE SET
                            delivered_on = EXCLUDED.delivered_on,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE telegram_daily_prompt_delivery.delivered_on <> EXCLUDED.delivered_on
                        """)
                .param(PARAM_PROMPT_KEY, promptKey)
                .param(PARAM_DELIVERED_ON, deliveredOn, Types.DATE)
                .update();
        return updatedRows == 1;
    }

    public void releaseDelivery(String promptKey, LocalDate deliveredOn) {
        jdbc.sql("""
                        DELETE FROM telegram_daily_prompt_delivery
                        WHERE prompt_key = :promptKey AND delivered_on = :deliveredOn
                        """)
                .param(PARAM_PROMPT_KEY, promptKey)
                .param(PARAM_DELIVERED_ON, deliveredOn, Types.DATE)
                .update();
    }
}
