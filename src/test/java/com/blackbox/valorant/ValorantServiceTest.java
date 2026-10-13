package com.blackbox.valorant;

import com.blackbox.valorant.client.HenrikDevClient;
import com.blackbox.valorant.entity.ValorantProfileEntity;
import com.blackbox.valorant.repository.ValorantProfileRepository;
import com.blackbox.valorant.service.ValorantService;
import com.blackbox.valorant.properties.ValorantProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ValorantServiceTest {
    private final ValorantProfileRepository repository = mock(ValorantProfileRepository.class);
    private final HenrikDevClient client = mock(HenrikDevClient.class);
    private final ValorantService service = new ValorantService(repository, client,
            new ValorantProperties("test-key", java.time.Duration.ofHours(1)));

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
    void registersAPcProfileWhenHenrikDevReturnsUppercasePlatformNames() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.henrikdev.xyz");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.henrikdev.xyz/valorant/v2/account/TZ%20Lazo/5730"))
                .andRespond(withSuccess("""
                        {"status":200,"data":{"puuid":"stable-id","region":"eu","account_level":241,
                        "name":"TZ Lazo","tag":"5730","card":"card-id","title":"title-id",
                        "platforms":["PC"],"updated_at":"2026-10-13T13:54:33.984Z"}}
                        """, MediaType.APPLICATION_JSON));
        var properties = new ValorantProperties("test-key", java.time.Duration.ofHours(1));
        var realClient = new HenrikDevClient(builder.build(), JsonMapper.builder().build(), properties);
        var realService = new ValorantService(repository, realClient, properties);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var profile = realService.addProfile("Lazo", "TZ Lazo#5730", "pc");

        assertThat(profile.getProfileName()).isEqualTo("Lazo");
        assertThat(profile.getRiotId()).isEqualTo("TZ Lazo#5730");
        assertThat(profile.getPlatform()).isEqualTo("pc");
        assertThat(profile.getRegion()).isEqualTo("eu");
        server.verify();
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
        alex.recordHeadshotFailure(updatedAt.plusSeconds(3600),
                "HenrikDev denied access (HTTP 403). Check your API key's permissions.");
        sam.recordHeadshots(new HenrikDevClient.Headshots(20, 250, 750, 0), updatedAt);
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(alex, sam));

        assertThat(service.headshotMessages()).anySatisfy(message ->
                assertThat(message).contains("Alex", "10.0%", "last refresh failed", "12:00 UTC", "403", "permissions"))
                .anySatisfy(message -> assertThat(message).contains("Sam", "25.0%", "20/20"));
        verifyNoInteractions(client);
    }

    @Test
    void missingKeyShowsAConfigurationWarningWithoutMakingAnyApiCalls() {
        var unconfigured = new ValorantService(repository, client,
                new ValorantProperties("", java.time.Duration.ofHours(1)));

        assertThat(unconfigured.headshotMessages()).anySatisfy(message ->
                assertThat(message).contains("⚠", "HENRIKDEV_API_KEY", "not configured"));
        verifyNoInteractions(client);
    }

    @Test
    void firstRefreshPermissionFailureIsVisibleWithoutAnExistingResult() {
        var profile = profile("Alex", "one");
        profile.recordHeadshotFailure(java.time.Instant.parse("2026-10-13T12:00:00Z"),
                "HenrikDev denied access (HTTP 403). Check your API key's permissions.");
        when(repository.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(profile));

        assertThat(service.headshotMessages()).anySatisfy(message ->
                assertThat(message).contains("Alex", "⚠", "403", "permissions"));
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
