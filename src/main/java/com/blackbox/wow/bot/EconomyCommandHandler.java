package com.blackbox.wow.bot;

import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.blizzard.BlizzardAuctionService.PriceResult;
import com.blackbox.wow.blizzard.BlizzardItemService;
import com.blackbox.wow.blizzard.BlizzardItemService.ItemRef;
import com.blackbox.wow.properties.WowWatchlistProperties;
import com.blackbox.wow.service.WowTokenReportService;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class EconomyCommandHandler {

    private static final String USAGE_PREFIX = "Usage: ";
    private static final String WOW_TOKEN_EU_SCOPE = "WoW Token (EU)";
    private static final String TOKEN_LOWEST_WEEK_COMMAND = "/token_lowest_week";
    private static final String TOKEN_LOWEST_MONTH_COMMAND = "/token_lowest_month";
    private static final String TOKEN_HIGHEST_WEEK_COMMAND = "/token_highest_week";
    private static final String TOKEN_HIGHEST_MONTH_COMMAND = "/token_highest_month";
    private static final String TOKEN_BEST_COMMAND = "/token_best";
    private static final Map<String, List<Long>> MIDNIGHT_MATERIAL_IDS = Map.ofEntries(
            Map.entry("refulgent copper ore", List.of(237359L, 237361L)),
            Map.entry("umbral tin ore", List.of(237362L, 237363L)),
            Map.entry("brilliant silver ore", List.of(237364L, 237365L)),
            Map.entry("dazzling thorium", List.of(237366L)),
            Map.entry("dazzling thorium ore", List.of(237366L)),
            Map.entry("tranquility bloom", List.of(236761L, 236767L)),
            Map.entry("sanguithorn", List.of(236770L, 236771L)),
            Map.entry("azeroot", List.of(236774L, 236775L)),
            Map.entry("argentleaf", List.of(236776L, 236777L)),
            Map.entry("mana lily", List.of(236778L, 236779L)),
            Map.entry("nocturnal lotus", List.of(236780L))
    );

    private final BlizzardAuctionService auctionService;
    private final WowTokenReportService tokenReportService;
    private final BlizzardItemService itemService;
    private final WowWatchlistProperties watchlistProperties;
    private final MessageSender messageSender;

    EconomyCommandHandler(
            BlizzardAuctionService auctionService,
            WowTokenReportService tokenReportService,
            BlizzardItemService itemService,
            WowWatchlistProperties watchlistProperties,
            MessageSender messageSender
    ) {
        this.auctionService = auctionService;
        this.tokenReportService = tokenReportService;
        this.itemService = itemService;
        this.watchlistProperties = watchlistProperties;
        this.messageSender = messageSender;
    }

    boolean handle(long chatId, String text, String command) {
        return switch (command) {
            case "/price" -> handled(() -> handlePrice(chatId, text));
            case "/priceah" -> handled(() -> handleAuctionHousePrice(chatId, text));
            case "/token" -> handled(() -> send(chatId, tokenReportService.currentPrice()));
            case TOKEN_LOWEST_WEEK_COMMAND -> handled(() -> handleTokenPriceExtreme(
                    chatId, text, command, TokenPriceExtreme.LOWEST, TokenHistoryPeriod.WEEK));
            case TOKEN_LOWEST_MONTH_COMMAND -> handled(() -> handleTokenPriceExtreme(
                    chatId, text, command, TokenPriceExtreme.LOWEST, TokenHistoryPeriod.MONTH));
            case TOKEN_HIGHEST_WEEK_COMMAND -> handled(() -> handleTokenPriceExtreme(
                    chatId, text, command, TokenPriceExtreme.HIGHEST, TokenHistoryPeriod.WEEK));
            case TOKEN_HIGHEST_MONTH_COMMAND -> handled(() -> handleTokenPriceExtreme(
                    chatId, text, command, TokenPriceExtreme.HIGHEST, TokenHistoryPeriod.MONTH));
            case TOKEN_BEST_COMMAND -> handled(() -> handleBestTokenTradingHours(chatId, text));
            case "/tokenlowest" -> handled(() -> handleTokenPriceExtreme(
                    chatId, text, command, TokenPriceExtreme.LOWEST));
            case "/tokenhighest" -> handled(() -> handleTokenPriceExtreme(
                    chatId, text, command, TokenPriceExtreme.HIGHEST));
            case "/tokenbest" -> handled(() -> handleBestTokenTradingHours(chatId, text));
            case "/ores", "/ore" -> handled(() -> send(
                    chatId,
                    formatWatchlistWithSilverGold("Ores (EU)", watchlistProperties.ores())
            ));
            case "/herbs", "/herb" -> handled(() -> send(
                    chatId,
                    formatWatchlistWithSilverGold("Herbs (EU)", watchlistProperties.herbs())
            ));
            default -> false;
        };
    }

    private void handlePrice(long chatId, String text) {
        String arguments = commandArguments(text);
        if (arguments.isBlank()) {
            send(chatId, """
                    Usage: /price <itemId|item name> [realm-if-itemId]
                    Examples:
                    /price 72092 Draenor
                    /price wow token
                    """.strip());
            return;
        }

        try {
            String[] idAndRealm = arguments.split("\\s+", 2);
            Long itemId = parseLong(idAndRealm[0]);
            if (itemId == null) {
                sendNamedItemPrice(chatId, arguments);
            } else {
                String realm = idAndRealm.length == 2 ? idAndRealm[1].trim() : "";
                sendItemIdPrice(chatId, itemId, realm);
            }
        } catch (Exception e) {
            send(chatId, "Blizzard price lookup failed: " + e.getMessage());
        }
    }

    private void sendItemIdPrice(long chatId, long itemId, String realm) {
        ItemRef item = itemService.getById(itemId);
        String itemName = resolveDisplayName(item, itemId);
        if (!realm.isBlank()) {
            PriceResult result = auctionService.getRealmAverage(realm, itemId);
            send(chatId, formatPriceMessage("Realm " + realm, itemName, result));
            return;
        }

        PriceResult result = auctionService.getRegionAverage(itemId);
        if (!result.available() && isWowToken(itemName)) {
            result = auctionService.getWowTokenPrice();
            send(chatId, formatPriceMessage(WOW_TOKEN_EU_SCOPE, itemName, result));
            return;
        }
        send(chatId, formatPriceMessage("Region avg (EU)", itemName, result));
    }

    private void sendNamedItemPrice(long chatId, String itemName) {
        ItemRef item = itemService.findByName(itemName);
        if (item == null) {
            send(chatId, "Item not found: " + itemName);
            return;
        }

        PriceResult result = isWowToken(item.name())
                ? auctionService.getWowTokenPrice()
                : auctionService.getRegionAverage(item.id());
        String scope = isWowToken(item.name()) ? WOW_TOKEN_EU_SCOPE : "Region avg (EU)";
        send(chatId, formatPriceMessage(scope, item.name(), result));
    }

    private void handleAuctionHousePrice(long chatId, String text) {
        String[] parts = text.split("\\s+");
        if (parts.length != 4) {
            send(chatId, """
                    Usage: /price_ah <connectedRealmId> <auctionHouseId> <itemId>
                    Example: /price_ah 1080 2 72092
                    """.strip());
            return;
        }

        Long connectedRealmId = parseLong(parts[1]);
        Long auctionHouseId = parseLong(parts[2]);
        Long itemId = parseLong(parts[3]);
        if (connectedRealmId == null || auctionHouseId == null || itemId == null) {
            send(chatId, "Invalid numbers. Example: /price_ah 1080 2 72092");
            return;
        }

        try {
            PriceResult result = auctionService.getAuctionHouseAverage(connectedRealmId, auctionHouseId, itemId);
            ItemRef item = itemService.getById(itemId);
            send(chatId, formatPriceMessage(
                    "AuctionHouse " + auctionHouseId,
                    resolveDisplayName(item, itemId),
                    result
            ));
        } catch (Exception e) {
            send(chatId, "Blizzard price lookup failed: " + e.getMessage());
        }
    }

    private void handleTokenPriceExtreme(
            long chatId,
            String text,
            String command,
            TokenPriceExtreme extreme
    ) {
        String requestedPeriod = commandArguments(text).toLowerCase(Locale.ROOT);
        switch (requestedPeriod) {
            case "week" -> handleTokenPriceExtreme(chatId, text, command, extreme, TokenHistoryPeriod.WEEK);
            case "month" -> handleTokenPriceExtreme(chatId, text, command, extreme, TokenHistoryPeriod.MONTH);
            default -> send(chatId, extreme.usageMessage());
        }
    }

    private void handleTokenPriceExtreme(
            long chatId,
            String text,
            String command,
            TokenPriceExtreme extreme,
            TokenHistoryPeriod period
    ) {
        if (!commandArguments(text).isBlank() && command.contains("_")) {
            send(chatId, USAGE_PREFIX + extreme.commandFor(period));
            return;
        }

        String report = extreme == TokenPriceExtreme.LOWEST
                ? tokenReportService.lowestPrice(period.lookback(), period.label())
                : tokenReportService.highestPrice(period.lookback(), period.label());
        send(chatId, report);
    }

    private void handleBestTokenTradingHours(long chatId, String text) {
        if (!commandArguments(text).isBlank()) {
            send(chatId, USAGE_PREFIX + TOKEN_BEST_COMMAND);
            return;
        }
        send(chatId, tokenReportService.bestTradingHours());
    }

    private String formatWatchlistWithSilverGold(String title, List<String> names) {
        if (names == null || names.isEmpty()) {
            return title + "\nNo items configured.";
        }

        StringBuilder message = new StringBuilder(title).append("\n");
        for (String rawBase : names) {
            String baseName = rawBase == null ? "" : rawBase.trim();
            if (baseName.isBlank()) {
                continue;
            }

            try {
                List<ItemRef> ranks = findMaterialRanks(baseName);
                if (ranks.size() == 1) {
                    message.append("• ").append(baseName)
                            .append(": ").append(formatItemPriceOrState(ranks.getFirst(), "n/a"))
                            .append("\n");
                } else {
                    ItemRef silver = findMaterialRank(ranks, 2);
                    ItemRef gold = findMaterialRank(ranks, 3);
                    message.append("• ").append(baseName)
                            .append(" | S: ").append(formatItemPriceOrState(silver, "n/a"))
                            .append(" | G: ").append(formatItemPriceOrState(gold, "n/a"))
                            .append("\n");
                }
            } catch (Exception _) {
                message.append("• ").append(baseName).append(": error\n");
            }
        }
        return message.toString().trim();
    }

    private List<ItemRef> findMaterialRanks(String baseName) {
        List<Long> ids = MIDNIGHT_MATERIAL_IDS.get(baseName.toLowerCase());
        if (ids == null) {
            return itemService.findExactByName(baseName);
        }
        return ids.stream().map(id -> new ItemRef(id, baseName)).toList();
    }

    private static ItemRef findMaterialRank(List<ItemRef> ranks, int qualityRank) {
        if (ranks == null || ranks.isEmpty()) {
            return null;
        }
        if (qualityRank == 2) {
            return ranks.size() >= 3 ? ranks.get(1) : ranks.get(0);
        }
        if (qualityRank == 3) {
            if (ranks.size() >= 3) {
                return ranks.get(2);
            }
            if (ranks.size() >= 2) {
                return ranks.get(1);
            }
        }
        return null;
    }

    private String formatItemPriceOrState(ItemRef item, String emptyLabel) {
        if (item == null) {
            return emptyLabel;
        }
        PriceResult result = isWowToken(item.name())
                ? auctionService.getWowTokenPrice()
                : auctionService.getRegionBuyPrice(item.id());
        return result.available() ? formatCopper(result.avgCopper()) : emptyLabel;
    }

    private void send(long chatId, String text) {
        messageSender.send(chatId, text);
    }

    private static String commandArguments(String text) {
        String[] commandAndArguments = text.split("\\s+", 2);
        return commandAndArguments.length == 2 ? commandAndArguments[1].trim() : "";
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception _) {
            return null;
        }
    }

    private static String formatPriceMessage(String scope, String itemName, PriceResult result) {
        if (!result.available()) {
            return "No pricing data for " + itemName + " (" + scope + ").";
        }
        return scope + " avg for " + itemName + ": " + formatCopper(result.avgCopper());
    }

    private static String resolveDisplayName(ItemRef item, long fallbackItemId) {
        if (item != null && item.name() != null && !item.name().isBlank()) {
            return item.name();
        }
        return "item " + fallbackItemId;
    }

    private static boolean isWowToken(String name) {
        return name != null && name.trim().equalsIgnoreCase("wow token");
    }

    private static String formatCopper(long copper) {
        long gold = copper / 10_000;
        long silver = copper % 10_000 / 100;
        return gold + "g " + silver + "s";
    }

    private static boolean handled(Runnable action) {
        action.run();
        return true;
    }

    private enum TokenPriceExtreme {
        LOWEST(TOKEN_LOWEST_WEEK_COMMAND, TOKEN_LOWEST_MONTH_COMMAND),
        HIGHEST(TOKEN_HIGHEST_WEEK_COMMAND, TOKEN_HIGHEST_MONTH_COMMAND);

        private final String weekCommand;
        private final String monthCommand;

        TokenPriceExtreme(String weekCommand, String monthCommand) {
            this.weekCommand = weekCommand;
            this.monthCommand = monthCommand;
        }

        String commandFor(TokenHistoryPeriod period) {
            return period == TokenHistoryPeriod.WEEK ? weekCommand : monthCommand;
        }

        String usageMessage() {
            return USAGE_PREFIX + weekCommand + " or " + monthCommand;
        }
    }

    private enum TokenHistoryPeriod {
        WEEK(Duration.ofDays(7), "last week"),
        MONTH(Duration.ofDays(30), "last 30 days");

        private final Duration lookback;
        private final String label;

        TokenHistoryPeriod(Duration lookback, String label) {
            this.lookback = lookback;
            this.label = label;
        }

        Duration lookback() {
            return lookback;
        }

        String label() {
            return label;
        }
    }

    @FunctionalInterface
    interface MessageSender {
        void send(long chatId, String text);
    }
}
