package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XamPulseTemplateServiceTest {

    private static XamPulseTemplateService service(boolean uat) {
        XamPulseProperties p = new XamPulseProperties();
        p.getSetup().setUat(uat);
        p.getSetup().setTemplateBaseUrl("https://templates.example.com/xampulse/");
        p.getSetup().setApiBaseUrl("https://live.example.com/");
        p.getSetup().setReleaseBaseUrl("https://releases.example.com/xampulse/");
        return new XamPulseTemplateService(p);
    }

    @Test
    void quickCreateUrlCarriesTheEncodedTemplateUrlStackNameAndParameters() {
        XamPulseTemplateService s = service(false);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("BucketName", "xamops-xampulse-123456789012-ap-south-1");
        params.put("InstanceIds", "i-0123abcd,i-4567ef01");
        params.put("PulseExternalId", "xp-abc_DEF-123");
        String url = s.quickCreateUrl("ap-south-1", s.templateUrl(s.template(false)), XamPulseTemplateService.stackName("123456789012"), params);

        assertTrue(url.startsWith("https://ap-south-1.console.aws.amazon.com/cloudformation/home?region=ap-south-1#/stacks/quickcreate?"), url);
        assertTrue(url.contains("templateURL=https%3A%2F%2Ftemplates.example.com%2Fxampulse%2Fxamops-xampulse.yml"), url);
        assertTrue(url.contains("stackName=XamOpsXamPulse-123456789012"), url);
        assertTrue(url.contains("param_InstanceIds=i-0123abcd%2Ci-4567ef01"), url);
        assertTrue(url.contains("param_BucketName=xamops-xampulse-123456789012-ap-south-1"), url);
        assertFalse(url.contains(" "));
    }

    @Test
    void stackNamesFollowThePlan() {
        assertEquals("XamOpsXamPulse-123456789012", XamPulseTemplateService.stackName("123456789012"));
        assertEquals("XamOpsXamPulseRegion-123456789012-eu-west-1", XamPulseTemplateService.regionalStackName("123456789012", "eu-west-1"));
    }

    @Test
    void parameterValuesCannotInjectExtraParameters() {
        XamPulseTemplateService s = service(false);
        String url = s.quickCreateUrl("us-east-1", "https://t.example.com/x.yml", "n", Map.of("ObserveTagKey", "a&param_PulseExternalId=evil#frag"));
        URI uri = URI.create(url);
        String fragment = uri.getRawFragment();
        assertEquals(1, fragment.split("&param_", -1).length - 1, fragment);
        assertFalse(fragment.contains("&param_PulseExternalId"), fragment);
        assertEquals("a&param_PulseExternalId=evil#frag",
                URLDecoder.decode(fragment.substring(fragment.indexOf("param_ObserveTagKey=") + 20), StandardCharsets.UTF_8));
    }

    @Test
    void templateHashIsTheShaOfTheShippedFileAndUatUsesItsOwnFiles() {
        XamPulseTemplateService prod = service(false);
        XamPulseTemplateService.Template t = prod.template(false);
        assertEquals("xamops-xampulse.yml", t.fileName());
        assertEquals(64, t.sha256().length());
        assertEquals(XamPulseTemplateService.sha256(t.content()), t.sha256());
        assertEquals("xamops-xampulse-region.yml", prod.template(true).fileName());
        assertEquals("xamops-xampulse-uat.yml", service(true).template(false).fileName());
        assertEquals("xamops-xampulse-region-uat.yml", service(true).template(true).fileName());
        assertEquals("-uat", service(true).resourceSuffix());
        assertEquals("", prod.resourceSuffix());
        assertNotEquals(t.sha256(), service(true).template(false).sha256());
    }

    @Test
    void templateHostingMustBeConfiguredAndHttps() {
        XamPulseProperties p = new XamPulseProperties();
        XamPulseTemplateService s = new XamPulseTemplateService(p);
        XamPulseTemplateService.Template t = s.template(false);
        assertThrows(XamPulseException.class, () -> s.templateUrl(t));
        p.getSetup().setTemplateBaseUrl("http://insecure.example.com");
        assertThrows(XamPulseException.class, () -> s.templateUrl(t));
    }

    @Test
    void installScriptHasItsTwoUrlsFilledInAndNoPlaceholdersLeft() {
        XamPulseTemplateService s = service(false);
        String script = s.installScript(s.releaseBaseUrl(), s.desiredStateUrl());
        assertTrue(script.contains("RELEASE_BASE_URL=\"https://releases.example.com/xampulse\""), "release url");
        assertTrue(script.contains("DESIRED_STATE_URL=\"https://live.example.com/api/v1/agent/xampulse/desired-state\""), "desired state url");
        assertFalse(script.contains("{{"), "placeholders must all be replaced");
    }

    @Test
    void installScriptRefusesUrlsThatCouldBreakOutOfTheShellString() {
        XamPulseTemplateService s = service(false);
        for (String bad : new String[] {"https://x.com/\"; rm -rf /; \"", "http://x.com", "https://x.com/$(id)", "https://x.com/a b"}) {
            assertThrows(XamPulseException.class, () -> s.installScript(bad, "https://ok.example.com/x"), bad);
        }
    }

    @Test
    void externalIdIsRandomUrlSafeAndKeptOnceCreated() {
        ExternalIdService ids = new ExternalIdService();
        String a = ids.generate();
        String b = ids.generate();
        assertNotEquals(a, b);
        assertTrue(a.matches("^xp-[A-Za-z0-9_-]{43}$"), a);
        com.xammer.cloud.xampulse.domain.XpAccountConfig cfg = new com.xammer.cloud.xampulse.domain.XpAccountConfig();
        String first = ids.getOrCreate(cfg);
        assertEquals(first, cfg.getArchiveExternalIdEnc());
        assertEquals(first, ids.getOrCreate(cfg));
    }
}
