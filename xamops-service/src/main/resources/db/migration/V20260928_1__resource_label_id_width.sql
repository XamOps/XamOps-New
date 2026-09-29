-- Resource labels: widen resource_id to hold every id form a resource can arrive as.
--
-- The column was sized at 255 when the only writer was the Cloud List, which stores the
-- inventory's short id ("i-089ccf1b5a839880a", a bucket name). The Cost page's per-resource
-- view now writes here too, and it reads the Cost and Usage Report, whose
-- line_item_resource_id is a full ARN for RDS, Lambda, DynamoDB, OpenSearch and the load
-- balancers. ResourceIdResolver normalises those to the short id wherever the inventory
-- knows the resource, so most rows stay short — but a resource billed and not indexed by
-- Resource Explorer is stored under the id we actually have, and an ARN with a long path
-- exceeds 255.
--
-- 512 rather than something larger: it matches aws_cloud_resource.resource_id, so the
-- canonical form can never fail to fit here, and the two columns that are meant to hold the
-- same value are declared the same way.
--
-- Widening a varchar in Postgres rewrites no rows and takes no table rewrite lock beyond a
-- brief ACCESS EXCLUSIVE to update the catalog, so this is safe on a live table. Indexes on
-- the column are unaffected.

ALTER TABLE resource_label_assignments
    ALTER COLUMN resource_id TYPE VARCHAR(512);
