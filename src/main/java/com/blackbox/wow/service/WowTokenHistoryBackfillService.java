package com.blackbox.wow.service;

import com.blackbox.wow.client.WowTokenHistoryClient;
import com.blackbox.wow.properties.WowTokenHistoryProperties;
import com.blackbox.wow.repository.WowTokenPriceSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@Slf4j
public class WowTokenHistoryBackfillService {

    private static final String TOKEN_REGION = "EU";
    private static final Duration BACKFILL_WINDOW = Duration.ofDays(30);
    private static final long SUFFICIENT_HISTORY_ROWS = 500;

    private final WowTokenHistoryClient historyClient;
    private final WowTokenHistoryImportPersistence importPersistence;
    private final WowTokenPriceSnapshotRepository repository;
    private final WowTokenHistoryProperties properties;

    public WowTokenHistoryBackfillService(
            WowTokenHistoryClient historyClient,
            WowTokenHistoryImportPersistence importPersistence,
            WowTokenPriceSnapshotRepository repository,
            WowTokenHistoryProperties properties
    ) {
        this.historyClient = historyClient;
        this.importPersistence = importPersistence;
        this.repository = repository;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(
            cron = "${wow.token-history.backfill-cron:0 15 3 * * *}",
            zone = "${wow.token-history.zone:UTC}"
    )
    public void backfillIfNeeded() {
        if (!properties.enabled() || !properties.backfillEnabled()) {
            return;
        }
        try {
            long existingRows = repository.countByRegionAndCapturedAtGreaterThanEqual(
                    TOKEN_REGION,
                    Instant.now().minus(BACKFILL_WINDOW)
            );
            if (existingRows >= SUFFICIENT_HISTORY_ROWS) {
                return;
            }
            int importedRows = importPersistence.insertIfMissing(historyClient.getEuRetailThirtyDayHistory());
            log.info("Imported {} historical EU WoW Token price rows", importedRows);
        } catch (RuntimeException e) {
            log.warn("WoW Token history backfill failed ({})", e.getClass().getSimpleName());
        }
    }
}
