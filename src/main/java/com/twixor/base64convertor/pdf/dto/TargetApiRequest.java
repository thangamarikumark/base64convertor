package com.twixor.base64convertor.pdf.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

@Data
public class TargetApiRequest {
    private Message message;
    private MetaData metaData;

    @Data
    public static class MetaData {
        private String version;
    }
    @Data
    public static class Message {
        private String channel;
        private Content content;
        private Recipient recipient;
        private Sender sender;
        private Preferences preferences;
    }

    @Data
    public static class Content {
        private String type;
        private Attachment attachment;
    }

    @Data
    public static class Attachment {
        private String type;
        private String caption;
        private String fileName;
        private String mimeType;
        private String attachmentData;
    }

    @Data
    public static class Recipient {
        private String to;
        private String recipient_type;
        private Reference reference;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Reference implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * Copied from the source request's recipient.reference.refId (see
         * PdfRequest.Reference). Previously this class had no fields at all, so
         * TargetApiRequestMapper always sent an empty Reference regardless of what the
         * caller supplied — silently dropping any reply-to/thread-correlation id.
         */
        private String refId;
    }

    @Data
    public static class Sender {
        private String from;
    }

    @Data
    public static class Preferences {
        private String webHookDNId;
    }
}
