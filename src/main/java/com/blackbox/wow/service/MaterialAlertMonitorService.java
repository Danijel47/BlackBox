package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.properties.BlizzardApiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MaterialAlertMonitorService {
    private final MaterialAlertService alerts;
    private final BlizzardAuctionService auctions;
    private final MaterialAlertCatalog catalog;
    private final BlackBoxBotNotifier notifier;
    private final BlizzardApiProperties properties;
    private final long adminId;

    public MaterialAlertMonitorService(MaterialAlertService alerts, BlizzardAuctionService auctions,
            MaterialAlertCatalog catalog, BlackBoxBotNotifier notifier, BlizzardApiProperties properties,
            @Value("${telegram.admin-user-id:0}") long adminId) {
        this.alerts = alerts; this.auctions = auctions; this.catalog = catalog;
        this.notifier = notifier; this.properties = properties; this.adminId = adminId;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void check() {
        if (adminId <= 0 || !"dynamic-eu".equals(properties.namespace())) return;
        try {
            if (!alerts.claimRefresh()) return;
            // The persisted claim commits before the network request, including on failure/restart.
            var snapshot = auctions.getRegionMaterialPrices(catalog.itemIds());
            alerts.storeSnapshot(snapshot);
            alerts.notifyAlerts(notifier, adminId);
        } catch (RuntimeException failure) {
            log.warn("Material alert refresh failed ({}); next scheduled attempt will retry.", failure.getClass().getSimpleName());
        }
    }
}
