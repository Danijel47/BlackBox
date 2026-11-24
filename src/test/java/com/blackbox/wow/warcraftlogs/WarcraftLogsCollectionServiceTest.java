package com.blackbox.wow.warcraftlogs;

import com.blackbox.wow.entity.WarcraftLogProfileSnapshotEntity;
import com.blackbox.wow.repository.WarcraftLogItemLevelRepository;
import com.blackbox.wow.repository.WarcraftLogPlayerRunRepository;
import com.blackbox.wow.repository.WarcraftLogProfileSnapshotRepository;
import com.blackbox.wow.service.MPlusRunCorrelationService;
import com.blackbox.wow.service.TrackedPlayerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WarcraftLogsCollectionServiceTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void finishesDiscoveryAtTheLastPageOrApiPageLimitWithoutRecordingAnError(int lastPage) {
        WarcraftLogsClient client = mock(WarcraftLogsClient.class);
        WarcraftLogPlayerRunRepository runs = mock(WarcraftLogPlayerRunRepository.class);
        WarcraftLogProfileSnapshotRepository snapshots = mock(WarcraftLogProfileSnapshotRepository.class);
        TrackedPlayerService players = mock(TrackedPlayerService.class);
        when(players.activePlayers()).thenReturn(List.of(
                new TrackedPlayerService.TrackedPlayer(1, "Linq", "eu", "Stormscale", "Thelinq")));
        when(client.rateLimit()).thenReturn(new WarcraftLogsClient.RateLimit(1000, 0, 3600));
        ObjectMapper mapper = new ObjectMapper();
        List<Integer> requestedPages = new ArrayList<>();
        when(client.query(anyString(), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> variables = invocation.getArgument(1);
            int page = (int) variables.get("page");
            requestedPages.add(page);
            if (page > 5) {
                throw new IllegalStateException("The page argument must be a value between 1 and 5.");
            }
            return mapper.readTree("""
                    {"characterData":{"character":{"recentReports":{
                      "has_more_pages":%s,"data":[]
                    }}}}
                    """.formatted(lastPage == 5));
        });
        WarcraftLogsCollectionService service = new WarcraftLogsCollectionService(
                client,
                new WarcraftLogsProperties("", "", "", "", 20, true,
                        "season", Instant.EPOCH, 80, 13),
                players, runs, snapshots,
                mock(MPlusRunCorrelationService.class),
                mock(WarcraftLogsEventPager.class),
                mock(WarcraftLogItemLevelRepository.class)
        );

        service.refresh();

        assertThat(requestedPages).containsExactlyElementsOf(
                lastPage == 1 ? List.of(1) : List.of(1, 2, 3, 4, 5));
        ArgumentCaptor<WarcraftLogProfileSnapshotEntity> savedSnapshot =
                ArgumentCaptor.forClass(WarcraftLogProfileSnapshotEntity.class);
        verify(snapshots).save(savedSnapshot.capture());
        assertThat(savedSnapshot.getValue().getLastError()).isNull();
        assertThat(service.isRefreshRunning()).isFalse();
    }

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
