package com.blackbox.valorant.service;

import com.blackbox.valorant.client.HenrikDevClient;
import com.blackbox.valorant.entity.ValorantProfileEntity;
import com.blackbox.valorant.properties.ValorantProperties;
import com.blackbox.valorant.repository.ValorantProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class ValorantRefreshService {
    private final ValorantProfileRepository repository;
    private final HenrikDevClient client;
    private final ValorantProperties properties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    public ValorantRefreshService(ValorantProfileRepository repository, HenrikDevClient client,
                                 ValorantProperties properties, Clock clock) {
        this.repository = repository;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    // Stagger collection: one due player per minute, with an hourly interval per player.
    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void refreshNextProfile() {
        if (properties.apiKey() == null || properties.apiKey().isBlank() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant dueBefore = clock.instant().minus(properties.refreshInterval());
            var next = repository.findByActiveTrueOrderByIdAsc().stream()
                    .filter(profile -> profile.getHeadshotAttemptedAt() == null
                            || !profile.getHeadshotAttemptedAt().isAfter(dueBefore))
                    .min(Comparator.comparing(ValorantProfileEntity::getHeadshotAttemptedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())));
            if (next.isEmpty()) {
                return;
            }
            var profile = next.get();
            try {
                var hits = client.headshots(profile.getRegion(), profile.getPlatform(), profile.getPuuid());
                profile.recordHeadshots(hits, clock.instant());
                repository.saveHeadshotResult(profile.getId(), hits.matches(), hits.headHits(),
                        hits.bodyHits(), hits.legHits(), profile.getHeadshotUpdatedAt());
            } catch (IllegalStateException failure) {
                String warning = failure instanceof HenrikDevClient.UnavailableException
                        ? failure.getMessage() : "Match statistics could not be verified; try again after the next refresh.";
                profile.recordHeadshotFailure(clock.instant(), warning);
                repository.saveHeadshotFailure(profile.getId(), profile.getHeadshotAttemptedAt(), warning);
                log.warn("Valorant headshot refresh failed for profile {}: {}", profile.getId(), warning);
            }
        } finally {
            running.set(false);
        }
    }
}
