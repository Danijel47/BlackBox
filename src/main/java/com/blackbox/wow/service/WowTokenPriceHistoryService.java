package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.blizzard.BlizzardAuctionService.WowTokenPriceSnapshot;
import com.blackbox.wow.entity.WowTokenPriceSnapshotEntity;
import com.blackbox.wow.properties.WowTokenHistoryProperties;
import com.blackbox.wow.repository.WowTokenPriceSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class WowTokenPriceHistoryService {

    private static final String TOKEN_REGION = "EU";
    public static final int MINIMUM_SAMPLES_PER_HOUR = 3;

    private final BlizzardAuctionService auctionService;
    private final WowTokenPriceSnapshotRepository repository;
    private final WowTokenHistoryProperties properties;

    public WowTokenPriceHistoryService(
            BlizzardAuctionService auctionService,
            WowTokenPriceSnapshotRepository repository,
            WowTokenHistoryProperties properties
    ) {
        this.auctionService = auctionService;
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(
            cron = "${wow.token-history.cron:0 0 * * * *}",
            zone = "${wow.token-history.zone:UTC}"
    )
    public void captureHourlyPrice() {
        capturePriceAt(Instant.now());
    }

    public Optional<TokenPricePoint> lowestPriceSince(Instant capturedAt) {
        return repository
                .findFirstByRegionAndCapturedAtGreaterThanEqualOrderByPriceCopperAscCapturedAtAsc(
                        TOKEN_REGION,
                        capturedAt
                )
                .map(WowTokenPriceHistoryService::toPricePoint);
    }

    public Optional<TokenPricePoint> highestPriceSince(Instant capturedAt) {
        return repository
                .findFirstByRegionAndCapturedAtGreaterThanEqualOrderByPriceCopperDescCapturedAtAsc(
                        TOKEN_REGION,
                        capturedAt
                )
                .map(WowTokenPriceHistoryService::toPricePoint);
    }

    public Optional<TokenTradingHours> bestTradingHoursSince(Instant capturedAt, ZoneId zone) {
        List<WowTokenPriceSnapshotEntity> snapshots =
                repository.findByRegionAndCapturedAtGreaterThanEqualOrderByCapturedAtAsc(
                        TOKEN_REGION,
                        capturedAt
                );
        BigInteger[] totals = new BigInteger[24];
        Arrays.fill(totals, BigInteger.ZERO);
        int[] sampleCounts = new int[24];
        for (WowTokenPriceSnapshotEntity snapshot : snapshots) {
            int hour = snapshot.getCapturedAt().atZone(zone).getHour();
            totals[hour] = totals[hour].add(BigInteger.valueOf(snapshot.getPriceCopper()));
            sampleCounts[hour]++;
        }

        List<TokenHourAverage> eligibleHours = java.util.stream.IntStream.range(0, 24)
                .filter(hour -> sampleCounts[hour] >= MINIMUM_SAMPLES_PER_HOUR)
                .mapToObj(hour -> new TokenHourAverage(
                        hour,
                        averagePrice(totals[hour], sampleCounts[hour]),
                        sampleCounts[hour]
                ))
                .toList();
        if (eligibleHours.isEmpty()) {
            return Optional.empty();
        }

        Comparator<TokenHourAverage> byPriceThenHour = Comparator
                .comparingLong(TokenHourAverage::averageCopper)
                .thenComparingInt(TokenHourAverage::hour);
        TokenHourAverage bestBuyHour = eligibleHours.stream().min(byPriceThenHour).orElseThrow();
        TokenHourAverage bestSellHour = eligibleHours.stream().max(
                Comparator.comparingLong(TokenHourAverage::averageCopper)
                        .thenComparing(Comparator.comparingInt(TokenHourAverage::hour).reversed())
        ).orElseThrow();
        return Optional.of(new TokenTradingHours(bestBuyHour, bestSellHour));
    }

    void capturePriceAt(Instant observedAt) {
        if (!properties.enabled()) {
            return;
        }

        Instant capturedAt = observedAt.truncatedTo(ChronoUnit.HOURS);
        try {
            if (repository.existsByRegionAndCapturedAt(TOKEN_REGION, capturedAt)) {
                return;
            }
            WowTokenPriceSnapshot snapshot = auctionService.getWowTokenPriceSnapshot();
            if (!snapshot.price().available()) {
                log.warn("WoW Token hourly price was unavailable for region {}", TOKEN_REGION);
                return;
            }
            repository.saveAndFlush(new WowTokenPriceSnapshotEntity(
                    TOKEN_REGION,
                    snapshot.price().avgCopper(),
                    snapshot.sourceUpdatedAt(),
                    capturedAt
            ));
        } catch (RuntimeException e) {
            log.warn("WoW Token hourly price capture failed ({})", e.getClass().getSimpleName());
        }
    }

    private static TokenPricePoint toPricePoint(WowTokenPriceSnapshotEntity snapshot) {
        Instant priceAt = snapshot.getSourceUpdatedAt() == null
                ? snapshot.getCapturedAt()
                : snapshot.getSourceUpdatedAt();
        return new TokenPricePoint(snapshot.getPriceCopper(), priceAt);
    }

    private static long averagePrice(BigInteger total, int samples) {
        return new BigDecimal(total)
                .divide(BigDecimal.valueOf(samples), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    public record TokenPricePoint(long priceCopper, Instant priceAt) {
    }

    public record TokenHourAverage(int hour, long averageCopper, int sampleCount) {
    }

    public record TokenTradingHours(TokenHourAverage buy, TokenHourAverage sell) {
    }
}
