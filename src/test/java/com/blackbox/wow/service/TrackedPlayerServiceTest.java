package com.blackbox.wow.service;

import com.blackbox.wow.entity.PlayerProfileEntity;
import com.blackbox.wow.entity.TrackedCharacterEntity;
import com.blackbox.wow.repository.PlayerProfileRepository;
import com.blackbox.wow.repository.TelegramBotUserRepository;
import com.blackbox.wow.repository.TrackedCharacterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackedPlayerServiceTest {

    @Mock private PlayerProfileRepository profileRepository;
    @Mock private TrackedCharacterRepository characterRepository;
    @Mock private TelegramBotUserRepository telegramUserRepository;

    @Test
    void linksARegisteredTelegramUserToAProfile() {
        long telegramUserId = 123456789L;
        PlayerProfileEntity profile = mock(PlayerProfileEntity.class);
        when(telegramUserRepository.existsById(telegramUserId)).thenReturn(true);
        when(profileRepository.findByProfileNameIgnoreCase("Alice")).thenReturn(Optional.of(profile));
        when(profileRepository.findByTelegramUserId(telegramUserId)).thenReturn(Optional.empty());

        service().linkProfile(telegramUserId, "Alice");

        verify(profile).assignTelegramUser(telegramUserId);
    }

    @Test
    void preventsOneTelegramUserFromOwningMultipleProfiles() {
        long telegramUserId = 123456789L;
        PlayerProfileEntity requestedProfile = profile(7L);
        PlayerProfileEntity linkedProfile = profile(8L);
        when(telegramUserRepository.existsById(telegramUserId)).thenReturn(true);
        when(profileRepository.findByProfileNameIgnoreCase("Alice")).thenReturn(Optional.of(requestedProfile));
        when(profileRepository.findByTelegramUserId(telegramUserId)).thenReturn(Optional.of(linkedProfile));

        assertThatThrownBy(() -> service().linkProfile(telegramUserId, "Alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That Telegram user already has a player profile.");

        verify(requestedProfile, never()).assignTelegramUser(telegramUserId);
    }

    @Test
    void ownerCanSelectACharacterAlreadyRegisteredToTheirProfile() {
        long telegramUserId = 123456789L;
        PlayerProfileEntity profile = profile(7L);
        TrackedCharacterEntity character = mock(TrackedCharacterEntity.class);
        when(profileRepository.findByTelegramUserId(telegramUserId)).thenReturn(Optional.of(profile));
        when(characterRepository.findByProfileIdAndRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCase(
                7L,
                "eu",
                "tarren-mill",
                "Alicemage"
        )).thenReturn(Optional.of(character));

        service().switchOwnedCharacter(telegramUserId, "Tarren-Mill", "Alicemage");

        verify(characterRepository).clearSelectedCharacter(7L);
        verify(character).select();
        verify(characterRepository).save(character);
    }

    @Test
    void ownerCannotSelectACharacterOutsideTheirProfile() {
        long telegramUserId = 123456789L;
        PlayerProfileEntity profile = profile(7L);
        when(profileRepository.findByTelegramUserId(telegramUserId)).thenReturn(Optional.of(profile));
        when(characterRepository.findByProfileIdAndRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCase(
                7L,
                "eu",
                "stormscale",
                "Unknownchar"
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().switchOwnedCharacter(telegramUserId, "stormscale", "Unknownchar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered to your profile");

        verify(characterRepository, never()).clearSelectedCharacter(7L);
    }

    private TrackedPlayerService service() {
        return new TrackedPlayerService(profileRepository, characterRepository, telegramUserRepository);
    }

    private static PlayerProfileEntity profile(long id) {
        PlayerProfileEntity profile = mock(PlayerProfileEntity.class);
        when(profile.getId()).thenReturn(id);
        return profile;
    }
}
