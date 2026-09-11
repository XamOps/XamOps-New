package com.xammer.cloud.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.design.catalog.ResourceCatalog;
import com.xammer.cloud.design.hcl.HclEmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end-to-end property the properties form exists to deliver: values typed on
 * the canvas reach the generated Terraform.
 *
 * <p>
 * Until the form existed, every resource carried catalog defaults — every VPC was
 * 10.0.0.0/16 and every database a db.t3.micro — which made the feature a demo
 * rather than a tool. The form writes attributes onto Cytoscape node data under
 * the attribute's own name; these tests use the same canvas JSON shape the browser
 * saves, so they verify the whole path (form → node data → graph_data →
 * translator → emitter) without a browser.
 */
class ConfiguredDesignTest {

    private CanvasDesignTranslator translator;
    private DesignValidator validator;
    private HclEmitter emitter;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResourceCatalog catalog = new ResourceCatalog(mapper);
        Method load = ResourceCatalog.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(catalog);

        translator = new CanvasDesignTranslator(mapper, catalog);
        validator = new DesignValidator(catalog);
        emitter = new HclEmitter(catalog);
    }

    private String generate(String canvasJson) {
        var result = translator.translate(canvasJson, "configured", "ap-south-1");
        var validation = validator.validate(result.design());
        assertTrue(validation.canGenerate(), () -> "unexpected errors: " + validation.errors());
        return emitter.emit(result.design()).get(HclEmitter.FILE_MAIN);
    }

    @Test
    @DisplayName("values set on the canvas override catalog defaults")
    void configuredValuesReachTheOutput() {
        // Exactly what the form writes: attributes on node data, by their own name.
        String configured = """
                [
                  {"group":"nodes","data":{
                    "id":"n1","label":"payments","type":"VPC",
                    "cidr_block":"172.19.0.0/16",
                    "enable_dns_hostnames":false
                  }}
                ]
                """;

        String main = generate(configured);

        assertTrue(main.contains("cidr_block = \"172.19.0.0/16\""), main);
        assertFalse(main.contains("10.0.0.0/16"), "the catalog default should have been overridden:\n" + main);
        // A boolean explicitly turned off must not be replaced by the default true.
        assertTrue(main.contains("enable_dns_hostnames = false"), main);
        assertTrue(main.contains("enable_dns_support = true"), "untouched defaults still apply:\n" + main);
    }

    @Test
    @DisplayName("an unset attribute falls back to its catalog default")
    void unsetAttributesFallBack() {
        String bare = """
                [{"group":"nodes","data":{"id":"n1","label":"core","type":"VPC"}}]
                """;
        String main = generate(bare);
        assertTrue(main.contains("cidr_block = \"10.0.0.0/16\""), main);
    }

    @Test
    @DisplayName("numbers typed as strings still emit unquoted")
    void numericStringsEmitAsNumbers() {
        // An HTML number input hands back a string; allocated_storage is a number in
        // HCL, and "20" would be a type error at plan time.
        String db = """
                [{"group":"nodes","data":{
                  "id":"n1","label":"orders","type":"RDS Instance",
                  "identifier":"orders","engine":"mysql","allocated_storage":"100",
                  "backup_retention_period":30
                }}]
                """;

        String main = generate(db);
        assertTrue(main.contains("allocated_storage = 100"), main);
        assertFalse(main.contains("allocated_storage = \"100\""), "must not quote a number:\n" + main);
        assertTrue(main.contains("backup_retention_period = 30"), main);
        assertTrue(main.contains("engine = \"mysql\""), main);
    }

    @Test
    @DisplayName("tags edited in the form are emitted as a map")
    void tagsFromTheFormAreEmitted() {
        String tagged = """
                [{"group":"nodes","data":{
                  "id":"n1","label":"artifacts","type":"S3 Bucket",
                  "bucket":"acme-prod-artifacts",
                  "tags":{"Environment":"prod","Owner":"platform"}
                }}]
                """;

        String main = generate(tagged);
        assertTrue(main.contains("Environment = \"prod\""), main);
        assertTrue(main.contains("Owner = \"platform\""), main);
        // Keys are sorted, so output does not depend on map iteration order.
        assertTrue(main.indexOf("Environment") < main.indexOf("Owner"), main);
    }

    @Test
    @DisplayName("a promoted attribute becomes a variable carrying the configured value")
    void promotedAttributesCarryConfiguredDefaults() {
        String sized = """
                [{"group":"nodes","data":{
                  "id":"n1","label":"web","type":"EC2 Instance",
                  "ami":"ami-abc","instance_type":"m6i.2xlarge"
                }}]
                """;

        var result = translator.translate(sized, "sized", "ap-south-1");
        var files = emitter.emit(result.design());

        // The value lands in variables.tf, and the resource references the variable
        // rather than inlining it.
        assertTrue(files.get(HclEmitter.FILE_VARIABLES).contains("default = \"m6i.2xlarge\""),
                files.get(HclEmitter.FILE_VARIABLES));
        assertTrue(files.get(HclEmitter.FILE_MAIN).contains("instance_type = var.web_instance_type"),
                files.get(HclEmitter.FILE_MAIN));
    }

    @Test
    @DisplayName("an enum set to a non-default option is honoured")
    void enumSelectionIsHonoured() {
        String nlb = """
                [{"group":"nodes","data":{
                  "id":"n1","label":"edge","type":"Load Balancer",
                  "name":"edge-nlb","load_balancer_type":"network","internal":true
                }}]
                """;

        String main = generate(nlb);
        assertTrue(main.contains("load_balancer_type = \"network\""), main);
        assertTrue(main.contains("internal = true"), main);
    }

    @Test
    @DisplayName("an invalid CIDR typed on the canvas blocks generation")
    void invalidValuesBlockGeneration() {
        String broken = """
                [{"group":"nodes","data":{"id":"n1","label":"core","type":"VPC","cidr_block":"10.0.0.0/64"}}]
                """;

        var result = translator.translate(broken, "broken", "us-east-1");
        var validation = validator.validate(result.design());

        assertFalse(validation.canGenerate(), "a /64 IPv4 prefix is not valid");
        assertTrue(validation.errors().stream().anyMatch(f -> "cidr_block".equals(f.field())),
                validation.errors().toString());
        // And the finding names the node, so the canvas can mark that card.
        assertTrue(validation.errors().stream().anyMatch(f -> "n1".equals(f.nodeId())),
                validation.errors().toString());
    }

    @Test
    @DisplayName("presentation values the form never writes cannot reach the output")
    void styleEditsDoNotLeak() {
        // The style tab writes fillColor, borderWidth and friends onto the same node
        // data. They must stay out of the resource block.
        String styled = """
                [{"group":"nodes","data":{
                  "id":"n1","label":"core","type":"VPC","cidr_block":"10.1.0.0/16",
                  "fillColor":"#22c55e","borderWidth":3,"fontSize":14,"nodeShape":"ellipse",
                  "rotation":45,"opacity":0.8
                }}]
                """;

        String main = generate(styled);
        for (String leaked : new String[] { "fillColor", "#22c55e", "borderWidth", "fontSize", "nodeShape", "rotation", "opacity" }) {
            assertFalse(main.contains(leaked), leaked + " leaked into the Terraform:\n" + main);
        }
        assertTrue(main.contains("cidr_block = \"10.1.0.0/16\""), main);
    }
}
