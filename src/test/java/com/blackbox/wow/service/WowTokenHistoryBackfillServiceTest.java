package com.blackbox.wow.service;

import com.blackbox.wow.client.WowTokenHistoryClient;
import com.blackbox.wow.client.WowTokenHistoryClient.HistoricalTokenPrice;
import com.blackbox.wow.properties.WowTokenHistoryProperties;
import com.blackbox.wow.repository.WowTokenPriceSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WowTokenHistoryBackfillServiceTest {

    @Mock private WowTokenHistoryClient historyClient;
    @Mock private WowTokenHistoryImportPersistence importPersistence;
    @Mock private WowTokenPriceSnapshotRepository repository;

    @Test
    void importsHistoryWhenTheDatabaseDoesNotHaveEnoughRows() {
        List<HistoricalTokenPrice> prices = List.of(new HistoricalTokenPrice(
                Instant.parse("2026-08-20T10:00:00Z"),
                3_600_000_000L
        ));
        when(historyClient.getEuRetailThirtyDayHistory()).thenReturn(prices);

        service(true).backfillIfNeeded();

        verify(importPersistence).insertIfMissing(prices);
    }

    @Test
    void skipsTheProviderWhenHistoryIsAlreadyPopulated() {
        when(repository.countByRegionAndCapturedAtGreaterThanEqual(any(), any())).thenReturn(500L);

        service(true).backfillIfNeeded();

        verify(historyClient, never()).getEuRetailThirtyDayHistory();
        verify(importPersistence, never()).insertIfMissing(any());
    }

    @Test
    void doesNothingWhenBackfillIsDisabled() {
        service(false).backfillIfNeeded();

        verify(repository, never()).countByRegionAndCapturedAtGreaterThanEqual(any(), any());
        verify(historyClient, never()).getEuRetailThirtyDayHistory();
    }

    private WowTokenHistoryBackfillService service(boolean backfillEnabled) {
        return new WowTokenHistoryBackfillService(
                historyClient,
                importPersistence,
                repository,
                new WowTokenHistoryProperties(true, backfillEnabled)
        );
    }
}
