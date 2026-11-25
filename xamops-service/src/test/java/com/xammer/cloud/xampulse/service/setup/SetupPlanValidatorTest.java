package com.xammer.cloud.xampulse.service.setup;

import com.xammer.cloud.xampulse.dto.XpRetention;
import com.xammer.cloud.xampulse.dto.XpSetupPlanRequest;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Every boundary of contract 2.9: retention, region, bucket name, instance ids and chunking, targeting, KMS. */
class SetupPlanValidatorTest {

    private static final String ACCT = "123456789012";
    private static final String KEY = "arn:aws:kms:ap-south-1:123456789012:key/1234abcd-12ab-34cd-56ef-1234567890ab";
    private final SetupPlanValidator validator = new SetupPlanValidator();

    private static XpRetention ret(int hot, int ia, int gir, int exp) {
        return new XpRetention(hot, ia, gir, exp);
    }

    private static List<String> ids(int n) {
        return IntStream.range(0, n).mapToObj(i -> String.format("i-%017x", i + 1)).toList();
    }

    private static XpSetupPlanRequest.RegionSelection instances(String region, List<String> ids) {
        return new XpSetupPlanRequest.RegionSelection(region, "INSTANCES", ids, null, null, null);
    }

    private static XpSetupPlanRequest create(XpRetention r, XpSetupPlanRequest.RegionSelection... regions) {
        return new XpSetupPlanRequest(new XpSetupPlanRequest.Bucket("CREATE", null, "ap-south-1"),
                new XpSetupPlanRequest.Kms("CREATE", null), "ap-south-1", List.of(regions), r, null);
    }

    private static XpSetupPlanRequest existing(String bucket, XpSetupPlanRequest.Kms kms) {
        return new XpSetupPlanRequest(new XpSetupPlanRequest.Bucket("EXISTING", bucket, "ap-south-1"), kms, "ap-south-1",
                List.of(instances("ap-south-1", ids(1))), ret(14, 30, 90, 365), null);
    }

    private XamPulseException rejects(XpSetupPlanRequest req) {
        return assertThrows(XamPulseException.class, () -> validator.validate(req, ACCT));
    }

    private static XpSetupPlanRequest.RegionSelection ok() {
        return instances("ap-south-1", ids(2));
    }

    @Test
    void validDefaultPlanPasses() {
        var v = validator.validate(create(ret(14, 30, 90, 365), ok()), ACCT);
        assertEquals("xamops-xampulse-123456789012-ap-south-1", v.bucketName());
        assertEquals(1, v.chunks().get("ap-south-1").size());
        assertTrue(v.warnings().isEmpty());
    }

    @Test
    void hotDaysBoundaries() {
        for (int d : new int[] {3, 30}) {
            validator.validate(create(ret(d, 30, 90, 365), ok()), ACCT);
        }
        for (int d : new int[] {2, 31}) {
            assertEquals(400, rejects(create(ret(d, 30, 90, 365), ok())).getHttpStatus(), "hotDays " + d);
        }
    }

    @Test
    void infrequentAccessNeedsAtLeastThirtyDays() {
        validator.validate(create(ret(14, 30, 90, 365), ok()), ACCT);
        rejects(create(ret(14, 29, 90, 365), ok()));
    }

    @Test
    void glacierNeedsNinetyDaysAndThirtyAfterIa() {
        validator.validate(create(ret(14, 30, 90, 365), ok()), ACCT);
        rejects(create(ret(14, 30, 89, 365), ok()));
        // IA at 100 means Glacier must be at least 130.
        validator.validate(create(ret(14, 100, 130, 365), ok()), ACCT);
        rejects(create(ret(14, 100, 129, 365), ok()));
    }

    @Test
    void expiryNeedsNinetyDaysAfterGlacierAndAtMostTenYears() {
        validator.validate(create(ret(14, 30, 90, 180), ok()), ACCT);
        rejects(create(ret(14, 30, 90, 179), ok()));
        validator.validate(create(ret(14, 30, 90, 3650), ok()), ACCT);
        rejects(create(ret(14, 30, 90, 3651), ok()));
    }

    @Test
    void allRetentionErrorsAreReportedTogether() {
        XamPulseException e = rejects(create(ret(1, 1, 1, 1), ok()));
        @SuppressWarnings("unchecked") List<String> errors = (List<String>) e.getDetails().get("errors");
        assertTrue(errors.size() >= 4, errors.toString());
    }

