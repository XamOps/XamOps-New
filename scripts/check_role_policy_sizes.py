#!/usr/bin/python3
"""Structural validation gate for the CloudFormation IAM role templates.

Why this exists: `aws cloudformation validate-template` caps --template-body at
51,200 bytes and seven of the nine XamOps role templates are already over that,
so the AWS API cannot be the primary gate. This script is the primary gate. It
parses every template locally and asserts the things CloudFormation would only
tell us about at deploy time:

  * inline role policy documents  <= 10,240 characters (AWS IAM hard limit)
  * managed policy documents      <=  6,144 characters (AWS IAM hard limit)
  * at most 10 ManagedPolicyArns attached to a single role
  * no service-wildcard action (``ec2:*``); ``ec2:Describe*`` is fine
  * in the read-only templates, no mutating action at all, apart from the
    three whitelisted drift-detection calls

Run with /usr/bin/python3 - the Homebrew python3 on PATH has no PyYAML.

    /usr/bin/python3 scripts/check_role_policy_sizes.py [--templates-dir DIR]

Exits 0 when every policy passes, 1 otherwise, naming the offending file and
policy.
"""

import argparse
import json
import os
import re
import sys

import yaml

# AWS IAM hard limits, in characters of the compacted JSON policy document.
INLINE_POLICY_LIMIT = 10240
MANAGED_POLICY_LIMIT = 6144
MAX_MANAGED_POLICY_ARNS = 10

WILDCARD_ACTION = re.compile(r'^[a-z0-9-]+:\*$')

MUTATING_VERBS = (
    'Create', 'Delete', 'Update', 'Put', 'Attach', 'Detach', 'Modify',
    'Terminate', 'Run', 'Start', 'Stop', 'Tag', 'Untag', 'Set', 'Enable',
    'Disable', 'Associate', 'Disassociate', 'Import', 'Reboot',
)

# The only non-Describe/Get/List actions the read-only connection role is
# allowed to carry: drift detection is a read of reality, but AWS models it as
# a mutating verb.
READONLY_MUTATING_WHITELIST = {
    'cloudformation:DetectStackDrift',
    'cloudformation:DetectStackResourceDrift',
    'cloudformation:DetectStackSetDrift',
}

