package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.blizzard.BlizzardAuctionService.MaterialPriceSnapshot;
import com.blackbox.wow.properties.BlizzardApiProperties;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.mockito.Mockito.*;

class MaterialAlertMonitorServiceTest {
    private final MaterialAlertService service = mock(MaterialAlertService.class);
    private final BlizzardAuctionService auctions = mock(BlizzardAuctionService.class);
    private final BlackBoxBotNotifier notifier = mock(BlackBoxBotNotifier.class);
    private final MaterialAlertCatalog catalog = new MaterialAlertCatalog();
    private MaterialAlertMonitorService monitor(long admin, String namespace) {
        return new MaterialAlertMonitorService(service, auctions, catalog, notifier,
                new BlizzardApiProperties(null, null, null, null, namespace, "en_GB", null), admin);
    }
    @Test void oneFetchServesAllGroupsAndNotifiesPrivately() {
        when(service.claimRefresh()).thenReturn(true);
        var snapshot = new MaterialPriceSnapshot(Map.of(1L, 100L), Instant.now(), Instant.now());
        when(auctions.getRegionMaterialPrices(catalog.itemIds())).thenReturn(snapshot);
        monitor(999, "dynamic-eu").check();
        verify(auctions, times(1)).getRegionMaterialPrices(catalog.itemIds());
        verify(service).storeSnapshot(snapshot);
        verify(service).notifyAlerts(notifier, 999);
    }
    @Test void noFetchWhenNotDueOrAdminMissingOrWrongRegion() {
        monitor(999, "dynamic-eu").check();
        monitor(0, "dynamic-eu").check();
        monitor(999, "dynamic-us").check();
        verify(service, times(1)).claimRefresh();
        verifyNoInteractions(auctions, notifier);
    }
    @Test void failureDoesNotReplaceSnapshotOrImmediatelyRetry() {
        when(service.claimRefresh()).thenReturn(true);
        when(auctions.getRegionMaterialPrices(catalog.itemIds())).thenThrow(new IllegalStateException("unavailable"));
        monitor(999, "dynamic-eu").check();
        verify(auctions, times(1)).getRegionMaterialPrices(catalog.itemIds());
        verify(service, never()).storeSnapshot(any());
        verify(service, never()).notifyAlerts(any(), anyLong());
    }
}
