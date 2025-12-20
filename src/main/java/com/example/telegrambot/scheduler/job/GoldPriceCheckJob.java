package com.example.telegrambot.scheduler.job;

import com.example.telegrambot.service.GoldPriceMonitorService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

@Component
public class GoldPriceCheckJob implements Job {

    private final GoldPriceMonitorService monitor;

    public GoldPriceCheckJob(GoldPriceMonitorService monitor) {
        this.monitor = monitor;
    }

    @Override
    public void execute(JobExecutionContext context) {
        monitor.checkOnce();
    }
}
