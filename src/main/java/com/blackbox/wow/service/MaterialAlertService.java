package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardAuctionService.MaterialPriceSnapshot;
import com.blackbox.wow.entity.MaterialAlertSnapshotEntity;
import com.blackbox.wow.entity.MaterialGroupAlertEntity;
import com.blackbox.wow.repository.MaterialAlertSnapshotRepository;
import com.blackbox.wow.repository.MaterialGroupAlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class MaterialAlertService {
    public static final Duration INTERVAL = Duration.ofHours(3);
    private final MaterialAlertSnapshotRepository snapshots;
    private final MaterialGroupAlertRepository groups;
    private final MaterialAlertCatalog catalog;
    private final Clock clock;

    public MaterialAlertService(MaterialAlertSnapshotRepository snapshots, MaterialGroupAlertRepository groups,
                                MaterialAlertCatalog catalog, Clock clock) {
        this.snapshots = snapshots; this.groups = groups; this.catalog = catalog; this.clock = clock;
    }

    public String groups() {
        return "Midnight material groups (EU); item IDs distinguish quality variants:\n" +
                catalog.groups().entrySet().stream().map(e -> e.getKey() + ": " + e.getValue().stream()
                        .map(MaterialAlertCatalog.Material::label).collect(Collectors.joining(", ")))
                        .collect(Collectors.joining("\n\n"));
    }

    public String configure(String key, BigDecimal threshold) {
        key = catalog.canonicalKey(key); catalog.group(key);
        if (threshold == null || threshold.signum() <= 0 || threshold.compareTo(new BigDecimal("99999999.9999")) > 0
                || threshold.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException("Percentage must be positive, at most 99999999.9999, with up to 4 decimal places.");
        }
        var snapshot = snapshots.lockSnapshot();
        var group = groups.findById(key).orElseGet(MaterialGroupAlertEntity::new);
        boolean initialize = !group.isEnabled();
        group.setGroupKey(key); group.setThreshold(threshold); group.setEnabled(true);
        if (initialize) resetBaseline(group, snapshot);
        groups.save(group);
        return key + " alert threshold: +" + threshold.stripTrailingZeros().toPlainString() + "%. " + state(group, snapshot);
    }

    public String reset(String key) {
        key = catalog.canonicalKey(key); catalog.group(key);
        var snapshot = snapshots.lockSnapshot();
        var group = groups.findById(key).filter(MaterialGroupAlertEntity::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("Enable this group first with /matalert <group> <percent>."));
        resetBaseline(group, snapshot); groups.save(group);
        return group.getGroupKey() + ": " + state(group, snapshot);
    }

    public String disable(String key) {
        key = catalog.canonicalKey(key); catalog.group(key); snapshots.lockSnapshot();
        groups.findById(key).ifPresent(group -> { group.setEnabled(false); group.setPending(false); groups.save(group); });
        return key + " alerts disabled.";
    }

    public String status() {
        var snapshot = snapshots.lockSnapshot();
        var all = groups.findAll().stream().sorted(Comparator.comparing(MaterialGroupAlertEntity::getGroupKey)).toList();
        String header = "Material alerts (EU)\nLatest fetch: " + timestamp(snapshot.getFetchedAt())
                + "\nBlizzard source: " + timestamp(snapshot.getSourceUpdatedAt())
                + "\nNext refresh due: " + timestamp(snapshot.getNextAttemptAt());
        if (all.isEmpty()) return header + "\nNo alerts configured. Use /matalert ores 15.";
        return header + "\n\n" + all.stream().map(g -> g.getGroupKey() + " | +" + percent(g.getThreshold())
                + "% | " + state(g, snapshot)).collect(Collectors.joining("\n"));
    }

    public boolean claimRefresh() {
        var snapshot = snapshots.lockSnapshot();
        if (groups.findAll().stream().noneMatch(MaterialGroupAlertEntity::isEnabled)) return false;
        Instant now = clock.instant();
        if (snapshot.getNextAttemptAt() != null && now.isBefore(snapshot.getNextAttemptAt())) return false;
        snapshot.setNextAttemptAt(now.plus(INTERVAL)); snapshots.save(snapshot); return true;
    }

    public void storeSnapshot(MaterialPriceSnapshot prices) {
        var snapshot = snapshots.lockSnapshot();
        if (prices.fetchedAt() == null || prices.prices() == null || prices.prices().values().stream().anyMatch(v -> v == null || v <= 0)) {
            throw new IllegalArgumentException("Invalid material price snapshot.");
        }
        snapshot.getPrices().clear(); snapshot.getPrices().putAll(prices.prices());
        snapshot.setFetchedAt(prices.fetchedAt()); snapshot.setSourceUpdatedAt(prices.sourceUpdatedAt());
        snapshots.save(snapshot);
        for (var group : groups.findAll()) {
            if (!group.isEnabled() || !catalog.groups().containsKey(group.getGroupKey())) continue;
            if (!membershipMatches(group)) group.setPending(true);
            if (group.isPending() && complete(group.getGroupKey(), snapshot)) resetBaseline(group, snapshot);
            groups.save(group);
        }
    }

    public void notifyAlerts(BlackBoxBotNotifier notifier, long adminId) {
        if (adminId <= 0) return;
        var snapshot = snapshots.lockSnapshot();
        for (var group : groups.findAll()) {
            if (!group.isEnabled() || group.isPending() || group.isFired()
                    || !catalog.groups().containsKey(group.getGroupKey()) || !membershipMatches(group)
                    || !complete(group.getGroupKey(), snapshot)) continue;
            BigDecimal change = movement(group.getBaseline(), snapshot.getPrices());
            if (change.compareTo(group.getThreshold()) < 0) continue;
            String message = group.getGroupKey() + " prices rose +" + percent(change) + "% (EU)\nThreshold: +"
                    + percent(group.getThreshold()) + "%\nBaseline fetch: " + timestamp(group.getBaselineAt())
                    + "\nBaseline source: " + timestamp(group.getBaselineSourceAt())
                    + "\nCurrent fetch: " + timestamp(snapshot.getFetchedAt())
                    + "\nCurrent source: " + timestamp(snapshot.getSourceUpdatedAt()) + "\n\n"
                    + catalog.group(group.getGroupKey()).stream().map(item -> item.label() + ": "
                    + gold(group.getBaseline().get(item.id())) + " → " + gold(snapshot.getPrices().get(item.id()))
                    + " (" + signed(movement(Map.of(item.id(), group.getBaseline().get(item.id())), snapshot.getPrices())) + "%)")
                    .collect(Collectors.joining("\n"))
                    + "\n\nTypical listed prices; check the AH before selling.\nRearm: /matalert reset " + group.getGroupKey();
            boolean delivered = true;
            for (String part : pages(message)) {
                if (!notifier.send(adminId, part)) { delivered = false; break; }
            }
            if (delivered) { group.setFired(true); group.setNotifiedAt(clock.instant()); groups.save(group); }
        }
    }

    private void resetBaseline(MaterialGroupAlertEntity group, MaterialAlertSnapshotEntity snapshot) {
        group.setFired(false); group.setNotifiedAt(null); group.setPending(true);
        group.getBaseline().clear(); group.setBaselineAt(null); group.setBaselineSourceAt(null);
        if (!complete(group.getGroupKey(), snapshot)) return;
        catalog.group(group.getGroupKey()).forEach(item -> group.getBaseline().put(item.id(), snapshot.getPrices().get(item.id())));
        group.setBaselineAt(snapshot.getFetchedAt()); group.setBaselineSourceAt(snapshot.getSourceUpdatedAt()); group.setPending(false);
    }

    private boolean complete(String key, MaterialAlertSnapshotEntity snapshot) {
        return fresh(snapshot.getFetchedAt()) && (snapshot.getSourceUpdatedAt() == null || fresh(snapshot.getSourceUpdatedAt()))
                && catalog.group(key).stream().allMatch(item -> snapshot.getPrices().getOrDefault(item.id(), 0L) > 0);
    }
    private boolean fresh(Instant time) {
        return time != null && !time.isBefore(clock.instant().minus(INTERVAL)) && !time.isAfter(clock.instant().plusSeconds(60));
    }
    private boolean membershipMatches(MaterialGroupAlertEntity group) {
        return group.getBaseline().keySet().equals(catalog.group(group.getGroupKey()).stream()
                .map(MaterialAlertCatalog.Material::id).collect(Collectors.toSet()));
    }
    private String state(MaterialGroupAlertEntity group, MaterialAlertSnapshotEntity snapshot) {
        if (!group.isEnabled()) return "disabled";
        if (group.isPending()) return "waiting for a complete fresh scheduled snapshot";
        if (!catalog.groups().containsKey(group.getGroupKey()) || !membershipMatches(group)) return "catalog changed; reset required";
        String state = group.isFired() ? "alert sent; reset to rearm" : "armed";
        if (!complete(group.getGroupKey(), snapshot)) return state + "; prices missing/stale";
        return state + "; change " + signed(movement(group.getBaseline(), snapshot.getPrices()))
                + "%; baseline " + timestamp(group.getBaselineAt());
    }
    static BigDecimal movement(Map<Long, Long> baseline, Map<Long, Long> current) {
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : baseline.entrySet()) {
            total = total.add(BigDecimal.valueOf(current.get(entry.getKey())).subtract(BigDecimal.valueOf(entry.getValue()))
                    .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(entry.getValue()), 16, RoundingMode.HALF_UP));
        }
        return total.divide(BigDecimal.valueOf(baseline.size()), 12, RoundingMode.HALF_UP);
    }
    public static List<String> pages(String message) {
        var result = new ArrayList<String>();
        while (message.length() > 3900) {
            int cut = message.lastIndexOf('\n', 3900); if (cut <= 0) cut = 3900;
            result.add(message.substring(0, cut)); message = message.substring(cut).stripLeading();
        }
        if (!message.isEmpty()) result.add(message); return result;
    }
    private static String timestamp(Instant time) { return time == null ? "unknown / not yet available" : time.toString(); }
    private static String percent(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private static String signed(BigDecimal value) { return (value.signum() >= 0 ? "+" : "") + percent(value); }
    private static String gold(long copper) { return BigDecimal.valueOf(copper, 4).stripTrailingZeros().toPlainString() + "g"; }
}