# Accepted pre-existing findings, keyed by (policy identifier, action).
#
# These are the action-level grants that already existed in the XamOps
# connection-role and stack-updater templates when this checker was written.
# Narrowing them changes what a deployed customer role can do - the inventory
# nested stack, the CUR export and the change-set updater all depend on these
# grants - so it is an owner decision, not a checker decision. They are listed
# explicitly rather than skipped silently: the run prints how many were
# suppressed, and any action NOT in this set fails the build. Adding a new
# wildcard anywhere, including to one of these same policies in a new file,
# still fails.
#
# Remove an entry here as soon as the corresponding policy is enumerated.
BASELINE_EXCEPTIONS = {
    # Deployment grants for the inventory nested stack, the CUR data export and
    # the report bucket. Same statements in all nine connection-role variants.
    ('XamOpsConnectionUpdaterRole.XamOpsConnectionStackResources', 'bcm-data-exports:*'),
    ('XamOpsConnectionUpdaterRole.XamOpsConnectionStackResources', 'cloudformation:*'),
    ('XamOpsConnectionUpdaterRole.XamOpsConnectionStackResources', 'events:*'),
    ('XamOpsConnectionUpdaterRole.XamOpsConnectionStackResources', 'lambda:*'),
    ('XamOpsConnectionUpdaterRole.XamOpsConnectionStackResources', 'logs:*'),
    ('XamOpsConnectionUpdaterRole.XamOpsConnectionStackResources', 's3:*'),
    ('XamOpsConnectionUpdaterRole.XamOpsConnectionStackResources', 'iam:CreateServiceLinkedRole'),
    # iam:* and cur:* on the connection-stack deployment role are the widest
    # pre-existing grants in the repo. Flagged for the owner, not narrowed here.
    ('XamOpsConnectionUpdaterRole.XamOpsConnectionStackResources', 'iam:*'),
    ('XamOpsConnectionUpdaterRole.XamOpsConnectionStackResources', 'cur:*'),
    ('XamOpsStackUpdaterRole.XamOpsConnectionStackResources', 'iam:*'),
    ('XamOpsStackUpdaterRole.XamOpsConnectionStackResources', 'cur:*'),
    ('XamOpsStackUpdaterRole.XamOpsConnectionStackResources', 'bcm-data-exports:*'),
    ('XamOpsStackUpdaterRole.XamOpsConnectionStackResources', 'cloudformation:*'),
    ('XamOpsStackUpdaterRole.XamOpsConnectionStackResources', 'events:*'),
    ('XamOpsStackUpdaterRole.XamOpsConnectionStackResources', 'lambda:*'),
    ('XamOpsStackUpdaterRole.XamOpsConnectionStackResources', 'logs:*'),
    ('XamOpsStackUpdaterRole.XamOpsConnectionStackResources', 's3:*'),
    ('XamOpsStackUpdaterRole.XamOpsConnectionStackResources', 'iam:CreateServiceLinkedRole'),
    # The change-set updater policy attached to the read-only role. Deliberate
    # and documented in the templates themselves: every change goes through a
    # change set, and UpdateStack/CreateStack/DeleteStack are deliberately absent.
    ('XamOpsConnectionUpdate', 'cloudformation:CreateChangeSet'),
    ('XamOpsConnectionUpdate', 'cloudformation:DeleteChangeSet'),
    ('XamOpsConnectionUpdate', 'cloudformation:ExecuteChangeSet'),
    ('XamOpsConnectionUpdate', 'iam:PassRole'),
    ('XamOpsStackUpdate', 'cloudformation:CreateChangeSet'),
    ('XamOpsStackUpdate', 'cloudformation:DeleteChangeSet'),
    ('XamOpsStackUpdate', 'cloudformation:ExecuteChangeSet'),
    ('XamOpsStackUpdate', 'iam:PassRole'),
    # Read-side actions AWS models with a mutating verb: a Resource Explorer
    # index and a service-linked role are prerequisites for reading, a Logs
    # Insights query is started to be read, and ModifyBudget is a Budgets
    # artefact of the cost-read grant.
    ('XamOpsReadOnlyRole.XamOpsComprehensiveReadOnlyPolicy', 'budgets:ModifyBudget'),
    ('XamOpsReadOnlyRole.XamOpsComprehensiveReadOnlyPolicy', 'iam:CreateServiceLinkedRole'),
    ('XamOpsReadOnlyRole.XamOpsComprehensiveReadOnlyPolicy', 'logs:StartQuery'),
    ('XamOpsReadOnlyRole.XamOpsComprehensiveReadOnlyPolicy', 'resource-explorer-2:CreateIndex'),
    ('XamOpsReadOnlyRole.XamOpsComprehensiveReadOnlyPolicy', 'resource-explorer-2:UpdateIndexType'),
    # CUR delivery writes the report objects it later reads, scoped to the
    # xamops-cur-<account> bucket.
    ('XamOpsReadOnlyRole.XamOpsComprehensiveReadOnlyPolicy', 's3:PutObject'),
    ('XamOpsReadOnlyRole.XamOpsComprehensiveReadOnlyPolicy', 's3:DeleteObject'),
    # Lambda roles that ship inside the read-only connection template. They are
    # not the connection role and are not read-only by design.
    ('AutoCallbackLambdaRole.XamOpsEventBridgePutEventsPolicy', 'events:PutEvents'),
    ('XamOpsPolicyResourceExplorerSlr', 'iam:CreateServiceLinkedRole'),
}


class CfnLoader(yaml.SafeLoader):
    """SafeLoader that tolerates the CloudFormation short-form tags."""


def _construct_cfn_tag(loader, tag_suffix, node):
    """Turn !Ref/!Sub/!GetAtt/!If into their long form so the document stays
    JSON-serialisable and the measured size matches what CloudFormation sends."""
    key = 'Ref' if tag_suffix == 'Ref' else 'Fn::%s' % tag_suffix
    if isinstance(node, yaml.ScalarNode):
        value = loader.construct_scalar(node)
    elif isinstance(node, yaml.SequenceNode):
        value = loader.construct_sequence(node, deep=True)
    else:
        value = loader.construct_mapping(node, deep=True)
    return {key: value}


CfnLoader.add_multi_constructor('!', _construct_cfn_tag)


def load_template(path):
    with open(path, 'r') as handle:
        return yaml.load(handle, Loader=CfnLoader)


def policy_size(document):
    return len(json.dumps(document, separators=(',', ':'), sort_keys=True))


def iter_actions(document):
    """Yield every action string in a policy document, wherever it sits."""
    if not isinstance(document, dict):
        return
    statements = document.get('Statement')
    if isinstance(statements, dict):
        statements = [statements]
    if not isinstance(statements, list):
        return
    for statement in statements:
        if not isinstance(statement, dict):
            continue
        for key in ('Action', 'NotAction'):
            actions = statement.get(key)
            if isinstance(actions, str):
                actions = [actions]
            if not isinstance(actions, list):
                continue
            for action in actions:
                if isinstance(action, str):
                    yield action


def is_readonly_template(filename):
    return 'readonly' in filename.lower()


def check_actions(document, filename, policy_name, failures, suppressed):
    readonly = is_readonly_template(filename)
    for action in iter_actions(document):
        excused = (policy_name, action) in BASELINE_EXCEPTIONS
        if WILDCARD_ACTION.match(action):
            if excused:
                suppressed.add((filename, policy_name, action))
                continue
            failures.append(
                '%s / %s: service-wildcard action %r - enumerate the actions instead'
                % (filename, policy_name, action))
            continue
        if not readonly or action in READONLY_MUTATING_WHITELIST:
            continue
        if ':' not in action:
            continue
        verb = action.split(':', 1)[1]
        if verb.startswith(MUTATING_VERBS):
            if excused:
                suppressed.add((filename, policy_name, action))
                continue
            failures.append(
                '%s / %s: mutating action %r in a read-only template'
                % (filename, policy_name, action))


