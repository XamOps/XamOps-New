package com.xammer.cloud.service.inventory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the state column the cloud list renders in its STATE badge.
 *
 * <p>
 * Regression test for the plane shipping every resource with a null state, which the UI
 * rendered as "Unknown" across the board where the describe path had shown "Available".
 * Resource Explorer reports no state at all, so this is not something the plane can read —
 * it has to be reconstructed, and these tests fix how.
 *
 * <p>
 * The distinction being protected is between types whose state is a foregone conclusion and
 * types where it genuinely varies. Getting that boundary wrong fails in one of two ways:
 * too few static defaults and the UI says "Unknown" for things that are plainly fine; too
 * many and the plane asserts a state it never checked, which is how a stopped instance ends
 * up displayed as running.
 */
class InventoryResourceStateTest {

    /**
     * Strings are compared exactly, including casing. The frontend maps state to a badge
     * colour, so "available" and "Available" are not interchangeable even though they read
     * the same.
     */
    @Test
    void staticStatesMatchTheStringsTheDescribePathEmitted() {
        assertEquals("Available", AwsResourceTypeMapper.staticStateFor("s3:bucket"));
        assertEquals("Available", AwsResourceTypeMapper.staticStateFor("ec2:security-group"));
        assertEquals("Available", AwsResourceTypeMapper.staticStateFor("route53:hostedzone"));
        assertEquals("Active", AwsResourceTypeMapper.staticStateFor("lambda:function"));
        assertEquals("Active", AwsResourceTypeMapper.staticStateFor("sqs:queue"));
        assertEquals("Active", AwsResourceTypeMapper.staticStateFor("logs:log-group"));
        // CloudListService really does use lowercase here while using "Available"
        // elsewhere. Preserved deliberately rather than normalised.
        assertEquals("available", AwsResourceTypeMapper.staticStateFor("ec2:internet-gateway"));
    }

    /**
     * The types where a guess would be a lie. These must stay absent so the reconciler
     * knows to spend a describe call on them.
     */
    @Test
    void typesWithRealStateHaveNoStaticDefault() {
        for (String type : List.of("ec2:instance", "ec2:volume", "rds:db")) {
            assertNull(AwsResourceTypeMapper.staticStateFor(type),
                    type + " has genuinely variable state — a static default would assert "
                            + "something never checked, e.g. showing a stopped instance as running");
        }
    }

    /** An unmapped type must degrade to "Unknown", not to a wrong guess. */
    @Test
    void unknownTypesGetNoInventedState() {
        assertNull(AwsResourceTypeMapper.staticStateFor("somenewservice:widget"));
        assertNull(AwsResourceTypeMapper.staticStateFor(null));
    }

    /**
     * The most visible types on the cloud list must all render something. This is the check
     * that would have caught the original bug: it asserts coverage rather than individual
     * values, so a type added to the display map without a state is flagged.
     */
    @Test
    void everydayTypesAllResolveToAState() {
        List<String> shouldNeverBeUnknown = List.of(
                "s3:bucket", "lambda:function", "ec2:security-group", "ec2:vpc",
                "ec2:subnet", "sns:topic", "sqs:queue", "logs:log-group",
                "events:rule", "route53:hostedzone", "ec2:internet-gateway",
                "ec2:natgateway", "ec2:snapshot", "elasticloadbalancing:loadbalancer/app");
        for (String type : shouldNeverBeUnknown) {
            assertNotNull(AwsResourceTypeMapper.staticStateFor(type),
                    type + " would render as Unknown in the cloud list");
        }
    }
}
