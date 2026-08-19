package com.blackbox.wow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    @Primary
    public RestClient defaultRestClient() {
        return RestClient.builder().build();
    }

    @Bean("raiderIoRestClient")
    public RestClient raiderIoRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder()
                .baseUrl("https://raider.io")
                .requestFactory(requestFactory)
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
