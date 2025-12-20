package com.example.telegrambot.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NjuskaloScraper {

    private static final Pattern OGLAS_ID = Pattern.compile("-oglas-(\\d+)(?:\\b|$)");

    private final RestClient http;

    public NjuskaloScraper(@Qualifier("njuskaloRestClient") RestClient http) {
        this.http = http;
    }

    public List<Listing> fetch(String url, int maxScan) {
        String html = http.get().uri(url).retrieve().body(String.class);
        if (html == null || html.isBlank()) return List.of();

        Document doc = Jsoup.parse(html, url);

        Map<Long, Listing> unique = new LinkedHashMap<>();

        for (Element a : doc.select("a[href*=-oglas-]")) {
            String href = a.attr("abs:href");
            Matcher m = OGLAS_ID.matcher(href);
            if (!m.find()) continue;

            long id = Long.parseLong(m.group(1));
            String title = a.text().trim();
            if (title.isBlank()) title = "(no title)";

            unique.putIfAbsent(id, new Listing(id, href, title));

            // stop when we reach maxScan unique listings
            if (unique.size() >= maxScan) break;
        }

        return new ArrayList<>(unique.values());
    }


    public record Listing(long id, String url, String title) {}
}
