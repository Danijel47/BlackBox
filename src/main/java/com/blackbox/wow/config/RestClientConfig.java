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

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final String SADDLEBAG_EXCHANGE_BASE_URL = "https://api.saddlebagexchange.com";

    @Bean
    @Primary
    public RestClient defaultRestClient() {
        return RestClient.builder().build();
    }

    @Bean("raiderIoRestClient")
    public RestClient raiderIoRestClient() {
        return restClientWithTimeouts("https://raider.io");
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
        return restClientWithTimeouts(baseUrl);
    }

    @Bean("wowTokenHistoryRestClient")
    public RestClient wowTokenHistoryRestClient(
            @Value("${wow.token-history.backfill-base-url:https://data.wowtoken.app}") String baseUrl
    ) {
        return restClientWithTimeouts(baseUrl);
    }

    @Bean("saddlebagExchangeRestClient")
    public RestClient saddlebagExchangeRestClient() {
        return restClientWithTimeouts(SADDLEBAG_EXCHANGE_BASE_URL);
    }

    private static RestClient restClientWithTimeouts(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
