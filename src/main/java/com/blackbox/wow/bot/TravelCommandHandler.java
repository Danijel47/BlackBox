package com.blackbox.wow.bot;

import com.blackbox.time_to_go.service.TimeToGoCommandService;

final class TravelCommandHandler {

    private static final String ROAD_COMMAND = "/road";
    private static final String ROAD_BEST_COMMAND = "/roadbest";

    private final TimeToGoCommandService timeToGoCommands;
    private final long adminUserId;
    private final MessageSender messageSender;

    TravelCommandHandler(
            TimeToGoCommandService timeToGoCommands,
            long adminUserId,
            MessageSender messageSender
    ) {
        this.timeToGoCommands = timeToGoCommands;
        this.adminUserId = adminUserId;
        this.messageSender = messageSender;
    }

    boolean handle(long chatId, Long senderUserId, String text, String command) {
        return switch (command) {
            case ROAD_COMMAND, "/travel", "/timetogo" -> handled(() ->
                    messageSender.send(chatId, timeToGoCommands.formatCurrent(text)));
            case ROAD_BEST_COMMAND, "/travelbest", "/timetogobest" -> handled(() ->
                    messageSender.send(chatId, timeToGoCommands.formatBest(text)));
            case "/timetogoimport30", "/roadimport30", "/travelimport30" -> handled(() ->
                    runAdminCommand(chatId, senderUserId, () -> submitHistoricalImport(chatId)));
            case "/timetogoimportstatus", "/roadimportstatus", "/travelimportstatus" -> handled(() ->
                    runAdminCommand(chatId, senderUserId, () -> refreshHistoricalImport(chatId)));
            default -> false;
        };
    }

    private void runAdminCommand(long chatId, Long senderUserId, Runnable action) {
        if (isAdmin(senderUserId)) {
            action.run();
        } else {
            messageSender.send(chatId, adminOnlyMessage());
        }
    }

    private void submitHistoricalImport(long chatId) {
        try {
            messageSender.send(chatId, timeToGoCommands.submitHistoricalImport());
        } catch (Exception e) {
            messageSender.send(chatId, "TomTom historical import submit failed: " + e.getMessage());
        }
    }

    private void refreshHistoricalImport(long chatId) {
        try {
            messageSender.send(chatId, timeToGoCommands.refreshHistoricalImport());
        } catch (Exception e) {
            messageSender.send(chatId, "TomTom historical import status failed: " + e.getMessage());
        }
    }

    private boolean isAdmin(Long senderUserId) {
        return adminUserId > 0
                && senderUserId != null
                && senderUserId == adminUserId;
    }

    private String adminOnlyMessage() {
        if (adminUserId <= 0) {
            return "Profile management is disabled because TELEGRAM_ADMIN_USER_ID is not configured. "
                    + "Send /my_id, then add that numeric user ID to the server .env.";
        }
        return "This command can only be used by the configured bot administrator.";
    }

    private static boolean handled(Runnable action) {
        action.run();
        return true;
    }

    @FunctionalInterface
    interface MessageSender {
        void send(long chatId, String text);
    }
}
