package com.blackbox.wow.warcraftlogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

final class WarcraftLogsRankingParser {
    private static final BigDecimal MAX_PERCENTILE = BigDecimal.valueOf(100);

    private WarcraftLogsRankingParser() {
    }

    static String rankingsQuery(Set<Integer> fightIds) {
        StringBuilder query = new StringBuilder(
                "query ReportRankings($code: String!) { reportData { report(code: $code) {"
        );
        for (Integer fightId : fightIds) {
            query.append(" p").append(fightId)
                    .append(": rankings(compare: Rankings, playerMetric: dps, fightIDs: [")
                    .append(fightId).append("])")
                    .append(" d").append(fightId)
                    .append(": table(dataType: DamageDone, viewBy: Source, fightIDs: [")
                    .append(fightId).append("])");
        }
        return query.append(" } } }").toString();
    }

    static RankingMetrics findMetrics(JsonNode rankings, JsonNode damageTable, int actorId, String name) {
        RankingPercentiles percentiles = findPercentiles(rankings, actorId, name);
        return new RankingMetrics(
                percentiles.parsePercentage(), percentiles.keyParsePercentage(),
                findDamagePerSecond(damageTable, actorId, name)
        );
    }

    static RankingPercentiles findPercentiles(JsonNode node, int actorId, String name) {
        if (node == null || node.isMissingNode() || node.isNull()) return RankingPercentiles.unavailable();
        RankingPercentiles direct = percentilesForPlayer(node, actorId, name);
        if (direct.available()) return direct;
        var children = node.elements();
        while (children.hasNext()) {
            RankingPercentiles nested = findPercentiles(children.next(), actorId, name);
            if (nested.available()) return nested;
        }
        return RankingPercentiles.unavailable();
    }

    private static RankingPercentiles percentilesForPlayer(JsonNode node, int actorId, String name) {
        JsonNode parse = node.path("rankPercent");
        JsonNode keyParse = node.path("bracketPercent");
        if (!belongsToPlayer(node, actorId, name) || !parse.isNumber() || !keyParse.isNumber()
                || !validPercentile(parse.decimalValue()) || !validPercentile(keyParse.decimalValue())) {
            return RankingPercentiles.unavailable();
        }
        return new RankingPercentiles(parse.decimalValue(), keyParse.decimalValue());
    }

    static BigDecimal findDamagePerSecond(JsonNode table, int actorId, String name) {
        JsonNode data = unwrap(table);
        JsonNode totalTime = data.path("totalTime");
        if (!totalTime.isNumber() || totalTime.decimalValue().signum() <= 0) return null;
        BigDecimal totalDamage = findTotalDamage(data.path("entries"), actorId, name);
        return totalDamage == null ? null : totalDamage.multiply(BigDecimal.valueOf(1_000))
                .divide(totalTime.decimalValue(), 1, RoundingMode.HALF_UP);
    }

    private static JsonNode unwrap(JsonNode table) {
        if (table == null || table.isMissingNode() || table.isNull()) return MissingNode.getInstance();
        JsonNode data = table.path("data");
        return data.isObject() ? data : table;
    }

    private static BigDecimal findTotalDamage(JsonNode node, int actorId, String name) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        JsonNode total = node.path("total");
        if (total.isNumber() && belongsToPlayer(node, actorId, name)) return total.decimalValue();
        var children = node.elements();
        while (children.hasNext()) {
            BigDecimal nested = findTotalDamage(children.next(), actorId, name);
            if (nested != null) return nested;
        }
        return null;
    }

    private static boolean belongsToPlayer(JsonNode node, int actorId, String name) {
        return node.path("id").asInt(-1) == actorId
                || node.path("actorID").asInt(-1) == actorId
                || node.path("sourceID").asInt(-1) == actorId
                || node.path("name").asText("").equalsIgnoreCase(name);
    }

    private static boolean validPercentile(BigDecimal value) {
        return value != null && value.signum() > 0 && value.compareTo(MAX_PERCENTILE) <= 0;
    }

    record RankingMetrics(BigDecimal parsePercentage, BigDecimal keyParsePercentage, BigDecimal damagePerSecond) {}

    record RankingPercentiles(BigDecimal parsePercentage, BigDecimal keyParsePercentage) {
        private static RankingPercentiles unavailable() { return new RankingPercentiles(null, null); }
        private boolean available() { return parsePercentage != null && keyParsePercentage != null; }
    }
}
