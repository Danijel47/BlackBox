package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.warcraftlogs.WarcraftLogsEventPager.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WarcraftLogsEventPagerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final WarcraftLogsClient client = mock(WarcraftLogsClient.class);
    private final WarcraftLogsEventPager pager = new WarcraftLogsEventPager(client);

    @Test
    void loadsEveryPageUntilTheCursorIsExhausted() throws Exception {
        when(client.query(anyString(), anyMap()))
                .thenReturn(response("[{\"sourceID\":1}]", "1000"))
                .thenReturn(response("[{\"sourceID\":2}]", "null"));

        List<JsonNode> events = pager.events("report", 7, EventType.INTERRUPTS);

        assertThat(events).extracting(event -> event.path("sourceID").asInt())
                .containsExactly(1, 2);
    }

    @Test
    void rejectsANonAdvancingCursorInsteadOfSilentlyTruncating() throws Exception {
        when(client.query(anyString(), anyMap())).thenReturn(response("[]", "0"));

        assertThatThrownBy(() -> pager.events("report", 7, EventType.DEATHS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-advancing");
    }

    private JsonNode response(String events, String nextCursor) throws Exception {
        return jsonMapper.readTree("""
                {
                  "reportData": {
                    "report": {
                      "events": {
                        "data": %s,
                        "nextPageTimestamp": %s
                      }
                    }
                  }
                }
                """.formatted(events, nextCursor));
    }
}
