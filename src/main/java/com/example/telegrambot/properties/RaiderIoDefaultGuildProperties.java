package com.example.telegrambot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "raiderio.default")
public record RaiderIoDefaultGuildProperties(
        String region,
        String realm,
        String guildName,
        String raidName
) {}
