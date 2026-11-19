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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "material_group_alert")
@Getter
@Setter
public class MaterialGroupAlertEntity {
    @Id @Column(length = 32) private String groupKey;
    @Column(name = "threshold_percent", nullable = false, precision = 12, scale = 4)
    private BigDecimal threshold;
    private boolean enabled;
    private boolean pending;
    private boolean fired;
    private Instant baselineAt;
    private Instant baselineSourceAt;
    private Instant notifiedAt;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "material_alert_baseline", joinColumns = @JoinColumn(name = "group_key"))
    @MapKeyColumn(name = "item_id")
    @Column(name = "price_copper", nullable = false)
    private Map<Long, Long> baseline = new HashMap<>();
}
