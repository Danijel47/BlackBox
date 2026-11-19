package com.blackbox.wow.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "material_alert_snapshot")
@Getter
@Setter
public class MaterialAlertSnapshotEntity {
    @Id private Long id = 1L;
    private Instant nextAttemptAt;
    private Instant fetchedAt;
    private Instant sourceUpdatedAt;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "material_alert_price", joinColumns = @JoinColumn(name = "snapshot_id"))
    @MapKeyColumn(name = "item_id")
    @Column(name = "price_copper", nullable = false)
    private Map<Long, Long> prices = new HashMap<>();
}
