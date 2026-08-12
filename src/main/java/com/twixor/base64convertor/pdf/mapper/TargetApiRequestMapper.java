package com.twixor.base64convertor.pdf.mapper;

import com.twixor.base64convertor.pdf.dto.PdfRequest;
import com.twixor.base64convertor.pdf.dto.TargetApiRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * PdfRequest -> TargetApiRequest mapping, extracted verbatim from PdfController's private
 * buildTargetApiRequest/buildTargetHeaders methods (Phase A, A10). Field-copy logic and the
 * existing quirk where TargetApiRequest.Reference is always built empty are preserved
 * unchanged, per the "do not fix existing behavior" constraint.
 */
@Component
public class TargetApiRequestMapper {

    public TargetApiRequest toTargetApiRequest(PdfRequest req) {
        TargetApiRequest target = new TargetApiRequest();
        PdfRequest.Message srcMsg = req.getMessage();
        if (srcMsg == null) return target;

        TargetApiRequest.Message tgtMsg = new TargetApiRequest.Message();
        tgtMsg.setChannel(srcMsg.getChannel());

        if (srcMsg.getContent() != null && srcMsg.getContent().getAttachment() != null) {
            TargetApiRequest.Content tgtContent = new TargetApiRequest.Content();
            tgtContent.setType(srcMsg.getContent().getType());

            TargetApiRequest.Attachment att = new TargetApiRequest.Attachment();
            var srcAtt = srcMsg.getContent().getAttachment();
            att.setType(srcAtt.getType());
            att.setCaption(srcAtt.getCaption());
            att.setFileName(srcAtt.getFileName());
            att.setMimeType(srcAtt.getMimeType());
            att.setAttachmentData(srcAtt.getAttachmentData());
            tgtContent.setAttachment(att);
            tgtMsg.setContent(tgtContent);
        }

        if (srcMsg.getRecipient() != null) {
            TargetApiRequest.Recipient tgtRecipient = new TargetApiRequest.Recipient();
            tgtRecipient.setTo(srcMsg.getRecipient().getTo());
            tgtRecipient.setRecipient_type(srcMsg.getRecipient().getRecipient_type());

            TargetApiRequest.Reference tgtReference = new TargetApiRequest.Reference();
            PdfRequest.Reference srcReference = srcMsg.getRecipient().getReference();
            if (srcReference != null && srcReference.hasData()) {
                tgtReference.setRefId(srcReference.getRefId());
            }
            tgtRecipient.setReference(tgtReference);

            tgtMsg.setRecipient(tgtRecipient);
        }

        if (srcMsg.getSender() != null) {
            TargetApiRequest.Sender sender = new TargetApiRequest.Sender();
            sender.setFrom(srcMsg.getSender().getFrom());
            tgtMsg.setSender(sender);
        }

        if (srcMsg.getPreferences() != null) {
            TargetApiRequest.Preferences prefs = new TargetApiRequest.Preferences();
            prefs.setWebHookDNId(srcMsg.getPreferences().getWebHookDNId());
            tgtMsg.setPreferences(prefs);
        }

        target.setMessage(tgtMsg);

        if (req.getMetaData() != null) {
            TargetApiRequest.MetaData meta = new TargetApiRequest.MetaData();
            meta.setVersion(req.getMetaData().getVersion());
            target.setMetaData(meta);
        }

        return target;
    }

    public HttpHeaders buildTargetHeaders(PdfRequest req, String defaultToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT_CHARSET, "UTF-8");

        String targetAuth = req.getTarget_auth();
        if (targetAuth != null && !targetAuth.isBlank()) {
            headers.set("Authorization", targetAuth.trim());
        } else {
            headers.set("Authorization", "Bearer " + defaultToken);
        }
        return headers;
    }
}
