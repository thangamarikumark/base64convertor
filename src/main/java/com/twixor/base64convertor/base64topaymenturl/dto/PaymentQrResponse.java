package com.twixor.base64convertor.base64topaymenturl.dto;

import com.twixor.base64convertor.base64topaymenturl.dto.PaymentQrData;
import lombok.Data;

@Data
public class PaymentQrResponse {

    private String status;
    private String statusCode;
    private PaymentQrData data;
}