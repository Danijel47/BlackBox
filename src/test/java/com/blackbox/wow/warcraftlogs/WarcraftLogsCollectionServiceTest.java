package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.repository.WarcraftLogItemLevelRepository;
import com.blackbox.wow.repository.WarcraftLogPlayerRunRepository;
import com.blackbox.wow.repository.WarcraftLogProfileSnapshotRepository;
import com.blackbox.wow.service.MPlusRunCorrelationService;
import com.blackbox.wow.service.TrackedPlayerService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WarcraftLogsCollectionServiceTest {
    @Test
    void disabledCollectionDoesNotCallExternalOrPersistenceDependencies() {
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        WarcraftLogPlayerRunRepository runs = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshots = mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService players = mock(TrackedPlayerService.class);
        WarcraftLogsCollectionService service = new WarcraftLogsCollectionService(
                client,
                new WarcraftLogsProperties("", "", "", "", 10, false,
                        "season", Instant.EPOCH, 80, 13),
                players,
                runs,
                snapshots,
                mock(MPlusRunCorrelationService.class),
                mock(WarcraftLogsEventPager.class),
                mock(WarcraftLogItemLevelRepository.class)
        );

        service.refresh();

        assertThat(service.isRefreshRunning()).isFalse();
        verifyNoInteractions(client, runs, snapshots, players);
    }
}