    @Test
    void regionMustLookLikeARegion() {
        for (String bad : new String[] {"ap-south", "AP-SOUTH-1", "us_east_1", "", "ap-south-1; rm", "1-south-1"}) {
            XpSetupPlanRequest req = new XpSetupPlanRequest(new XpSetupPlanRequest.Bucket("CREATE", null, "ap-south-1"),
                    new XpSetupPlanRequest.Kms("CREATE", null), bad, List.of(ok()), ret(14, 30, 90, 365), null);
            rejects(req);
        }
        rejects(create(ret(14, 30, 90, 365), instances("not a region", ids(1))));
    }

    @Test
    void newBucketMustUseTheDerivedNameOrAFourCharacterSuffix() {
        XpSetupPlanRequest base = create(ret(14, 30, 90, 365), ok());
        String name = "xamops-xampulse-123456789012-ap-south-1";
        for (String good : new String[] {name, name + "-a1b2"}) {
            validator.validate(withBucket(base, new XpSetupPlanRequest.Bucket("CREATE", good, "ap-south-1")), ACCT);
        }
        for (String bad : new String[] {"my-bucket", name + "-toolong", "xamops-cur-123456789012", name.toUpperCase()}) {
            rejects(withBucket(base, new XpSetupPlanRequest.Bucket("CREATE", bad, "ap-south-1")));
        }
    }

    @Test
    void newBucketIsCreatedInTheHomeRegion() {
        XpSetupPlanRequest base = create(ret(14, 30, 90, 365), ok());
        rejects(withBucket(base, new XpSetupPlanRequest.Bucket("CREATE", null, "us-east-1")));
    }

    @Test
    void existingBucketNamesAreCheckedAgainstS3Rules() {
        var kms = new XpSetupPlanRequest.Kms("AWS_MANAGED", null);
        validator.validate(existing("my-logs.bucket-1", kms), ACCT);
        for (String bad : new String[] {"ab", "UPPER", "-lead", "trail-", "a..b", "192.168.0.1", "x".repeat(64), "bucket_name", "a.-b"}) {
            rejects(existing(bad, kms));
        }
        rejects(existing(null, kms));
    }

    @Test
    void existingBucketStartingWithCurPrefixOnlyWarns() {
        var v = validator.validate(existing("xamops-cur-prod", new XpSetupPlanRequest.Kms("AWS_MANAGED", null)), ACCT);
        assertTrue(v.warnings().stream().anyMatch(w -> w.contains("xamops-cur-")), v.warnings().toString());
    }

    @Test
    void awsManagedEncryptionOnlyWithAnExistingBucket() {
        rejects(withKms(create(ret(14, 30, 90, 365), ok()), new XpSetupPlanRequest.Kms("AWS_MANAGED", null)));
        var v = validator.validate(existing("my-logs", new XpSetupPlanRequest.Kms("AWS_MANAGED", null)), ACCT);
        assertTrue(v.warnings().stream().anyMatch(w -> w.contains("deny snippet")));
    }

    @Test
    void customerKeyNeedsAKmsArn() {
        validator.validate(existing("my-logs", new XpSetupPlanRequest.Kms("CUSTOMER_KEY", KEY)), ACCT);
        rejects(existing("my-logs", new XpSetupPlanRequest.Kms("CUSTOMER_KEY", null)));
        rejects(existing("my-logs", new XpSetupPlanRequest.Kms("CUSTOMER_KEY", "arn:aws:s3:::bucket")));
        rejects(existing("my-logs", new XpSetupPlanRequest.Kms("CREATE", null))); // a new key cannot be applied to a bucket we do not own
        rejects(existing("my-logs", new XpSetupPlanRequest.Kms("NONSENSE", null)));
    }

    @Test
    void instanceIdsMustLookLikeInstanceIds() {
        for (String bad : new String[] {"i-123", "i-ZZZZZZZZ", "vol-0123456789abcdef0", "i-0123456789abcdef01234", "i-0123456789abcdef0; x"}) {
            rejects(create(ret(14, 30, 90, 365), instances("ap-south-1", List.of(bad))));
        }
        validator.validate(create(ret(14, 30, 90, 365), instances("ap-south-1", List.of("i-0123abcd", "i-0123456789abcdef0"))), ACCT);
    }

