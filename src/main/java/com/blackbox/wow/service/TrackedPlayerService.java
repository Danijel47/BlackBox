package com.blackbox.wow.service;

import com.blackbox.wow.entity.PlayerProfileEntity;
import com.blackbox.wow.entity.TrackedCharacterEntity;
import com.blackbox.wow.repository.PlayerProfileRepository;
import com.blackbox.wow.repository.TelegramBotUserRepository;
import com.blackbox.wow.repository.TrackedCharacterRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class TrackedPlayerService {

    private final PlayerProfileRepository profileRepository;
    private final TrackedCharacterRepository characterRepository;
    private final TelegramBotUserRepository telegramUserRepository;

    public TrackedPlayerService(
            PlayerProfileRepository profileRepository,
            TrackedCharacterRepository characterRepository,
            TelegramBotUserRepository telegramUserRepository
    ) {
        this.profileRepository = profileRepository;
        this.characterRepository = characterRepository;
        this.telegramUserRepository = telegramUserRepository;
    }

    @Transactional(readOnly = true)
    public List<TrackedPlayer> seasonRecapPlayers() {
        return selectedPlayers(
                profileRepository.findByActiveTrueAndSeasonRecapEnabledTrueOrderByDisplayOrderAscIdAsc()
        );
    }

    @Transactional(readOnly = true)
    public List<TrackedPlayer> vaultWatchPlayers() {
        return selectedPlayers(
                profileRepository.findByActiveTrueAndVaultWatchEnabledTrueOrderByDisplayOrderAscIdAsc()
        );
    }

    @Transactional(readOnly = true)
    public List<TrackedPlayer> titleWatchPlayers() {
        return selectedPlayers(
                profileRepository.findByActiveTrueAndTitleWatchEnabledTrueOrderByDisplayOrderAscIdAsc()
        );
    }

    @Transactional(readOnly = true)
    public List<TrackedPlayer> activePlayers() {
        return selectedPlayers(profileRepository.findByActiveTrueOrderByDisplayOrderAscIdAsc());
    }

    @Transactional(readOnly = true)
    public Optional<TrackedPlayer> titleZeroPointOneWatchPlayer() {
        return profileRepository
                .findByActiveTrueAndTitleZeroPointOneWatchEnabledTrueOrderByDisplayOrderAscIdAsc()
                .stream()
                .map(this::selectedPlayer)
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Transactional(readOnly = true)
    public List<PlayerProfile> profiles() {
        return profileRepository.findAllByOrderByDisplayOrderAscIdAsc().stream()
                .map(this::toProfile)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<PlayerProfile> profileForTelegramUser(long telegramUserId) {
        return profileRepository.findByTelegramUserId(telegramUserId).map(this::toProfile);
    }

    @Transactional
    public void addProfile(String profileName, String region, String realm, String characterName) {
        validateProfileName(profileName);
        validateCharacter(region, realm, characterName);
        String normalizedProfileName = capitalizeFirst(profileName);
        String normalizedRealm = normalizeRealm(realm);
        String normalizedCharacterName = capitalizeFirst(characterName);
        if (profileRepository.findByProfileNameIgnoreCase(normalizedProfileName).isPresent()) {
            throw new IllegalArgumentException("Profile already exists: " + normalizedProfileName);
        }

        try {
            PlayerProfileEntity profile = profileRepository.saveAndFlush(
                    new PlayerProfileEntity(normalizedProfileName, profileRepository.findMaximumDisplayOrder() + 1)
            );
            characterRepository.saveAndFlush(new TrackedCharacterEntity(
                    profile,
                    normalizeRegion(region),
                    normalizedRealm,
                    normalizedCharacterName,
                    true
            ));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("That profile or character already exists.", e);
        }
    }

    @Transactional
    public void switchCharacter(String profileName, String region, String realm, String characterName) {
        validateCharacter(region, realm, characterName);
        PlayerProfileEntity profile = requireProfile(profileName);
        String normalizedRealm = normalizeRealm(realm);
        String normalizedCharacterName = capitalizeFirst(characterName);

        TrackedCharacterEntity character = characterRepository
                .findByProfileIdAndRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCase(
                        profile.getId(),
                        normalizeRegion(region),
                        normalizedRealm,
                        normalizedCharacterName
                )
                .orElse(null);

        characterRepository.clearSelectedCharacter(profile.getId());
        if (character == null) {
            character = new TrackedCharacterEntity(
                    profile,
                    normalizeRegion(region),
                    normalizedRealm,
                    normalizedCharacterName,
                    true
            );
        } else {
            character.select();
        }
        characterRepository.save(character);
    }

    @Transactional
    public void addCharacter(String profileName, String realm, String characterName) {
        validateCharacter("eu", realm, characterName);
        PlayerProfileEntity profile = requireProfile(profileName);
        String normalizedRealm = normalizeRealm(realm);
        String normalizedCharacterName = capitalizeFirst(characterName);
        boolean alreadyRegistered = characterRepository
                .findByProfileIdAndRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCase(
                        profile.getId(),
                        "eu",
                        normalizedRealm,
                        normalizedCharacterName
                )
                .isPresent();
        if (alreadyRegistered) {
            throw new IllegalArgumentException("Character is already registered to this profile.");
        }

        try {
            characterRepository.saveAndFlush(new TrackedCharacterEntity(
                    profile,
                    "eu",
                    normalizedRealm,
                    normalizedCharacterName,
                    false
            ));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Character could not be added because it already exists.", e);
        }
    }

    @Transactional
    public void switchOwnedCharacter(long telegramUserId, String realm, String characterName) {
        validateCharacter("eu", realm, characterName);
        PlayerProfileEntity profile = profileRepository.findByTelegramUserId(telegramUserId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No player profile is linked to your Telegram account."
                ));
        TrackedCharacterEntity character = characterRepository
                .findByProfileIdAndRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCase(
                        profile.getId(),
                        "eu",
                        normalizeRealm(realm),
                        capitalizeFirst(characterName)
                )
                .orElseThrow(() -> new IllegalArgumentException(
                        "That character is not registered to your profile. Ask the bot admin to add it."
                ));

        characterRepository.clearSelectedCharacter(profile.getId());
        character.select();
        characterRepository.save(character);
    }

    @Transactional
    public void deleteCharacter(String profileName, String realm, String characterName) {
        validateCharacter("eu", realm, characterName);
        PlayerProfileEntity profile = requireProfile(profileName);
        TrackedCharacterEntity character = characterRepository
                .findByProfileIdAndRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCase(
                        profile.getId(),
                        "eu",
                        normalizeRealm(realm),
                        capitalizeFirst(characterName)
                )
                .orElseThrow(() -> new IllegalArgumentException(
                        "Character is not registered to profile " + profile.getProfileName() + "."
                ));
        if (character.isSelected()) {
            throw new IllegalArgumentException(
                    "The selected main cannot be deleted. Use /profile_switch to select another character first."
            );
        }
        characterRepository.delete(character);
    }

    @Transactional
    public void linkProfile(long telegramUserId, String profileName) {
        if (telegramUserId <= 0 || !telegramUserRepository.existsById(telegramUserId)) {
            throw new IllegalArgumentException("Register the Telegram user with /user_add first.");
        }
        PlayerProfileEntity profile = requireProfile(profileName);
        Optional<PlayerProfileEntity> linkedProfile = profileRepository.findByTelegramUserId(telegramUserId);
        if (linkedProfile.isPresent() && !linkedProfile.get().getId().equals(profile.getId())) {
            throw new IllegalArgumentException("That Telegram user already has a player profile.");
        }
        try {
            profile.assignTelegramUser(telegramUserId);
            profileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("That Telegram user or profile is already linked.", e);
        }
    }

    @Transactional
    public void unlinkProfile(String profileName) {
        requireProfile(profileName).clearTelegramUser();
    }

    @Transactional
    public void setProfileActive(String profileName, boolean active) {
        PlayerProfileEntity profile = requireProfile(profileName);
        profile.setActive(active);
    }

    @Transactional
    public void setProfileActive(long profileId, boolean active) {
        PlayerProfileEntity profile = profileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found."));
        profile.setActive(active);
    }

    private PlayerProfileEntity requireProfile(String profileName) {
        return profileRepository.findByProfileNameIgnoreCase(profileName)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + profileName));
    }

    private List<TrackedPlayer> selectedPlayers(List<PlayerProfileEntity> profiles) {
        return profiles.stream()
                .map(this::selectedPlayer)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<TrackedPlayer> selectedPlayer(PlayerProfileEntity profile) {
        return characterRepository.findByProfileIdAndSelectedTrueAndActiveTrue(profile.getId())
                .map(character -> new TrackedPlayer(
                        profile.getId(),
                        profile.getProfileName(),
                        character.getRegion(),
                        character.getRealm(),
                        character.getCharacterName()
                ));
    }

    private PlayerProfile toProfile(PlayerProfileEntity profile) {
        List<ProfileCharacter> characters = characterRepository
                .findByProfileIdOrderBySelectedDescIdAsc(profile.getId())
                .stream()
                .map(character -> new ProfileCharacter(
                        character.getRegion(),
                        character.getRealm(),
                        character.getCharacterName(),
                        character.isSelected(),
                        character.isActive()
                ))
                .toList();
        return new PlayerProfile(
                profile.getId(),
                profile.getProfileName(),
                profile.getTelegramUserId(),
                profile.isActive(),
                characters
        );
    }

    private static void validateProfileName(String profileName) {
        if (profileName == null || !profileName.matches("[\\p{L}\\p{N}_-]{1,64}")) {
            throw new IllegalArgumentException("Profile name must be 1-64 letters, numbers, _ or -.");
        }
    }

    private static void validateCharacter(String region, String realm, String characterName) {
        if (region == null || !region.matches("[A-Za-z]{2,8}")) {
            throw new IllegalArgumentException("Invalid region. Example: eu");
        }
        if (realm == null || !realm.matches("[\\p{L}\\p{N}'-]{1,128}")) {
            throw new IllegalArgumentException("Invalid realm. Use a hyphen instead of spaces.");
        }
        if (characterName == null || !characterName.matches("[\\p{L}'-]{1,64}")) {
            throw new IllegalArgumentException("Invalid character name.");
        }
    }

    private static String normalizeRegion(String region) {
        return region.toLowerCase(Locale.ROOT);
    }

    private static String normalizeRealm(String realm) {
        return capitalizeFirst(realm.replace(' ', '-'));
    }

    private static String capitalizeFirst(String value) {
        String lowercaseValue = value.toLowerCase(Locale.ROOT);
        int firstCodePoint = lowercaseValue.codePointAt(0);
        int firstCodePointLength = Character.charCount(firstCodePoint);
        return new StringBuilder(lowercaseValue.length())
                .appendCodePoint(Character.toUpperCase(firstCodePoint))
                .append(lowercaseValue.substring(firstCodePointLength))
                .toString();
    }

    public record TrackedPlayer(long profileId, String profileName, String region, String realm, String name) {
    }

    public record PlayerProfile(
            long id,
            String name,
            Long telegramUserId,
            boolean active,
            List<ProfileCharacter> characters
    ) {
    }

    public record ProfileCharacter(
            String region,
            String realm,
            String name,
            boolean selected,
            boolean active
    ) {
    }
}
