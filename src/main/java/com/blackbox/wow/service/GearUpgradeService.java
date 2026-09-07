package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardApiClient;
import com.blackbox.wow.blizzard.BlizzardCache;
import com.blackbox.wow.blizzard.BlizzardEquipmentService;
import com.blackbox.wow.service.GearUpgradeAdvisor.Advice;
import com.blackbox.wow.service.GearUpgradeAdvisor.Status;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.blackbox.wow.service.GearUpgradeAdvisor.safeText;

@Service
public class GearUpgradeService {

    public static final String COMMAND = "/gearupg";
    public static final String ACTION = "gearupg";
    public static final String LABEL = "Gear Upg";
    private static final int MAX_MESSAGE_LENGTH = 3500;
    private static final int SUMMARY_ITEMS = 3;
    private static final String CREST_STATUS = "Crest balance unavailable; affordability unknown.";
    private static final String UNAVAILABLE =
            "❔ Unavailable — equipment could not be checked. Try again after logging out of WoW.";
    private static final String COST_NOTE =
            "Costs are standard estimates before same-slot / Warband discounts, plus gold. "
                    + "Discount eligibility is unknown; check the upgrade vendor.";
    private static final String GENERAL_ORDER =
            "Guide-based priority, not a simulation. Compare trinkets and special effects for your spec; "
                    + "claim your available Vault reward before spending. Each crest type is a separate budget.";
    private static final String LEVEL_PREFIX = "ilvl ";
    private static final String SEPARATOR = " — ";
    private static final String ID_FIELD = "id";
    private static final String NAME_FIELD = "name";

    private final BlizzardEquipmentService equipment;
    private final BlizzardApiClient api;
    private final BlizzardCache<Specialization> specializationCache = new BlizzardCache<>(256, value -> 1);

    public GearUpgradeService(BlizzardEquipmentService equipment, BlizzardApiClient api) {
        this.equipment = equipment;
        this.api = api;
    }

    public String summary(TrackedPlayer player) {
        try {
            Report report = load(player);
            StringBuilder text = new StringBuilder(header(player)).append('\n');
            List<Advice> upgrades = report.advice().stream().filter(row -> row.status() == Status.UPGRADE).toList();
            for (Advice row : upgrades.stream().limit(SUMMARY_ITEMS).toList()) {
                text.append(row.priority().label()).append(": ").append(row.slotLabel()).append(' ')
                        .append(row.itemLevel()).append(" → ").append(row.step().nextLevel())
                        .append(" (~").append(GearUpgradeRules.CRESTS_PER_RANK).append(' ')
                        .append(row.step().crest()).append(")\n");
            }
            if (upgrades.isEmpty()) {
                text.append("No verified standard upgrades found.\n");
            } else if (upgrades.size() > SUMMARY_ITEMS) {
                text.append('+').append(upgrades.size() - SUMMARY_ITEMS).append(" more in Details.\n");
            }
            appendStatusCounts(text, report);
            if (!report.missingSlots().isEmpty()) {
                text.append("⚠️ Partial equipment; see Details.\n");
            }
            return text.append(CREST_STATUS).append("\nGuide-based order; costs before discounts + gold.").toString();
        } catch (RestClientException | IllegalStateException | IllegalArgumentException _) {
            return header(player) + "\n" + UNAVAILABLE;
        }
    }

    public List<String> details(TrackedPlayer player) {
        try {
            Report report = load(player);
            String intro = detailHeader(player, report);
            List<String> pages = new ArrayList<>();
            StringBuilder page = new StringBuilder(intro);
            int number = 0;
            for (Advice row : report.advice()) {
                String block = detailRow(row, row.status() == Status.UPGRADE ? ++number : 0);
                if (page.length() + block.length() > MAX_MESSAGE_LENGTH) {
                    pages.add(page.toString().strip());
                    page = new StringBuilder(intro);
                }
                page.append(block);
            }
            pages.add(page.toString().strip());
            return List.copyOf(pages);
        } catch (RestClientException | IllegalStateException | IllegalArgumentException _) {
            return List.of(header(player) + "\n" + UNAVAILABLE);
        }
    }

    private Report load(TrackedPlayer player) {
        var snapshot = equipment.snapshot(player);
        Specialization specialization = specialization(player);
        return new Report(GearUpgradeAdvisor.advise(snapshot, specialization.id()),
                GearUpgradeAdvisor.missingSlots(snapshot), specialization, snapshot.sourceUpdatedAt());
    }

