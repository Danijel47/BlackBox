package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardDecorService;
import com.blackbox.wow.client.TsmPublicDataClient;
import com.blackbox.wow.client.TsmPublicDataClient.RegionItem;
import com.blackbox.wow.properties.HousingMarketProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.blackbox.wow.service.HousingMarketUnavailableException.DataSource.BLIZZARD_DECOR;
import static com.blackbox.wow.service.HousingMarketUnavailableException.DataSource.TSM_EU;

@Service
public class HousingSalesReportService {

    private static final long COPPER_PER_GOLD = 10_000L;
    private static final String UNAVAILABLE_PRICE = "n/a";
    private static final DateTimeFormatter UPDATED_DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final BlizzardDecorService decorService;
    private final TsmPublicDataClient tsmClient;
    private final int resultLimit;

    public HousingSalesReportService(
            BlizzardDecorService decorService,
            TsmPublicDataClient tsmClient,
            HousingMarketProperties properties
    ) {
        this.decorService = decorService;
        this.tsmClient = tsmClient;
        this.resultLimit = properties.resultLimit();
    }

    public String topSellingMessage() {
        Set<Long> decorItemIds = loadDecorItemIds();
        List<RegionItem> rankedItems = loadTsmRegionItems().stream()
                .filter(item -> decorItemIds.contains(item.itemId()))
                .filter(HousingSalesReportService::hasSalesData)
                .sorted(Comparator.comparingDouble(RegionItem::soldPerDay)
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(RegionItem::saleRate).reversed())
                        .thenComparing(RegionItem::name))
                .limit(resultLimit)
                .toList();
        if (rankedItems.isEmpty()) {
            return "No auctionable housing decor with EU sales data is currently available.";
        }

        StringBuilder message = new StringBuilder("Housing decor — top EU sellers\n");
        for (int index = 0; index < rankedItems.size(); index++) {
            appendItem(message, index + 1, rankedItems.get(index));
        }
        Instant updatedAt = rankedItems.stream()
                .map(RegionItem::updatedAt)
                .min(Instant::compareTo)
                .orElseThrow();
        return message.append("\nUpdated ")
                .append(UPDATED_DATE_FORMAT.format(updatedAt))
                .append(" UTC. TSM regional estimates; realm sales are not guaranteed.")
                .toString();
    }

    private Set<Long> loadDecorItemIds() {
        try {
            return decorService.getDecorAuctionItemIds();
        } catch (RuntimeException e) {
            throw HousingMarketUnavailableException.from(BLIZZARD_DECOR, e);
        }
    }

    private List<RegionItem> loadTsmRegionItems() {
        try {
            return tsmClient.getEuRetailRegionItems();
        } catch (RuntimeException e) {
            throw HousingMarketUnavailableException.from(TSM_EU, e);
        }
    }

    private static boolean hasSalesData(RegionItem item) {
        return item.averageSalePriceCopper() > 0
                && (item.soldPerDay() > 0D || item.saleRate() > 0D);
    }

    private static void appendItem(StringBuilder message, int rank, RegionItem item) {
        message.append('\n')
                .append(rank)
                .append(". ")
                .append(item.name())
                .append(" [")
                .append(item.itemId())
                .append("]\n   ")
                .append(formatRate(item.soldPerDay()))
                .append("/day | ")
                .append(formatPercent(item.saleRate()))
                .append(" sale | avg ")
                .append(formatGold(item.averageSalePriceCopper()))
                .append(" | market ")
                .append(formatGold(item.marketValueCopper()));
    }

    private static String formatRate(double rate) {
        return String.format(Locale.ROOT, "%.3f", rate);
    }

    private static String formatPercent(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100D);
    }

    private static String formatGold(long copper) {
        if (copper <= 0) {
            return UNAVAILABLE_PRICE;
        }
        return String.format(Locale.ROOT, "%,dg", copper / COPPER_PER_GOLD);
    }
}
