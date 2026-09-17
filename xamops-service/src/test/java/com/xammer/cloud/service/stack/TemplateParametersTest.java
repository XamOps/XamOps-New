package com.xammer.cloud.service.stack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateParametersTest {

    @Test
    @DisplayName("Reads parameters from a template full of CloudFormation short-form tags")
    void readsThroughIntrinsicFunctionTags() {
        String template = """
                AWSTemplateFormatVersion: '2010-09-09'
                Parameters:
                  ExternalId:
                    Type: String
                  XamOpsInventoryQueueUrl:
                    Type: String
                    Default: ''
                  Environment:
                    Type: String
                    Default: production
                Conditions:
                  HasQueue: !Not [!Equals [!Ref XamOpsInventoryQueueUrl, '']]
                Resources:
                  Role:
                    Type: AWS::IAM::Role
                    Properties:
                      RoleName: !Sub 'XamOps-${Environment}'
                      Arn: !GetAtt Other.Arn
                """;

        Map<String, Boolean> declared = TemplateParameters.parse(template);

        assertEquals(3, declared.size());
        assertEquals(false, declared.get("ExternalId"), "no Default means it must be supplied");
        assertEquals(true, declared.get("XamOpsInventoryQueueUrl"), "an empty-string Default is still a default");
        assertEquals(true, declared.get("Environment"));
    }

    @Test
    @DisplayName("Reads a JSON template, which is valid YAML")
    void readsJson() {
        String template = """
                {"Parameters": {"ExternalId": {"Type": "String"},
                                "Region": {"Type": "String", "Default": "us-east-1"}}}
                """;

        Map<String, Boolean> declared = TemplateParameters.parse(template);

        assertEquals(false, declared.get("ExternalId"));
        assertEquals(true, declared.get("Region"));
    }

    @Test
    @DisplayName("A template with no Parameters block declares none, rather than failing")
    void noParametersBlock() {
        assertTrue(TemplateParameters.parse("Resources: {}").isEmpty());
    }

    @Test
    @DisplayName("Rejects something that is not a template")
    void rejectsNonTemplate() {
        assertThrows(IllegalArgumentException.class, () -> TemplateParameters.parse("- just\n- a list"));
        assertThrows(IllegalArgumentException.class, () -> TemplateParameters.parse("key: [unclosed"));
    }
}
