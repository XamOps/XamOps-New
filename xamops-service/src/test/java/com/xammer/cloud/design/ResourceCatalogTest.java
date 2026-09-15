package com.xammer.cloud.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.design.catalog.ResourceCatalog;
import com.xammer.cloud.design.catalog.ResourceTypeSpec;
import com.xammer.cloud.design.catalog.ResourceTypeSpec.AttributeKind;
import com.xammer.cloud.design.hcl.HclEmitter;
import com.xammer.cloud.design.model.DesignModel;
import com.xammer.cloud.design.model.DesignModel.ResourceNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the catalog itself.
 *
 * <p>
 * The catalog is data, which makes it cheap to extend and easy to get subtly
 * wrong. {@link ResourceCatalog} already fails startup on an inconsistent entry;
 * these tests assert the properties that self-check cannot see — that the canvas
 * vocabulary lines up with the frontend's, and that every declared type can
 * actually be emitted.
 */
class ResourceCatalogTest {

    private ResourceCatalog catalog;
    private HclEmitter emitter;
    private DesignValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new ResourceCatalog(new ObjectMapper());
        Method load = ResourceCatalog.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(catalog);
        emitter = new HclEmitter(catalog);
        validator = new DesignValidator(catalog);
    }

    @Test
    @DisplayName("the catalog loads and its own consistency check passes")
    void loadsCleanly() {
        // load() throws on an inconsistent catalog, so reaching here is the assertion.
        assertTrue(catalog.size() >= 9, "expected at least the nine seeded types, got " + catalog.size());
    }

    @Test
    @DisplayName("the types needed to express a routable three-tier VPC are present")
    void coversARoutableArchitecture() {
        for (String tfType : List.of(
                "aws_vpc", "aws_subnet", "aws_security_group", "aws_instance",
                "aws_internet_gateway", "aws_nat_gateway",
                "aws_lb", "aws_db_instance", "aws_s3_bucket")) {
            assertTrue(catalog.find(tfType).isPresent(), "missing catalog entry: " + tfType);
        }
    }

    @Test
    @DisplayName("every canvasType matches a key the frontend palette already uses")
    void canvasTypesMatchTheFrontendVocabulary() {
        // These are NODE_TYPE_DATA keys in frontend-app/src/types/cloudmap.ts. A
        // mismatch here is invisible at compile time and shows up as a resource the
        // canvas can draw but the translator silently skips.
        var knownCanvasTypes = List.of(
                "VPC", "Subnet", "Security Group", "EC2 Instance",
                "Internet Gateway", "NAT Gateway", "Load Balancer",
                "RDS Instance", "S3 Bucket");

        for (ResourceTypeSpec spec : catalog.all()) {
            assertNotNull(spec.canvasType(), spec.tfType() + " has no canvasType");
            assertTrue(knownCanvasTypes.contains(spec.canvasType()),
                    "%s maps to canvasType '%s', which is not a NODE_TYPE_DATA key"
                            .formatted(spec.tfType(), spec.canvasType()));
            // And the reverse lookup the translator relies on must work.
            assertEquals(spec.tfType(),
                    catalog.findByCanvasType(spec.canvasType()).orElseThrow().tfType());
        }
    }

    @Test
    @DisplayName("every type emits valid-looking Terraform from defaults alone")
    void everyTypeEmitsFromDefaults() {
        // A palette drop with no configuration must still produce something, because
        // that is exactly what a user does first.
        for (ResourceTypeSpec spec : catalog.all()) {
            var node = ResourceNode.builder("n1", spec.tfType(), "probe").build();
            String main = emitter.emit(new DesignModel("probe", "us-east-1", List.of(node)))
                    .get(HclEmitter.FILE_MAIN);

            assertTrue(main.contains("resource \"%s\" \"probe\" {".formatted(spec.tfType())),
                    spec.tfType() + " did not emit a resource block:\n" + main);
            // Placeholders would mean an unsubstituted template rather than generated code.
            assertFalse(main.contains("{{"), spec.tfType() + " emitted a template placeholder");
            assertFalse(main.contains("null"), spec.tfType() + " emitted a null literal:\n" + main);
        }
    }

    @Test
    @DisplayName("required attributes without a default are reported, not silently emitted")
    void requiredAttributesWithoutDefaultsAreFlagged() {
        for (ResourceTypeSpec spec : catalog.all()) {
            var node = ResourceNode.builder("n1", spec.tfType(), "probe").build();
            var result = validator.validate(new DesignModel("d", "us-east-1", List.of(node)));

            long missing = spec.attributes().stream()
                    .filter(a -> a.required() && !a.hasDefault())
                    .count();
            long errorsForMissingAttributes = result.errors().stream()
                    .filter(f -> f.field() != null)
                    .filter(f -> f.message().contains("is required"))
                    .count();

            assertEquals(missing, errorsForMissingAttributes,
                    spec.tfType() + ": every required attribute without a default should be an error");
        }
    }

    @Test
    @DisplayName("a promoted variable always has a default, or plan would prompt")
    void promotedVariablesHaveDefaults() {
        for (ResourceTypeSpec spec : catalog.all()) {
            spec.attributes().stream()
                    .filter(ResourceTypeSpec.AttributeSpec::promoteToVariable)
                    .forEach(a -> assertTrue(a.hasDefault(),
                            "%s.%s is promoted to a variable with no default"
                                    .formatted(spec.tfType(), a.name())));
        }
    }

    @Test
    @DisplayName("enum attributes only offer values the provider accepts")
    void enumsDeclareOptionsAndDefaultIsAmongThem() {
        for (ResourceTypeSpec spec : catalog.all()) {
            for (var attr : spec.attributes()) {
                if (attr.kind() != AttributeKind.ENUM) {
                    continue;
                }
                assertFalse(attr.options().isEmpty(),
                        "%s.%s is an ENUM with no options".formatted(spec.tfType(), attr.name()));
                if (attr.hasDefault()) {
                    assertTrue(attr.options().contains(attr.defaultValue()),
                            "%s.%s default '%s' is not one of %s"
                                    .formatted(spec.tfType(), attr.name(),
                                            attr.defaultValue(), attr.options()));
                }
            }
        }
    }

    @Test
    @DisplayName("a routable three-tier design generates end to end")
    void generatesARoutableThreeTier() {
        var vpc = ResourceNode.builder("vpc", "aws_vpc", "main")
                .attr("cidr_block", "10.40.0.0/16").build();
        var igw = ResourceNode.builder("igw", "aws_internet_gateway", "edge")
                .ref("vpc_id", "vpc").build();
        var publicSubnet = ResourceNode.builder("pub", "aws_subnet", "public_1a")
                .attr("cidr_block", "10.40.1.0/24").attr("map_public_ip_on_launch", true)
                .ref("vpc_id", "vpc").build();
        var privateSubnet = ResourceNode.builder("priv", "aws_subnet", "private_1a")
                .attr("cidr_block", "10.40.11.0/24").ref("vpc_id", "vpc").build();
        var nat = ResourceNode.builder("nat", "aws_nat_gateway", "egress")
                .attr("allocation_id", "eipalloc-123").ref("subnet_id", "pub").build();
        var sg = ResourceNode.builder("sg", "aws_security_group", "app")
                .attr("name", "app-sg").ref("vpc_id", "vpc").build();
        var alb = ResourceNode.builder("alb", "aws_lb", "edge")
                .attr("name", "edge-alb")
                .refs("subnets", List.of("pub"))
                .refs("security_groups", List.of("sg")).build();
        var db = ResourceNode.builder("db", "aws_db_instance", "orders")
                .attr("identifier", "orders").attr("engine", "postgres")
                .refs("vpc_security_group_ids", List.of("sg")).build();
        var bucket = ResourceNode.builder("s3", "aws_s3_bucket", "artifacts")
                .attr("bucket", "acme-prod-artifacts").build();
        var ec2 = ResourceNode.builder("ec2", "aws_instance", "app")
                .attr("ami", "ami-123").ref("subnet_id", "priv")
                .refs("vpc_security_group_ids", List.of("sg")).build();

        var design = new DesignModel("three tier", "ap-south-1",
                List.of(vpc, igw, publicSubnet, privateSubnet, nat, sg, alb, db, bucket, ec2));

        var validation = validator.validate(design);
        assertTrue(validation.canGenerate(), () -> "unexpected errors: " + validation.errors());

        Map<String, String> files = emitter.emit(design);
        String main = files.get(HclEmitter.FILE_MAIN);

        // Cross-resource wiring across all nine types.
        assertTrue(main.contains("vpc_id = aws_vpc.main.id"), main);
        assertTrue(main.contains("subnet_id = aws_subnet.public_1a.id"), main);
        assertTrue(main.contains("subnets = [aws_subnet.public_1a.id]"), main);
        assertTrue(main.contains("security_groups = [aws_security_group.app.id]"), main);
        assertTrue(main.contains("vpc_security_group_ids = [aws_security_group.app.id]"), main);
        // Both promoted variables present and referenced.
        assertTrue(files.get(HclEmitter.FILE_VARIABLES).contains("variable \"app_instance_type\""), main);
        assertTrue(files.get(HclEmitter.FILE_VARIABLES).contains("variable \"orders_instance_class\""), main);
        assertTrue(main.contains("instance_class = var.orders_instance_class"), main);
        // Secure defaults carried through rather than being dropped.
        assertTrue(main.contains("storage_encrypted = true"), main);
    }
}
