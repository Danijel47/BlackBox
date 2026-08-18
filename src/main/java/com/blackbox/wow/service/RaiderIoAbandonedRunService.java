package com.blackbox.wow.service;

import com.blackbox.wow.entity.RaiderIoAbandonedRunDungeonStatEntity;
import com.blackbox.wow.entity.RaiderIoAbandonedRunImportEntity;
import com.blackbox.wow.repository.RaiderIoAbandonedRunDungeonStatRepository;
import com.blackbox.wow.repository.RaiderIoAbandonedRunImportRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class RaiderIoAbandonedRunService {

    private static final String EXPECTED_STAT_TYPE = "mythic-plus-abandoned-runs";

    private final JsonMapper json;
    private final RaiderIoAbandonedRunImportRepository importRepository;
    private final RaiderIoAbandonedRunDungeonStatRepository dungeonStatRepository;

    public RaiderIoAbandonedRunService(
            JsonMapper json,
            RaiderIoAbandonedRunImportRepository importRepository,
            RaiderIoAbandonedRunDungeonStatRepository dungeonStatRepository
    ) {
        this.json = json;
        this.importRepository = importRepository;
        this.dungeonStatRepository = dungeonStatRepository;
    }

    @Transactional
    public boolean importSnapshot(Path path) {
        AbandonedRunSnapshot snapshot;
        try {
            snapshot = json.readValue(path.toFile(), AbandonedRunSnapshot.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse " + path, e);
        }

        validate(snapshot, path);
        SnapshotSource source = parseSource(snapshot.sourceUrl());
        Instant generatedAt = Instant.parse(snapshot.generatedAt());
        if (importRepository
                .findByRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCaseAndSeasonSlugAndGeneratedAt(
                        source.region(),
                        source.realm(),
                        source.characterName(),
                        source.season(),
                        generatedAt
                )
                .isPresent()) {
            return false;
        }

        var imported = importRepository.saveAndFlush(new RaiderIoAbandonedRunImportEntity(
                source.region(),
                source.realm(),
                source.characterName(),
                source.season(),
                source.scope(),
                source.groupingDimension(),
                snapshot.statType(),
                generatedAt,
                snapshot.sourceUrl(),
                snapshot.rowCount()
        ));

        List<RaiderIoAbandonedRunDungeonStatEntity> rows = snapshot.rows().stream()
                .map(row -> new RaiderIoAbandonedRunDungeonStatEntity(
                        imported.getId(),
                        row.dungeon(),
                        row.abandonedRuns(),
                        row.liveTrackedRuns(),
                        row.abandonPercent()
                ))
                .toList();
        dungeonStatRepository.saveAll(rows);
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<AbandonedRunSummary> findLatest(
            String region,
            String realm,
            String characterName,
            String season
    ) {
        String normalizedRealm = realm.trim().replace(' ', '-');
        return importRepository
                .findFirstByRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCaseAndSeasonSlugOrderByGeneratedAtDesc(
                        region,
                        normalizedRealm,
                        characterName,
                        season
                )
                .map(imported -> toSummary(imported, dungeonStatRepository
                        .findByImportIdOrderByAbandonedRunsDesc(imported.getId())));
    }

    private static AbandonedRunSummary toSummary(
            RaiderIoAbandonedRunImportEntity imported,
            List<RaiderIoAbandonedRunDungeonStatEntity> rows
    ) {
        int abandonedRuns = rows.stream()
                .mapToInt(RaiderIoAbandonedRunDungeonStatEntity::getAbandonedRuns)
                .sum();
        int liveTrackedRuns = rows.stream()
                .mapToInt(RaiderIoAbandonedRunDungeonStatEntity::getLiveTrackedRuns)
                .sum();
        RaiderIoAbandonedRunDungeonStatEntity mostAbandoned = rows.isEmpty() ? null : rows.getFirst();
        return new AbandonedRunSummary(
                imported.getCharacterName(),
                abandonedRuns,
                liveTrackedRuns,
                mostAbandoned == null ? null : mostAbandoned.getDungeon(),
                mostAbandoned == null ? 0 : mostAbandoned.getAbandonedRuns(),
                imported.getGeneratedAt()
        );
    }

    private static void validate(AbandonedRunSnapshot snapshot, Path path) {
        if (snapshot == null || !EXPECTED_STAT_TYPE.equals(snapshot.statType())) {
            throw new IllegalArgumentException("Unsupported Raider.IO statistic in " + path);
        }
        if (snapshot.generatedAt() == null || snapshot.sourceUrl() == null || snapshot.rows() == null) {
            throw new IllegalArgumentException("Missing snapshot metadata in " + path);
        }
        if (snapshot.rowCount() != snapshot.rows().size()) {
            throw new IllegalArgumentException("rowCount does not match rows in " + path);
        }
        for (AbandonedRunRow row : snapshot.rows()) {
            if (row.dungeon() == null || row.dungeon().isBlank()
                || row.abandonedRuns() < 0
                || row.liveTrackedRuns() < row.abandonedRuns()
                || row.abandonPercent() == null
                || row.abandonPercent().compareTo(BigDecimal.ZERO) < 0
                || row.abandonPercent().compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("Invalid dungeon row in " + path);
            }
        }
    }

    private static SnapshotSource parseSource(String sourceUrl) {
        URI uri = URI.create(sourceUrl);
        List<String> segments = Arrays.stream(uri.getPath().split("/"))
                .filter(segment -> !segment.isBlank())
                .map(segment -> URLDecoder.decode(segment, StandardCharsets.UTF_8))
                .toList();
        if (segments.size() < 5 || !"characters".equals(segments.get(0))) {
            throw new IllegalArgumentException("Unexpected Raider.IO source URL: " + sourceUrl);
        }

        Map<String, String> query = Arrays.stream(Optional.ofNullable(uri.getRawQuery()).orElse("").split("&"))
                .filter(value -> value.contains("="))
                .map(value -> value.split("=", 2))
                .collect(Collectors.toMap(
                        value -> URLDecoder.decode(value[0], StandardCharsets.UTF_8),
                        value -> URLDecoder.decode(value[1], StandardCharsets.UTF_8),
                        (left, right) -> right
                ));
        String season = query.getOrDefault("statSeason", query.get("season"));
        if (season == null || season.isBlank()) {
            throw new IllegalArgumentException("Missing statSeason in Raider.IO source URL: " + sourceUrl);
        }
        return new SnapshotSource(
                segments.get(1),
                segments.get(2),
                segments.get(3),
                season,
                query.getOrDefault("scope", "season"),
                query.getOrDefault("groupBy", "dungeon")
        );
    }

    private record AbandonedRunSnapshot(
            String generatedAt,
            String sourceUrl,
            String statType,
            int rowCount,
            List<AbandonedRunRow> rows
    ) {
    }

    private record AbandonedRunRow(
            String dungeon,
            int abandonedRuns,
            int liveTrackedRuns,
            BigDecimal abandonPercent
    ) {
    }

    private record SnapshotSource(
            String region,
            String realm,
            String characterName,
            String season,
            String scope,
            String groupingDimension
    ) {
    }

    public record AbandonedRunSummary(
            String characterName,
            int abandonedRuns,
            int liveTrackedRuns,
            String mostAbandonedDungeon,
            int mostAbandonedDungeonRuns,
            Instant generatedAt
    ) {
    }
}
