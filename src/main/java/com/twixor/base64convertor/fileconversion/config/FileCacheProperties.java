package com.twixor.base64convertor.fileconversion.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "file.cache")
public class FileCacheProperties {
    private String path;
    private long retentionHours;
    private Audit audit = new Audit();

    @Getter
    @Setter
    public static class Audit {
        private boolean rotationEnabled = true;
        private boolean archiveEnabled = true;
        private int archiveRetentionDays = 7;
    }
}

