package com.xammer.cloud.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.design.catalog.ResourceCatalog;
import com.xammer.cloud.design.hcl.HclEmitter;
import com.xammer.cloud.design.model.DesignModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that Terraform generation reads the canvas format already in use, and
 * that adding it cannot degrade the diagram editor.
 *
 * <p>
 * The load-bearing assertion is {@link #freeformShapesAreSkippedNotFailed()}: the
 * design canvas is a general drawing tool, and a diagram containing rectangles,
 * text and custom icons has to keep working. If generation ever started rejecting
 * such a diagram, the feature would have made the product worse.
 */
class CanvasDesignTranslatorTest {

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

    /**
     * Exactly what the canvas writes: cy.elements().jsons() with palette nodes
     * carrying {id,label,type,_iconUrl}, containment via parent, and edgehandles
     * edges. Includes presentation keys, because real saved data has them.
     */
    private static final String REAL_CANVAS_JSON = """
            [
              {"group":"nodes","data":{"id":"n-vpc","label":"prod-vpc","type":"VPC","cidr_block":"10.24.0.0/16","fillColor":"#22c55e","borderWidth":2},"position":{"x":100,"y":100}},
              {"group":"nodes","data":{"id":"n-subnet","label":"prod-private-1a","type":"Subnet","parent":"n-vpc","cidr_block":"10.24.11.0/24","availability_zone":"ap-south-1a"},"position":{"x":150,"y":180}},
              {"group":"nodes","data":{"id":"n-sg","label":"prod-web-sg","type":"Security Group","parent":"n-vpc","name":"prod-web-sg"},"position":{"x":400,"y":180}},
              {"group":"nodes","data":{"id":"n-ec2","label":"prod-web-1","type":"EC2 Instance","parent":"n-subnet","ami":"ami-0f58b397bc5c1f04a","instance_type":"t3.large","_iconUrl":"/icons/ec2-instances.png"},"position":{"x":180,"y":260}},
              {"group":"edges","data":{"id":"e1","source":"n-ec2","target":"n-sg","label":"uses SG"}}
            ]
            """;

    @Test
    @DisplayName("a real saved canvas becomes Terraform with references resolved")
    void translatesRealCanvas() {
        var result = translator.translate(REAL_CANVAS_JSON, "prod", "ap-south-1");

        assertEquals(4, result.resourceCount());
        assertTrue(result.skipped().isEmpty(), "every shape here is a real resource");

        var validation = validator.validate(result.design());
        assertTrue(validation.canGenerate(),
                () -> "unexpected errors: " + validation.errors());

        String main = emitter.emit(result.design()).get(HclEmitter.FILE_MAIN);

        // Containment on the canvas became Terraform references.
        assertTrue(main.contains("vpc_id = aws_vpc.prod_vpc.id"), main);
        assertTrue(main.contains("subnet_id = aws_subnet.prod_private_1a.id"), main);
        // The edge became a list-valued reference.
        assertTrue(main.contains("vpc_security_group_ids = [aws_security_group.prod_web_sg.id]"), main);
        // Attributes typed on the canvas came through.
        assertTrue(main.contains("cidr_block = \"10.24.11.0/24\""), main);
    }

    @Test
    @DisplayName("presentation data never leaks into a resource block")
    void ignoresPresentationKeys() {
        var result = translator.translate(REAL_CANVAS_JSON, "prod", "ap-south-1");
        String main = emitter.emit(result.design()).get(HclEmitter.FILE_MAIN);

        // The canvas stores colour and geometry on the same objects as meaning.
        assertFalse(main.contains("fillColor"), main);
        assertFalse(main.contains("#22c55e"), main);
        assertFalse(main.contains("borderWidth"), main);
        assertFalse(main.contains("_iconUrl"), main);
        assertFalse(main.contains("/icons/"), main);
    }

    @Test
    @DisplayName("freeform shapes are skipped and reported, never treated as errors")
    void freeformShapesAreSkippedNotFailed() {
        // A diagram the way people actually draw them: real resources mixed with
        // a group box, a text label, a generic shape and a custom icon.
        String mixed = """
                [
                  {"group":"nodes","data":{"id":"n-vpc","label":"prod-vpc","type":"VPC","cidr_block":"10.0.0.0/16"}},
                  {"group":"nodes","data":{"id":"n-group","label":"Payments platform","isContainer":true,"containerType":"region"}},
                  {"group":"nodes","data":{"id":"n-note","label":"TODO: add NAT","type":"shape","nodeShape":"rectangle"}},
                  {"group":"nodes","data":{"id":"n-icon","label":"Datadog","customIcon":"datadog"}},
                  {"group":"nodes","data":{"id":"n-subnet","label":"web","type":"Subnet","parent":"n-vpc","cidr_block":"10.0.1.0/24"}},
                  {"group":"edges","data":{"id":"e1","source":"n-note","target":"n-subnet","label":""}}
                ]
                """;

        var result = translator.translate(mixed, "mixed", "us-east-1");

        assertEquals(2, result.resourceCount(), "only the VPC and subnet are resources");
        assertEquals(3, result.skipped().size(), "group box, note and custom icon are skipped");

        // Skipping must be explained, so the UI can tell the user what is covered.
        result.skipped().forEach(s ->
                assertFalse(s.reason() == null || s.reason().isBlank(), "every skip needs a reason"));

        // And crucially: the design still generates.
        var validation = validator.validate(result.design());
        assertTrue(validation.canGenerate(),
                () -> "a diagram containing drawings must still generate: " + validation.errors());
        assertTrue(emitter.emit(result.design()).get(HclEmitter.FILE_MAIN).contains("aws_subnet"));
    }

    @Test
    @DisplayName("containment resolves through a decorative group box")
    void walksThroughNonResourceContainers() {
        // Subnet sits inside a plain group, which sits inside the VPC. vpc_id must
        // still resolve, or the design fails for a reason invisible on screen.
        String nested = """
                [
                  {"group":"nodes","data":{"id":"n-vpc","label":"core","type":"VPC","cidr_block":"10.0.0.0/16"}},
                  {"group":"nodes","data":{"id":"n-group","label":"AZ a","isContainer":true,"parent":"n-vpc"}},
                  {"group":"nodes","data":{"id":"n-subnet","label":"app","type":"Subnet","parent":"n-group","cidr_block":"10.0.1.0/24"}}
                ]
                """;

        var result = translator.translate(nested, "nested", "us-east-1");
        assertTrue(validator.validate(result.design()).canGenerate());
        assertTrue(emitter.emit(result.design()).get(HclEmitter.FILE_MAIN)
                .contains("vpc_id = aws_vpc.core.id"));
    }

    @Test
    @DisplayName("an edge works whichever way the user drew it")
    void toleratesEdgeDirection() {
        String reversed = """
                [
                  {"group":"nodes","data":{"id":"n-vpc","label":"core","type":"VPC","cidr_block":"10.0.0.0/16"}},
                  {"group":"nodes","data":{"id":"n-subnet","label":"app","type":"Subnet","parent":"n-vpc","cidr_block":"10.0.1.0/24"}},
                  {"group":"nodes","data":{"id":"n-sg","label":"web","type":"Security Group","parent":"n-vpc","name":"web"}},
                  {"group":"nodes","data":{"id":"n-ec2","label":"srv","type":"EC2 Instance","parent":"n-subnet","ami":"ami-1"}},
                  {"group":"edges","data":{"id":"e1","source":"n-sg","target":"n-ec2","label":"protects"}}
                ]
                """;

        // Drawn security-group -> instance, but the attribute lives on the instance.
        String main = emitter.emit(translator.translate(reversed, "d", "us-east-1").design())
                .get(HclEmitter.FILE_MAIN);
        assertTrue(main.contains("vpc_security_group_ids = [aws_security_group.web.id]"), main);
    }

    @Test
    @DisplayName("a node label becomes a Name tag")
    void infersNameTag() {
        var result = translator.translate(REAL_CANVAS_JSON, "prod", "ap-south-1");
        String main = emitter.emit(result.design()).get(HclEmitter.FILE_MAIN);
        assertTrue(main.contains("Name = \"prod-vpc\""), main);
        assertTrue(main.contains("Name = \"prod-web-1\""), main);
    }

    @Test
    @DisplayName("the legacy {nodes,edges} save format still loads")
    void supportsLegacyGraphShape() {
        // useCloudMap.ts still handles this older shape on load, so this must too.
        String legacy = """
                {
                  "nodes":[
                    {"data":{"id":"n1","label":"core","type":"VPC","cidr_block":"10.0.0.0/16"}},
                    {"data":{"id":"n2","label":"app","type":"Subnet","parent":"n1","cidr_block":"10.0.1.0/24"}}
                  ],
                  "edges":[]
                }
                """;

        var result = translator.translate(legacy, "legacy", "us-east-1");
        assertEquals(2, result.resourceCount());
        assertTrue(emitter.emit(result.design()).get(HclEmitter.FILE_MAIN)
                .contains("vpc_id = aws_vpc.core.id"));
    }

    @Test
    @DisplayName("a decorative connection between two resources is noted, not wired")
    void reportsMeaninglessConnections() {
        String odd = """
                [
                  {"group":"nodes","data":{"id":"a","label":"one","type":"VPC","cidr_block":"10.0.0.0/16"}},
                  {"group":"nodes","data":{"id":"b","label":"two","type":"VPC","cidr_block":"10.1.0.0/16"}},
                  {"group":"edges","data":{"id":"e","source":"a","target":"b","label":"peers with"}}
                ]
                """;

        var result = translator.translate(odd, "d", "us-east-1");
        assertEquals(2, result.resourceCount());
        assertEquals(1, result.notes().size(), "the connection should be explained");
        assertTrue(result.notes().get(0).toLowerCase().contains("decorative"));
    }

    @Test
    @DisplayName("an empty or absent canvas is not an error")
    void handlesEmptyCanvas() {
        for (String graph : List.of("[]", "")) {
            var result = translator.translate(graph, "empty", "us-east-1");
            assertEquals(0, result.resourceCount());
        }
        assertEquals(0, translator.translate(null, "empty", "us-east-1").resourceCount());
    }

    @Test
    @DisplayName("malformed graph data is rejected clearly rather than half-parsed")
    void rejectsMalformedJson() {
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> translator.translate("{not json", "d", "us-east-1"));
        assertTrue(thrown.getMessage().contains("not valid JSON"));
    }

    @Test
    @DisplayName("a palette node dropped with no configuration still generates")
    void usesCatalogDefaultsForUnconfiguredNodes() {
        // Dropping "VPC" from the palette yields only {id,label,type} — no attributes.
        String bare = """
                [{"group":"nodes","data":{"id":"n1","label":"VPC","type":"VPC"}}]
                """;

        var result = translator.translate(bare, "bare", "us-east-1");
        var validation = validator.validate(result.design());
        assertTrue(validation.canGenerate(), () -> validation.errors().toString());

        String main = emitter.emit(result.design()).get(HclEmitter.FILE_MAIN);
        // Falls back to the catalog default rather than emitting an invalid resource.
        assertTrue(main.contains("cidr_block = \"10.0.0.0/16\""), main);
        // Label equalled the type, so the name comes from the type instead.
        assertTrue(main.contains("resource \"aws_vpc\" \"vpc\" {"), main);
    }

    @Test
    @DisplayName("translation is deterministic")
    void isDeterministic() {
        DesignModel first = translator.translate(REAL_CANVAS_JSON, "prod", "ap-south-1").design();
        for (int i = 0; i < 4; i++) {
            assertEquals(emitter.emit(first),
                    emitter.emit(translator.translate(REAL_CANVAS_JSON, "prod", "ap-south-1").design()));
        }
    }
}
