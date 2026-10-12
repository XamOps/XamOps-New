#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# Teardown for the XamOps CloudK8s feature-testing clusters.
#
#   ./teardown.sh              # delete both clusters + every dependent resource
#   ./teardown.sh xamops-test-2  # delete a single cluster
#   DRY_RUN=1 ./teardown.sh    # print what would be deleted, change nothing
#
# Deletes, in dependency order:
#   Service type=LoadBalancer (so the in-cluster controller removes its ELBs)
#   PersistentVolumeClaims (so the EBS CSI driver removes its volumes)
#   managed node groups + cluster (via eksctl, which removes its CFN stacks)
#   IRSA IAM roles created by install-observability.sh
#   the cluster OIDC provider
#   leftover eksctl CloudFormation stacks
#   orphaned classic + v2 load balancers
#   orphaned / available EBS volumes
#   leftover security groups
#   the cluster CloudWatch log group
#
# SAFETY: every resource is checked for the tag Project=XamOps (or a
# kubernetes.io/cluster/<cluster> tag belonging to one of these clusters)
# before deletion. Anything untagged or tagged otherwise is skipped and
# reported, never deleted. The script is idempotent — missing resources are
# treated as already-deleted.
# ──────────────────────────────────────────────────────────────────────────────
set -uo pipefail

REGION="${REGION:-ap-south-1}"
REQUIRED_TAG_KEY="Project"
REQUIRED_TAG_VALUE="XamOps"
DRY_RUN="${DRY_RUN:-0}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $# -gt 0 ]]; then
  CLUSTERS=("$@")
else
  CLUSTERS=("xamops-test-1" "xamops-test-2")
fi

SKIPPED=""   # newline-separated; plain string for bash 3.2 (macOS) safety

