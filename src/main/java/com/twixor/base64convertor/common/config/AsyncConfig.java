package com.twixor.base64convertor.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableRetry
public class AsyncConfig {

    @Value("${async.file-executor.core-pool-size:4}")
    private int corePoolSize;

    @Value("${async.file-executor.max-pool-size:8}")
    private int maxPoolSize;

    @Value("${async.file-executor.queue-capacity:50}")
    private int queueCapacity;

    @Bean("fileExecutor")
    public Executor fileExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("FileWorker-");
        executor.initialize();
        return executor;
    }
}
