package com.twixor.base64convertor.qrgeneration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.zxing.qrcode.QRCodeWriter;
import lombok.Data;

@Data
public class QrGeneratorRequest {
//    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String url;

    public void setUrl(String url) {
        this.url = url;
    }
}