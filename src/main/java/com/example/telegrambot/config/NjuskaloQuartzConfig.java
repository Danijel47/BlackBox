package com.example.telegrambot.config;

import com.example.telegrambot.scheduler.job.NjuskaloJob;
import org.quartz.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NjuskaloQuartzConfig {

    @Bean
    public JobDetail njuskaloJobDetail() {
        return JobBuilder.newJob(NjuskaloJob.class)
                .withIdentity("njuskaloJob")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger njuskaloHourlyTrigger(JobDetail njuskaloJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(njuskaloJobDetail)
                .withIdentity("njuskaloHourlyTrigger")
                .startNow()
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInHours(1)
                        .repeatForever())
                .build();
    }
}
