package com.blackbox.wow.client;

import com.blackbox.wow.properties.WowTuningNewsProperties;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class WowheadNewsFeedClient {

    private static final int MAX_FEED_BYTES = 1_048_576;
    private static final int MAX_GUID_LENGTH = 512;
    private static final int MAX_TITLE_LENGTH = 512;
    private static final int MAX_URL_LENGTH = 1_024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final URI feedUri;
    private final HttpClient httpClient;

    public WowheadNewsFeedClient(WowTuningNewsProperties properties) {
        this.feedUri = properties.feedUrl();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<NewsItem> fetch() throws IOException, InterruptedException, XMLStreamException {
        HttpRequest request = HttpRequest.newBuilder(feedUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/rss+xml, application/xml;q=0.9")
                .header("User-Agent", "BlackBox-WoW-Tuning-Notifier/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Wowhead RSS returned HTTP " + response.statusCode());
            }
            byte[] xml = body.readNBytes(MAX_FEED_BYTES + 1);
            if (xml.length > MAX_FEED_BYTES) {
                throw new IOException("Wowhead RSS exceeded the one-megabyte safety limit");
            }
            return parse(xml);
        }
    }

    static List<NewsItem> parse(byte[] xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        List<NewsItem> items = new ArrayList<>();
        XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
        try {
            MutableNewsItem current = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String element = reader.getLocalName();
                    if ("item".equals(element)) {
                        current = new MutableNewsItem();
                    } else if (current != null) {
                        switch (element) {
                            case "title" -> current.title = reader.getElementText();
                            case "link" -> current.link = reader.getElementText();
                            case "category" -> current.category = reader.getElementText();
                            case "pubDate" -> current.publishedAt = parsePublishedAt(reader.getElementText());
                            case "guid" -> current.guid = reader.getElementText();
                            default -> {
                                // Ignore article bodies, images, and unrelated RSS metadata.
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "item".equals(reader.getLocalName())
                        && current != null) {
                    current.toNewsItem().ifPresent(items::add);
                    current = null;
                }
            }
        } finally {
            reader.close();
        }
        return List.copyOf(items);
    }

    private static Instant parsePublishedAt(String value) {
        try {
            return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // A malformed feed entry is excluded without failing the entire polling run.
            return null;
        }
    }

    public record NewsItem(String guid, String title, URI link, String category, Instant publishedAt) {
    }

    private static final class MutableNewsItem {
        private String guid;
        private String title;
        private String link;
        private String category;
        private Instant publishedAt;

        private Optional<NewsItem> toNewsItem() {
            if (isBlank(guid) || isBlank(title) || isBlank(link) || isBlank(category) || publishedAt == null) {
                return Optional.empty();
            }
            try {
                URI linkUri = URI.create(link.trim());
                String normalizedGuid = guid.trim();
                String normalizedTitle = WHITESPACE.matcher(title.trim()).replaceAll(" ");
                String normalizedUrl = linkUri.toString();
                if (!"https".equalsIgnoreCase(linkUri.getScheme())
                        || !isWowheadHost(linkUri.getHost())
                        || normalizedGuid.length() > MAX_GUID_LENGTH
                        || normalizedTitle.length() > MAX_TITLE_LENGTH
                        || normalizedUrl.length() > MAX_URL_LENGTH) {
                    return Optional.empty();
                }
                return Optional.of(new NewsItem(
                        normalizedGuid,
                        normalizedTitle,
                        linkUri,
                        category.trim(),
                        publishedAt
                ));
            } catch (IllegalArgumentException ignored) {
                // A malformed feed link is excluded without failing the remaining valid entries.
                return Optional.empty();
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private static boolean isWowheadHost(String host) {
            if (host == null) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return normalizedHost.equals("wowhead.com") || normalizedHost.endsWith(".wowhead.com");
        }
    }
}
