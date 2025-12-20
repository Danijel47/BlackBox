package com.example.telegrambot.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "bot_subscription", uniqueConstraints = {
        @UniqueConstraint(name = "uk_chat_source", columnNames = {"chat_id", "source"})
})
public class Subscription {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "source", nullable = false)
    private String source; // e.g. "GOLD_XAU"

    protected Subscription() {}

    public Subscription(Long chatId, String source) {
        this.chatId = chatId;
        this.source = source;
    }

    public Long getChatId() { return chatId; }
    public String getSource() { return source; }
}
