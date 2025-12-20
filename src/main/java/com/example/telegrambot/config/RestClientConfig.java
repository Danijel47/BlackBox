package com.example.telegrambot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    @Primary
    public RestClient defaultRestClient() {
        return RestClient.builder().build();
    }

    @Bean("raiderIoRestClient")
    public RestClient raiderIoRestClient() {
        return RestClient.builder()
                .baseUrl("https://raider.io")
                .build();
    }

    @Bean("njuskaloRestClient")
    public RestClient njuskaloRestClient() {
        return RestClient.builder()
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 (compatible; BackBoxBot/1.0)")
                .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build();
    }
}

