package com.example.telegrambot.service;

import com.example.telegrambot.entity.SeenNjuskaloAd;
import com.example.telegrambot.repository.SeenNjuskaloAdRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NjuskaloMonitorService {

    private static final Logger log = LoggerFactory.getLogger(NjuskaloMonitorService.class);
    public static final String SOURCE = "NJUSKALO_FLATS";

    private final NjuskaloScraper scraper;
    private final SeenNjuskaloAdRepository seenRepo;
    private final SubscriptionService subscriptionService;
    private final GoldBotNotifier notifier; // or your Telegram send wrapper

    @Value("${njuskalo.filter.url}")
    private String filterUrl;

    @Value("${njuskalo.filter.max-scan:40}")
    private int maxScan;

    public NjuskaloMonitorService(
            NjuskaloScraper scraper,
            SeenNjuskaloAdRepository seenRepo,
            SubscriptionService subscriptionService,
            GoldBotNotifier notifier) {
        this.scraper = scraper;
        this.seenRepo = seenRepo;
        this.subscriptionService = subscriptionService;
        this.notifier = notifier;
    }

    @Transactional
    public int checkOnceForChat(long chatId) {
        List<NjuskaloScraper.Listing> listings = scraper.fetch(filterUrl, maxScan);

        int newCount = 0;
        for (var l : listings) {
            if (!seenRepo.existsByAdId(l.id())) {
                seenRepo.save(new SeenNjuskaloAd(l.id())); // or (id,url,title) if you added fields
                newCount++;
                notifier.send(chatId, "🏠 New listing:\n" + l.title() + "\n" + l.url());
            }
        }
        return newCount;
    }


    /**
     * Save current ads without sending notifications (avoid spamming on first subscribe).
     */
    @Transactional
    public int prime() {
        List<NjuskaloScraper.Listing> listings = scraper.fetch(filterUrl, maxScan);
        int saved = 0;

        for (var l : listings) {
            if (seenRepo.existsByAdId(l.id())) {
                seenRepo.save(new SeenNjuskaloAd(l.id()));
                saved++;
            }
        }

        log.info("Njuskalo prime done: fetched={}, saved={}", listings.size(), saved);
        return saved;
    }

    /**
     * Check and notify subscribers about NEW ads only.
     */
    @Transactional
    public int checkAndNotify() {
        List<Long> chatIds = subscriptionService.findChatIdsBySource(SOURCE);
        if (chatIds.isEmpty()) {
            log.info("Njuskalo: no subscribers, skipping.");
            return 0;
        }

        List<NjuskaloScraper.Listing> listings = scraper.fetch(filterUrl, maxScan);

        int newCount = 0;
        for (var l : listings) {
            if (seenRepo.existsByAdId(l.id())) {
                seenRepo.save(new SeenNjuskaloAd(l.id()));
                newCount++;

                String msg = "New Njuškalo listing:\n" + l.title() + "\n" + l.url();
                for (Long chatId : chatIds) {
                    notifier.send(chatId, msg);
                }
            }
        }

        log.info("Njuskalo check done: fetched={}, new={}", listings.size(), newCount);
        return newCount;
    }
}
