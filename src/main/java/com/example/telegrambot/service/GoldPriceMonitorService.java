package com.example.telegrambot.service;

import com.example.telegrambot.clinet.GoldApiClient;
import com.example.telegrambot.entity.PriceState;
import com.example.telegrambot.repository.PriceStateRepository;
import com.example.telegrambot.repository.SubscriptionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Service
public class GoldPriceMonitorService {

    private static final String SOURCE = "GOLD_XAU";

    private final GoldApiClient client;
    private final SubscriptionRepository subs;
    private final PriceStateRepository stateRepo;
    private final GoldBotNotifier notifier;

    private final BigDecimal minChangeUsd;

    public GoldPriceMonitorService(
            GoldApiClient client,
            SubscriptionRepository subs,
            PriceStateRepository stateRepo,
            GoldBotNotifier notifier,
            @Value("${bot.gold.min-change-usd:0.50}") BigDecimal minChangeUsd
    ) {
        this.client = client;
        this.subs = subs;
        this.stateRepo = stateRepo;
        this.notifier = notifier;
        this.minChangeUsd = minChangeUsd;
    }

    public void checkOnce() {
        var p = client.fetchXau();

        PriceState st = stateRepo.findById(p.symbol())
                .orElseGet(() -> new PriceState(p.symbol(), p.price(), p.updatedAt()));

        BigDecimal diff = p.price().subtract(st.getLastPrice()).abs();

        // Always update state; only notify if big enough
        boolean shouldNotify = diff.compareTo(minChangeUsd) >= 0;

        st.setLastPrice(p.price());
        st.setUpdatedAt(p.updatedAt() != null ? p.updatedAt() : Instant.now());
        stateRepo.save(st);

        if (!shouldNotify) return;

        String text = "Gold (XAU) changed: " +
                      p.price().setScale(2, RoundingMode.HALF_UP) +
                      " USD/oz (Δ " + diff.setScale(2, RoundingMode.HALF_UP) + ")";

        for (var s : subs.findBySource(SOURCE)) {
            notifier.send(s.getChatId(), text);
        }
    }
}

