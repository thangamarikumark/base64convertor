package com.twixor.base64convertor.pdf.dto;

import jakarta.validation.constraints.NotBlank;

public class PdfBase64Request {

    @NotBlank(message = "URL must not be empty")
    private String url;

    private String cookie;
    private String payload;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getCookie() { return cookie; }
    public void setCookie(String cookie) { this.cookie = cookie; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
