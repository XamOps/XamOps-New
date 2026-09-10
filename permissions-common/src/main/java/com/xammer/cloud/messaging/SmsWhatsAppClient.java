package com.xammer.cloud.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the outbound SMS / WhatsApp / RCS provider.
 *
 * <p>Endpoints used, both authenticated with {@code ?api_key=}:
 * <ul>
 *   <li>{@code POST /api/v1/whatsapp/single} — one templated WhatsApp message</li>
 *   <li>{@code POST /v1/sms/countrycode} — SMS to an E.164 number</li>
 * </ul>
 *
 * <p>The provider also publishes bulk and RCS-with-fallback endpoints. Neither is implemented:
 * bulk cannot report which recipient failed, which the per-recipient SMS fallback depends on, and
 * the RCS endpoint sends RCS rather than WhatsApp and needs a third, separately-approved template
 * namespace. Both are straightforward to add on top of {@link #post} when there is a use for them.
 *
 * <p><b>Contract:</b> no method throws. Each returns a {@link MessageResult} whose status
 * separates "not configured" ({@code SKIPPED}) from "provider said no" ({@code FAILED}), so a
 * deployment that has not set credentials stays silent while a real delivery problem is logged.
 * This mirrors the never-throw behaviour of {@code ExternalMessagingService} but keeps the
 * reason, which a bare null return loses.
 *
 * <p><b>Templates are mandatory.</b> The provider is DLT-registered, so SMS needs an approved
 * {@code sender_id} plus the {@code template_id} whose text the body matches, and WhatsApp needs
 * a Meta-approved template. Sending free-form text on either channel is rejected upstream, which
 * is why callers supply a variable map rather than a finished string.
 */
@Service
public class SmsWhatsAppClient {

    private static final Logger log = LoggerFactory.getLogger(SmsWhatsAppClient.class);

    private static final String PATH_WHATSAPP_SINGLE = "/api/v1/whatsapp/single";
    private static final String PATH_SMS_COUNTRY_CODE = "/v1/sms/countrycode";

    /** Single-segment SMS is 160 GSM-7 characters; longer bodies are billed per segment. */
    private static final int SMS_SEGMENT_LENGTH = 160;

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final MessagingProperties properties;

    public SmsWhatsAppClient(@Qualifier("messagingRestClient") RestClient restClient,
                             MessagingProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    // ── WhatsApp ─────────────────────────────────────────────────────────────

    /**
     * Sends one templated WhatsApp message.
     *
     * @param rawContact stored contact value; normalised to E.164 here
     * @param templateId Meta-approved template
     * @param variables  template placeholder values, sent as {@code sample}; may be empty
     */
    public MessageResult sendWhatsApp(String rawContact, String templateId, Map<String, String> variables) {
        MessagingProperties.WhatsApp cfg = properties.getWhatsapp();

        MessageResult unusable = checkChannelUsable(NotificationChannel.WHATSAPP, cfg.isEnabled(), templateId);
        if (unusable != null) {
            return unusable;
        }
        if (cfg.getSender().isBlank()) {
            return MessageResult.skipped(NotificationChannel.WHATSAPP, "***",
                    "no WhatsApp sender configured");
        }

        String to = PhoneNumbers.toE164(rawContact, properties.getDefaultCountryCode());
        if (to == null) {
            return MessageResult.skipped(NotificationChannel.WHATSAPP, "***",
                    "contact is not a dialable number");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message_type", "text");
        payload.put("sender", cfg.getSender());
        payload.put("to", to);
        payload.put("template_id", templateId);
        if (variables != null && !variables.isEmpty()) {
            payload.put("sample", variables);
        }

        return post(NotificationChannel.WHATSAPP, PATH_WHATSAPP_SINGLE, payload, PhoneNumbers.mask(to));
    }

    // ── SMS ──────────────────────────────────────────────────────────────────

    /**
     * Sends an SMS via {@code /v1/sms/countrycode}.
     *
     * @param body       message text; must match the DLT template registered under
     *                   {@code templateId} or the provider rejects it
     * @param templateId approved DLT template ID
     */
    public MessageResult sendSms(String rawContact, String body, String templateId) {
        MessagingProperties.Sms cfg = properties.getSms();

        MessageResult unusable = checkChannelUsable(NotificationChannel.SMS, cfg.isEnabled(), templateId);
        if (unusable != null) {
            return unusable;
        }
        if (cfg.getSenderId().isBlank()) {
            return MessageResult.skipped(NotificationChannel.SMS, "***", "no SMS sender_id configured");
        }
        if (body == null || body.isBlank()) {
            return MessageResult.skipped(NotificationChannel.SMS, "***", "empty message body");
        }

        String to = PhoneNumbers.toE164(rawContact, properties.getDefaultCountryCode());
        if (to == null) {
            return MessageResult.skipped(NotificationChannel.SMS, "***",
                    "contact is not a dialable number");
        }

        if (body.length() > SMS_SEGMENT_LENGTH) {
            // Not an error — just billed as multiple segments. Worth surfacing because the
            // report and alert summaries are built to stay inside one segment.
            log.debug("SMS body is {} chars — will be billed as {} segments",
                    body.length(), (body.length() + SMS_SEGMENT_LENGTH - 1) / SMS_SEGMENT_LENGTH);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sender_id", cfg.getSenderId());
        payload.put("to", List.of(to));
        payload.put("route", cfg.getRoute());
        payload.put("message", body);
        payload.put("template_id", templateId);

        return post(NotificationChannel.SMS, PATH_SMS_COUNTRY_CODE, payload, PhoneNumbers.mask(to));
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    /**
     * Shared precondition check. Returns a SKIPPED result when the channel cannot be used, or
     * null when it can.
     */
    private MessageResult checkChannelUsable(NotificationChannel channel, boolean channelEnabled, String templateId) {
        if (!properties.isEnabled()) {
            return MessageResult.skipped(channel, "***", "messaging disabled");
        }
        if (properties.getBaseUrl().isBlank() || properties.getApiKey().isBlank()) {
            return MessageResult.skipped(channel, "***", "provider base-url or api-key not set");
        }
        if (!channelEnabled) {
            return MessageResult.skipped(channel, "***", channel + " channel disabled");
        }
        if (templateId == null || templateId.isBlank()) {
            return MessageResult.skipped(channel, "***", "no " + channel + " template configured");
        }
        return null;
    }

    /**
     * Performs the call and classifies the outcome.
     *
     * <p>Uses {@code exchange} so a 4xx/5xx returns normally instead of throwing: a rejected
     * message is an expected outcome here, not an exceptional one. Only the path is ever
     * logged — the URI carries the {@code api_key} and must not reach the log.
     */
    private MessageResult post(NotificationChannel channel, String path,
                               Map<String, Object> payload, String recipientLabel) {
        URI uri = UriComponentsBuilder.fromUriString(properties.getBaseUrl())
                .path(path)
                .queryParam("api_key", properties.getApiKey())
                .encode()
                .build()
                .toUri();

        try {
            return restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((request, response) -> {
                        Map<String, Object> body = readBody(response);
                        boolean httpOk = response.getStatusCode().is2xxSuccessful();

                        if (!httpOk) {
                            String detail = "HTTP " + response.getStatusCode().value()
                                    + describe(body);
                            log.error("{} send to {} rejected by provider {}: {}",
                                    channel, recipientLabel, path, detail);
                            return MessageResult.failed(channel, recipientLabel, detail);
                        }

                        // The provider returns 200 with {"success": false} on validation
                        // errors such as an unapproved template, so the flag is authoritative
                        // where present. The WhatsApp and SMS responses are undocumented, so
                        // absence of the flag is treated as success on a 2xx.
                        Object success = body == null ? null : body.get("success");
                        if (Boolean.FALSE.equals(success) || "false".equals(String.valueOf(success))) {
                            String detail = "provider reported failure" + describe(body);
                            log.error("{} send to {} failed at {}: {}",
                                    channel, recipientLabel, path, detail);
                            return MessageResult.failed(channel, recipientLabel, detail);
                        }

                        String messageId = extractMessageId(body);
                        log.info("{} sent to {} (provider ref {})", channel, recipientLabel,
                                messageId == null ? "n/a" : messageId);
                        return MessageResult.sent(channel, recipientLabel, messageId);
                    });
        } catch (Exception e) {
            // Timeout, DNS failure, TLS problem, malformed response. Never propagates:
            // one undeliverable notification must not abort an alert scan or report run.
            log.error("{} send to {} errored at {}: {}", channel, recipientLabel, path, e.getMessage());
            return MessageResult.failed(channel, recipientLabel, e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private Map<String, Object> readBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(JSON_MAP);
        } catch (Exception e) {
            // Non-JSON or empty body — the status code is still meaningful.
            return null;
        }
    }

    /** Appends the provider's own message text to a failure detail, when it sent one. */
    private String describe(Map<String, Object> body) {
        if (body == null) {
            return "";
        }
        Object message = body.get("message");
        if (message == null) {
            message = body.get("error");
        }
        return message == null ? "" : " — " + message;
    }

    /**
     * Pulls a provider reference out of the response so a delivery can be traced in the
     * provider's dashboard. Key names differ per endpoint and the WhatsApp/SMS response
     * schemas are undocumented, so several shapes are tried.
     */
    @SuppressWarnings("unchecked")
    private String extractMessageId(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Object results = body.get("results");
        if (results instanceof Map<?, ?> map) {
            Object id = firstNonNull(map, "transaction_id", "message_id", "id");
            if (id != null) {
                return String.valueOf(id);
            }
        }
        if (results instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            Object id = firstNonNull((Map<String, Object>) first, "transaction_id", "message_id", "id");
            if (id != null) {
                return String.valueOf(id);
            }
        }
        Object id = firstNonNull(body, "transaction_id", "message_id", "id");
        return id == null ? null : String.valueOf(id);
    }

    private Object firstNonNull(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
