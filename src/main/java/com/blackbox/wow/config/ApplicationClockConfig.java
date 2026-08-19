package com.blackbox.wow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ApplicationClockConfig {

    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }
}
