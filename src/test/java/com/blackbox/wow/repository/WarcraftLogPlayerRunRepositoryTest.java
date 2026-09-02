package com.blackbox.wow.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import static org.assertj.core.api.Assertions.assertThat;

class WarcraftLogPlayerRunRepositoryTest {

    @Test
    void selectsTimedRunsAtOrAboveTheMinimumWithoutRequiringRaiderIoMatching() throws Exception {
        Query query = WarcraftLogPlayerRunRepository.class
                .getMethod(
                        "findTimedBySeasonKeyAndMinimumKeystoneLevel",
                        String.class,
                        int.class
                )
                .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isFalse();
        assertThat(query.value())
                .contains("run.keystoneLevel >= :minimumKeystoneLevel")
                .contains("run.timed = true")
                .doesNotContain("mplus_run_log_match", "MATCHED");
    }
}
