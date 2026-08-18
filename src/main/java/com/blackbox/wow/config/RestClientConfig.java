package com.blackbox.wow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
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

    @Bean("mplusTitleRestClient")
    public RestClient mplusTitleRestClient() {
        return RestClient.builder()
                .baseUrl("https://mplus-title.vercel.app")
                .build();
    }

    @Bean("blizzardAuthRestClient")
    public RestClient blizzardAuthRestClient(@Value("${blizzard.auth-base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    @Bean("blizzardApiRestClient")
    public RestClient blizzardApiRestClient(@Value("${blizzard.api-base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }
}