    private Specialization specialization(TrackedPlayer player) {
        String region = player.region().toLowerCase(Locale.ROOT);
        String realm = player.realm().toLowerCase(Locale.ROOT).replace(' ', '-');
        String name = player.name().toLowerCase(Locale.ROOT);
        try {
            return specializationCache.getOrCompute(region + ":" + realm + ":" + name, Duration.ofMinutes(5), () -> {
                var profile = api.get("/profile/wow/character/{realm}/{name}",
                        Map.of("realm", realm, "name", name), api.profileQuery());
                var spec = profile.path("active_spec");
                if (!spec.path(ID_FIELD).isIntegralNumber() || !spec.path(ID_FIELD).canConvertToInt()
                        || spec.path(ID_FIELD).asInt() <= 0) {
                    throw new IllegalStateException("Specialization unavailable.");
                }
                return new Specialization(spec.path(ID_FIELD).asInt(),
                        safeText(spec.path(NAME_FIELD).asText()) + " "
                                + safeText(profile.path("character_class").path(NAME_FIELD).asText()));
            });
        } catch (RestClientException | IllegalStateException | IllegalArgumentException _) {
            return new Specialization(0, "Unavailable; general slot priorities used");
        }
    }

    private static String header(TrackedPlayer player) {
        return LABEL + SEPARATOR + safeText(player.profileName()) + " (" + safeText(player.name())
                + "-" + safeText(player.realm()) + ") · " + GearUpgradeRules.SEASON;
    }

    private static String detailHeader(TrackedPlayer player, Report report) {
        StringBuilder text = new StringBuilder(header(player)).append("\nSpec: ")
                .append(report.specialization().label()).append("\nGear updated: ")
                .append(report.sourceUpdatedAt() == null ? "Unknown" : report.sourceUpdatedAt()).append('\n')
                .append(CREST_STATUS).append('\n').append(COST_NOTE).append('\n').append(GENERAL_ORDER).append('\n');
        if (!report.missingSlots().isEmpty()) {
            text.append("⚠️ Partial equipment — missing: ").append(String.join(", ", report.missingSlots())).append('\n');
        }
        return text.append("Rules: ").append(GearUpgradeRules.GUIDE_URL).append("\n\n").toString();
    }

    private static String detailRow(Advice row, int number) {
        String item = row.slotLabel() + SEPARATOR + row.name() + " (" + LEVEL_PREFIX
                + (row.itemLevel() > 0 ? row.itemLevel() : "unknown") + ")\n";
        return switch (row.status()) {
            case UPGRADE -> number + ". " + row.priority().label() + " · " + item
                    + row.step().label() + " → " + (row.step().rank() + 1) + "/" + row.step().track().levels().size()
                    + " · " + LEVEL_PREFIX + row.itemLevel() + " → " + row.step().nextLevel()
                    + " · ~" + GearUpgradeRules.CRESTS_PER_RANK + " " + row.step().crest()
                    + " + gold\n" + row.reason() + "\n\n";
            case MAXED -> "✅ Maxed · " + item + row.step().label() + "; seek a higher-track replacement if available.\n\n";
            case CRAFTED -> "🔨 Crafted · " + item
                    + "Use a crafting/recrafting order; ordinary vendor ranks do not apply. Check recipe and reagent costs.\n\n";
            case UNKNOWN -> "❔ Unknown track · " + item
                    + "Season/track/rank could not be verified; inspect the item at the upgrade vendor.\n\n";
        };
    }

    private static void appendStatusCounts(StringBuilder text, Report report) {
        for (Status status : List.of(Status.MAXED, Status.CRAFTED, Status.UNKNOWN)) {
            long count = report.advice().stream().filter(row -> row.status() == status).count();
            if (count > 0) {
                text.append(switch (status) {
                    case MAXED -> "Maxed: ";
                    case CRAFTED -> "Crafted: ";
                    default -> "Unknown track: ";
                }).append(count).append(". ");
            }
        }
        text.append('\n');
    }

    private record Specialization(int id, String label) {}

    private record Report(List<Advice> advice, List<String> missingSlots,
                          Specialization specialization, Instant sourceUpdatedAt) {}
}