    @Test
    void instanceListsAreCappedAt500AndChunkedBy50() {
        assertEquals(1, chunks(50).size());
        assertEquals(50, chunks(50).get(0).size());
        assertEquals(List.of(50, 1), chunks(51).stream().map(List::size).toList());
        assertEquals(10, chunks(500).size());
        assertEquals(400, rejects(create(ret(14, 30, 90, 365), instances("ap-south-1", ids(501)))).getHttpStatus());
    }

    private List<List<String>> chunks(int n) {
        return validator.validate(create(ret(14, 30, 90, 365), instances("ap-south-1", ids(n))), ACCT).chunks().get("ap-south-1");
    }

    @Test
    void duplicateIdsAreCollapsedBeforeChunking() {
        List<String> dup = new ArrayList<>(ids(3));
        dup.addAll(ids(3));
        var v = validator.validate(create(ret(14, 30, 90, 365), instances("ap-south-1", dup)), ACCT);
        assertEquals(3, v.chunks().get("ap-south-1").get(0).size());
    }

    @Test
    void anInstanceSelectionMustNotBeEmpty() {
        rejects(create(ret(14, 30, 90, 365), instances("ap-south-1", List.of())));
        rejects(create(ret(14, 30, 90, 365), instances("ap-south-1", null)));
        rejects(create(ret(14, 30, 90, 365)));
    }

    @Test
    void allManagedNeedsExplicitConfirmation() {
        var unconfirmed = new XpSetupPlanRequest.RegionSelection("ap-south-1", "ALL_MANAGED", null, null, null, null);
        var no = new XpSetupPlanRequest.RegionSelection("ap-south-1", "ALL_MANAGED", null, null, null, false);
        var yes = new XpSetupPlanRequest.RegionSelection("ap-south-1", "ALL_MANAGED", null, null, null, true);
        rejects(create(ret(14, 30, 90, 365), unconfirmed));
        rejects(create(ret(14, 30, 90, 365), no));
        validator.validate(create(ret(14, 30, 90, 365), yes), ACCT);
    }

    @Test
    void tagTargetingNeedsAKey() {
        rejects(create(ret(14, 30, 90, 365), new XpSetupPlanRequest.RegionSelection("ap-south-1", "TAG", null, null, null, null)));
        rejects(create(ret(14, 30, 90, 365), new XpSetupPlanRequest.RegionSelection("ap-south-1", "TAG", null, "bad;key", "v", null)));
        validator.validate(create(ret(14, 30, 90, 365), new XpSetupPlanRequest.RegionSelection("ap-south-1", "TAG", null, "xamops:observe", "true", null)), ACCT);
    }

    @Test
    void duplicateRegionsAndUnknownModesAreRejected() {
        rejects(create(ret(14, 30, 90, 365), ok(), ok()));
        rejects(create(ret(14, 30, 90, 365), new XpSetupPlanRequest.RegionSelection("ap-south-1", "EVERYTHING", null, null, null, null)));
    }

    @Test
    void customLogPathsMustBeAbsoluteWithoutTraversal() {
        for (String bad : new String[] {"relative/path", "/var/log/../../etc/shadow", null}) {
            XpSetupPlanRequest base = create(ret(14, 30, 90, 365), ok());
            rejects(new XpSetupPlanRequest(base.bucket(), base.kms(), base.homeRegion(), base.regions(), base.retention(),
                    new XpSetupPlanRequest.Sources(true, false, List.of(new XpSetupPlanRequest.CustomSource(bad, null)))));
        }
    }

    @Test
    void incompleteRequestsAndBadAwsAccountIdsAreRejected() {
        assertThrows(XamPulseException.class, () -> validator.validate(null, ACCT));
        assertThrows(XamPulseException.class, () -> validator.validate(create(ret(14, 30, 90, 365), ok()), "abc"));
        assertThrows(XamPulseException.class, () -> validator.validate(create(ret(14, 30, 90, 365), ok()), null));
    }

    private static XpSetupPlanRequest withBucket(XpSetupPlanRequest r, XpSetupPlanRequest.Bucket b) {
        return new XpSetupPlanRequest(b, r.kms(), r.homeRegion(), r.regions(), r.retention(), r.sources());
    }

    private static XpSetupPlanRequest withKms(XpSetupPlanRequest r, XpSetupPlanRequest.Kms k) {
        return new XpSetupPlanRequest(r.bucket(), k, r.homeRegion(), r.regions(), r.retention(), r.sources());
    }
}
