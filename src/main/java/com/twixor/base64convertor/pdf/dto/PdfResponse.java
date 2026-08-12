package com.twixor.base64convertor.pdf.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Data
@NoArgsConstructor
public class PdfResponse {
    private String fileName;
    private String status;
    private String base64;
    private String mimeType;  // <-- new field to handle mime type
    private long size;
    public PdfResponse(String fileName, String success, String base64) {
    }
    public PdfResponse(String fileName, String status, String base64, String mimeType) {
        this.fileName = fileName;
        this.status = status;
        this.base64 = base64;
        this.mimeType = mimeType;
    }



public PdfResponse(String fileName, String status, String base64, String mimeType,long size) {
    this.fileName = fileName;
    this.status = status;
    this.base64 = base64;
    this.mimeType = mimeType;
    this.size = size;
}


    @Override
    public String toString() {
        return "PdfResponse{" +
                "fileName='" + fileName + '\'' +
                ", status='" + status + '\'' +
                ", mimeType='" + mimeType + '\'' +
                ", size=" + size +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PdfResponse)) return false;
        PdfResponse that = (PdfResponse) o;
        return size == that.size &&
                Objects.equals(fileName, that.fileName) &&
                Objects.equals(status, that.status) &&
                Objects.equals(base64, that.base64) &&
                Objects.equals(mimeType, that.mimeType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, status, base64, mimeType, size);
    }




}
