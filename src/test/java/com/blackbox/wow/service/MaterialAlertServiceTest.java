package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardAuctionService.MaterialPriceSnapshot;
import com.blackbox.wow.entity.MaterialAlertSnapshotEntity;
import com.blackbox.wow.entity.MaterialGroupAlertEntity;
import com.blackbox.wow.repository.MaterialAlertSnapshotRepository;
import com.blackbox.wow.repository.MaterialGroupAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaterialAlertServiceTest {
    private final Instant now = Instant.parse("2026-11-19T12:00:00Z");
    private final MaterialAlertSnapshotRepository snapshots = mock(MaterialAlertSnapshotRepository.class);
    private final MaterialGroupAlertRepository groups = mock(MaterialGroupAlertRepository.class);
    private final MaterialAlertSnapshotEntity snapshot = new MaterialAlertSnapshotEntity();
    private final Map<String, MaterialGroupAlertEntity> stored = new HashMap<>();
    private final MaterialAlertCatalog catalog = new MaterialAlertCatalog();
    private final BlackBoxBotNotifier notifier = mock(BlackBoxBotNotifier.class);
    private MaterialAlertService service;

    @BeforeEach void setup() {
        when(snapshots.lockSnapshot()).thenReturn(snapshot);
        when(groups.findById(anyString())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
        when(groups.save(any())).thenAnswer(i -> { MaterialGroupAlertEntity g = i.getArgument(0); stored.put(g.getGroupKey(), g); return g; });
        when(groups.findAll()).thenAnswer(i -> new ArrayList<>(stored.values()));
        service = new MaterialAlertService(snapshots, groups, catalog, Clock.fixed(now, ZoneOffset.UTC));
    }

    private Map<Long, Long> prices(long value) {
        Map<Long, Long> result = new HashMap<>();
        catalog.itemIds().forEach(id -> result.put(id, value));
        return result;
    }
    private void capture(long value) { service.storeSnapshot(new MaterialPriceSnapshot(prices(value), now, now)); }

    @Test void commandsWaitForSnapshotAndChangingThresholdDoesNotResetBaseline() {
        assertThat(service.configure("ores", new BigDecimal("15"))).contains("waiting");
        assertThat(stored.get("ores").isPending()).isTrue();
        capture(100);
        assertThat(stored.get("ores").isPending()).isFalse();
        service.configure("ores", new BigDecimal("20"));
        assertThat(stored.get("ores").getBaseline().values()).containsOnly(100L);
        assertThat(stored.get("ores").getThreshold()).isEqualByComparingTo("20");
    }

    @Test void averagesPercentChangesAndSendsOnlyOnceUntilReset() {
        capture(100);
        service.configure("ores", new BigDecimal("15"));
        capture(115);
        when(notifier.send(eq(999L), anyString())).thenReturn(true);
        service.notifyAlerts(notifier, 999L);
        service.notifyAlerts(notifier, 999L);
        verify(notifier, times(1)).send(eq(999L), contains("+15.00%"));
        assertThat(stored.get("ores").isFired()).isTrue();
        service.configure("ores", new BigDecimal("10"));
        assertThat(stored.get("ores").isFired()).isTrue();
        service.reset("ores");
        assertThat(stored.get("ores").isFired()).isFalse();
        assertThat(stored.get("ores").getBaseline().values()).containsOnly(115L);
    }

    @Test void failedDeliveryRemainsArmedAndUsesPrivateAdminId() {
        capture(100); service.configure("herbs", new BigDecimal("15")); capture(120);
        service.notifyAlerts(notifier, 999L);
        assertThat(stored.get("herbs").isFired()).isFalse();
        verify(notifier).send(eq(999L), contains("herbs"));
    }

    @Test void missingOrStalePricesAndPendingResetSuppressAlerts() {
        capture(100); service.configure("ores", BigDecimal.ONE);
        snapshot.setFetchedAt(now.minus(Duration.ofHours(4)));
        assertThat(service.reset("ores")).contains("waiting");
        service.notifyAlerts(notifier, 999L);
        var missing = prices(200); missing.remove(catalog.group("ores").getFirst().id());
        service.storeSnapshot(new MaterialPriceSnapshot(missing, now, now));
        service.notifyAlerts(notifier, 999L);
        assertThat(stored.get("ores").isPending()).isTrue();
        verifyNoInteractions(notifier);
    }

    @Test void staleSourceAndFutureTimestampsAreNotFresh() {
        for (Instant time : List.of(now.minus(Duration.ofHours(4)), now.plus(Duration.ofHours(1)))) {
            service.storeSnapshot(new MaterialPriceSnapshot(prices(100), time, now));
            assertThat(service.configure("ores", BigDecimal.ONE)).contains("waiting");
        }
    }

    @Test void disableAndReenableCapturesANewBaseline() {
        capture(100); service.configure("ores", BigDecimal.TEN); service.disable("ores");
        capture(200); service.configure("ores", BigDecimal.TEN);
        assertThat(stored.get("ores").getBaseline().values()).containsOnly(200L);
        assertThat(stored.get("ores").isEnabled()).isTrue();
    }

    @Test void catalogChangeRequiresANewBaseline() {
        capture(100); service.configure("ores", BigDecimal.ONE);
        stored.get("ores").getBaseline().remove(catalog.group("ores").getFirst().id());
        capture(200); service.notifyAlerts(notifier, 999L);
        verifyNoInteractions(notifier);
        assertThat(stored.get("ores").getBaseline().values()).containsOnly(200L);
    }

    @Test void refreshClaimSurvivesServiceRestartAndNoGroupsMeansNoFetch() {
        assertThat(service.claimRefresh()).isFalse();
        service.configure("ores", BigDecimal.TEN);
        assertThat(service.claimRefresh()).isTrue();
        var restarted = new MaterialAlertService(snapshots, groups, catalog, Clock.fixed(now, ZoneOffset.UTC));
        assertThat(restarted.claimRefresh()).isFalse();
        assertThat(snapshot.getNextAttemptAt()).isEqualTo(now.plus(Duration.ofHours(3)));
    }

    @Test void validatesGroupAndThresholdWithoutChangingSettings() {
        assertThatThrownBy(() -> service.configure("invalid", BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
        for (var value : List.of(BigDecimal.ZERO, BigDecimal.ONE.negate(), new BigDecimal("1E100"))) {
            assertThatThrownBy(() -> service.configure("ores", value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(stored).isEmpty();
    }

    @Test void averageGivesEqualWeightRegardlessOfAbsolutePrice() {
        assertThat(MaterialAlertService.movement(Map.of(1L, 100L, 2L, 10000L), Map.of(1L, 110L, 2L, 12000L)))
                .isEqualByComparingTo("15");
    }
}
