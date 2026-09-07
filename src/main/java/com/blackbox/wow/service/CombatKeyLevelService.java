package com.blackbox.wow.service;

import com.blackbox.wow.repository.CombatKeyLevelRepository;
import com.blackbox.wow.warcraftlogs.WarcraftLogsProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CombatKeyLevelService {

    public static final String COMMAND = "/mplus_keylevel";
    public static final int MIN_LEVEL = 12;
    public static final int MAX_LEVEL = 18;
    public static final String RANGE_MESSAGE = "Choose a whole key level from " + MIN_LEVEL + " to " + MAX_LEVEL + ".";

    private final CombatKeyLevelRepository repository;
    private final int defaultLevel;
    private final long adminUserId;

    public CombatKeyLevelService(CombatKeyLevelRepository repository, WarcraftLogsProperties properties,
                                 @Value("${telegram.admin-user-id:0}") long adminUserId) {
        this.repository = repository;
        this.defaultLevel = properties.combatMinimumKeystoneLevel();
        this.adminUserId = adminUserId;
    }

    public int currentLevel() {
        // Read once per report: no stale process-local cache or unsaved in-memory override.
        return repository.findLevel().orElse(defaultLevel);
    }

    public void changeLevel(long senderUserId, int level) {
        if (adminUserId <= 0 || senderUserId != adminUserId) {
            throw new SecurityException("Only the bot administrator can change the combat key level.");
        }
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException(RANGE_MESSAGE);
        }
        repository.saveLevel(level, senderUserId);
    }
}
