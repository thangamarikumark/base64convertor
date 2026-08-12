package com.twixor.base64convertor.checksum.dto;

/**
 * Promoted from a static nested class inside ChecksumController (Phase A, A3).
 * Same fields/getters/setters as before.
 */
public class ChecksumRequest {
    private String message;
    private String secretKey;

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
}
