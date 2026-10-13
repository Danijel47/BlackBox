package com.blackbox.valorant.bot;

import com.blackbox.valorant.client.HenrikDevClient;
import com.blackbox.valorant.service.ValorantService;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

public final class ValorantCommandHandler {
    private static final Pattern RIOT_ID_AND_PLATFORM = Pattern.compile("^(.+#[^\\s#]+)(?:\\s+(pc|console))?$",
            Pattern.CASE_INSENSITIVE);
    private final ValorantService service;
    private final long adminUserId;
    private final BiConsumer<Long, String> sender;

    public ValorantCommandHandler(ValorantService service, long adminUserId, BiConsumer<Long, String> sender) {
        this.service = service;
        this.adminUserId = adminUserId;
        this.sender = sender;
    }

    public boolean handle(long chatId, Long userId, String text, String command) {
        switch (command) {
            case "/valorant", "/val" -> sender.accept(chatId, """
                    Valorant — one Riot account per profile
                    /val_profiles — registered profiles
                    /val_hs — saved HS% for each active player, latest 20 competitive matches

                    Administrator:
                    /val_profile_add <profile> <Name#Tag> [pc|console]
                    /val_profile_disable <profile>
                    /val_profile_enable <profile>
                    """.strip());
            case "/valprofiles" -> sendLines(chatId, service.profileMessages());
            case "/valhs" -> sendLines(chatId, service.headshotMessages());
            case "/valprofileadd", "/valprofiledisable", "/valprofileenable" -> {
                if (adminUserId <= 0 || userId == null || userId != adminUserId) {
                    sender.accept(chatId, "Only the configured bot administrator can manage Valorant profiles.");
                    return true;
                }
                try {
                    if (command.equals("/valprofileadd")) {
                        addProfile(chatId, text);
                    } else {
                        String[] parts = text.split("\\s+", 2);
                        if (parts.length < 2 || parts[1].isBlank()) {
                            sender.accept(chatId, "Usage: /val_profile_"
                                    + (command.equals("/valprofileenable") ? "enable" : "disable") + " <profile>");
                        } else {
                            boolean active = command.equals("/valprofileenable");
                            service.setActive(parts[1], active);
                            sender.accept(chatId, "Valorant profile " + parts[1] + (active ? " enabled." : " disabled."));
                        }
                    }
                } catch (IllegalArgumentException e) {
                    sender.accept(chatId, e.getMessage());
                } catch (HenrikDevClient.UnavailableException e) {
                    sender.accept(chatId, e.getMessage());
                } catch (IllegalStateException e) {
                    sender.accept(chatId, "Valorant account data could not be verified; try again later.");
                }
            }
            default -> { return false; }
        }
        return true;
    }

    private void addProfile(long chatId, String text) {
        String[] parts = text.split("\\s+", 3);
        var match = RIOT_ID_AND_PLATFORM.matcher(parts.length == 3 ? parts[2] : "");
        if (parts.length != 3 || !match.matches()) {
            sender.accept(chatId, "Usage: /val_profile_add <profile> <Name#Tag> [pc|console]");
            return;
        }
        var profile = service.addProfile(parts[1], match.group(1), match.group(2) == null ? "pc" : match.group(2));
        sender.accept(chatId, "Added Valorant profile " + profile.getProfileName() + " — " + profile.getRiotId()
                + ". The first result is collected on the next scheduled refresh. Use /val_hs to view saved results.");
    }

    private void sendLines(long chatId, List<String> lines) {
        StringBuilder message = new StringBuilder();
        for (String line : lines) {
            if (!message.isEmpty() && message.length() + line.length() + 1 > 3500) {
                sender.accept(chatId, message.toString());
                message.setLength(0);
            }
            if (!message.isEmpty()) {
                message.append('\n');
            }
            message.append(line);
        }
        if (!message.isEmpty()) {
            sender.accept(chatId, message.toString());
        }
    }
}
