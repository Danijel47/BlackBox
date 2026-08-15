package com.example.blackbox.wow.service;

import com.example.blackbox.wow.client.WowheadNewsFeedClient;
import com.example.blackbox.wow.client.WowheadNewsFeedClient.NewsItem;
import com.example.blackbox.wow.entity.WowTuningNewsItemEntity;
import com.example.blackbox.wow.properties.WowTuningNewsProperties;
import com.example.blackbox.wow.repository.WowTuningNewsItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@Slf4j
public class WowTuningNewsNotifierService {

    private static final Pattern TUNING_SUBJECT = Pattern.compile(
            "\\b(class|classes|pve|pvp|tank|healer|dps|specialization|spec)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern(
            "d MMM uuuu, HH:mm z",
            Locale.ENGLISH
    );

    private final WowheadNewsFeedClient feedClient;
    private final WowTuningNewsItemRepository repository;
    private final RioBotNotifier notifier;
    private final WowTuningNewsProperties properties;
    private final Clock clock;
    private final ZoneId messageZone;

    @Autowired
    public WowTuningNewsNotifierService(
            WowheadNewsFeedClient feedClient,
            WowTuningNewsItemRepository repository,
            RioBotNotifier notifier,
            WowTuningNewsProperties properties,
            @Value("${wow.tuning-news.zone:Europe/Zagreb}") String zone
    ) {
        this(feedClient, repository, notifier, properties, Clock.systemUTC(), ZoneId.of(zone));
    }

    WowTuningNewsNotifierService(
            WowheadNewsFeedClient feedClient,
            WowTuningNewsItemRepository repository,
            RioBotNotifier notifier,
            WowTuningNewsProperties properties,
            Clock clock,
            ZoneId messageZone
    ) {
        this.feedClient = feedClient;
        this.repository = repository;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
        this.messageZone = messageZone;
    }

    @Scheduled(
            cron = "${wow.tuning-news.cron:0 */15 * * * *}",
            zone = "${wow.tuning-news.zone:Europe/Zagreb}"
    )
    public void checkForTuningUpdates() {
        if (!properties.enabled() || properties.chatId() == 0) {
            log.debug("WoW tuning notifier skipped because it is disabled or its chat ID is not configured.");
            return;
        }

        try {
            List<NewsItem> tuningItems = feedClient.fetch().stream()
                    .filter(WowTuningNewsNotifierService::isTuningUpdate)
                    .sorted(Comparator.comparing(NewsItem::publishedAt))
                    .toList();
            notifyUnseenItems(tuningItems);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("WoW tuning feed check was interrupted");
        } catch (IOException | XMLStreamException e) {
            log.warn("WoW tuning feed check failed ({})", rootCauseType(e));
        }
    }

    private void notifyUnseenItems(List<NewsItem> items) {
        boolean initializing = repository.count() == 0;
        Instant now = clock.instant();
        Instant initialCutoff = now.minus(properties.initialLookback());
        int sentThisRun = 0;

        for (NewsItem item : items) {
            if (repository.existsById(item.guid())) {
                continue;
            }
            if (initializing && item.publishedAt().isBefore(initialCutoff)) {
                repository.save(toEntity(item, false, now));
                continue;
            }
            if (sentThisRun >= properties.maxItemsPerRun()) {
                break;
            }
            if (!notifier.send(properties.chatId(), formatMessage(item))) {
                break;
            }
            repository.save(toEntity(item, true, now));
            sentThisRun++;
        }

        if (sentThisRun > 0) {
            log.info("Sent {} WoW tuning update notification(s)", sentThisRun);
        }
    }

    static boolean isTuningUpdate(NewsItem item) {
        if (!"live".equalsIgnoreCase(item.category())) {
            return false;
        }
        String title = item.title().toLowerCase(Locale.ROOT);
        return title.contains("tuning") && TUNING_SUBJECT.matcher(title).find();
    }

    private String formatMessage(NewsItem item) {
        return "⚖️ WoW tuning update\n\n"
                + item.title() + "\n"
                + "Published: " + MESSAGE_TIME.format(item.publishedAt().atZone(messageZone)) + "\n"
                + item.link() + "\n\n"
                + "Source: Wowhead";
    }

    private static WowTuningNewsItemEntity toEntity(NewsItem item, boolean sent, Instant now) {
        return new WowTuningNewsItemEntity(
                item.guid(),
                item.title(),
                item.link().toString(),
                item.publishedAt(),
                sent,
                now
        );
    }

    private static String rootCauseType(Throwable failure) {
        Throwable rootCause = failure;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        return rootCause.getClass().getSimpleName();
    }
}
