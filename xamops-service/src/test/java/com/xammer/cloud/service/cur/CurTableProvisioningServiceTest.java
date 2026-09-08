package com.xammer.cloud.service.cur;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CurSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Column naming is the seam between the generated table and the hand-written
 * queries in {@link CurCostDataSource}. If the two disagree the table still builds
 * and every query fails at runtime with a missing column, so it is worth pinning.
 */
class CurTableProvisioningServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CurTableProvisioningService service = new CurTableProvisioningService(null);

    @Test
    @DisplayName("Column names match the convention AWS's Glue crawler produces")
    void columnNamingMatchesCrawlerConvention() {
        assertEquals("line_item_unblended_cost",
                CurTableProvisioningService.toAthenaColumn("lineItem", "UnblendedCost"));
        assertEquals("line_item_usage_start_date",
                CurTableProvisioningService.toAthenaColumn("lineItem", "UsageStartDate"));
        assertEquals("line_item_usage_account_id",
                CurTableProvisioningService.toAthenaColumn("lineItem", "UsageAccountId"));
        assertEquals("product_product_name",
                CurTableProvisioningService.toAthenaColumn("product", "ProductName"));
        assertEquals("product_region",
                CurTableProvisioningService.toAthenaColumn("product", "region"));
    }

    @Test
    @DisplayName("Cost allocation tag keys lose punctuation the way CUR columns do")
    void tagKeysAreSanitised() {
        assertEquals("resource_tags_user_environment",
                CurTableProvisioningService.toAthenaColumn("resourceTags", "user:Environment"));
        assertEquals("resource_tags_user_cost_center",
                CurTableProvisioningService.toAthenaColumn("resourceTags", "user:cost-center"));
    }

    @Test
    @DisplayName("Every column the query layer names is derivable from a manifest entry")
    void queryLayerColumnsAreProducible() {
        // These are the exact identifiers CurCostDataSource and CurSql interpolate.
        assertEquals("line_item_unblended_cost",
                CurTableProvisioningService.toAthenaColumn("lineItem", "UnblendedCost"));
        assertEquals("line_item_product_code",
                CurTableProvisioningService.toAthenaColumn("lineItem", "ProductCode"));
        assertEquals("line_item_usage_type",
                CurTableProvisioningService.toAthenaColumn("lineItem", "UsageType"));
        assertEquals("line_item_line_item_type",
                CurTableProvisioningService.toAthenaColumn("lineItem", "LineItemType"));
        assertEquals("product_instance_type",
                CurTableProvisioningService.toAthenaColumn("product", "instanceType"));
    }

    @Test
    @DisplayName("Prefixed manifest names are used as-is, not prefixed twice")
    void currentManifestDialectIsNotDoublePrefixed() {
        // Verified against a live AWS manifest (2026-09): the name field already holds the
        // resolved snake_case column. Prefixing again produced
        // line_item_line_item_resource_id — every column wrong, table built without error,
        // every query failing.
        assertEquals("line_item_resource_id",
                CurTableProvisioningService.toAthenaColumn("lineItem", "line_item_resource_id", true));
        assertEquals("pricing_public_on_demand_cost",
                CurTableProvisioningService.toAthenaColumn("pricing", "pricing_public_on_demand_cost", true));
    }

    @Test
    @DisplayName("Both manifest dialects resolve to the same column")
    void bothDialectsAgree() {
        assertEquals(
                CurTableProvisioningService.toAthenaColumn("lineItem", "UnblendedCost", false),
                CurTableProvisioningService.toAthenaColumn("lineItem", "line_item_unblended_cost", true));
        assertEquals(
                CurTableProvisioningService.toAthenaColumn("product", "ProductName", false),
                CurTableProvisioningService.toAthenaColumn("product", "product_product_name", true));
    }

    @Test
    @DisplayName("Dialect is decided per manifest, since no single column is decisive")
    void dialectDetectedByMajority() throws Exception {
        // product/ProductName -> product_name and lineItem/LineItemType -> line_item_type
        // both LOOK prefixed on their own. Only the whole manifest disambiguates.
        JsonNode legacy = objectMapper.readTree("""
                {"columns":[
                  {"category":"lineItem","name":"UnblendedCost"},
                  {"category":"lineItem","name":"LineItemType"},
                  {"category":"product","name":"ProductName"},
                  {"category":"identity","name":"LineItemId"}
                ]}
                """);
        JsonNode current = objectMapper.readTree("""
                {"columns":[
                  {"category":"lineItem","name":"line_item_unblended_cost"},
                  {"category":"lineItem","name":"line_item_line_item_type"},
                  {"category":"product","name":"product_product_name"},
                  {"category":"identity","name":"identity_line_item_id"}
                ]}
                """);

        assertFalse(CurTableProvisioningService.usesPrefixedNames(legacy));
        assertTrue(CurTableProvisioningService.usesPrefixedNames(current));
    }

    @Test
    @DisplayName("Athena-native type names map correctly, not just the legacy Java-ish ones")
    void currentManifestTypesMap() {
        // The live manifest types cost columns as DOUBLE and dates as TIMESTAMP. These fell
        // through to "string", which makes SUM() on a cost column fail outright.
        assertEquals("double", CurTableProvisioningService.toAthenaType("DOUBLE"));
        assertEquals("timestamp", CurTableProvisioningService.toAthenaType("TIMESTAMP"));
        assertEquals("string", CurTableProvisioningService.toAthenaType("STRING"));
        assertEquals("bigint", CurTableProvisioningService.toAthenaType("BIGINT"));
    }

    @Test
    @DisplayName("Costs are typed double so SUM works; timestamps are typed timestamp")
    void typeMapping() {
        assertEquals("double", CurTableProvisioningService.toAthenaType("BigDecimal"));
        assertEquals("double", CurTableProvisioningService.toAthenaType("OptionalBigDecimal"));
        assertEquals("timestamp", CurTableProvisioningService.toAthenaType("DateTime"));
        assertEquals("string", CurTableProvisioningService.toAthenaType("String"));
        assertEquals("string", CurTableProvisioningService.toAthenaType("Interval"));
        assertEquals("string", CurTableProvisioningService.toAthenaType(null));
    }

    @Test
    @DisplayName("Partition keys are not also emitted as data columns")
    void partitionKeysExcludedFromColumns() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {"columns":[
                  {"category":"lineItem","name":"UnblendedCost","type":"BigDecimal"},
                  {"category":"","name":"year","type":"String"},
                  {"category":"identity","name":"LineItemId","type":"String"}
                ]}
                """);

        List<CurTableProvisioningService.Column> columns = service.columnsFrom(manifest);
        List<String> names = columns.stream().map(CurTableProvisioningService.Column::name).toList();

        assertTrue(names.contains("line_item_unblended_cost"));
        assertTrue(names.contains("identity_line_item_id"));
        assertFalse(names.contains("year"), "year is a partition key, not a data column");
    }

    @Test
    @DisplayName("Colliding column names are dropped rather than failing the whole table")
    void duplicateColumnsAreDropped() throws Exception {
        // Two tag keys differing only in punctuation collapse to one Athena identifier.
        // A repeated column name would make CREATE TABLE fail, taking the source down
        // for one tag.
        JsonNode manifest = objectMapper.readTree("""
                {"columns":[
                  {"category":"resourceTags","name":"user:cost-center","type":"String"},
                  {"category":"resourceTags","name":"user:cost_center","type":"String"}
                ]}
                """);

        List<CurTableProvisioningService.Column> columns = service.columnsFrom(manifest);
        assertEquals(1, columns.size());
        assertEquals("resource_tags_user_cost_center", columns.get(0).name());
    }

    @Test
    @DisplayName("Table points at the data directory, not the manifest directory")
    void dataLocationUsesDoubledReportName() {
        CurSource source = new CurSource();
        source.setS3Bucket("xamops-cur-605134457560");
        source.setS3Prefix("cur");
        source.setReportName("xamops-cur");

        // <prefix>/<report>/<report>/ is the Parquet data; <prefix>/<report>/YYYYMMDD-YYYYMMDD/
        // alongside it holds manifests, which Athena would fail to read as Parquet.
        assertEquals("s3://xamops-cur-605134457560/cur/xamops-cur/xamops-cur/",
                service.dataLocation(source));
    }

    @Test
    @DisplayName("A blank prefix does not produce a doubled slash in the location")
    void dataLocationHandlesBlankPrefix() {
        CurSource source = new CurSource();
        source.setS3Bucket("bucket");
        source.setS3Prefix("");
        source.setReportName("rep");

        assertEquals("s3://bucket/rep/rep/", service.dataLocation(source));
    }
}
