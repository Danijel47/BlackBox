package com.example.telegrambot.scheduler.job;

import com.example.telegrambot.service.NjuskaloMonitorService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

@Component
@DisallowConcurrentExecution
public class NjuskaloJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(NjuskaloJob.class);

    private final NjuskaloMonitorService monitor;

    public NjuskaloJob(NjuskaloMonitorService monitor) {
        this.monitor = monitor;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        try {
            int newCount = monitor.checkAndNotify();
            log.info("NjuskaloJob finished. newCount={}", newCount);
        } catch (Exception e) {
            log.error("NjuskaloJob failed: {}", e.getMessage(), e);
        }
    }
}
