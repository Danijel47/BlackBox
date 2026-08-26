package com.blackbox.wow.warcraftlogs;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class WarcraftLogsEventPager {

    private static final int MAX_PAGES = 1_000;
    private static final String EVENTS_QUERY = """
            query FightEvents($code: String!, $fightId: Int!, $start: Float!, $dataType: EventDataType!) {
              reportData {
                report(code: $code) {
                  events(
                    dataType: $dataType,
                    fightIDs: [$fightId],
                    startTime: $start,
                    limit: 10000
                  ) {
                    data
                    nextPageTimestamp
                  }
                }
              }
            }
            """;

    private final WarcraftLogsClient client;

    public WarcraftLogsEventPager(WarcraftLogsClient client) {
        this.client = client;
    }

    public List<JsonNode> events(String reportCode, int fightId, EventType eventType) {
        List<JsonNode> events = new ArrayList<>();
        double cursor = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode paginator = loadPage(reportCode, fightId, eventType, cursor);
            JsonNode pageData = paginator.path("data");
            if (!pageData.isArray()) {
                throw new IllegalStateException("Warcraft Logs returned incomplete event data.");
            }
            pageData.forEach(events::add);
            JsonNode nextNode = paginator.path("nextPageTimestamp");
            if (!nextNode.isNumber()) {
                return List.copyOf(events);
            }
            double nextCursor = nextNode.asDouble();
            if (nextCursor <= cursor) {
                throw new IllegalStateException("Warcraft Logs returned a non-advancing event cursor.");
            }
            cursor = nextCursor;
        }
        throw new IllegalStateException("Warcraft Logs event pagination exceeded the safety limit.");
    }

    private JsonNode loadPage(String reportCode, int fightId, EventType eventType, double cursor) {
        return client.query(EVENTS_QUERY, Map.of(
                        "code", reportCode,
                        "fightId", fightId,
                        "start", cursor,
                        "dataType", eventType.graphQlValue()
                ))
                .path("reportData")
                .path("report")
                .path("events");
    }

    public enum EventType {
        INTERRUPTS("Interrupts"),
        DEATHS("Deaths"),
        DAMAGE_TAKEN("DamageTaken");

        private final String graphQlValue;

        EventType(String graphQlValue) {
            this.graphQlValue = graphQlValue;
        }

        public String graphQlValue() {
            return graphQlValue;
        }
    }
}
