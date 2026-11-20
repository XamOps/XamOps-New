package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Build 7 design 4.3 and 5.4: the public path's datastore id and its file ids. Synthetic ids only. */
class PublicLineageKeyTest {

    private static final String HEX = "0123456789abcdef01234567";
    private static final String RESOURCE = "0000000e-0000-4000-8000-00000000000f";
    private static final String UUID = "0000000c-0000-4000-8000-00000000000d";
    private static final String DOC_ID = "Doc_Synthetic-0001";

    private static void assertRejected(String raw) {
        assertThatThrownBy(() -> PublicLineageKey.parse("datastoreId", raw))
                .as("%s", raw)
                .isInstanceOfSatisfying(DspmBadRequestException.class, e -> {
                    assertThat(e.parameter()).isEqualTo("datastoreId");
                    assertThat(e.getMessage()).isEqualTo("datastoreId must be a datastore of the lineage page");
                });
    }

    @Test
    @DisplayName("an endpoint id parses, the agent id lower-cased, and formats back")
    void endpoint() {
        PublicLineageKey key = PublicLineageKey.parse("datastoreId", "ep-" + HEX.toUpperCase());

        assertThat(key).isEqualTo(new PublicLineageKey(PublicLineageKey.Kind.ENDPOINT, HEX, null, null));
        assertThat(key.value()).isEqualTo("ep-" + HEX);
        assertThat(PublicLineageKey.endpoint(HEX.toUpperCase())).isEqualTo(key);
    }

    @Test
    @DisplayName("a drive id parses, the integration id lower-cased and the resource id kept as sent")
    void drive() {
        String mixedResource = "Res_Mixed-Case9";
        PublicLineageKey key = PublicLineageKey.parse("datastoreId", "dr-" + HEX.toUpperCase() + "-" + mixedResource);

        assertThat(key).isEqualTo(new PublicLineageKey(PublicLineageKey.Kind.DRIVE, null, HEX, mixedResource));
        assertThat(key.value()).isEqualTo("dr-" + HEX + "-" + mixedResource);
        assertThat(PublicLineageKey.parse("datastoreId", "dr-" + HEX + "-" + RESOURCE).resourceId()).isEqualTo(RESOURCE);
        assertThat(PublicLineageKey.drive(HEX.toUpperCase(), mixedResource)).isEqualTo(key);
    }

    @Test
    @DisplayName("the 64-character resource bound: 64 passes, 65 is rejected")
    void resourceBound() {
        String r64 = "r".repeat(64);
        assertThat(PublicLineageKey.parse("datastoreId", "dr-" + HEX + "-" + r64).resourceId()).isEqualTo(r64);
        assertRejected("dr-" + HEX + "-" + "r".repeat(65));
        assertThat(PublicLineageKey.drive(HEX, r64)).isNotNull();
        assertThat(PublicLineageKey.drive(HEX, "r".repeat(65))).isNull();
    }

    @Test
    @DisplayName("prefixes, separators, lengths and characters are rejected with the parameter name")
    void rejected() {
        Arrays.asList(null, "", HEX, "ep-", "ep-" + HEX + "0", "ep-" + HEX.substring(1), "ep-zz23456789abcdef01234567",
                        "EP-" + HEX, "ep_" + HEX, " ep-" + HEX, "ep-" + HEX + " ", "dr-" + HEX, "dr-" + HEX + "-",
                        "dr-" + HEX + "_" + RESOURCE, "dr-" + HEX.substring(1) + "-" + RESOURCE,
                        "dr-" + HEX + "-a/b", "dr-" + HEX + "-a.b", "dr-" + HEX + "-a b", "DR-" + HEX + "-x",
                        "xx-" + HEX, "ep-" + HEX + "-x")
                .forEach(PublicLineageKeyTest::assertRejected);
        assertThatThrownBy(() -> PublicLineageKey.parse("other", "nope"))
                .isInstanceOfSatisfying(DspmBadRequestException.class, e ->
                        assertThat(e.parameter()).isEqualTo("other"));
    }

    @Test
    @DisplayName("endpoint() and drive() answer null when a part fails its rule")
    void factoriesAnswerNull() {
        assertThat(PublicLineageKey.endpoint(null)).isNull();
        assertThat(PublicLineageKey.endpoint(HEX + "0")).isNull();
        assertThat(PublicLineageKey.endpoint("not-hex-not-hex-not-hex!")).isNull();
        assertThat(PublicLineageKey.drive(null, RESOURCE)).isNull();
        assertThat(PublicLineageKey.drive("short", RESOURCE)).isNull();
        assertThat(PublicLineageKey.drive(HEX, null)).isNull();
        assertThat(PublicLineageKey.drive(HEX, "")).isNull();
        assertThat(PublicLineageKey.drive(HEX, "a/b")).isNull();
    }

    @Test
    @DisplayName("fileId: an endpoint takes a UUID, lower-cased; a drive takes a doc id kept as sent; cross-kind rejected")
    void fileIds() {
        PublicLineageKey endpoint = PublicLineageKey.endpoint(HEX);
        PublicLineageKey drive = PublicLineageKey.drive(HEX, RESOURCE);

        assertThat(endpoint.fileId("fileId", UUID.toUpperCase())).isEqualTo(UUID);
        assertThat(drive.fileId("fileId", DOC_ID)).isEqualTo(DOC_ID);
        assertThat(drive.fileId("fileId", "D".repeat(128))).isEqualTo("D".repeat(128));

        for (String bad : Arrays.asList(null, "", DOC_ID, HEX, UUID + "0", UUID.replace('-', '_'))) {
            assertThatThrownBy(() -> endpoint.fileId("fileId", bad)).as("%s", bad)
                    .isInstanceOfSatisfying(DspmBadRequestException.class, e -> {
                        assertThat(e.parameter()).isEqualTo("fileId");
                        assertThat(e.getMessage()).isEqualTo("fileId must be a UUID for an endpoint file");
                    });
        }
        for (String bad : Arrays.asList(null, "", UUID, UUID.toUpperCase(), "D".repeat(129), "doc.pdf", "a/b", "a b")) {
            assertThatThrownBy(() -> drive.fileId("fileId", bad)).as("%s", bad)
                    .isInstanceOfSatisfying(DspmBadRequestException.class, e -> {
                        assertThat(e.parameter()).isEqualTo("fileId");
                        assertThat(e.getMessage()).isEqualTo("fileId must be a drive document id");
                    });
        }
    }
}
