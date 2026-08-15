package com.example.blackbox.wow.client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WowheadNewsFeedClientTest {

    @Test
    void parsesSafeRssMetadataWithoutArticleContent() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <item>
                      <title>Season 2 Class Tuning Incoming</title>
                      <link>https://www.wowhead.com/news=382466/example</link>
                      <description>Full article text is deliberately ignored.</description>
                      <category>Live</category>
                      <pubDate>Fri, 14 Aug 2026 19:21:34 -0500</pubDate>
                      <guid isPermaLink="false">https://www.wowhead.com/news=382466</guid>
                    </item>
                  </channel>
                </rss>
                """;

        var items = WowheadNewsFeedClient.parse(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().guid()).isEqualTo("https://www.wowhead.com/news=382466");
        assertThat(items.getFirst().title()).isEqualTo("Season 2 Class Tuning Incoming");
        assertThat(items.getFirst().link().toString()).isEqualTo("https://www.wowhead.com/news=382466/example");
        assertThat(items.getFirst().category()).isEqualTo("Live");
        assertThat(items.getFirst().publishedAt()).isEqualTo(java.time.Instant.parse("2026-08-15T00:21:34Z"));
    }

    @Test
    void rejectsLinksOutsideWowhead() throws Exception {
        String xml = """
                <rss><channel><item>
                  <title>Class Tuning</title>
                  <link>https://example.com/untrusted</link>
                  <category>Live</category>
                  <pubDate>Fri, 14 Aug 2026 19:21:34 -0500</pubDate>
                  <guid>untrusted</guid>
                </item></channel></rss>
                """;

        assertThat(WowheadNewsFeedClient.parse(xml.getBytes(StandardCharsets.UTF_8))).isEmpty();
    }
}
