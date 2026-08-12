package com.twixor.base64convertor.filestorage.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Base64SaveRequest {

    @NotBlank(message = "base64Content is required")
    private String base64Content;

    @NotBlank(message = "fileName is required")
    private String fileName;

    // Capture any extra parameters (including custom metadata)
    @JsonAnySetter
    private Map<String, Object> extraParams = new HashMap<>();

    public Map<String, Object> getExtraParams() {
        return extraParams;
    }
}
