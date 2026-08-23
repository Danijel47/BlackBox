package com.blackbox.wow.service;

import com.blackbox.wow.entity.PlayerProfileEntity;
import com.blackbox.wow.entity.TrackedCharacterEntity;
import com.blackbox.wow.repository.PlayerProfileRepository;
import com.blackbox.wow.repository.TelegramBotUserRepository;
import com.blackbox.wow.repository.TrackedCharacterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
                "Tarren-mill",
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
                "Stormscale",
                "Unknownchar"
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().switchOwnedCharacter(telegramUserId, "stormscale", "Unknownchar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered to your profile");

        verify(characterRepository, never()).clearSelectedCharacter(7L);
    }

    @Test
    void normalizesNamesWhenAddingAProfile() {
        when(profileRepository.findByProfileNameIgnoreCase("Buco")).thenReturn(Optional.empty());
        when(profileRepository.findMaximumDisplayOrder()).thenReturn(4);
        when(profileRepository.saveAndFlush(any(PlayerProfileEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().addProfile("buco", "EU", "stormscale", "bucodh");

        ArgumentCaptor<PlayerProfileEntity> profileCaptor = ArgumentCaptor.forClass(PlayerProfileEntity.class);
        ArgumentCaptor<TrackedCharacterEntity> characterCaptor = ArgumentCaptor.forClass(TrackedCharacterEntity.class);
        verify(profileRepository).saveAndFlush(profileCaptor.capture());
        verify(characterRepository).saveAndFlush(characterCaptor.capture());
        assertThat(profileCaptor.getValue().getProfileName()).isEqualTo("Buco");
        assertThat(characterCaptor.getValue().getRealm()).isEqualTo("Stormscale");
        assertThat(characterCaptor.getValue().getCharacterName()).isEqualTo("Bucodh");
    }

    @Test
    void deletesAnUnselectedCharacterFromAProfile() {
        PlayerProfileEntity profile = profile(7L);
        TrackedCharacterEntity character = mock(TrackedCharacterEntity.class);
        when(profileRepository.findByProfileNameIgnoreCase("Buco")).thenReturn(Optional.of(profile));
        when(characterRepository.findByProfileIdAndRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCase(
                7L,
                "eu",
                "Stormscale",
                "Bucomonk"
        )).thenReturn(Optional.of(character));
        when(character.isSelected()).thenReturn(false);

        service().deleteCharacter("Buco", "stormscale", "bucomonk");

        verify(characterRepository).delete(character);
    }

    @Test
    void refusesToDeleteTheSelectedMain() {
        PlayerProfileEntity profile = profile(7L);
        TrackedCharacterEntity character = mock(TrackedCharacterEntity.class);
        when(profileRepository.findByProfileNameIgnoreCase("Buco")).thenReturn(Optional.of(profile));
        when(characterRepository.findByProfileIdAndRegionIgnoreCaseAndRealmIgnoreCaseAndCharacterNameIgnoreCase(
                7L,
                "eu",
                "Stormscale",
                "Bucodh"
        )).thenReturn(Optional.of(character));
        when(character.isSelected()).thenReturn(true);

        assertThatThrownBy(() -> service().deleteCharacter("Buco", "stormscale", "bucodh"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selected main cannot be deleted");

        verify(characterRepository, never()).delete(character);
    }

    @Test
    void disablesAnAltWithoutChangingTheSelectedMain() {
        PlayerProfileEntity profile = profile(7L);
        TrackedCharacterEntity alt = mock(TrackedCharacterEntity.class);
        when(characterRepository.findById(22L)).thenReturn(Optional.of(alt));
        when(alt.getProfile()).thenReturn(profile);
        when(alt.isSelected()).thenReturn(false);

        service().setCharacterActive(7L, 22L, false);

        verify(alt).setActive(false);
        verify(characterRepository, never()).clearSelectedCharacter(7L);
    }

    @Test
    void refusesToDisableTheSelectedMain() {
        PlayerProfileEntity profile = profile(7L);
        TrackedCharacterEntity main = mock(TrackedCharacterEntity.class);
        when(characterRepository.findById(21L)).thenReturn(Optional.of(main));
        when(main.getProfile()).thenReturn(profile);
        when(main.isSelected()).thenReturn(true);

        assertThatThrownBy(() -> service().setCharacterActive(7L, 21L, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The selected main cannot be disabled.");

        verify(main, never()).setActive(false);
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
