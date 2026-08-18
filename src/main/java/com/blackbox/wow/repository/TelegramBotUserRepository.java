package com.blackbox.wow.repository;

import com.blackbox.wow.entity.TelegramBotUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TelegramBotUserRepository extends JpaRepository<TelegramBotUserEntity, Long> {

    boolean existsByTelegramUserIdAndActiveTrue(long telegramUserId);

    List<TelegramBotUserEntity> findAllByOrderByDisplayNameAscTelegramUserIdAsc();
}
