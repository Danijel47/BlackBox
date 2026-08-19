package com.blackbox.wow.service;

import com.blackbox.wow.service.TrackedPlayerService.PlayerProfile;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class MPlusPlayerResolver {

    private final TrackedPlayerService trackedPlayerService;

    public MPlusPlayerResolver(TrackedPlayerService trackedPlayerService) {
        this.trackedPlayerService = trackedPlayerService;
    }

    public Resolution resolveSelfOrNamed(String argument, Long telegramUserId) {
        String profileName = argument == null ? "" : argument.trim();
        if (profileName.isBlank() || "me".equalsIgnoreCase(profileName)) {
            return resolveOwnProfile(telegramUserId);
        }
        String requestedProfile = profileName;
        return trackedPlayerService.activePlayers().stream()
                .filter(player -> player.profileName().equalsIgnoreCase(requestedProfile))
                .findFirst()
                .map(Resolution::found)
                .orElseGet(() -> Resolution.error("Active player profile not found: " + requestedProfile));
    }

    private Resolution resolveOwnProfile(Long telegramUserId) {
        if (telegramUserId == null) {
            return Resolution.error("Telegram user information is unavailable for this message.");
        }
        Optional<PlayerProfile> profile = trackedPlayerService.profileForTelegramUser(telegramUserId);
        if (profile.isEmpty()) {
            return Resolution.error(
                    "No player profile is linked to your Telegram account. Ask the bot admin to link it."
            );
        }
        String profileName = profile.get().name();
        return trackedPlayerService.activePlayers().stream()
                .filter(player -> player.profileName().equalsIgnoreCase(profileName))
                .findFirst()
                .map(Resolution::found)
                .orElseGet(() -> Resolution.error("Active player profile not found: " + profileName));
    }

    public record Resolution(TrackedPlayer player, String error) {
        public static Resolution found(TrackedPlayer player) {
            return new Resolution(player, null);
        }

        public static Resolution error(String error) {
            return new Resolution(null, error);
        }
    }
}
