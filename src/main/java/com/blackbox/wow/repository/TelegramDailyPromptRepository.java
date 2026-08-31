package com.blackbox.wow.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.LocalDate;

@Repository
public class TelegramDailyPromptRepository {

    private static final String PARAM_PROMPT_KEY = "promptKey";
    private static final String PARAM_DELIVERED_ON = "deliveredOn";
    private static final String PARAM_ACTIVITY_DATE = "activityDate";
    private static final String PARAM_TRIGGER_MESSAGE_NUMBER = "triggerMessageNumber";

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

    @Transactional
    public boolean recordMessageAndClaimDelivery(
            String promptKey,
            LocalDate activityDate,
            int triggerMessageNumber
    ) {
        jdbc.sql("""
                        INSERT INTO telegram_daily_prompt_activity (
                            prompt_key, activity_date, message_count, trigger_message_number, delivered, updated_at
                        ) VALUES (:promptKey, :activityDate, 1, :triggerMessageNumber, FALSE, CURRENT_TIMESTAMP)
                        ON CONFLICT (prompt_key) DO UPDATE SET
                            activity_date = EXCLUDED.activity_date,
                            message_count = CASE
                                WHEN telegram_daily_prompt_activity.activity_date = EXCLUDED.activity_date
                                    THEN telegram_daily_prompt_activity.message_count + 1
                                ELSE 1
                            END,
                            trigger_message_number = CASE
                                WHEN telegram_daily_prompt_activity.activity_date = EXCLUDED.activity_date
                                    THEN telegram_daily_prompt_activity.trigger_message_number
                                ELSE EXCLUDED.trigger_message_number
                            END,
                            delivered = CASE
                                WHEN telegram_daily_prompt_activity.activity_date = EXCLUDED.activity_date
                                    THEN telegram_daily_prompt_activity.delivered
                                ELSE FALSE
                            END,
                            updated_at = CURRENT_TIMESTAMP
                        """)
                .param(PARAM_PROMPT_KEY, promptKey)
                .param(PARAM_ACTIVITY_DATE, activityDate, Types.DATE)
                .param(PARAM_TRIGGER_MESSAGE_NUMBER, triggerMessageNumber)
                .update();

        return jdbc.sql("""
                        UPDATE telegram_daily_prompt_activity
                        SET delivered = TRUE, updated_at = CURRENT_TIMESTAMP
                        WHERE prompt_key = :promptKey
                            AND activity_date = :activityDate
                            AND delivered = FALSE
                            AND message_count >= trigger_message_number
                        """)
                .param(PARAM_PROMPT_KEY, promptKey)
                .param(PARAM_ACTIVITY_DATE, activityDate, Types.DATE)
                .update() == 1;
    }

    public void releasePromptDelivery(String promptKey, LocalDate activityDate) {
        jdbc.sql("""
                        UPDATE telegram_daily_prompt_activity
                        SET delivered = FALSE, updated_at = CURRENT_TIMESTAMP
                        WHERE prompt_key = :promptKey AND activity_date = :activityDate
                        """)
                .param(PARAM_PROMPT_KEY, promptKey)
                .param(PARAM_ACTIVITY_DATE, activityDate, Types.DATE)
                .update();
    }
}
