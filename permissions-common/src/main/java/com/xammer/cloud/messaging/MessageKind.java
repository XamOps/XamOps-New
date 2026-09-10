package com.xammer.cloud.messaging;

/**
 * Which notification is being sent, and therefore which configured template and body pattern
 * apply.
 *
 * <p>Exists because each kind needs its own separately-approved template on both channels — DLT
 * approves a specific piece of SMS wording, and Meta approves a specific WhatsApp template. A
 * single "send text" entry point would have no way to pick between them.
 */
public enum MessageKind {

    /** Threshold breach digest, e.g. disks near full. */
    ALERT,

    /** Scheduled FinOps cost report summary. */
    REPORT,

    /** Unread support message reminder from the BillOps auto-ping scheduler. */
    SUPPORT;

    String smsTemplateId(MessagingProperties.Sms sms) {
        return switch (this) {
            case ALERT -> sms.getAlertTemplateId();
            case REPORT -> sms.getReportTemplateId();
            case SUPPORT -> sms.getSupportTemplateId();
        };
    }

    String smsBodyPattern(MessagingProperties.Sms sms) {
        return switch (this) {
            case ALERT -> sms.getAlertBody();
            case REPORT -> sms.getReportBody();
            case SUPPORT -> sms.getSupportBody();
        };
    }

    String whatsAppTemplateId(MessagingProperties.WhatsApp whatsapp) {
        return switch (this) {
            case ALERT -> whatsapp.getAlertTemplateId();
            case REPORT -> whatsapp.getReportTemplateId();
            case SUPPORT -> whatsapp.getSupportTemplateId();
        };
    }
}
