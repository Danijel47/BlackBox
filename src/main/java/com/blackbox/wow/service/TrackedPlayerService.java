package com.blackbox.wow.service;

import com.blackbox.wow.entity.PlayerProfileEntity;
import com.blackbox.wow.entity.TrackedCharacterEntity;
import com.blackbox.wow.repository.PlayerProfileRepository;
import com.blackbox.wow.repository.TrackedCharacterRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class TrackedPlayerService {

    private final PlayerProfileRepository profileRepository;
    private final TrackedCharacterRepository characterRepository;

    public TrackedPlayerService(
            PlayerProfileRepository profileRepository,
            TrackedCharacterRepository characterRepository
    ) {
        this.profileRepository = profileRepository;
        this.characterRepository = characterRepository;
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
                .map(profile -> new PlayerProfile(
                        profile.getProfileName(),
                        profile.isActive(),
                        characterRepository.findByProfileIdOrderBySelectedDescIdAsc(profile.getId()).stream()
                                .map(character -> new ProfileCharacter(
                                        character.getRegion(),
                                        character.getRealm(),
                                        character.getCharacterName(),
                                        character.isSelected(),
                                        character.isActive()
                                ))
                                .toList()
                ))
                .toList();
    }

    @Transactional
    public void addProfile(String profileName, String region, String realm, String characterName) {
        validateProfileName(profileName);
        validateCharacter(region, realm, characterName);
        if (profileRepository.findByProfileNameIgnoreCase(profileName).isPresent()) {
            throw new IllegalArgumentException("Profile already exists: " + profileName);
        }

        try {
            PlayerProfileEntity profile = profileRepository.saveAndFlush(
                    new PlayerProfileEntity(profileName, profileRepository.findMaximumDisplayOrder() + 1)
            );
            characterRepository.save(new TrackedCharacterEntity(
                    profile,
                    normalizeRegion(region),
                    normalizeRealm(realm),
                    characterName,
                    true
            ));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("That profile or character already exists.");
        }
    }

    @Transactional
    public void switchCharacter(String profileName, String region, String realm, String characterName) {
        validateCharacter(region, realm, characterName);
        PlayerProfileEntity profile = requireProfile(profileName);

        TrackedCharacterEntity character = characterRepository
                .findByProfileIdAndRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCase(
                        profile.getId(),
                        normalizeRegion(region),
                        normalizeRealm(realm),
                        characterName
                )
                .orElse(null);

        characterRepository.clearSelectedCharacter(profile.getId());
        if (character == null) {
            character = new TrackedCharacterEntity(
                    profile,
                    normalizeRegion(region),
                    normalizeRealm(realm),
                    characterName,
                    true
            );
        } else {
            character.select();
        }
        characterRepository.save(character);
    }

    @Transactional
    public void setProfileActive(String profileName, boolean active) {
        PlayerProfileEntity profile = requireProfile(profileName);
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
        return region.toLowerCase();
    }

    private static String normalizeRealm(String realm) {
        return realm.toLowerCase().replace(' ', '-');
    }

    public record TrackedPlayer(long profileId, String profileName, String region, String realm, String name) {
    }

    public record PlayerProfile(String name, boolean active, List<ProfileCharacter> characters) {
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
