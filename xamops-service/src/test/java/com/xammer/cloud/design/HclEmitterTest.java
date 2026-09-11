package com.xammer.cloud.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.design.catalog.ResourceCatalog;
import com.xammer.cloud.design.hcl.HclEmitter;
import com.xammer.cloud.design.hcl.HclWriter;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the generated Terraform exactly.
 *
 * <p>
 * Asserting on whole-file equality rather than "contains" is the point. The
 * emitter's contract is that a design produces byte-identical output every run —
 * that is what keeps a generated pull request reviewable — and a substring
 * assertion would let ordering, indentation and quoting drift silently.
 */
class HclEmitterTest {

    private ResourceCatalog catalog;
    private HclEmitter emitter;
    private DesignValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new ResourceCatalog(new ObjectMapper());
        // @PostConstruct is package-private; invoke it directly so the test needs no
        // Spring context for what is otherwise a pure function.
        Method load = ResourceCatalog.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(catalog);

        emitter = new HclEmitter(catalog);
        validator = new DesignValidator(catalog);
    }

    /**
     * A three-tier slice: VPC, private subnet, security group, instance. Exercises
     * containment references, an edge reference emitted as a list, nested blocks,
     * variable promotion, tags and defaults in one pass.
     */
    private DesignModel threeTier() {
        var vpc = ResourceNode.builder("n1", "aws_vpc", "main")
                .attr("cidr_block", "10.24.0.0/16")
                .attr("tags", Map.of("Name", "prod-vpc"))
                .build();

        var subnet = ResourceNode.builder("n2", "aws_subnet", "private 1a")
                .attr("cidr_block", "10.24.11.0/24")
                .attr("availability_zone", "ap-south-1a")
                .attr("tags", Map.of("Name", "prod-private-1a", "Tier", "private"))
                .ref("vpc_id", "n1")
                .build();

        var sg = ResourceNode.builder("n3", "aws_security_group", "web")
                .attr("name", "prod-web-sg")
                .attr("description", "Web tier")
                .attr("ingress", List.of(Map.of(
                        "description", "HTTPS from inside the VPC",
                        "from_port", 443,
                        "to_port", 443,
                        "protocol", "tcp",
                        "cidr_blocks", "10.24.0.0/16")))
                .attr("egress", List.of(Map.of(
                        "from_port", 0,
                        "to_port", 0,
                        "protocol", "-1",
                        "cidr_blocks", "0.0.0.0/0")))
                .ref("vpc_id", "n1")
                .build();

        var instance = ResourceNode.builder("n4", "aws_instance", "web")
                .attr("ami", "ami-0f58b397bc5c1f04a")
                .attr("instance_type", "t3.large")
                .attr("tags", Map.of("Name", "prod-web-1"))
                .ref("subnet_id", "n2")
                .refs("vpc_security_group_ids", List.of("n3"))
                .build();

        return new DesignModel("prod three-tier", "ap-south-1", List.of(instance, sg, subnet, vpc));
    }

    @Test
    @DisplayName("main.tf matches exactly, with references resolved to other resources")
    void emitsExactMainTf() {
        String main = emitter.emit(threeTier()).get(HclEmitter.FILE_MAIN);

        String expected = """
                # prod three-tier

                resource "aws_vpc" "main" {
                  cidr_block = "10.24.0.0/16"
                  enable_dns_support = true
                  enable_dns_hostnames = true
                  tags = {
                    Name = "prod-vpc"
                  }
                }

                resource "aws_subnet" "private_1a" {
                  vpc_id = aws_vpc.main.id
                  cidr_block = "10.24.11.0/24"
                  availability_zone = "ap-south-1a"
                  map_public_ip_on_launch = false
                  tags = {
                    Name = "prod-private-1a"
                    Tier = "private"
                  }
                }

                resource "aws_security_group" "web" {
                  vpc_id = aws_vpc.main.id
                  name = "prod-web-sg"
                  description = "Web tier"
                  ingress {
                    description = "HTTPS from inside the VPC"
                    from_port = 443
                    to_port = 443
                    protocol = "tcp"
                    cidr_blocks = ["10.24.0.0/16"]
                  }
                  egress {
                    from_port = 0
                    to_port = 0
                    protocol = "-1"
                    cidr_blocks = ["0.0.0.0/0"]
                  }
                }

                resource "aws_instance" "web" {
                  subnet_id = aws_subnet.private_1a.id
                  vpc_security_group_ids = [aws_security_group.web.id]
                  ami = "ami-0f58b397bc5c1f04a"
                  instance_type = var.web_instance_type
                  monitoring = true
                  associate_public_ip_address = false
                  tags = {
                    Name = "prod-web-1"
                  }
                }
                """;

        assertEquals(expected, main);
    }

    @Test
    @DisplayName("references are unquoted expressions, not string literals")
    void referencesAreExpressions() {
        String main = emitter.emit(threeTier()).get(HclEmitter.FILE_MAIN);

        // The distinction the whole feature rests on.
        assertTrue(main.contains("vpc_id = aws_vpc.main.id"),
                "containment must become a resource reference");
        assertTrue(main.contains("subnet_id = aws_subnet.private_1a.id"),
                "the sanitised identifier must be used in the reference");
        assertTrue(main.contains("vpc_security_group_ids = [aws_security_group.web.id]"),
                "a scalar target on a list field must still emit a list");

        assertFalse(main.contains("\"aws_vpc.main.id\""),
                "a quoted reference would be a literal string and never resolve");
        assertFalse(main.contains("subnet-"), "no hardcoded AWS resource ids");
    }

    @Test
    @DisplayName("providers.tf and variables.tf match exactly, including the promoted variable")
    void emitsExactProvidersAndVariables() {
        Map<String, String> files = emitter.emit(threeTier());

        assertEquals("""
                # Generated by XamOps CloudMap. Edit the design, not this file.

                terraform {
                  required_version = ">= 1.6"

                  required_providers {
                    aws = {
                      source  = "hashicorp/aws"
                      version = "~> 5.0"
                    }
                  }
                }

                provider "aws" {
                  region = var.region
                }
                """, files.get(HclEmitter.FILE_PROVIDERS));

        assertEquals("""
                variable "region" {
                  description = "AWS region to deploy into."
                  type = string
                  default = "ap-south-1"
                }

                variable "web_instance_type" {
                  description = "Instance size. Promoted to a variable so it can be tuned per environment without editing the resource."
                  type = string
                  default = "t3.large"
                }
                """, files.get(HclEmitter.FILE_VARIABLES));
    }

    @Test
    @DisplayName("outputs.tf exposes each type's declared outputs")
    void emitsOutputs() {
        assertEquals("""
                output "main_id" {
                  value = aws_vpc.main.id
                }

                output "main_cidr_block" {
                  value = aws_vpc.main.cidr_block
                }

                output "private_1a_id" {
                  value = aws_subnet.private_1a.id
                }

                output "web_id" {
                  value = aws_security_group.web.id
                }

                output "web_id" {
                  value = aws_instance.web.id
                }

                output "web_private_ip" {
                  value = aws_instance.web.private_ip
                }
                """, emitter.emit(threeTier()).get(HclEmitter.FILE_OUTPUTS));
    }

    @Test
    @DisplayName("dependencies are emitted before the resources that use them")
    void ordersDependenciesFirst() {
        String main = emitter.emit(threeTier()).get(HclEmitter.FILE_MAIN);
        int vpc = main.indexOf("resource \"aws_vpc\"");
        int subnet = main.indexOf("resource \"aws_subnet\"");
        int sg = main.indexOf("resource \"aws_security_group\"");
        int instance = main.indexOf("resource \"aws_instance\"");

        assertTrue(vpc < subnet, "VPC before subnet");
        assertTrue(vpc < sg, "VPC before security group");
        assertTrue(subnet < instance, "subnet before instance");
        assertTrue(sg < instance, "security group before instance");
    }

    @Test
    @DisplayName("output does not depend on the order the canvas supplied nodes")
    void isOrderIndependent() {
        DesignModel forward = threeTier();
        DesignModel reversed = new DesignModel(
                forward.name(), forward.region(), forward.nodes().reversed());

        assertEquals(emitter.emit(forward), emitter.emit(reversed));
    }

    @Test
    @DisplayName("repeated emission is byte-identical")
    void isDeterministic() {
        DesignModel design = threeTier();
        String first = emitter.emitSingleFile(design);
        for (int i = 0; i < 5; i++) {
            assertEquals(first, emitter.emitSingleFile(design), "run " + i + " diverged");
        }
    }

    @Test
    @DisplayName("colliding names are suffixed so resource addresses stay unique")
    void suffixesCollidingNames() {
        var vpc = ResourceNode.builder("v", "aws_vpc", "main").build();
        var a = ResourceNode.builder("a", "aws_subnet", "Web Tier").attr("cidr_block", "10.0.1.0/24").ref("vpc_id", "v").build();
        var b = ResourceNode.builder("b", "aws_subnet", "web-tier").attr("cidr_block", "10.0.2.0/24").ref("vpc_id", "v").build();

        String main = emitter.emit(new DesignModel("d", "us-east-1", List.of(vpc, a, b)))
                .get(HclEmitter.FILE_MAIN);

        assertTrue(main.contains("resource \"aws_subnet\" \"web_tier\" {"));
        assertTrue(main.contains("resource \"aws_subnet\" \"web_tier_2\" {"));
    }

    @Test
    @DisplayName("hostile names and values cannot break the generated file")
    void sanitisesHostileInput() {
        var vpc = ResourceNode.builder("v", "aws_vpc", "10.0.0.0/8 prod \"main\"")
                .attr("cidr_block", "10.0.0.0/16")
                .attr("tags", Map.of("Name", "a\"b ${var.oops}"))
                .build();

        String main = emitter.emit(new DesignModel("d", "us-east-1", List.of(vpc)))
                .get(HclEmitter.FILE_MAIN);

        // Identifier normalised, so Terraform can parse the address.
        assertTrue(main.contains("resource \"aws_vpc\" \"r_10_0_0_0_8_prod_main\" {"), main);
        // Quote escaped, and interpolation neutralised so a tag cannot inject an expression.
        assertTrue(main.contains("Name = \"a\\\"b $${var.oops}\""), main);
    }

    @Test
    @DisplayName("a dangling reference omits the attribute instead of emitting broken HCL")
    void danglingReferenceDegradesGracefully() {
        var subnet = ResourceNode.builder("n2", "aws_subnet", "orphan")
                .attr("cidr_block", "10.0.1.0/24")
                .ref("vpc_id", "does-not-exist")
                .build();

        String main = emitter.emit(new DesignModel("d", "us-east-1", List.of(subnet)))
                .get(HclEmitter.FILE_MAIN);

        assertFalse(main.contains("vpc_id"), "must not emit a reference to a missing node");
        assertTrue(main.contains("resource \"aws_subnet\" \"orphan\" {"));

        // ...and the validator is what tells the author about it.
        var result = validator.validate(new DesignModel("d", "us-east-1", List.of(subnet)));
        assertFalse(result.canGenerate(), "a missing required VPC is an error");
    }

    @Test
    @DisplayName("an unknown type is reported in place rather than silently dropped")
    void unknownTypeLeavesATrace() {
        var node = ResourceNode.builder("x", "aws_quantum_flux", "thing").build();
        String main = emitter.emit(new DesignModel("d", "us-east-1", List.of(node)))
                .get(HclEmitter.FILE_MAIN);

        assertTrue(main.contains("# Skipped 'thing': no catalog entry for aws_quantum_flux"), main);
    }

    @Test
    @DisplayName("whole-number ports emit as integers, not doubles")
    void emitsWholeNumbersAsIntegers() {
        // Jackson yields Double for JSON numbers bound to Object; `from_port = 443.0`
        // is not valid Terraform.
        var writer = new HclWriter();
        writer.attribute("from_port", 443.0d);
        assertEquals("from_port = 443\n", writer.build());
    }

    @Test
    @DisplayName("an empty design still produces a valid module skeleton")
    void handlesEmptyDesign() {
        Map<String, String> files = emitter.emit(new DesignModel("empty", "eu-west-1", List.of()));

        assertTrue(files.containsKey(HclEmitter.FILE_PROVIDERS));
        assertTrue(files.get(HclEmitter.FILE_VARIABLES).contains("default = \"eu-west-1\""));
        assertFalse(files.containsKey(HclEmitter.FILE_OUTPUTS), "no resources means no outputs file");
    }
}
