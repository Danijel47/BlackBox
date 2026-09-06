package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.blizzard.BlizzardItemService;
import com.blackbox.wow.blizzard.CommodityMarket;
import com.blackbox.wow.properties.BlizzardApiProperties;
import com.blackbox.wow.repository.ProspectingSampleRepository;
import com.blackbox.wow.repository.ProspectingSampleRepository.SavedSample;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProspectingReportService {

    public static final String COMMAND = "/prospect";
    private static final String MARKET_LABEL = "Prospecting — Stormscale EU (EU-wide commodities)";
    private static final String USAGE = COMMAND + " <ore ID or alias> <ore used> <outputID>:<quantity> ...";
    private static final String UNKNOWN_PRICE_AGE = "Price age is unverified; no current profitability verdict.";
    private static final String FALLBACK_ITEM_NAME = "Item";
    private static final String UNAVAILABLE_PRICES = "Prospecting prices are temporarily unavailable. Please try again later.";
    private static final String EU_CONFIG_REQUIRED = "Stormscale prospecting requires the EU retail commodity market configuration.";
    private static final String EU_NAMESPACE = "dynamic-eu";
    private static final long COMPARISON_ORE_QUANTITY = 1_000L;
    private static final BigDecimal NET_AUCTION_PROCEEDS = new BigDecimal("0.95");
    private static final BigDecimal LOWER_SALE_PRICE = new BigDecimal("0.90");
    private static final BigDecimal COPPER_PER_GOLD = BigDecimal.valueOf(10_000);
    private static final Duration MAXIMUM_PRICE_AGE = Duration.ofHours(2);
    private static final Duration FUTURE_CLOCK_TOLERANCE = Duration.ofMinutes(5);
    private static final long SMALL_SAMPLE_ORE_QUANTITY = 1_000L;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final BlizzardAuctionService auctions;
    private final BlizzardItemService items;
    private final BlizzardApiProperties properties;
    private final Clock clock;
    private final ProspectingSampleRepository samples;

    public ProspectingReportService(
            BlizzardAuctionService auctions,
            BlizzardItemService items,
            BlizzardApiProperties properties,
            Clock clock,
            ProspectingSampleRepository samples
    ) {
        this.auctions = auctions;
        this.items = items;
        this.properties = properties;
        this.clock = clock;
        this.samples = samples;
    }

    public String report(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return help();
        }
        ProspectingBatch batch;
        try {
            batch = ProspectingBatch.parse(arguments);
        } catch (IllegalArgumentException e) {
            return e.getMessage() + "\n\n" + USAGE;
        }
        if (!EU_NAMESPACE.equals(properties.namespace())) {
            return EU_CONFIG_REQUIRED;
        }
        try {
            return reportBatch(batch, auctions.getCommodityMarket(batch.itemIds()));
        } catch (RuntimeException _) {
            return UNAVAILABLE_PRICES;
        }
    }

    public Optional<String> savedReport(long userId, ProspectingOre ore) {
        if (!EU_NAMESPACE.equals(properties.namespace())) {
            return Optional.of(EU_CONFIG_REQUIRED);
        }
        return samples.find(userId, ore.itemId())
                .map(sample -> report(ProspectingSampleRepository.arguments(sample.batch()))
                        + "\nSample saved: " + TIME_FORMAT.format(sample.recordedAt()));
    }

    public String saveAndReport(long userId, String arguments) {
        ProspectingBatch batch;
        try {
            batch = ProspectingBatch.parse(arguments);
        } catch (IllegalArgumentException _) {
            return report(arguments);
        }
        if (!EU_NAMESPACE.equals(properties.namespace())) {
            return EU_CONFIG_REQUIRED;
        }
        if (ProspectingOre.fromItemId(batch.oreId()).isEmpty()) {
            return report(arguments);
        }
        try {
            samples.save(userId, batch, clock.instant());
        } catch (RuntimeException _) {
            return "Could not save your prospecting sample. Please try again later.";
        }
        return report(arguments) + "\nRecorded batch saved for this ore and quality. Use All ores to compare.";
    }

    public String compareSaved(long userId) {
        if (!EU_NAMESPACE.equals(properties.namespace())) {
            return EU_CONFIG_REQUIRED;
        }
        try {
            Map<Long, SavedSample> recorded = samples.findAll(userId).stream()
                    .filter(sample -> ProspectingOre.fromItemId(sample.batch().oreId()).isPresent())
                    .collect(Collectors.toMap(sample -> sample.batch().oreId(), Function.identity()));
            if (recorded.isEmpty()) {
                return "No recorded batches yet. Choose an ore button and enter its batch once. "
                        + "All ores will then compare your saved samples using current EU prices. "
                        + "Each ore and quality needs its own sample.";
            }
            Set<Long> itemIds = recorded.values().stream().flatMap(sample -> sample.batch().itemIds().stream())
                    .collect(Collectors.toSet());
            CommodityMarket market = auctions.getCommodityMarket(itemIds);
            List<ComparisonRow> rows = Arrays.stream(ProspectingOre.values())
                    .map(ore -> comparisonRow(ore, recorded.get(ore.itemId()), market))
                    .sorted(Comparator.comparing(ComparisonRow::margin,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            return formatComparison(rows, market);
        } catch (RuntimeException _) {
            return "Could not compare prospecting samples and prices. Please try again later.";
        }
    }

    private static ComparisonRow comparisonRow(ProspectingOre ore, SavedSample sample, CommodityMarket market) {
        if (sample == null) {
            return new ComparisonRow(ore, null, "No sample yet — choose this ore to add one.");
        }
        var cost = market.purchaseCost(ore.itemId(), COMPARISON_ORE_QUANTITY);
        if (cost.isEmpty()) {
            return new ComparisonRow(ore, null, "Not enough listed ore to price 1,000 units.");
        }
        List<OutputValue> outputs = outputValues(sample.batch(), market);
        if (outputs.stream().anyMatch(output -> output.unitCopper() == null)) {
            return new ComparisonRow(ore, null, "Missing output prices.");
        }
        BigDecimal gross = outputs.stream().map(OutputValue::totalCopper).reduce(BigDecimal.ZERO, BigDecimal::add)
                .multiply(BigDecimal.valueOf(COMPARISON_ORE_QUANTITY))
                .divide(BigDecimal.valueOf(sample.batch().oreQuantity()), 12, RoundingMode.DOWN);
        BigDecimal net = gross.multiply(NET_AUCTION_PROCEEDS);
        BigDecimal margin = net.subtract(cost.get());
        String details = gold(margin) + " per 1,000 ore | ROI "
                + margin.multiply(BigDecimal.valueOf(100)).divide(cost.get(), 1, RoundingMode.HALF_UP)
                + "%\n   Cost " + gold(cost.get()) + " | break-even/ore "
                + gold(net.divide(BigDecimal.valueOf(COMPARISON_ORE_QUANTITY), 0, RoundingMode.DOWN))
                + "\n   Sample: " + sample.batch().oreQuantity() + " ore, " + TIME_FORMAT.format(sample.recordedAt());
        return new ComparisonRow(ore, margin, details);
    }

    private String formatComparison(List<ComparisonRow> rows, CommodityMarket market) {
        StringBuilder message = new StringBuilder(MARKET_LABEL).append("\nAll ores — estimated profit per 1,000 ore\n");
        if (!hasCurrentPrices(market.sourceUpdatedAt())) {
            message.append(verdict(market.sourceUpdatedAt(), BigDecimal.ZERO));
            appendPriceAge(message, market);
            return message.append("\nTry again when a fresh Blizzard snapshot is available.").toString();
        }
        ComparisonRow best = rows.getFirst();
        if (best.margin() == null) {
            message.append("No saved sample could be priced completely.\n");
        } else if (best.margin().signum() > 0) {
            message.append("Highest estimate among priced samples: ").append(best.ore().label()).append('\n');
        } else {
            message.append("No priced sample currently shows a positive estimated margin.\n");
        }
        for (ComparisonRow row : rows) {
            message.append('\n').append(row.ore().label()).append("\n   ").append(row.details()).append('\n');
        }
        appendPriceAge(message, market);
        return message.append("\nIncludes the 5% AH cut. Each ore uses its own recorded yield, scaled to 1,000 ore.")
                .append(" Missing samples are not ranked. Small samples can be distorted by lucky drops.")
                .append(" Estimates assume all outputs sell; failed-listing deposits and time are excluded.")
                .append(" Refresh samples after skill, gear, specialization or yield changes; verify prices in game.")
                .toString();
    }

    private boolean hasCurrentPrices(Instant sourceUpdatedAt) {
        return sourceUpdatedAt != null && !sourceUpdatedAt.isAfter(clock.instant().plus(FUTURE_CLOCK_TOLERANCE))
                && !sourceUpdatedAt.isBefore(clock.instant().minus(MAXIMUM_PRICE_AGE));
    }

    private String reportBatch(ProspectingBatch batch, CommodityMarket market) {
        var oreCost = market.purchaseCost(batch.oreId(), batch.oreQuantity());
        if (oreCost.isEmpty()) {
            return MARKET_LABEL + "\nNot enough listed ore to price the full " + batch.oreQuantity()
                    + "-ore batch. No profitability estimate is available.";
        }
        List<OutputValue> outputs = outputValues(batch, market);
        List<Long> missing = outputs.stream().filter(output -> output.unitCopper() == null)
                .map(OutputValue::itemId).toList();
        if (!missing.isEmpty()) {
            return MARKET_LABEL + "\nMissing commodity prices for output IDs: " + missing
                    + ". No profitability estimate is available; check every item's quality-specific ID.";
        }
        BigDecimal gross = outputs.stream().map(OutputValue::totalCopper).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = gross.multiply(NET_AUCTION_PROCEEDS);
        return formatReport(batch, market, outputs, new BatchValue(oreCost.get(), gross, net));
    }

    private static List<OutputValue> outputValues(ProspectingBatch batch, CommodityMarket market) {
        List<OutputValue> outputs = new ArrayList<>();
        batch.outputs().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                outputs.add(new OutputValue(entry.getKey(), entry.getValue(),
                        market.lowestUnitPrice(entry.getKey()).orElse(null))));
        return List.copyOf(outputs);
    }

    private String formatReport(
            ProspectingBatch batch, CommodityMarket market, List<OutputValue> outputs, BatchValue value
    ) {
        BigDecimal oreQuantity = BigDecimal.valueOf(batch.oreQuantity());
        BigDecimal margin = value.netCopper().subtract(value.oreCostCopper());
        BigDecimal stressedMargin = value.netCopper().multiply(LOWER_SALE_PRICE).subtract(value.oreCostCopper());
        StringBuilder message = new StringBuilder(MARKET_LABEL).append('\n')
                .append(verdict(market.sourceUpdatedAt(), margin)).append("\n\n")
                .append("Observed batch: ").append(batch.oreQuantity()).append(" × ")
                .append(itemLabel(batch.oreId())).append('\n');
        for (OutputValue output : outputs) {
            message.append(output.quantity()).append(" × ").append(itemLabel(output.itemId()))
                    .append(" @ ").append(gold(output.unitCopper())).append('\n');
        }
        message.append("\nOre purchase cost (full batch): ").append(gold(value.oreCostCopper()))
                .append("\nOutput asking value: ").append(gold(value.grossCopper()))
                .append("\nAfter 5% AH cut: ").append(gold(value.netCopper()))
                .append("\nEstimated margin: ").append(gold(margin))
                .append(" (ROI ").append(margin.multiply(BigDecimal.valueOf(100))
                        .divide(value.oreCostCopper(), 1, RoundingMode.HALF_UP).toPlainString()).append("%)")
                .append("\nBreak-even average ore price: ")
                .append(gold(value.netCopper().divide(oreQuantity, 0, RoundingMode.DOWN)))
                .append("\nMargin if output prices fall 10%: ").append(gold(stressedMargin));
        appendPriceAge(message, market);
        message.append("\n\nAssumes the same yields as your recorded batch and that all outputs sell at these prices.")
                .append(" Deposits lost on failed listings and your time are excluded.")
                .append(" Mined ore is valued at its current purchase cost, not treated as free.")
                .append(" Verify prices in game before buying; API snapshots are not live quotes.");
        if (batch.oreQuantity() < SMALL_SAMPLE_ORE_QUANTITY) {
            message.append("\nSmall sample: fewer than 1,000 ore. Random outcomes can substantially change the margin.");
        }
        message.append("\nUse a fresh sample after skill, gear, specialization or prospecting yield changes.");
        return message.toString();
    }

    private String verdict(Instant sourceUpdatedAt, BigDecimal margin) {
        if (sourceUpdatedAt == null || sourceUpdatedAt.isAfter(clock.instant().plus(FUTURE_CLOCK_TOLERANCE))) {
            return UNKNOWN_PRICE_AGE;
        }
        if (sourceUpdatedAt.isBefore(clock.instant().minus(MAXIMUM_PRICE_AGE))) {
            return "Prices are over 2 hours old; no current profitability verdict.";
        }
        return switch (margin.signum()) {
            case 1 -> "Positive estimated margin for this recorded yield.";
            case -1 -> "Negative estimated margin for this recorded yield.";
            default -> "Break-even before failed listings and time.";
        };
    }

    private static void appendPriceAge(StringBuilder message, CommodityMarket market) {
        if (market.sourceUpdatedAt() != null) {
            message.append("\nBlizzard snapshot: ").append(TIME_FORMAT.format(market.sourceUpdatedAt()));
        }
        if (market.fetchedAt() != null) {
            message.append("\nRetrieved: ").append(TIME_FORMAT.format(market.fetchedAt()));
        }
    }

    private String itemLabel(long itemId) {
        String name = itemName(itemId).replace('\n', ' ').replace('\r', ' ');
        return name.substring(0, Math.min(name.length(), 64)) + " [" + itemId + "]";
    }

    private String itemName(long itemId) {
        try {
            var item = items.getById(itemId);
            return item == null ? FALLBACK_ITEM_NAME : item.name();
        } catch (RuntimeException _) {
            return FALLBACK_ITEM_NAME;
        }
    }

    private static String gold(BigDecimal copper) {
        return copper.divide(COPPER_PER_GOLD, 2, RoundingMode.DOWN).toPlainString() + "g";
    }

    private static String help() {
        return MARKET_LABEL + "\n\n" + USAGE + "\n\n"
                + "Open /prospect without arguments for ore buttons. All ores compares saved batches per 1,000 ore.\n\n"
                + "Enter the ore used and ALL saleable outputs from one recorded batch, including byproducts. "
                + "Keep ore and gem quality ranks separate. Each Midnight ore button reuses your latest saved batch; "
                + "Update recorded batch replaces it. Missing samples are not estimated.\n\n"
                + "Midnight ore aliases: copper1/copper2, tin1/tin2, silver1/silver2, thorium. "
                + "The number is the ore quality rank. Other ores can use their item ID.\n"
                + "Get item IDs with /price <item name>. ProspectMate + Auctionator can record your yields.\n\n"
                + "The checker prices the full ore quantity, deducts the 5% AH cut and shows a break-even ore price. "
                + "It estimates returns from your sample; it cannot predict random drops from your skill alone.";
    }

    private record OutputValue(long itemId, long quantity, BigDecimal unitCopper) {
        BigDecimal totalCopper() {
            return unitCopper.multiply(BigDecimal.valueOf(quantity));
        }
    }

    private record BatchValue(BigDecimal oreCostCopper, BigDecimal grossCopper, BigDecimal netCopper) {}

    private record ComparisonRow(ProspectingOre ore, BigDecimal margin, String details) {}
}
