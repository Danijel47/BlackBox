package com.example.telegrambot.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Entity
@Table(name = "price_state")
public class PriceState {

    @Id
    @Column(name = "symbol")
    private String symbol;

    @Setter
    @Column(name = "last_price", nullable = false, precision = 19, scale = 6)
    private BigDecimal lastPrice;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PriceState() {}

    public PriceState(String symbol, BigDecimal lastPrice, Instant updatedAt) {
        this.symbol = symbol;
        this.lastPrice = lastPrice;
        this.updatedAt = updatedAt;
    }

}
