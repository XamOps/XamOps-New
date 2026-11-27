/**
 * Which DevOps-in-the-Box scripts belong on a cloud's shelf.
 *
 * Shared by useDevOpsScripts (the page's filter) and its tests, so the two cannot drift apart.
 */

const SPECIFIC_CLOUDS = ['aws', 'azure', 'gcp'];

interface ScriptLike {
  cloudProviders?: (string | null | undefined)[] | null;
  code?: string | null;
}

/**
 * The clouds a script's code targets. Only used for scripts with no provider tag: the legacy
 * catalogue is untagged, so its EC2, S3 and boto3 scripts passed the filter and showed on the
 * Azure and GCP shelves.
 */
export const inferProviders = (script: ScriptLike): string[] => {
  const code = script.code ?? '';
  const found: string[] = [];
  if (/provider\s+"aws"|\baws_[a-z]|\bboto3\b|\baws\s+(ec2|s3|s3api|rds|iam|cloudwatch|ce|lambda|ecs|eks|sts)\b/i.test(code)) {
    found.push('aws');
  }
  if (/provider\s+"azurerm"|\bazurerm_|\baz\s+(group|vm|aks|storage|network|sql|webapp|account|monitor|keyvault|role)\b|\bazure\.(identity|mgmt|storage)\b/i.test(code)) {
    found.push('azure');
  }
  if (/provider\s+"google"|\bgoogle_[a-z]|\bgcloud\s|\bgsutil\s|\bgoogle\.cloud\b/i.test(code)) {
    found.push('gcp');
  }
  return found;
};

/**
 * True when a script belongs on the shelf for `provider`.
 *
 * A script that names specific clouds shows only on those clouds' shelves, even when it is also
 * tagged `multi-cloud`: the AWS-to-GCP VPN is multi-cloud but has nothing for Azure. A script with
 * no specific cloud (tagged only `multi-cloud`, or untagged with no recognisable cloud in its code)
 * is cloud-agnostic and shows everywhere. With no account selected the whole catalogue shows.
 */
export const matchesProvider = (script: ScriptLike, provider?: string): boolean => {
  if (!provider) return true;
  const tagged = (script.cloudProviders ?? [])
    .map((p) => p?.toLowerCase())
    .filter((p): p is string => !!p);
  const providers = tagged.length > 0 ? tagged : inferProviders(script);
  const specific = providers.filter((p) => SPECIFIC_CLOUDS.includes(p));
  if (specific.length === 0) return true;
  return specific.includes(provider.toLowerCase());
};
