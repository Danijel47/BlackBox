package com.blackbox.wow.blizzard;

import com.blackbox.wow.properties.HousingMarketProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlizzardDecorServiceTest {

    private static final String DECOR_SEARCH_PATH = "/data/wow/search/decor";

    @Test
    void loadsEveryDecorSearchPageAndCachesTheItemIds() throws Exception {
        BlizzardApiClient api = mock(BlizzardApiClient.class);
        when(api.staticSearchQuery()).thenReturn(Map.of("namespace", "static-eu"));
        when(api.get(eq(DECOR_SEARCH_PATH), isNull(), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> query = invocation.getArgument(2);
            assertThat(query)
                    .containsEntry("namespace", "static-eu")
                    .containsEntry("orderby", "id")
                    .doesNotContainKey("locale");
            int page = (int) query.get("_page");
            return page == 1
                    ? payload(2, 264710L, 264711L)
                    : payload(2, 264711L, 264712L);
        });
        BlizzardDecorService service = service(api);

        assertThat(service.getDecorAuctionItemIds()).containsExactlyInAnyOrder(264710L, 264711L, 264712L);
        assertThat(service.getDecorAuctionItemIds()).containsExactlyInAnyOrder(264710L, 264711L, 264712L);
        verify(api, times(2)).get(eq(DECOR_SEARCH_PATH), isNull(), anyMap());
    }

    @Test
    void rejectsAnUnboundedPageCount() throws Exception {
        BlizzardApiClient api = mock(BlizzardApiClient.class);
        when(api.staticSearchQuery()).thenReturn(Map.of("namespace", "static-eu"));
        when(api.get(eq(DECOR_SEARCH_PATH), isNull(), anyMap())).thenReturn(payload(51, 264710L));

        assertThatThrownBy(() -> service(api).getDecorAuctionItemIds())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid page count");
    }

    @Test
    void rejectsAChangingPageCount() throws Exception {
        BlizzardApiClient api = mock(BlizzardApiClient.class);
        when(api.staticSearchQuery()).thenReturn(Map.of("namespace", "static-eu"));
        when(api.get(eq(DECOR_SEARCH_PATH), isNull(), anyMap()))
                .thenReturn(payload(2, 264710L), payload(3, 264711L));

        assertThatThrownBy(() -> service(api).getDecorAuctionItemIds())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("page count changed");
    }

    private static BlizzardDecorService service(BlizzardApiClient api) {
        return new BlizzardDecorService(api, new HousingMarketProperties(10, 12, 24));
    }

    private static JsonNode payload(int pageCount, long... itemIds) throws Exception {
        var results = JsonMapper.builder().build().createArrayNode();
        for (long itemId : itemIds) {
            results.addObject().putObject("data").putObject("item").put("id", itemId);
        }
        return JsonMapper.builder().build().createObjectNode()
                .put("pageCount", pageCount)
                .set("results", results);
    }
}
