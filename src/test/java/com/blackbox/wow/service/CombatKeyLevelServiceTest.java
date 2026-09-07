package com.blackbox.wow.service;

import com.blackbox.wow.repository.CombatKeyLevelRepository;
import com.blackbox.wow.warcraftlogs.WarcraftLogsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CombatKeyLevelServiceTest {

    private static final long ADMIN_ID = 999L;
    private static final int DEFAULT_LEVEL = 14;
    private final CombatKeyLevelRepository repository = mock(CombatKeyLevelRepository.class);

    @Test
    void usesConfiguredDefaultUntilAnAdminSavesASelection() {
        CombatKeyLevelService service = service(ADMIN_ID);
        assertThat(service.currentLevel()).isEqualTo(DEFAULT_LEVEL);

        when(repository.findLevel()).thenReturn(Optional.of(18));

        assertThat(service.currentLevel()).isEqualTo(18);
        assertThat(service(ADMIN_ID).currentLevel()).isEqualTo(18);
    }

    @ParameterizedTest
    @ValueSource(ints = {12, 13, 14, 15, 16, 17, 18})
    void savesEverySupportedLevelWithTheAdminIdentity(int level) {
        service(ADMIN_ID).changeLevel(ADMIN_ID, level);

        verify(repository).saveLevel(level, ADMIN_ID);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 11, 19, Integer.MAX_VALUE})
    void rejectsOutOfRangeValuesBeforeWriting(int level) {
        CombatKeyLevelService service = service(ADMIN_ID);

        assertThatThrownBy(() -> service.changeLevel(ADMIN_ID, level))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(CombatKeyLevelService.RANGE_MESSAGE);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, 456L})
    void rejectsNonAdminsAtTheServiceBoundary(long senderId) {
        CombatKeyLevelService service = service(ADMIN_ID);

        assertThatThrownBy(() -> service.changeLevel(senderId, 14)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void disabledAdminCannotWriteEvenWhenSenderIdMatches() {
        CombatKeyLevelService service = service(0);

        assertThatThrownBy(() -> service.changeLevel(0, 14)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void repeatedChangesAreVisibleAcrossServiceInstancesWithoutACache() {
        AtomicReference<Integer> stored = new AtomicReference<>();
        when(repository.findLevel()).thenAnswer(_ -> Optional.ofNullable(stored.get()));
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveLevel(anyInt(), anyLong());
        CombatKeyLevelService first = service(ADMIN_ID);
        CombatKeyLevelService second = service(ADMIN_ID);

        for (int level : new int[]{18, 12, 16, 14}) {
            first.changeLevel(ADMIN_ID, level);
            assertThat(second.currentLevel()).isEqualTo(level);
            assertThat(service(ADMIN_ID).currentLevel()).isEqualTo(level);
        }
    }

    @Test
    void failedSaveNeverPublishesAnInMemorySelection() {
        when(repository.findLevel()).thenReturn(Optional.of(14));
        doThrow(new DataAccessResourceFailureException("Unavailable")).when(repository).saveLevel(18, ADMIN_ID);
        CombatKeyLevelService service = service(ADMIN_ID);

        assertThatThrownBy(() -> service.changeLevel(ADMIN_ID, 18)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(service.currentLevel()).isEqualTo(14);
    }

    private CombatKeyLevelService service(long adminId) {
        WarcraftLogsProperties properties = mock(WarcraftLogsProperties.class);
        when(properties.combatMinimumKeystoneLevel()).thenReturn(DEFAULT_LEVEL);
        return new CombatKeyLevelService(repository, properties, adminId);
    }
}
