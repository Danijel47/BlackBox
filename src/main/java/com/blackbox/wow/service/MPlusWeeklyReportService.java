package com.blackbox.wow.service;

import com.blackbox.wow.helper.MPlusResetCalendar;
import com.blackbox.wow.helper.VaultSlotCalculator;
import com.blackbox.wow.helper.VaultSlotCalculator.VaultSlots;
import com.blackbox.wow.properties.MPlusProgressProperties;
import com.blackbox.wow.properties.MPlusWeeklyReportProperties;
import com.blackbox.wow.repository.MPlusProgressRepository;
import com.blackbox.wow.repository.MPlusProgressRepository.ScorePoint;
import com.blackbox.wow.repository.MPlusProgressRepository.WeeklyRun;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@Slf4j
public class MPlusWeeklyReportService {

    private static final String GREETING = "sretna srijeda kojima slave";
    private static final String UNAVAILABLE = "unavailable";
    private static final DateTimeFormatter REPORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final TrackedPlayerService trackedPlayerService;
    private final MPlusProgressRepository repository;
    private final BlackBoxBotNotifier notifier;
    private final MPlusWeeklyReportProperties properties;
    private final MPlusResetCalendar resetCalendar;
    private final Clock clock;

    public MPlusWeeklyReportService(
            TrackedPlayerService trackedPlayerService,
            MPlusProgressRepository repository,
            BlackBoxBotNotifier notifier,
            MPlusWeeklyReportProperties properties,
            MPlusProgressProperties progressProperties,
            Clock clock
    ) {
        this.trackedPlayerService = trackedPlayerService;
        this.repository = repository;
        this.notifier = notifier;
        this.properties = properties;
        this.resetCalendar = new MPlusResetCalendar(progressProperties);
        this.clock = clock;
    }

    @Scheduled(
            cron = "${wow.mplus-weekly-report.cron:0 0 9 * * WED}",
            zone = "${wow.mplus-weekly-report.zone:Europe/Zagreb}"
    )
    public void sendScheduledReport() {
        if (!properties.enabled() || properties.chatId() == 0) {
            log.debug("Weekly Mythic+ report skipped because it is disabled or its chat ID is not configured.");
            return;
        }
        try {
            if (!notifier.send(properties.chatId(), reportMessage())) {
                log.warn("Weekly Mythic+ report could not be delivered.");
            }
        } catch (RuntimeException failure) {
            log.error("Weekly Mythic+ report failed ({})", failure.getClass().getSimpleName());
        }
    }

    public String reportMessage() {
        Instant periodEnd = resetCalendar.periodStart(clock.instant());
        Instant periodStart = periodEnd.minus(Duration.ofDays(7));
        List<TrackedPlayer> players = trackedPlayerService.activePlayers();
        StringBuilder message = new StringBuilder(GREETING)
                .append("\n\n📊 Weekly profile report — ")
                .append(formatDate(periodStart))
                .append("–")
                .append(formatDate(periodEnd))
                .append('\n');
        if (players.isEmpty()) {
            return message.append("\nNo active player profiles have a selected character.").toString();
        }
        for (TrackedPlayer player : players) {
            appendProfile(message, player, periodStart, periodEnd);
        }
        return message.append("\nComparisons use snapshots around the EU weekly resets. ")
                .append("Unavailable changes need a full week of item-level history.")
                .toString();
    }

    private void appendProfile(
            StringBuilder message,
            TrackedPlayer player,
            Instant periodStart,
            Instant periodEnd
    ) {
        Optional<ScorePoint> ending = repository.latestScoreAtOrBefore(player.profileId(), periodEnd);
        if (ending.isEmpty()) {
            appendUnavailableProfile(message, player);
            return;
        }
        String season = ending.get().season();
        Optional<ScorePoint> starting = repository.latestScoreAtOrBefore(
                player.profileId(), season, periodStart, ending.get().characterName()
        );
        List<WeeklyRun> runs = repository.weeklyRuns(
                player.profileId(), season, periodStart, periodEnd, ending.get().characterName()
        );
        BigDecimal endingItemLevel = repository.latestItemLevelAtOrBefore(
                player.profileId(), ending.get().characterName(), periodEnd
        ).orElse(null);
        BigDecimal startingItemLevel = repository.latestItemLevelAtOrBefore(
                player.profileId(), ending.get().characterName(), periodStart
        ).orElse(null);
        ScorePoint end = ending.get();
        message.append("\n• ").append(player.profileName());
        if (!player.profileName().equalsIgnoreCase(end.characterName())) {
            message.append(" (").append(end.characterName()).append(')');
        }
        message.append("\n  Item level: ").append(formatValueAndChange(
                        endingItemLevel, startingItemLevel))
                .append("\n  M+ rating: ").append(formatValueAndChange(
                        end.score(), starting.map(ScorePoint::score).orElse(null)))
                .append("\n  Keys: ").append(runs.size())
                .append(" completed · ").append(runs.stream().filter(WeeklyRun::timed).count())
                .append(" timed")
                .append("\n  Highest key: ").append(formatHighestKey(runs))
                .append("\n  Vault: ").append(formatVault(runs));
    }

    private static void appendUnavailableProfile(StringBuilder message, TrackedPlayer player) {
        message.append("\n• ").append(player.profileName())
                .append(" (").append(player.name()).append(")")
                .append("\n  Weekly data: ").append(UNAVAILABLE);
    }

    private String formatDate(Instant instant) {
        LocalDate date = instant.atZone(properties.zone()).toLocalDate();
        return REPORT_DATE.format(date);
    }

    private static String formatValueAndChange(BigDecimal current, BigDecimal baseline) {
        if (current == null) {
            return UNAVAILABLE;
        }
        String value = formatDecimal(current);
        if (baseline == null) {
            return value + " (change " + UNAVAILABLE + ")";
        }
        BigDecimal change = current.subtract(baseline);
        return value + " (" + (change.signum() >= 0 ? "+" : "") + formatDecimal(change) + ")";
    }

    private static String formatDecimal(BigDecimal value) {
        DecimalFormat formatter = new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.US));
        formatter.setRoundingMode(RoundingMode.HALF_UP);
        return formatter.format(value);
    }

    private static String formatHighestKey(List<WeeklyRun> runs) {
        return runs.stream()
                .mapToInt(WeeklyRun::level)
                .max()
                .stream()
                .mapToObj(level -> "+" + level)
                .findFirst()
                .orElse("—");
    }

    private static String formatVault(List<WeeklyRun> runs) {
        VaultSlots slots = VaultSlotCalculator.calculate(runs.stream().map(WeeklyRun::level).toList());
        return formatSlot(slots.slotOne()) + " / "
                + formatSlot(slots.slotFour()) + " / "
                + formatSlot(slots.slotEight());
    }

    private static String formatSlot(Integer level) {
        return level == null ? "—" : "+" + level;
    }
}
