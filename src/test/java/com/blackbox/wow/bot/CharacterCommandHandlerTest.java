package com.blackbox.wow.bot;

import com.blackbox.wow.blizzard.BlizzardItemLevelService;
import com.blackbox.wow.blizzard.BlizzardMountService;
import com.blackbox.wow.client.RaiderIoClient;
import com.blackbox.wow.service.TrackedPlayerService;
import com.blackbox.wow.service.TrackedPlayerService.TrackedPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterCommandHandlerTest {

    private static final long CHAT_ID = 123L;

    @Mock private RaiderIoClient raiderIoClient;
    @Mock private TrackedPlayerService trackedPlayerService;
    @Mock private BlizzardMountService mountService;
    @Mock private BlizzardItemLevelService itemLevelService;

    @Test
    void directRaiderIoCommandFormatsTheRequestedCharacter() {
        List<SentMessage> messages = new ArrayList<>();
        when(raiderIoClient.getCurrentMPlusScore("eu", "stormscale", "bucothered"))
                .thenReturn(score("Bucothered", "Stormscale", "303.25", "3210.5"));

        boolean handled = handler(messages).handle(
                CHAT_ID,
                "/rio eu stormscale bucothered",
                "/rio"
        );

        assertThat(handled).isTrue();
        assertThat(messages).singleElement().extracting(SentMessage::text).asString()
                .contains("Raider.IO (current season)", "Bucothered - Stormscale (eu)",
                        "Item level: 303.25", "Score: 3210.5");
    }

    @Test
    void directRaiderIoFailurePreservesTheProviderErrorMessage() {
        List<SentMessage> messages = new ArrayList<>();
        when(raiderIoClient.getCurrentMPlusScore("eu", "stormscale", "missing"))
                .thenThrow(new IllegalStateException("character not found"));

        handler(messages).handle(CHAT_ID, "/rio eu stormscale missing", "/rio");

        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "Couldn’t fetch Raider.IO for eu/stormscale/missing\nReason: character not found",
                null
        ));
    }

    @Test
    void directMountCommandFormatsAchievementProgress() {
        List<SentMessage> messages = new ArrayList<>();
        when(mountService.getMountProgress("stormscale", "bucothered"))
                .thenReturn(new BlizzardMountService.MountProgress("Bucothered", "stormscale", 590, 650));

        handler(messages).handle(
                CHAT_ID,
                "/mount-achiv stormscale bucothered",
                "/mount-achiv"
        );

        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "Insurmountable Collection: 590/600 (10 missing)",
                null
        ));
    }

    @Test
    void itemLevelCommandOpensTheItemLevelProfilePicker() {
        List<SentMessage> messages = new ArrayList<>();
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(player(11L, "Buco")));

        handler(messages).handle(CHAT_ID, "/ilvl", "/ilvl");

        assertThat(buttons(messages.getFirst().keyboard()))
                .extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("wow:character:ilvl:all", "wow:character:ilvl:11", "wow:menu:character");
    }

    @Test
    void characterMenuKeepsReportAndGearCallbackIdentifiers() {
        List<SentMessage> messages = new ArrayList<>();

        handler(messages).sendMenu(CHAT_ID);

        assertThat(buttons(messages.getFirst().keyboard()))
                .extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly(
                        "wow:character:rio",
                        "wow:character:ilvl",
                        "wow:character:mount",
                        "wow:character:gearcheck",
                        "wow:character:gearupg",
                        "wow:menu"
                );
    }

    @Test
    void genericProfilePickerCapsIndividualProfilesForGearUpgradeReuse() {
        List<SentMessage> messages = new ArrayList<>();
        when(trackedPlayerService.activePlayers()).thenReturn(IntStream.rangeClosed(1, 100)
                .mapToObj(index -> player(index, "Profile" + index))
                .toList());

        handler(messages).sendProfileMenu(CHAT_ID, "gearupg", "Gear Upgrades");

        List<InlineKeyboardButton> buttons = buttons(messages.getFirst().keyboard());
        assertThat(buttons).hasSize(91);
        assertThat(buttons.getFirst().getCallbackData()).isEqualTo("wow:character:gearupg:all");
        assertThat(buttons.get(89).getCallbackData()).isEqualTo("wow:character:gearupg:89");
        assertThat(buttons.getLast().getCallbackData()).isEqualTo("wow:menu:character");
    }

    @Test
    void selectedItemLevelCallbackUsesOnlyTheSelectedProfile() {
        List<SentMessage> messages = new ArrayList<>();
        TrackedPlayer buco = player(11L, "Buco");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco, player(12L, "Linq")));
        when(itemLevelService.equippedItemLevel(buco)).thenReturn(new BigDecimal("303"));

        handler(messages).handleCallback(CHAT_ID, "wow:character:ilvl:11");

        assertThat(messages).singleElement().extracting(SentMessage::text).asString()
                .contains("Item Level", "Bucochar - stormscale (eu)", "Equipped item level: 303")
                .doesNotContain("Linq");
        verifyNoInteractions(raiderIoClient);
    }

    @Test
    void allItemLevelsSortDescendingThenByProfileAndKeepFailuresLast() {
        List<SentMessage> messages = new ArrayList<>();
        TrackedPlayer buco = player(11L, "Buco");
        TrackedPlayer linq = player(12L, "Linq");
        TrackedPlayer alpha = player(13L, "Alpha");
        TrackedPlayer failed = player(14L, "Failed");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(failed, buco, linq, alpha));
        when(itemLevelService.equippedItemLevel(buco)).thenReturn(new BigDecimal("303"));
        when(itemLevelService.equippedItemLevel(linq)).thenReturn(new BigDecimal("306"));
        when(itemLevelService.equippedItemLevel(alpha)).thenReturn(new BigDecimal("306"));
        when(itemLevelService.equippedItemLevel(failed)).thenThrow(new IllegalStateException("private details"));

        handler(messages).handleCallback(CHAT_ID, "wow:character:ilvl:all");

        String report = messages.getFirst().text();
        assertThat(report)
                .containsSubsequence("• Alpha", "• Linq", "• Buco", "• Failed")
                .contains("• Failed (Failedchar-stormscale)\n  Status: unavailable")
                .doesNotContain("private details");
    }

    @Test
    void allMountReportsSortByUsableMountCount() {
        List<SentMessage> messages = new ArrayList<>();
        TrackedPlayer buco = player(11L, "Buco");
        TrackedPlayer linq = player(12L, "Linq");
        when(trackedPlayerService.activePlayers()).thenReturn(List.of(buco, linq));
        when(mountService.getMountProgress("stormscale", "Bucochar"))
                .thenReturn(new BlizzardMountService.MountProgress("Bucochar", "stormscale", 500, 550));
        when(mountService.getMountProgress("stormscale", "Linqchar"))
                .thenReturn(new BlizzardMountService.MountProgress("Linqchar", "stormscale", 600, 650));

        handler(messages).handleCallback(CHAT_ID, "wow:character:mount:all");

        String report = messages.getFirst().text();
        assertThat(report).contains("Mount Progress — all profiles", "Usable mounts: 600", "Usable mounts: 500");
        assertThat(report.indexOf("• Linq")).isLessThan(report.indexOf("• Buco"));
    }

    @Test
    void invalidCharacterCallbackUsesTheExistingRecoveryMessage() {
        List<SentMessage> messages = new ArrayList<>();

        handler(messages).handleCallback(CHAT_ID, "wow:character:unknown");

        assertThat(messages).containsExactly(new SentMessage(
                CHAT_ID,
                "That character report is no longer valid. Use /wow to start again.",
                null
        ));
        verifyNoInteractions(trackedPlayerService, raiderIoClient, mountService, itemLevelService);
    }

    @Test
    void unknownCommandIsLeftForTheNextHandler() {
        List<SentMessage> messages = new ArrayList<>();

        boolean handled = handler(messages).handle(CHAT_ID, "/unknown", "/unknown");

        assertThat(handled).isFalse();
        assertThat(messages).isEmpty();
        verifyNoInteractions(trackedPlayerService, raiderIoClient, mountService, itemLevelService);
    }

    private CharacterCommandHandler handler(List<SentMessage> messages) {
        return new CharacterCommandHandler(
                raiderIoClient,
                trackedPlayerService,
                mountService,
                itemLevelService,
                (chatId, text, keyboard) -> messages.add(new SentMessage(chatId, text, keyboard))
        );
    }

    private static TrackedPlayer player(long id, String profileName) {
        return new TrackedPlayer(id, profileName, "eu", "stormscale", profileName + "char");
    }

    private static RaiderIoClient.RaiderIoScore score(
            String name,
            String realm,
            String itemLevel,
            String overallScore
    ) {
        return new RaiderIoClient.RaiderIoScore(
                name,
                realm,
                "eu",
                new BigDecimal(itemLevel),
                new BigDecimal(overallScore),
                new BigDecimal(overallScore),
                null,
                null,
                "",
                Instant.EPOCH
        );
    }

    private static List<InlineKeyboardButton> buttons(InlineKeyboardMarkup keyboard) {
        return keyboard.getKeyboard().stream().flatMap(List::stream).toList();
    }

    private record SentMessage(long chatId, String text, InlineKeyboardMarkup keyboard) {
    }
}
