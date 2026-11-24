package com.xammer.cloud.service.autospotting.aws.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AwsEventEnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static String body(String id, String detailType, String account, String detail) {
        return "{\"version\":\"0\",\"id\":\"" + id + "\",\"detail-type\":\"" + detailType
                + "\",\"source\":\"aws.ec2\",\"account\":\"" + account
                + "\",\"time\":\"2026-11-09T10:15:30Z\",\"region\":\"ap-south-1\",\"resources\":[],\"detail\":" + detail + "}";
    }

    @Test
    void parsesTheForwarderEnvelope() {
        AwsEventEnvelope e = AwsEventEnvelope.parse(MAPPER,
                body("ev-1", AwsEventEnvelope.INTERRUPTION, "123456789012", "{\"instance-id\":\"i-0abc\"}")).orElseThrow();
        assertThat(e.id()).isEqualTo("ev-1");
        assertThat(e.account()).isEqualTo("123456789012");
        assertThat(e.region()).isEqualTo("ap-south-1");
        assertThat(e.detailText("instance-id")).isEqualTo("i-0abc");
        assertThat(e.time()).isNotNull();
    }

    @Test
    void anUnparseableBodyIsEmpty() {
        assertThat(AwsEventEnvelope.parse(MAPPER, "not json")).isEmpty();
        assertThat(AwsEventEnvelope.parse(MAPPER, "")).isEmpty();
        assertThat(AwsEventEnvelope.parse(MAPPER, null)).isEmpty();
        assertThat(AwsEventEnvelope.parse(MAPPER, "[1,2]")).isEmpty();
    }

    @Test
    void aMissingIdAccountOrDetailIsEmpty() {
        assertThat(AwsEventEnvelope.parse(MAPPER, "{\"detail-type\":\"x\",\"account\":\"123456789012\",\"detail\":{}}")).isEmpty();
        assertThat(AwsEventEnvelope.parse(MAPPER, body("e", "x", "12345", "{}"))).isEmpty();
        assertThat(AwsEventEnvelope.parse(MAPPER, "{\"id\":\"e\",\"detail-type\":\"x\",\"account\":\"123456789012\"}")).isEmpty();
    }

    @Test
    void anOverlongIdIsRefusedAndAnOverlongDetailTypeIsTruncated() {
        assertThat(AwsEventEnvelope.parse(MAPPER, body("x".repeat(129), "t", "123456789012", "{}"))).isEmpty();
        assertThat(AwsEventEnvelope.parse(MAPPER, body("e", "t".repeat(200), "123456789012", "{}")).orElseThrow()
                .detailType()).hasSize(80);
    }
}
