package com.twixor.base64convertor.qrgeneration.dto;

import lombok.Data;

@Data
public class QrGeneratorResponse {
    private String qrCode;
    private String errorCode;
    private String errorMessage;


    public void setQrCode(String qrCode) {
        this.qrCode = qrCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}