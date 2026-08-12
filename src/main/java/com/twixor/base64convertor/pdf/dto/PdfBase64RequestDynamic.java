package com.twixor.base64convertor.pdf.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Setter;

import java.util.Map;

public class PdfBase64RequestDynamic {

    @NotBlank(message = "URL must not be empty")
    private String url;

    private String cookie;
    private String httpMethod;
    private Map<String, Object> payload;

    @Setter
    private String authorization;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getCookie() { return cookie; }
    public void setCookie(String cookie) { this.cookie = cookie; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public String getAuthorization() { return authorization; }
}