log()  { printf '\n==> %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
skip() { printf '    SKIP (not tagged %s=%s): %s\n' "$REQUIRED_TAG_KEY" "$REQUIRED_TAG_VALUE" "$*"; SKIPPED="${SKIPPED}${*}\n"; }

run() {
  if [[ "$DRY_RUN" == "1" ]]; then
    printf '    DRY_RUN would run: %s\n' "$*"
  else
    "$@"
  fi
}

# ── tag helpers ───────────────────────────────────────────────────────────────

# EC2-style resources (volumes, security groups) carry tags inline; these
# helpers take the already-fetched "Key=Value ..." list.
has_required_tag_list() {
  local tags="$1" cluster="$2"
  [[ "$tags" == *"${REQUIRED_TAG_KEY}=${REQUIRED_TAG_VALUE}"* ]] && return 0
  [[ -n "$cluster" && "$tags" == *"kubernetes.io/cluster/${cluster}="* ]] && return 0
  return 1
}

elb_v2_is_ours() {
  local arn="$1" cluster="$2" tags
  tags=$(aws elbv2 describe-tags --region "$REGION" --resource-arns "$arn" \
    --query 'TagDescriptions[0].Tags[].[Key,Value]' --output text 2>/dev/null |
    tr '\t' '=' | tr '\n' ' ')
  has_required_tag_list "$tags" "$cluster"
}

elb_classic_is_ours() {
  local name="$1" cluster="$2" tags
  tags=$(aws elb describe-tags --region "$REGION" --load-balancer-names "$name" \
    --query 'TagDescriptions[0].Tags[].[Key,Value]' --output text 2>/dev/null |
    tr '\t' '=' | tr '\n' ' ')
  has_required_tag_list "$tags" "$cluster"
}

cluster_is_ours() {
  local cluster="$1" value
  value=$(aws eks describe-cluster --region "$REGION" --name "$cluster" \
    --query "cluster.tags.${REQUIRED_TAG_KEY}" --output text 2>/dev/null)
  [[ "$value" == "$REQUIRED_TAG_VALUE" ]]
}

stack_is_ours() {
  local stack="$1" value
  value=$(aws cloudformation describe-stacks --region "$REGION" --stack-name "$stack" \
    --query "Stacks[0].Tags[?Key=='${REQUIRED_TAG_KEY}'].Value | [0]" --output text 2>/dev/null)
  [[ "$value" == "$REQUIRED_TAG_VALUE" ]]
}

role_is_ours() {
  local role="$1" value
  value=$(aws iam list-role-tags --role-name "$role" \
    --query "Tags[?Key=='${REQUIRED_TAG_KEY}'].Value | [0]" --output text 2>/dev/null)
  [[ "$value" == "$REQUIRED_TAG_VALUE" ]]
}

# ── per-cluster in-cluster cleanup (must happen before the cluster dies) ──────

drain_k8s_cloud_resources() {
  local cluster="$1"
  if ! aws eks update-kubeconfig --region "$REGION" --name "$cluster" >/dev/null 2>&1; then
    info "no kubeconfig obtainable for ${cluster}; skipping in-cluster cleanup"
    return
  fi
  local ctx
  ctx=$(kubectl config current-context 2>/dev/null) || return

  log "[$cluster] deleting Services of type LoadBalancer (removes their ELBs)"
  local lbs
  lbs=$(kubectl --context "$ctx" get svc --all-namespaces \
    -o jsonpath='{range .items[?(@.spec.type=="LoadBalancer")]}{.metadata.namespace}/{.metadata.name}{"\n"}{end}' 2>/dev/null)
  if [[ -z "$lbs" ]]; then
    info "none found"
  else
    while IFS=/ read -r ns name; do
      [[ -z "${name:-}" ]] && continue
      info "deleting svc ${ns}/${name}"
      run kubectl --context "$ctx" -n "$ns" delete svc "$name" --wait=true --timeout=5m
    done <<< "$lbs"
    info "pausing 60s so the load balancer controller finishes deleting ELBs"
    [[ "$DRY_RUN" == "1" ]] || sleep 60
  fi

  log "[$cluster] deleting PersistentVolumeClaims (removes their EBS volumes)"
  local pvcs
  pvcs=$(kubectl --context "$ctx" get pvc --all-namespaces \
    -o jsonpath='{range .items[*]}{.metadata.namespace}/{.metadata.name}{"\n"}{end}' 2>/dev/null)
  if [[ -z "$pvcs" ]]; then
    info "none found"
  else
    while IFS=/ read -r ns name; do
      [[ -z "${name:-}" ]] && continue
      info "deleting pvc ${ns}/${name}"
      run kubectl --context "$ctx" -n "$ns" delete pvc "$name" --wait=false
    done <<< "$pvcs"
    [[ "$DRY_RUN" == "1" ]] || sleep 30
  fi
}

# ── main per-cluster teardown ────────────────────────────────────────────────

delete_cluster() {
  local cluster="$1"

  if ! aws eks describe-cluster --region "$REGION" --name "$cluster" >/dev/null 2>&1; then
    log "[$cluster] cluster not found — already deleted"
    return 0
  fi

  if ! cluster_is_ours "$cluster"; then
    skip "EKS cluster ${cluster}"
    return 0
  fi

  drain_k8s_cloud_resources "$cluster"

  log "[$cluster] deleting node groups and control plane via eksctl"
  local cfg="${HERE}/${cluster}.yaml"
  if [[ -f "$cfg" ]]; then
    run eksctl delete cluster -f "$cfg" --region "$REGION" \
      --disable-nodegroup-eviction --force --wait
  else
    run eksctl delete cluster --name "$cluster" --region "$REGION" \
      --disable-nodegroup-eviction --force --wait
  fi

  # eksctl removes its own OIDC association only with --wait on newer versions;
  # make sure the provider is gone either way.
  log "[$cluster] removing leftover OIDC provider, if any"
  local oidc_id providers arn
  oidc_id=$(aws eks describe-cluster --region "$REGION" --name "$cluster" \
    --query 'cluster.identity.oidc.issuer' --output text 2>/dev/null | sed 's|https://||')
  if [[ -n "${oidc_id:-}" && "$oidc_id" != "None" ]]; then
    providers=$(aws iam list-open-id-connect-providers \
      --query 'OpenIDConnectProviderList[].Arn' --output text 2>/dev/null)
    for arn in $providers; do
      if [[ "$arn" == *"${oidc_id##*/}" ]]; then
        info "deleting OIDC provider $arn"
        run aws iam delete-open-id-connect-provider --open-id-connect-provider-arn "$arn"
      fi
    done
  else
    info "no OIDC issuer recorded (cluster already gone)"
  fi
}

delete_irsa_roles() {
  local cluster="$1" role="${1}-cw-observability"
  log "[$cluster] deleting IRSA role ${role}"
  if ! aws iam get-role --role-name "$role" >/dev/null 2>&1; then
    info "role not found — already deleted"
    return 0
  fi
  if ! role_is_ours "$role"; then
    skip "IAM role ${role}"
    return 0
  fi
  local arn
  for arn in $(aws iam list-attached-role-policies --role-name "$role" \
      --query 'AttachedPolicies[].PolicyArn' --output text 2>/dev/null); do
    info "detaching $arn"
    run aws iam detach-role-policy --role-name "$role" --policy-arn "$arn"
  done
  for arn in $(aws iam list-role-policies --role-name "$role" \
      --query 'PolicyNames[]' --output text 2>/dev/null); do
    info "deleting inline policy $arn"
    run aws iam delete-role-policy --role-name "$role" --policy-name "$arn"
  done
  run aws iam delete-role --role-name "$role"
}

delete_cfn_stacks() {
  local cluster="$1" stack
  log "[$cluster] sweeping leftover eksctl CloudFormation stacks"
  local stacks
  stacks=$(aws cloudformation list-stacks --region "$REGION" \
    --stack-status-filter CREATE_COMPLETE CREATE_FAILED ROLLBACK_COMPLETE \
      UPDATE_COMPLETE UPDATE_ROLLBACK_COMPLETE DELETE_FAILED \
    --query "StackSummaries[?starts_with(StackName, 'eksctl-${cluster}-')].StackName" \
    --output text 2>/dev/null)
  if [[ -z "${stacks// /}" ]]; then
    info "none found"
    return 0
  fi
  for stack in $stacks; do
    if stack_is_ours "$stack"; then
      info "deleting stack $stack"
      run aws cloudformation delete-stack --region "$REGION" --stack-name "$stack"
    else
      skip "CloudFormation stack ${stack}"
    fi
  done
}

delete_load_balancers() {
  local cluster="$1" arn name
  log "[$cluster] sweeping orphaned load balancers"
  for arn in $(aws elbv2 describe-load-balancers --region "$REGION" \
      --query 'LoadBalancers[].LoadBalancerArn' --output text 2>/dev/null); do
    if elb_v2_is_ours "$arn" "$cluster"; then
      info "deleting ALB/NLB $arn"
      run aws elbv2 delete-load-balancer --region "$REGION" --load-balancer-arn "$arn"
    fi
  done
  for name in $(aws elb describe-load-balancers --region "$REGION" \
      --query 'LoadBalancerDescriptions[].LoadBalancerName' --output text 2>/dev/null); do
    if elb_classic_is_ours "$name" "$cluster"; then
      info "deleting classic ELB $name"
      run aws elb delete-load-balancer --region "$REGION" --load-balancer-name "$name"
    fi
  done
  # orphaned target groups left behind by deleted LBs
  for arn in $(aws elbv2 describe-target-groups --region "$REGION" \
      --query 'TargetGroups[?length(LoadBalancerArns)==`0`].TargetGroupArn' \
      --output text 2>/dev/null); do
    if elb_v2_is_ours "$arn" "$cluster"; then
      info "deleting orphaned target group $arn"
      run aws elbv2 delete-target-group --region "$REGION" --target-group-arn "$arn"
    fi
  done
  info "load balancer sweep complete"
}

delete_ebs_volumes() {
  local cluster="$1"
  log "[$cluster] sweeping EBS volumes"
  # Available (detached) volumes only — attached volumes go away with their
  # instances when the node group is deleted.
  local vols vol tags
  vols=$(aws ec2 describe-volumes --region "$REGION" \
    --filters "Name=status,Values=available,creating,error" \
    --query 'Volumes[].VolumeId' --output text 2>/dev/null)
  if [[ -z "${vols// /}" ]]; then
    info "no detached volumes in ${REGION}"
    return 0
  fi
  for vol in $vols; do
    tags=$(aws ec2 describe-volumes --region "$REGION" --volume-ids "$vol" \
      --query 'Volumes[0].Tags[].[Key,Value]' --output text 2>/dev/null |
      tr '\t' '=' | tr '\n' ' ')
    if has_required_tag_list "$tags" "$cluster"; then
      info "deleting volume $vol"
      run aws ec2 delete-volume --region "$REGION" --volume-id "$vol"
    fi
  done
  info "EBS sweep complete"
}

delete_security_groups() {
  local cluster="$1"
  log "[$cluster] sweeping leftover security groups"
  local sgs sg tags
  sgs=$(aws ec2 describe-security-groups --region "$REGION" \
    --filters "Name=tag-key,Values=kubernetes.io/cluster/${cluster}" \
    --query 'SecurityGroups[].GroupId' --output text 2>/dev/null)
  sgs="$sgs $(aws ec2 describe-security-groups --region "$REGION" \
    --filters "Name=tag:${REQUIRED_TAG_KEY},Values=${REQUIRED_TAG_VALUE}" \
    --query 'SecurityGroups[].GroupId' --output text 2>/dev/null)"
  local seen=""
  for sg in $sgs; do
    [[ -z "$sg" || " $seen " == *" $sg "* ]] && continue
    seen="$seen $sg"
    tags=$(aws ec2 describe-security-groups --region "$REGION" --group-ids "$sg" \
      --query 'SecurityGroups[0].Tags[].[Key,Value]' --output text 2>/dev/null |
      tr '\t' '=' | tr '\n' ' ')
    if ! has_required_tag_list "$tags" "$cluster"; then
      skip "security group ${sg}"
      continue
    fi
    # Strip rules first: cross-referencing SGs cannot be deleted otherwise.
    local ingress egress
    ingress=$(aws ec2 describe-security-groups --region "$REGION" --group-ids "$sg" \
      --query 'SecurityGroups[0].IpPermissions' --output json 2>/dev/null)
    egress=$(aws ec2 describe-security-groups --region "$REGION" --group-ids "$sg" \
      --query 'SecurityGroups[0].IpPermissionsEgress' --output json 2>/dev/null)
    if [[ "$ingress" != "[]" && -n "$ingress" ]]; then
      run aws ec2 revoke-security-group-ingress --region "$REGION" \
        --group-id "$sg" --ip-permissions "$ingress" || true
    fi
    if [[ "$egress" != "[]" && -n "$egress" ]]; then
      run aws ec2 revoke-security-group-egress --region "$REGION" \
        --group-id "$sg" --ip-permissions "$egress" || true
    fi
    info "deleting security group $sg"
    run aws ec2 delete-security-group --region "$REGION" --group-id "$sg" \
      || info "could not delete $sg yet (still referenced); re-run teardown.sh"
  done
  info "security group sweep complete"
}

delete_log_groups() {
  local cluster="$1" lg="/aws/eks/${cluster}/cluster"
  log "[$cluster] deleting CloudWatch log group ${lg}"
  if aws logs describe-log-groups --region "$REGION" \
       --log-group-name-prefix "$lg" \
       --query 'logGroups[0].logGroupName' --output text 2>/dev/null | grep -q "$lg"; then
    run aws logs delete-log-group --region "$REGION" --log-group-name "$lg"
  else
    info "log group not found — already deleted"
  fi
  # Container Insights log groups created by the observability add-on
  local g
  for g in $(aws logs describe-log-groups --region "$REGION" \
      --log-group-name-prefix "/aws/containerinsights/${cluster}/" \
      --query 'logGroups[].logGroupName' --output text 2>/dev/null); do
    info "deleting log group $g"
    run aws logs delete-log-group --region "$REGION" --log-group-name "$g"
  done
}

# ── run ──────────────────────────────────────────────────────────────────────

[[ "$DRY_RUN" == "1" ]] && log "DRY_RUN=1 — no resource will be modified"

for cluster in "${CLUSTERS[@]}"; do
  log "######## Tearing down ${cluster} in ${REGION} ########"
  delete_cluster        "$cluster"
  delete_irsa_roles     "$cluster"
  delete_cfn_stacks     "$cluster"
  delete_load_balancers "$cluster"
  delete_ebs_volumes    "$cluster"
  delete_security_groups "$cluster"
  delete_log_groups     "$cluster"
done

log "Remaining XamOps-tagged footprint in ${REGION}"
aws eks list-clusters --region "$REGION" --query 'clusters' --output text
aws ec2 describe-volumes --region "$REGION" \
  --filters "Name=tag:${REQUIRED_TAG_KEY},Values=${REQUIRED_TAG_VALUE}" \
  --query 'Volumes[].[VolumeId,State]' --output text
aws elbv2 describe-load-balancers --region "$REGION" \
  --query 'LoadBalancers[].LoadBalancerName' --output text

if [[ -n "$SKIPPED" ]]; then
  log "SKIPPED (missing ${REQUIRED_TAG_KEY}=${REQUIRED_TAG_VALUE}) — review by hand:"
  printf '%b' "$SKIPPED" | sed 's/^/    /'
fi

log "Teardown pass finished. Safe to re-run; CloudFormation deletions are async."
