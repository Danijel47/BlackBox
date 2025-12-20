package com.example.telegrambot.config;

import com.example.telegrambot.scheduler.job.GoldPriceCheckJob;
import org.quartz.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GoldQuartzConfig {

    @Bean
    public JobDetail goldJobDetail() {
        return JobBuilder.newJob(GoldPriceCheckJob.class)
                .withIdentity("goldPriceCheckJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger goldTrigger(
            JobDetail goldJobDetail,
            @Value("${bot.gold.poll-interval-seconds:60}") int intervalSeconds
    ) {
        return TriggerBuilder.newTrigger()
                .forJob(goldJobDetail)
                .withIdentity("goldPriceCheckTrigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(intervalSeconds)
                        .repeatForever())
                .build();
    }
}

