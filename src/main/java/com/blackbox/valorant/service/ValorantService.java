package com.blackbox.valorant.service;

import com.blackbox.valorant.client.HenrikDevClient;
import com.blackbox.valorant.entity.ValorantProfileEntity;
import com.blackbox.valorant.repository.ValorantProfileRepository;
import com.blackbox.valorant.properties.ValorantProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
public class ValorantService {
    private static final Set<String> REGIONS = Set.of("eu", "na", "ap", "kr", "br", "latam");
    private static final DateTimeFormatter UPDATED_AT = DateTimeFormatter.ofPattern("dd MMM HH:mm 'UTC'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);
    private final ValorantProfileRepository repository;
    private final HenrikDevClient client;
    private final ValorantProperties properties;

    public ValorantService(ValorantProfileRepository repository, HenrikDevClient client, ValorantProperties properties) {
        this.repository = repository;
        this.client = client;
        this.properties = properties;
    }

    public ValorantProfileEntity addProfile(String profileName, String riotId, String platform) {
        String label = profileName.strip();
        if (label.isBlank() || label.length() > 64 || label.contains("\n")) {
            throw new IllegalArgumentException("Profile name must contain 1–64 characters.");
        }
        int separator = riotId.lastIndexOf('#');
        if (separator <= 0 || separator == riotId.length() - 1 || riotId.indexOf('#') != separator) {
            throw new IllegalArgumentException("Use a Riot ID in Name#Tag format.");
        }
        String selectedPlatform = platform.toLowerCase(Locale.ROOT);
        if (!Set.of("pc", "console").contains(selectedPlatform)) {
            throw new IllegalArgumentException("Platform must be pc or console.");
        }
        if (repository.findByProfileNameIgnoreCase(label).isPresent()) {
            throw new IllegalArgumentException("That profile name is already registered.");
        }
        var account = client.account(riotId.substring(0, separator).strip(), riotId.substring(separator + 1).strip());
        if (!REGIONS.contains(account.region()) || !account.platforms().contains(selectedPlatform)) {
            throw new IllegalArgumentException("That account does not support the selected platform or region.");
        }
        try {
            return repository.saveAndFlush(new ValorantProfileEntity(label, account.puuid(), account.riotId(),
                    account.region(), selectedPlatform));
        } catch (DataIntegrityViolationException _) {
            throw new IllegalArgumentException("That profile name or Riot account is already registered.");
        }
    }

    @Transactional
    public void setActive(String profileName, boolean active) {
        var profile = repository.findByProfileNameIgnoreCase(profileName.strip())
                .orElseThrow(() -> new IllegalArgumentException("Valorant profile not found."));
        profile.setActive(active);
        repository.save(profile);
    }

    public List<String> profileMessages() {
        var profiles = repository.findAllByOrderByIdAsc();
        if (profiles.isEmpty()) {
            return List.of("No Valorant profiles. Add one with /val_profile_add <profile> <Name#Tag> [pc|console].");
        }
        List<String> messages = new ArrayList<>();
        messages.add("Valorant profiles — one Riot account per profile");
        for (var profile : profiles) {
            messages.add(profile.getProfileName() + " — " + profile.getRiotId() + " (" + profile.getRegion()
                    + ", " + profile.getPlatform() + ") — " + (profile.isActive() ? "active" : "disabled"));
        }
        return messages;
    }

    public List<String> headshotMessages() {
        return reportMessages(false);
    }

    public List<String> statisticsMessages() {
        return reportMessages(true);
    }

    private List<String> reportMessages(boolean includeCombat) {
        List<String> messages = new ArrayList<>();
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            messages.add("⚠ HENRIKDEV_API_KEY is not configured. Automatic Valorant refresh is disabled; "
                    + "set the key in .env and recreate the bot container.");
        }
        var profiles = repository.findByActiveTrueOrderByIdAsc();
        if (profiles.isEmpty()) {
            messages.add("No active Valorant profiles. Add one with /val_profile_add <profile> <Name#Tag> [pc|console].");
            return messages;
        }
        messages.add((includeCombat ? "Valorant statistics" : "Valorant HS%") + " — latest 20 competitive matches\n");
        for (var profile : profiles) {
            messages.add(profileReport(profile, includeCombat));
        }
        return messages;
    }

    private static String profileReport(ValorantProfileEntity profile, boolean includeCombat) {
        String prefix = profile.getProfileName() + " — ";
        var saved = profile.savedHeadshots();
        if (saved.isEmpty()) {
            return prefix + (profile.isHeadshotRefreshFailed()
                    ? "unavailable — ⚠ " + refreshWarning(profile) + " Collection will retry automatically."
                    : "awaiting first scheduled refresh");
        }
        var hits = saved.get();
        String percentage = hits.percentage().map(value -> value.toPlainString() + "%")
                .orElse(hits.matches() == 0 ? "no competitive matches available" : "unavailable (no recorded hits)");
        String summary = prefix + percentage + (includeCombat ? " HS" : "") + " · " + hits.matches() + "/20 matches"
                + " · " + hits.headHits() + "/" + hits.totalHits() + " head hits"
                + " · updated " + UPDATED_AT.format(profile.getHeadshotUpdatedAt())
                + (profile.isHeadshotRefreshFailed()
                ? " · ⚠ last refresh failed: " + refreshWarning(profile) + " Showing saved result." : "");
        if (includeCombat && hits.matches() > 0) {
            summary += hits.combatStats() == null
                    ? "\nCombat statistics unavailable; waiting for complete data from a scheduled refresh."
                    : combatMessage(hits);
        }
        return summary;
    }

    private static String combatMessage(HenrikDevClient.Headshots hits) {
        var stats = hits.combatStats();
        return "\nK/D/A totals: " + stats.kills() + " / " + stats.deaths() + " / " + stats.assists()
                + "\nAvg K/D/A per match: " + average(stats.kills(), hits.matches()) + " / "
                + average(stats.deaths(), hits.matches()) + " / " + average(stats.assists(), hits.matches())
                + " · K/D " + metric(stats.killDeathRatio())
                + "\nDamage dealt / received: " + stats.damageDealt() + " / " + stats.damageReceived()
                + "\nADR " + metric(stats.averageDamage()) + " · ACS " + metric(stats.averageCombatScore())
                + " · damage delta/round " + metric(stats.damageDelta()) + " · kills/round " + metric(stats.killsPerRound())
                + "\nResults: " + stats.wins() + "W / " + stats.losses() + "L / " + stats.draws() + "D"
                + " · win rate " + average(stats.wins() * 100L, hits.matches()) + "%"
                + " · " + stats.rounds() + " rounds";
    }

    private static String metric(Optional<BigDecimal> value) {
        return value.map(BigDecimal::toPlainString).orElse("n/a");
    }

    private static String average(long total, int matches) {
        return matches == 0 ? "n/a" : BigDecimal.valueOf(total)
                .divide(BigDecimal.valueOf(matches), 1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String refreshWarning(ValorantProfileEntity profile) {
        return profile.getHeadshotRefreshWarning() == null || profile.getHeadshotRefreshWarning().isBlank()
                ? "The last scheduled refresh failed." : profile.getHeadshotRefreshWarning();
    }
}
