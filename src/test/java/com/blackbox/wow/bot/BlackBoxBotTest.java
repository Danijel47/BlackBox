package com.blackbox.wow.bot;

import com.blackbox.time_to_go.service.TimeToGoCommandService;
import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.blizzard.BlizzardItemService;
import com.blackbox.wow.blizzard.BlizzardMountService;
import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.properties.RaiderIoDefaultGuildProperties;
import com.blackbox.wow.properties.WowWatchlistProperties;
import com.blackbox.wow.service.RaiderIoAbandonedRunService;
import com.blackbox.wow.service.TelegramAccessPolicy;
import com.blackbox.wow.service.TelegramBotUserService;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.VaultReminderService;
import com.blackbox.wow.warcraftlogs.WarcraftLogsStatisticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlackBoxBotTest {

    @Mock private TelegramClient telegramClient;
    @Mock private RaiderIoClient raiderIoClient;
    @Mock private RaiderIoDefaultGuildProperties defaultGuildProperties;
    @Mock private WowWatchlistProperties watchlistProperties;
    @Mock private BlizzardAuctionService auctionService;
    @Mock private BlizzardItemService itemService;
    @Mock private BlizzardMountService mountService;
    @Mock private TimeToGoCommandService timeToGoCommandService;
    @Mock private RaiderIoAbandonedRunService abandonedRunService;
    @Mock private TrackedPlayerService trackedPlayerService;
    @Mock private VaultReminderService vaultReminderService;
    @Mock private WarcraftLogsStatisticsService warcraftLogsStatisticsService;
    @Mock private TelegramAccessPolicy accessPolicy;
    @Mock private TelegramBotUserService telegramBotUserService;

    private BlackBoxBot bot;

    @AfterEach
    void stopScheduler() {
        if (bot != null) {
            bot.stopWorkingMessageScheduler();
        }
    }

    @Test
    void routesVaultCommandWithBotMention() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/vault@BlackBoxBot");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(true);
        when(trackedPlayerService.vaultWatchPlayers()).thenReturn(List.of());

        bot().consume(update);

        ArgumentCaptor<SendMessage> message = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(message.capture());
        assertThat(message.getValue().getText()).isEqualTo("No Mythic+ vault watch players configured.");
    }

    @Test
    void ignoresCommandsFromUnauthorizedUsers() throws Exception {
        long chatId = 123L;
        long userId = 456L;
        Update update = update(chatId, userId, "/vault");
        when(accessPolicy.isAllowed(chatId, userId)).thenReturn(false);

        bot().consume(update);

        verify(telegramClient, never()).execute(org.mockito.ArgumentMatchers.any(SendMessage.class));
    }

    private BlackBoxBot bot() {
        bot = new BlackBoxBot(
                "token",
                999L,
                telegramClient,
                raiderIoClient,
                defaultGuildProperties,
                watchlistProperties,
                auctionService,
                itemService,
                mountService,
                timeToGoCommandService,
                abandonedRunService,
                trackedPlayerService,
                vaultReminderService,
                warcraftLogsStatisticsService,
                accessPolicy,
                telegramBotUserService
        );
        return bot;
    }

    private static Update update(long chatId, long userId, String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        User user = mock(User.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn(text);
        when(message.getChatId()).thenReturn(chatId);
        when(message.getFrom()).thenReturn(user);
        when(user.getId()).thenReturn(userId);
        return update;
    }
}
