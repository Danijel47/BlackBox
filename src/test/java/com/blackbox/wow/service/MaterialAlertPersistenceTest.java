package com.blackbox.wow.service;

import com.blackbox.wow.blizzard.BlizzardAuctionService.MaterialPriceSnapshot;
import com.blackbox.wow.entity.MaterialAlertSnapshotEntity;
import com.blackbox.wow.entity.MaterialGroupAlertEntity;
import com.blackbox.wow.repository.MaterialAlertSnapshotRepository;
import com.blackbox.wow.repository.MaterialGroupAlertRepository;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in: URL must point to an empty disposable database, never application data. */
@EnabledIfEnvironmentVariable(named = "MATERIAL_ALERT_TEST_DB", matches = ".+")
class MaterialAlertPersistenceTest {
    @Test void migrationMappingsLocksAndRestartPreserveAlertState() {
        String url = System.getenv("MATERIAL_ALERT_TEST_DB");
        var dataSource = new DriverManagerDataSource(url, "postgres", "blackbox-test");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V35__create_material_group_alerts.sql"))
                .execute(dataSource);
        var configuration = new Configuration().addAnnotatedClass(MaterialAlertSnapshotEntity.class)
                .addAnnotatedClass(MaterialGroupAlertEntity.class);
        configuration.setProperty("hibernate.connection.url", url);
        configuration.setProperty("hibernate.connection.username", "postgres");
        configuration.setProperty("hibernate.connection.password", "blackbox-test");
        configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        configuration.setProperty("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        try (var factory = configuration.buildSessionFactory()) {
            var transactions = new TransactionTemplate(new JpaTransactionManager(factory));
            var repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory));
            var snapshots = repositories.getRepository(MaterialAlertSnapshotRepository.class);
            var groups = repositories.getRepository(MaterialGroupAlertRepository.class);
            var catalog = new MaterialAlertCatalog();
            Instant now = Instant.parse("2026-11-19T12:00:00Z");
            var clock = Clock.fixed(now, ZoneOffset.UTC);
            var service = new MaterialAlertService(snapshots, groups, catalog, clock);
            transactions.executeWithoutResult(_ -> service.configure("ores", BigDecimal.valueOf(15)));
            assertThat(transactions.<Boolean>execute(_ -> service.claimRefresh())).isTrue();
            var prices = new HashMap<Long, Long>(); catalog.itemIds().forEach(id -> prices.put(id, 100L));
            transactions.executeWithoutResult(_ -> service.storeSnapshot(new MaterialPriceSnapshot(prices, now, now)));
            var restarted = new MaterialAlertService(snapshots, groups, catalog, clock);
            assertThat(transactions.<Boolean>execute(_ -> restarted.claimRefresh())).isFalse();
            assertThat(transactions.<String>execute(_ -> restarted.status())).contains("armed", "+0.00%");
            prices.replaceAll((_, _) -> 115L);
            transactions.executeWithoutResult(_ -> restarted.storeSnapshot(new MaterialPriceSnapshot(prices, now, now)));
            var notifier = mock(BlackBoxBotNotifier.class);
            when(notifier.send(eq(999L), anyString())).thenReturn(true);
            transactions.executeWithoutResult(_ -> restarted.notifyAlerts(notifier, 999L));
            assertThat(transactions.<String>execute(_ -> restarted.status())).contains("alert sent; reset to rearm");
            transactions.executeWithoutResult(_ -> restarted.reset("ores"));
            assertThat(transactions.<String>execute(_ -> restarted.status())).contains("armed", "+0.00%");
        }
    }
}
