package com.blackbox.wow.bot;

import com.blackbox.time_to_go.service.TimeToGoCommandService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TravelCommandHandlerTest {

    private static final long ADMIN_USER_ID = 999L;
    private static final long CHAT_ID = 123L;

    @Mock private TimeToGoCommandService timeToGoCommands;

    @ParameterizedTest
    @ValueSource(strings = {"/road", "/travel", "/timetogo"})
    void currentTravelAliasesPreserveTheOriginalCommandText(String command) {
        List<SentMessage> messages = new ArrayList<>();
        String text = command + " zadar zagreb";
        when(timeToGoCommands.formatCurrent(text)).thenReturn("Current travel time");

        boolean handled = handler(messages).handle(CHAT_ID, 456L, text, command);

        assertThat(handled).isTrue();
        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "Current travel time"));
        verify(timeToGoCommands).formatCurrent(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/roadbest", "/travelbest", "/timetogobest"})
    void bestTravelAliasesPreserveTheOriginalCommandText(String command) {
        List<SentMessage> messages = new ArrayList<>();
        String text = command + " zadar zagreb";
        when(timeToGoCommands.formatBest(text)).thenReturn("Best travel time");

        boolean handled = handler(messages).handle(CHAT_ID, 456L, text, command);

        assertThat(handled).isTrue();
        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "Best travel time"));
        verify(timeToGoCommands).formatBest(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/timetogoimport30", "/roadimport30", "/travelimport30"})
    void administratorCanSubmitHistoricalImport(String command) {
        List<SentMessage> messages = new ArrayList<>();
        when(timeToGoCommands.submitHistoricalImport()).thenReturn("Import submitted");

        boolean handled = handler(messages).handle(CHAT_ID, ADMIN_USER_ID, command, command);

        assertThat(handled).isTrue();
        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "Import submitted"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/timetogoimportstatus", "/roadimportstatus", "/travelimportstatus"})
    void administratorCanRefreshHistoricalImport(String command) {
        List<SentMessage> messages = new ArrayList<>();
        when(timeToGoCommands.refreshHistoricalImport()).thenReturn("Import status");

        boolean handled = handler(messages).handle(CHAT_ID, ADMIN_USER_ID, command, command);

        assertThat(handled).isTrue();
        assertThat(messages).containsExactly(new SentMessage(CHAT_ID, "Import status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/timetogoimport30", "/roadimport30", "/travelimport30",
            "/timetogoimportstatus", "/roadimportstatus", "/travelimportstatus"
    })
    void nonAdministratorCannotRunHistoricalImports(String command) {
        List<SentMessage> messages = new ArrayList<>();

        boolean handled = handler(messages).handle(CHAT_ID, 456L, command, command);

        assertThat(handled).isTrue();
        assertThat(messages).singleElement().extracting(SentMessage::text)
                .asString().contains("only be used by the configured bot administrator");
        verifyNoInteractions(timeToGoCommands);
    }

    @Test
    void reportsHistoricalImportSubmissionFailureWithoutThrowing() {
        List<SentMessage> messages = new ArrayList<>();
        when(timeToGoCommands.submitHistoricalImport()).thenThrow(new IllegalStateException("queue unavailable"));

        boolean handled = handler(messages).handle(
                CHAT_ID,
                ADMIN_USER_ID,
                "/roadimport30",
                "/roadimport30"
        );

        assertThat(handled).isTrue();
        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "TomTom historical import submit failed: queue unavailable"
        ));
    }

    @Test
    void reportsHistoricalImportStatusFailureWithoutThrowing() {
        List<SentMessage> messages = new ArrayList<>();
        when(timeToGoCommands.refreshHistoricalImport()).thenThrow(new IllegalStateException("status unavailable"));

        boolean handled = handler(messages).handle(
                CHAT_ID,
                ADMIN_USER_ID,
                "/roadimportstatus",
                "/roadimportstatus"
        );

        assertThat(handled).isTrue();
        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "TomTom historical import status failed: status unavailable"
        ));
    }

    @Test
    void leavesUnknownCommandsForTheNextHandler() {
        List<SentMessage> messages = new ArrayList<>();

        boolean handled = handler(messages).handle(CHAT_ID, 456L, "/unknown", "/unknown");

        assertThat(handled).isFalse();
        assertThat(messages).isEmpty();
        verify(timeToGoCommands, never()).formatCurrent("/unknown");
        verifyNoInteractions(timeToGoCommands);
    }

    private TravelCommandHandler handler(List<SentMessage> messages) {
        return new TravelCommandHandler(
                timeToGoCommands,
                ADMIN_USER_ID,
                (chatId, text) -> messages.add(new SentMessage(chatId, text))
        );
    }

    private record SentMessage(long chatId, String text) {
    }
}
