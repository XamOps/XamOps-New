-- Records which CloudFormation stack created an account's connection.
--
-- Until now nothing tied a connected account back to the stack that made it. The onboarding
-- template has always known its own id — xamops-readonly-role.yml passes AWS::StackId into the
-- callback Lambda — but the callback body carried only externalId, awsAccountId and roleArn, so
-- the value was computed and discarded. Without it we cannot tell a healthy account from one
-- whose stack was deleted while its IAM role was retained: both keep serving dashboards, and the
-- second silently skips every future template change.
--
-- The stack ID, not the name, is the identity. Stack names are reusable, so an account whose
-- customer deleted and recreated their stack presents the same name attached to different
-- resources and, usually, a different ExternalId. Only the ARN distinguishes "the stack we
-- created" from "a stack wearing its name".
ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS stack_id VARCHAR(512);

-- Kept alongside the ID because it is what a human sees in the console, and because it is the
-- fallback lookup when the ID is absent or no longer resolves.
ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS stack_name VARCHAR(255);

-- A stack is a regional resource and DescribeStacks only sees its own region. Without this the
-- diagnosis would have to sweep every enabled region to distinguish "deleted" from "deployed
-- somewhere else" — two situations needing opposite responses.
ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS stack_region VARCHAR(64);

-- Which template version the stack was last known to carry, so "needs updating" is a comparison
-- rather than a guess. Null means never established, which is every existing row.
ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS stack_template_version VARCHAR(64);

-- Outcome of the last diagnosis: the CloudFormation status where we could read one
-- (CREATE_COMPLETE, ROLLBACK_COMPLETE, …), otherwise one of this feature's own verdicts
-- (NO_CFN_PERMISSION, STACK_DELETED, NOT_STACK_MANAGED). Cached so the account list can show a
-- column without calling AWS once per row on every page load.
ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS stack_status VARCHAR(64);

ALTER TABLE cloud_account ADD COLUMN IF NOT EXISTS stack_checked_at TIMESTAMP;

-- Nothing is backfilled. Every existing row is "never established", which is what NULL means
-- here, and which the diagnosis resolves by asking the role which stack created it.
