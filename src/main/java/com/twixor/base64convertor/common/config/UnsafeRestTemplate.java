package com.twixor.base64convertor.common.config;

import com.twixor.base64convertor.common.util.LogSanitizer;
import com.twixor.base64convertor.common.util.LoggingInterceptor;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import java.io.IOException;

@Configuration
public class UnsafeRestTemplate {

    private static final Logger log = LoggerFactory.getLogger(UnsafeRestTemplate.class);

    private final AppProperties appProperties;

    public UnsafeRestTemplate(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Bean
    @Primary
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate(createRequestFactory());
        restTemplate.getInterceptors().add(new LoggingInterceptor());
        configureErrorHandler(restTemplate);
        return restTemplate;
    }

    private ClientHttpRequestFactory createRequestFactory() {
        AppProperties.Http http = appProperties.getHttp();
        Timeout connectTimeout = Timeout.ofSeconds(http.getConnectTimeoutSeconds());
        Timeout responseTimeout = Timeout.ofSeconds(http.getResponseTimeoutSeconds());

        try {
            CloseableHttpClient httpClient;

            if (http.isTrustAllSsl()) {
                SSLContext sslContext = SSLContextBuilder.create()
                        .loadTrustMaterial(null, (chain, authType) -> true)
                        .build();

                var connManager = PoolingHttpClientConnectionManagerBuilder.create()
                        .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                                .setSslContext(sslContext)
                                .build())
                        .setMaxConnTotal(http.getMaxConnections())
                        .setMaxConnPerRoute(http.getMaxConnectionsPerRoute())
                        .build();

                httpClient = HttpClients.custom()
                        .setConnectionManager(connManager)
                        .setDefaultRequestConfig(RequestConfig.custom()
                                .setConnectionRequestTimeout(connectTimeout)
                                .setResponseTimeout(responseTimeout)
                                .build())
                        .evictExpiredConnections()
                        .evictIdleConnections(TimeValue.ofMinutes(2))
                        .build();

                log.info("HttpClient initialised: trust-all-SSL=true, maxConn={}, maxPerRoute={}, connect={}s, response={}s",
                        http.getMaxConnections(), http.getMaxConnectionsPerRoute(),
                        http.getConnectTimeoutSeconds(), http.getResponseTimeoutSeconds());
            } else {
                var connManager = PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(http.getMaxConnections())
                        .setMaxConnPerRoute(http.getMaxConnectionsPerRoute())
                        .build();

                httpClient = HttpClients.custom()
                        .setConnectionManager(connManager)
                        .setDefaultRequestConfig(RequestConfig.custom()
                                .setConnectionRequestTimeout(connectTimeout)
                                .setResponseTimeout(responseTimeout)
                                .build())
                        .evictExpiredConnections()
                        .evictIdleConnections(TimeValue.ofMinutes(2))
                        .build();

                log.info("HttpClient initialised: trust-all-SSL=false, maxConn={}, maxPerRoute={}, connect={}s, response={}s",
                        http.getMaxConnections(), http.getMaxConnectionsPerRoute(),
                        http.getConnectTimeoutSeconds(), http.getResponseTimeoutSeconds());
            }

            HttpComponentsClientHttpRequestFactory baseFactory =
                    new HttpComponentsClientHttpRequestFactory(httpClient);
            // Wrap so interceptors can read the response body without closing the stream
            return new BufferingClientHttpRequestFactory(baseFactory);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to create RestTemplate HttpClient", e);
        }
    }

    /**
     * Only 5xx responses are treated as hard errors.
     * 4xx responses are passed through so callers can inspect the body.
     */
    private void configureErrorHandler(RestTemplate restTemplate) {
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return response.getStatusCode().value() >= 500;
            }

            @Override
            public void handleError(ClientHttpResponse response) throws IOException {
                int statusCode = response.getStatusCode().value();
                String body = new String(response.getBody().readAllBytes());
                // Logging remediation, CRITICAL #2: ERROR-level logs must never contain the
                // response body (may carry Base64/PDF/file content echoed back by a downstream
                // error page). Status code only at ERROR; a bounded, truncated preview is
                // available at DEBUG for local troubleshooting only.
                log.error("Remote API responded with HTTP {}", statusCode);
                log.debug("Remote API error body (truncated): {}", LogSanitizer.truncate(body, 200));
            }
        });
    }
}
