package com.blackbox.valorant;

import com.blackbox.valorant.bot.ValorantCommandHandler;
import com.blackbox.valorant.entity.ValorantProfileEntity;
import com.blackbox.valorant.service.ValorantService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ValorantCommandHandlerTest {
    private final ValorantService service = mock(ValorantService.class);
    private final List<String> sent = new ArrayList<>();
    private final ValorantCommandHandler handler = new ValorantCommandHandler(service, 999L,
            (chat, text) -> sent.add(text));

    @Test
    void aNonAdminCannotAddOrDisableProfiles() {
        handler.handle(1L, 123L, "/val_profile_add Alex Alex#EU1", "/valprofileadd");
        handler.handle(1L, 123L, "/val_profile_disable Alex", "/valprofiledisable");

        assertThat(sent).allSatisfy(message -> assertThat(message).contains("administrator"));
        verifyNoInteractions(service);
    }

    @Test
    void addingAProfilePreservesRiotNamesWithSpacesAndDefaultsToPc() {
        when(service.addProfile("Alex", "Alex Player#EU1", "pc")).thenReturn(
                new ValorantProfileEntity("Alex", "id", "Alex Player#EU1", "eu", "pc"));

        handler.handle(1L, 999L, "/valprofileadd Alex Alex Player#EU1", "/valprofileadd");

        assertThat(sent).singleElement().asString().contains("Added", "Alex Player#EU1");
    }

    @Test
    void malformedAddCommandsDoNotReachTheProvider() {
        handler.handle(1L, 999L, "/valprofileadd Alex", "/valprofileadd");

        assertThat(sent).singleElement().asString().contains("Usage:", "Name#Tag");
        verifyNoInteractions(service);
    }

    @Test
    void reportsAreCombinedAndSplitBeforeTelegramsMessageLimit() {
        when(service.headshotMessages()).thenReturn(List.of("Header", "A".repeat(2000), "B".repeat(2000)));

        handler.handle(1L, 123L, "/valhs", "/valhs");

        assertThat(sent).hasSize(2).allSatisfy(message -> assertThat(message.length()).isLessThanOrEqualTo(3500));
        assertThat(String.join("\n", sent)).contains("Header", "A".repeat(2000), "B".repeat(2000));
    }
}
