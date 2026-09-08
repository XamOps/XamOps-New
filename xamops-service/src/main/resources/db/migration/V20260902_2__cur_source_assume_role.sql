-- Supports more than one XamOps management/payer account.
--
-- V20260902_1 modelled "XamOps-owned" as a boolean, which the read path turned into "use the
-- application's own credentials". That holds for exactly one MPA: the account XamOps runs in.
-- A second MPA is a different AWS account, and the app's instance role cannot read its bucket,
-- so every query against it would fail and the resolver would fall back to Cost Explorer
-- permanently — with nothing in the UI to say why.
--
-- The fix is to make the credential explicit rather than inferred:
--
--   xamops_owned = true,  assume_role_arn NULL  -> the MPA XamOps itself runs in; host creds
--   xamops_owned = true,  assume_role_arn set   -> another XamOps MPA; assume that role
--   xamops_owned = false                        -> customer account; assume its onboarding role
--
-- Nothing is backfilled: existing rows are the first case, which is what NULL already means.
ALTER TABLE cur_source ADD COLUMN IF NOT EXISTS assume_role_arn VARCHAR(512);

-- External ID for the assume-role above, when the trust policy requires one. Separate from
-- cloud_account.external_id because these roles are XamOps-to-XamOps, not per customer.
ALTER TABLE cur_source ADD COLUMN IF NOT EXISTS assume_role_external_id VARCHAR(255);
