package com.blackbox.wow.repository;

import com.blackbox.wow.repository.ProspectingSampleRepository.SavedSample;
import com.blackbox.wow.service.ProspectingBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProspectingSampleRepositoryTest {

    private static final long USER_ID = 999L;
    private static final long ORE_ID = 237359L;
    private static final Instant RECORDED_AT = Instant.parse("2026-09-06T10:30:00Z");
    private static final String ARGUMENTS = "237359 1000 100:20 101:30";
    private static final String USER_PARAMETER = "userId";
    private static final String ORE_PARAMETER = "oreId";

    @Mock private JdbcClient jdbc;
    @Mock(answer = RETURNS_SELF) private JdbcClient.StatementSpec statement;
    @Mock private JdbcClient.MappedQuerySpec<SavedSample> query;

    @Test
    void savedArgumentsAreDeterministicAndRoundTripWithoutLosingOutputQualities() {
        ProspectingBatch batch = ProspectingBatch.parse("COPPER1 1000 101:30 100:20");

        String normalized = ProspectingSampleRepository.arguments(batch);

        assertThat(normalized).isEqualTo(ARGUMENTS);
        assertThat(ProspectingBatch.parse(normalized)).isEqualTo(batch);
    }

    @Test
    void upsertIsScopedToOwnerAndExactOreAndBindsValuesSeparately() {
        when(jdbc.sql(anyString())).thenReturn(statement);
        ProspectingBatch batch = ProspectingBatch.parse(ARGUMENTS);

        new ProspectingSampleRepository(jdbc).save(USER_ID, batch, RECORDED_AT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains("ON CONFLICT (telegram_user_id, ore_id) DO UPDATE",
                "VALUES (:userId, :oreId, :arguments, :recordedAt)")
                .doesNotContain(ARGUMENTS);
        verify(statement).param(USER_PARAMETER, USER_ID);
        verify(statement).param(ORE_PARAMETER, ORE_ID);
        verify(statement).param("arguments", ARGUMENTS);
        verify(statement).param("recordedAt", RECORDED_AT.atOffset(ZoneOffset.UTC));
        verify(statement).update();
    }

    @Test
    void selectedSampleReadRequiresBothOwnerAndOre() {
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(org.mockito.ArgumentMatchers.<RowMapper<SavedSample>>any())).thenReturn(query);
        SavedSample sample = new SavedSample(ProspectingBatch.parse(ARGUMENTS), RECORDED_AT);
        when(query.optional()).thenReturn(Optional.of(sample));

        assertThat(new ProspectingSampleRepository(jdbc).find(USER_ID, ORE_ID)).contains(sample);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains("WHERE telegram_user_id = :userId", "AND ore_id = :oreId");
        verify(statement).param(USER_PARAMETER, USER_ID);
        verify(statement).param(ORE_PARAMETER, ORE_ID);
    }

    @Test
    void allSampleReadIsOwnerScopedAndMapsTheRecordedTimestamp() throws Exception {
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(org.mockito.ArgumentMatchers.<RowMapper<SavedSample>>any())).thenReturn(query);
        SavedSample sample = new SavedSample(ProspectingBatch.parse(ARGUMENTS), RECORDED_AT);
        when(query.list()).thenReturn(List.of(sample));

        assertThat(new ProspectingSampleRepository(jdbc).findAll(USER_ID)).containsExactly(sample);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).sql(sql.capture());
        assertThat(sql.getValue()).contains("WHERE telegram_user_id = :userId", "ORDER BY ore_id");
        verify(statement).param(USER_PARAMETER, USER_ID);
        ArgumentCaptor<RowMapper<SavedSample>> mapper = ArgumentCaptor.captor();
        verify(statement).query(mapper.capture());
        ResultSet row = mock(ResultSet.class);
        when(row.getString("batch_arguments")).thenReturn(ARGUMENTS);
        when(row.getObject("recorded_at", OffsetDateTime.class))
                .thenReturn(RECORDED_AT.atOffset(ZoneOffset.ofHours(2)));
        assertThat(mapper.getValue().mapRow(row, 0)).isEqualTo(sample);
    }
}