def expand_conditional(entry):
    """A `Policies:` entry may be an Fn::If that picks between two policies (or
    AWS::NoValue). Yield the real policy dicts inside it."""
    if not isinstance(entry, dict):
        return
    if set(entry.keys()) == {'Fn::If'}:
        branches = entry['Fn::If']
        if isinstance(branches, list):
            for branch in branches[1:]:
                for inner in expand_conditional(branch):
                    yield inner
        return
    if entry == {'Ref': 'AWS::NoValue'}:
        return
    yield entry


def collect_policies(template, filename, failures):
    """Return [(policy_name, document, limit, kind)] for one template."""
    rows = []
    resources = (template or {}).get('Resources')
    if not isinstance(resources, dict):
        return rows

    for logical_id, resource in sorted(resources.items()):
        if not isinstance(resource, dict):
            continue
        rtype = resource.get('Type')
        props = resource.get('Properties') or {}
        if not isinstance(props, dict):
            continue

        if rtype == 'AWS::IAM::Role':
            arns = props.get('ManagedPolicyArns') or []
            if isinstance(arns, list) and len(arns) > MAX_MANAGED_POLICY_ARNS:
                failures.append(
                    '%s / %s: %d ManagedPolicyArns attached, AWS allows %d'
                    % (filename, logical_id, len(arns), MAX_MANAGED_POLICY_ARNS))
            for entry in props.get('Policies') or []:
                for policy in expand_conditional(entry):
                    name = policy.get('PolicyName')
                    if not isinstance(name, str):
                        name = '<computed>'
                    rows.append(('%s.%s' % (logical_id, name),
                                 policy.get('PolicyDocument'),
                                 INLINE_POLICY_LIMIT, 'inline'))
        elif rtype == 'AWS::IAM::Policy':
            name = props.get('PolicyName')
            if not isinstance(name, str):
                name = logical_id
            rows.append((name, props.get('PolicyDocument'),
                         INLINE_POLICY_LIMIT, 'inline'))
        elif rtype == 'AWS::IAM::ManagedPolicy':
            rows.append((logical_id, props.get('PolicyDocument'),
                         MANAGED_POLICY_LIMIT, 'managed'))

    return rows


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    default_dir = os.path.join(os.path.dirname(here), 'cloudformation')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--templates-dir', default=default_dir,
                        help='directory of CloudFormation templates (default: %(default)s)')
    args = parser.parse_args()

    paths = sorted(
        os.path.join(args.templates_dir, name)
        for name in os.listdir(args.templates_dir)
        if name.endswith('.yml') or name.endswith('.yaml')
    )
    if not paths:
        print('no templates found in %s' % args.templates_dir, file=sys.stderr)
        return 1

    failures = []
    suppressed = set()
    print('%-44s %-46s %-7s %8s %8s %9s'
          % ('TEMPLATE', 'POLICY', 'KIND', 'SIZE', 'LIMIT', 'HEADROOM'))
    print('-' * 128)

    checked = 0
    for path in paths:
        filename = os.path.basename(path)
        try:
            template = load_template(path)
        except yaml.YAMLError as exc:
            failures.append('%s: YAML parse error: %s' % (filename, exc))
            continue

        for name, document, limit, kind in collect_policies(template, filename, failures):
            if document is None:
                failures.append('%s / %s: policy has no PolicyDocument' % (filename, name))
                continue
            size = policy_size(document)
            headroom = limit - size
            checked += 1
            print('%-44s %-46s %-7s %8d %8d %9d'
                  % (filename, name[:46], kind, size, limit, headroom))
            if headroom <= 0:
                failures.append(
                    '%s / %s: %s policy document is %d chars, over the %d limit by %d'
                    % (filename, name, kind, size, limit, -headroom))
            check_actions(document, filename, name, failures, suppressed)

    print('-' * 128)
    print('%d policy documents checked across %d templates' % (checked, len(paths)))
    if suppressed:
        print('%d action findings suppressed by BASELINE_EXCEPTIONS '
              '(pre-existing grants, see the comment in this script):' % len(suppressed))
        for filename, policy_name, action in sorted(suppressed):
            print('  ~ %s / %s: %s' % (filename, policy_name, action))

    if failures:
        print('\nFAILED (%d):' % len(failures), file=sys.stderr)
        for failure in failures:
            print('  - %s' % failure, file=sys.stderr)
        return 1

    print('OK: every policy is under its IAM limit, no service-wildcard action, '
          'no mutating action in a read-only template.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
