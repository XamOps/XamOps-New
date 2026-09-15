package com.xammer.cloud.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Credentials and per-channel settings for the outbound SMS / WhatsApp / RCS provider.
 *
 * <p>Everything here is off by default and every secret is an env-var placeholder. A
 * deployment that sets nothing keeps the current behaviour exactly: email only, with the
 * SMS and WhatsApp calls skipped rather than failing.
 *
 * <p><b>Template IDs are not optional.</b> The provider is DLT-registered (India), so an
 * SMS is rejected unless {@code sender_id} is an approved header and {@code template_id} is
 * the approved DLT template that the message body matches. WhatsApp is the same shape with
 * Meta-approved templates. This is why alert and report bodies are rendered as a small
 * ordered variable map rather than free prose — free text cannot be delivered on either
 * channel. See {@code AlertTextBuilder} for the variable contract.
 */
@ConfigurationProperties(prefix = "xamops.messaging")
public class MessagingProperties {

    /** Master switch. When false no provider call is attempted on any channel. */
    private boolean enabled = false;

    /**
     * Fallback API root for channels that do not set their own, no trailing slash.
     *
     * <p>Usually leave this blank and set the per-channel hosts instead. Xamping serves each
     * product from a separate host, so there is no single root that answers for both: probing
     * {@code sms.xammer.in} with a WhatsApp path returns a plain 404, while the same path on
     * {@code whatsapp.xammer.in} returns a proper {@code API_KEY_MISSING} error. This exists only
     * for a deployment that fronts several products behind one gateway.
     */
    private String baseUrl = "";

    /**
     * Provider {@code api_key}. Passed as a query parameter because that is what the
     * provider requires; it is therefore kept out of every log line by
     * {@link SmsWhatsAppClient}, which logs the path only.
     */
    private String apiKey = "";

    /**
     * Dial code prepended to stored contact numbers that have none. {@code app_user.contact}
     * is free text with no validation, so most rows are bare 10-digit numbers.
     */
    private String defaultCountryCode = "+91";

    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Read timeout. Deliberately short: these calls run on a {@code @Scheduled} thread that
     * also drives email, and the existing {@code new RestTemplate()} usages in the codebase
     * have no timeout at all — a hung provider would stall the whole alert scan.
     */
    private Duration readTimeout = Duration.ofSeconds(10);

    private final Sms sms = new Sms();
    private final WhatsApp whatsapp = new WhatsApp();

    /** True when the master switch, an API key, and at least one channel host are all present. */
    public boolean isUsable() {
        return enabled && !apiKey.isBlank()
                && !(whatsappBaseUrl().isBlank() && smsBaseUrl().isBlank());
    }

    /**
     * Host serving the WhatsApp endpoints, e.g. {@code https://whatsapp.xammer.in}.
     *
     * <p>Per-channel because Xamping serves each product from its own host. Verified by probing:
     * {@code POST /api/v1/whatsapp/single} on the SMS host returns a bare 404, while the same path
     * on the WhatsApp host returns a proper {@code API_KEY_MISSING} error. A single shared root
     * would silently post every WhatsApp message to the wrong service.
     */
    public String whatsappBaseUrl() {
        return whatsapp.getBaseUrl().isBlank() ? baseUrl : whatsapp.getBaseUrl();
    }

    /** Host serving the SMS endpoints, e.g. {@code https://sms.xammer.in}. */
    public String smsBaseUrl() {
        return sms.getBaseUrl().isBlank() ? baseUrl : sms.getBaseUrl();
    }

