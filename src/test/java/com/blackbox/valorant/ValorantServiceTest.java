package com.blackbox.valorant;

import com.blackbox.valorant.client.HenrikDevClient;
import com.blackbox.valorant.entity.ValorantProfileEntity;
import com.blackbox.valorant.repository.ValorantProfileRepository;
import com.blackbox.valorant.service.ValorantService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ValorantServiceTest {
    private final ValorantProfileRepository repository = mock(ValorantProfileRepository.class);
    private final HenrikDevClient client = mock(HenrikDevClient.class);
    private final ValorantService service = new ValorantService(repository, client);

    @Test
    void addingAProfileResolvesTheSingleRiotAccountAndStoresIt() {
        when(client.account("Alex Player", "EU1")).thenReturn(
                new HenrikDevClient.Account("puuid", "Alex Player", "EU1", "eu", List.of("pc")));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var profile = service.addProfile(" Alex ", "Alex Player#EU1", "PC");

        assertThat(profile.getProfileName()).isEqualTo("Alex");
        assertThat(profile.getPuuid()).isEqualTo("puuid");
        assertThat(profile.getRiotId()).isEqualTo("Alex Player#EU1");
        assertThat(profile.getPlatform()).isEqualTo("pc");
        assertThat(profile.isActive()).isTrue();
    }

    @Test
    void malformedRiotIdsAreRejectedBeforeLookingUpAnAccount() {
        assertThatThrownBy(() -> service.addProfile("Alex", "MissingTag", "pc"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Name#Tag");
    }

    @Test
    void duplicateAccountsProduceAnActionableMessage() {
        when(client.account("Alex", "EU1")).thenReturn(
                new HenrikDevClient.Account("puuid", "Alex", "EU1", "eu", List.of("pc")));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("constraint"));

        assertThatThrownBy(() -> service.addProfile("Alex", "Alex#EU1", "pc"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already registered");
    }

    @Test
    void disablingAnAccountStopsItsInclusionWithoutDeletingTheProfile() {
        var profile = profile("Alex", "id");
        when(repository.findByProfileNameIgnoreCase("Alex")).thenReturn(Optional.of(profile));

        service.setActive("Alex", false);

        assertThat(profile.isActive()).isFalse();
        verify(repository).save(profile);
    }

    @Test
    void readsSavedResultsWithoutContactingTheProviderAndKeepsFailedRefreshesVisible() {
        var alex = profile("Alex", "one");
        var sam = profile("Sam", "two");
        java.time.Instant updatedAt = java.time.Instant.parse("2026-10-13T12:00:00Z");
        alex.recordHeadshots(new HenrikDevClient.Headshots(20, 100, 900, 0), updatedAt);
        alex.recordHeadshotFailure(updatedAt.plusSeconds(3600));
        sam.recordHeadshots(new HenrikDevClient.Headshots(20, 250, 750, 0), updatedAt);
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(alex, sam));

        assertThat(service.headshotMessages()).anySatisfy(message ->
                assertThat(message).contains("Alex", "10.0%", "last refresh failed", "12:00 UTC"))
                .anySatisfy(message -> assertThat(message).contains("Sam", "25.0%", "20/20"));
        verifyNoInteractions(client);
    }

    @Test
    void aNewProfileWaitsForTheScheduledRefreshInsteadOfMakingARequest() {
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(profile("Alex", "one")));

        assertThat(service.headshotMessages()).anySatisfy(message ->
                assertThat(message).contains("Alex", "awaiting first scheduled refresh"));
        verifyNoInteractions(client);
    }

    @Test
    void emptyProfilesExplainHowToGetStarted() {
        assertThat(service.headshotMessages()).singleElement()
                .asString().contains("No active Valorant profiles", "/val_profile_add");
    }

    private static ValorantProfileEntity profile(String name, String puuid) {
        return new ValorantProfileEntity(name, puuid, name + "#EU1", "eu", "pc");
    }
}
