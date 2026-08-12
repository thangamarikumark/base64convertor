package com.twixor.base64convertor.pdf.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PdfRequest {

    private String url;
    private String cookie;
    private String payload;
    private String target_url;
    private String target_auth;
    private Message message;
    private MetaData metaData;

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String channel;
        private Content content;
        private Recipient recipient;
        private Sender sender;
        private Preferences preferences;
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Content {
        private String type;
        private Attachment attachment;
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Attachment {
        private String type;
        private String caption;
        private String fileName;
        private String mimeType;
        private String attachmentData;
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recipient {
        private String to;
        private String recipient_type;
        private Reference reference;
    }
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reference {
        private String refId;

        public boolean hasData() {
            return refId != null && !refId.isBlank();
        }
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sender {
        private String from;
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Preferences {
        private String webHookDNId;
    }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetaData {
        private String version;
    }
}