    /** Strips trailing slashes so joining with a spec path cannot produce "//api/v1/...". */
    private static String normaliseBaseUrl(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = normaliseBaseUrl(baseUrl);
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getDefaultCountryCode() {
        return defaultCountryCode;
    }

    public void setDefaultCountryCode(String defaultCountryCode) {
        this.defaultCountryCode = defaultCountryCode;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Sms getSms() {
        return sms;
    }

    public WhatsApp getWhatsapp() {
        return whatsapp;
    }

    /** SMS channel settings. Maps onto {@code POST /v1/sms/countrycode}. */
    public static class Sms {

        private boolean enabled = false;

        /** Host serving the SMS endpoints, e.g. {@code https://sms.xammer.in}. */
        private String baseUrl = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = normaliseBaseUrl(baseUrl);
        }

        /** DLT-approved header, e.g. {@code XAMMER}. Sent as {@code sender_id}. */
        private String senderId = "";

        /**
         * One of {@code otp | transactional | promotional | global}. Alerts and reports are
         * account-activity notifications to existing customers, so {@code transactional} is
         * the correct route — {@code promotional} is subject to DND filtering and would drop
         * a meaningful share of alerts silently.
         */
        private String route = "transactional";

        /** DLT template ID the alert body must match. */
        private String alertTemplateId = "";

        /** DLT template ID the report body must match. */
        private String reportTemplateId = "";

        /** DLT template ID for the support auto-ping reminder in BillOps. */
        private String supportTemplateId = "";

        /*
         * Body patterns, rendered by MessageTemplate before sending.
         *
         * Written in real DLT syntax: bare, repeated {#var#}, filled positionally. That is what the
         * DLT portal actually approves — an approved template on this account reads
         * "Dear {#var#}, Scale securely with Xammer {#var#} ... Connect: {#var#}".
         *
         * These MUST match the approved wording character for character, because
         * POST /v1/sms/countrycode takes both `message` and `template_id` and the provider compares
         * them. The portal link is static text, not a variable: fewer variables to register, and
         * the target never varies.
         */

        /** {#var#} order: account name, alert count, critical count. */
        private String alertBody =
                "XamOps alert for {#var#}: {#var#} resource(s) need attention, {#var#} critical. Log in to XamOps to review. - Xammer";

        /**
         * {#var#} order: frequency, account name, date range, total spend — matching the order
         * {@code FinOpsReportTextBuilder} emits. Positional placeholders make a reordering here
         * silently render "total spend 2026-09-14" rather than failing, so the two must be changed
         * together.
         */
        private String reportBody =
                "{#var#} XamOps FinOps report for {#var#} ({#var#}): total spend {#var#}. Log in for the full breakdown. - Xammer";

        /** {#var#} order: username, account name. */
        private String supportBody =
                "Hi {#var#}, you have an unread XamOps support message about {#var#}. Please reply in the XamOps portal. - Xammer";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSenderId() {
            return senderId;
        }

        public void setSenderId(String senderId) {
            this.senderId = senderId == null ? "" : senderId.trim();
        }

        public String getRoute() {
            return route;
        }

        public void setRoute(String route) {
            this.route = route;
        }

        public String getAlertTemplateId() {
            return alertTemplateId;
        }

        public void setAlertTemplateId(String alertTemplateId) {
            this.alertTemplateId = alertTemplateId == null ? "" : alertTemplateId.trim();
        }

        public String getReportTemplateId() {
            return reportTemplateId;
        }

        public void setReportTemplateId(String reportTemplateId) {
            this.reportTemplateId = reportTemplateId == null ? "" : reportTemplateId.trim();
        }

        public String getSupportTemplateId() {
            return supportTemplateId;
        }

        public void setSupportTemplateId(String supportTemplateId) {
            this.supportTemplateId = supportTemplateId == null ? "" : supportTemplateId.trim();
        }

        public String getAlertBody() {
            return alertBody;
        }

        public void setAlertBody(String alertBody) {
            this.alertBody = alertBody;
        }

        public String getReportBody() {
            return reportBody;
        }

        public void setReportBody(String reportBody) {
            this.reportBody = reportBody;
        }

        public String getSupportBody() {
            return supportBody;
        }

        public void setSupportBody(String supportBody) {
            this.supportBody = supportBody;
        }
    }

    /** WhatsApp channel settings. Maps onto {@code POST /api/v1/whatsapp/single}. */
    public static class WhatsApp {

        private boolean enabled = false;

        /** Host serving the WhatsApp endpoints, e.g. {@code https://whatsapp.xammer.in}. */
        private String baseUrl = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = normaliseBaseUrl(baseUrl);
        }

        /** Registered WhatsApp Business number in E.164, sent as {@code sender}. */
        private String sender = "";

        /** Meta-approved template for alerts. */
        private String alertTemplateId = "";

        /** Meta-approved template for reports. */
        private String reportTemplateId = "";

        /** Meta-approved template for the support auto-ping reminder. */
        private String supportTemplateId = "";

        /**
         * When true, a failed WhatsApp send is retried over SMS to the same number.
         *
         * <p>This is an application-level fallback, not the provider's
         * {@code /api/v1/rcs/with_fallback} endpoint — that endpoint sends <em>RCS</em> with an
         * SMS backup and would not deliver a WhatsApp message at all. Requires the SMS channel
         * to be configured, since the retry uses the SMS sender and template.
         */
        private boolean smsFallback = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSender() {
            return sender;
        }

        public void setSender(String sender) {
            this.sender = sender == null ? "" : sender.trim();
        }

        public String getAlertTemplateId() {
            return alertTemplateId;
        }

        public void setAlertTemplateId(String alertTemplateId) {
            this.alertTemplateId = alertTemplateId == null ? "" : alertTemplateId.trim();
        }

        public String getReportTemplateId() {
            return reportTemplateId;
        }

        public void setReportTemplateId(String reportTemplateId) {
            this.reportTemplateId = reportTemplateId == null ? "" : reportTemplateId.trim();
        }

        public String getSupportTemplateId() {
            return supportTemplateId;
        }

        public void setSupportTemplateId(String supportTemplateId) {
            this.supportTemplateId = supportTemplateId == null ? "" : supportTemplateId.trim();
        }

        public boolean isSmsFallback() {
            return smsFallback;
        }

        public void setSmsFallback(boolean smsFallback) {
            this.smsFallback = smsFallback;
        }
    }
}
