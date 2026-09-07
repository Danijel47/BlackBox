package com.blackbox.wow.repository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CombatKeyLevelRepositoryTest {

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    private final CombatKeyLevelRepository repository = new CombatKeyLevelRepository(jdbc);

    @Test
    void readsTheSingleGlobalSetting() {
        JdbcClient.MappedQuerySpec<Integer> query = mock();
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(Integer.class)).thenReturn(query);
        when(query.optional()).thenReturn(Optional.empty(), Optional.of(18));

        assertThat(repository.findLevel()).isEmpty();
        assertThat(repository.findLevel()).contains(18);
    }

    @Test
    void atomicallyUpsertsTheLevelAndAdminWithBoundParameters() {
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.update()).thenReturn(1);

        repository.saveLevel(18, 999L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains("VALUES (1, :level, :adminUserId)", "ON CONFLICT (setting_id) DO UPDATE",
                "updated_at = CURRENT_TIMESTAMP");
        verify(statement).param("level", 18);
        verify(statement).param("adminUserId", 999L);
    }

    @Test
    void doesNotReportSuccessWhenNoRowWasSaved() {
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.update()).thenReturn(0);

        assertThatThrownBy(() -> repository.saveLevel(14, 999L)).isInstanceOf(IllegalStateException.class);
    }
}
