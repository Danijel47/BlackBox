package com.blackbox.wow.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import static org.assertj.core.api.Assertions.assertThat;

class WarcraftLogPlayerRunRepositoryTest {

    @Test
    void selectsOnlyTheCanonicalMatchedLogForEachObservedRun() throws Exception {
        Query query = WarcraftLogPlayerRunRepository.class
                .getMethod(
                        "findCanonicalTimedBySeasonKeyAndMinimumKeystoneLevel",
                        String.class,
                        int.class
                )
                .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value())
                .contains("JOIN mplus_run_log_match")
                .contains("run_match.match_status = 'MATCHED'")
                .contains("log_run.timed = TRUE");
    }
}
