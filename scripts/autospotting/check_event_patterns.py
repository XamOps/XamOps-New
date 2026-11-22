#!/usr/bin/env python3
"""Check the AutoSpotting events nested stack against recorded sample events (AC-EVT-01, AC-ONB-03).

Three checks, all offline:

1. Every rule pattern in RegionFanOut.Properties.RulePatterns is matched against each sample
   event in scripts/autospotting/fixtures/events/. A fixture lists the rules it must match
   ("expect"); every other rule must not match. Positive and negative samples both count.
2. No pattern may filter on a field EC2 does not emit (instance-lifecycle), the defect that made
   the legacy rules match nothing.
3. The forwarder Lambda code is run against a stub SQS client for every sample event. Each
   MessageDeduplicationId and MessageGroupId it sends must be accepted by SQS FIFO (allowed
   characters only, 128 characters at most, no whitespace). The validator is first shown to
   reject the legacy forwarder's id, so the check cannot pass vacuously.
4. The RegionFanOut Lambda's apply_region is run against a stub EventBridge/SQS client. No
   put_targets target on an event bus may carry a RetryPolicy (EventBridge rejects it, D-23-02).
   The validator is first shown to reject the rw-2026.11.1 target. A second run has PutTargets
   reject one rule: that rule must end up deleted or disabled (never ENABLED without a target),
   the other rules must keep their target, and apply_region must raise naming the rule. The
   check is first shown to fail against the previous apply_region, which left the rule behind.
5. RegionFanOut.Properties.FanOutCodeVersion must equal the first 12 hex characters of the
   SHA-256 of the RegionFanOutFunction ZipFile text, so editing the code without bumping the
   version (which would stop CloudFormation re-invoking the custom resource) fails here.

Needs PyYAML (installed with cfn-lint). Usage:
  python3 scripts/autospotting/check_event_patterns.py [template] [fixtures-dir]
"""
import contextlib
import hashlib
import io
import json
import os
import re
import sys
import types

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
DEFAULT_TEMPLATE = os.path.join(ROOT, 'cloudformation', 'xamops-autospotting-events-nested.yaml')
DEFAULT_FIXTURES = os.path.join(HERE, 'fixtures', 'events')

FORBIDDEN_FIELDS = {'instance-lifecycle'}
# SQS FIFO: up to 128 of alphanumerics and !"#$%&'()*+,-./:;<=>?@[\]^_`{|}~ ; no whitespace.
SQS_ID = re.compile(r"^[A-Za-z0-9!\"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~]{1,128}$")


class _Loader(yaml.SafeLoader):
    """SafeLoader that accepts CloudFormation short-form tags and keeps their value."""


def _tag(loader, suffix, node):
    if isinstance(node, yaml.ScalarNode):
        return {suffix: loader.construct_scalar(node)}
    if isinstance(node, yaml.SequenceNode):
        return {suffix: loader.construct_sequence(node, deep=True)}
    return {suffix: loader.construct_mapping(node, deep=True)}


_Loader.add_multi_constructor('!', _tag)


def load_template(path):
    with open(path, encoding='utf-8') as f:
        return yaml.load(f, Loader=_Loader)


# --------------------------------------------------------------------------- matcher
def _value_matches(rule, value):
    """One element of a pattern array against one event value."""
    if isinstance(rule, dict):
        if len(rule) != 1:
            raise ValueError(f'unsupported operator object {rule}')
        op, arg = next(iter(rule.items()))
        if op == 'prefix':
            return isinstance(value, str) and value.startswith(arg)
        if op == 'suffix':
            return isinstance(value, str) and value.endswith(arg)
        if op == 'equals-ignore-case':
            return isinstance(value, str) and value.lower() == arg.lower()
        if op == 'anything-but':
            args = arg if isinstance(arg, list) else [arg]
            return value is not None and value not in args
        raise ValueError(f'unsupported operator {op}')
    return value == rule


def matches(pattern, event):
    """EventBridge content filtering for the subset of operators the patterns use."""
    for key, rule in pattern.items():
        if isinstance(rule, dict):
            if 'exists' in rule and len(rule) == 1:
                if bool(rule['exists']) != (key in event):
                    return False
                continue
            sub = event.get(key)
            if not isinstance(sub, dict) or not matches(rule, sub):
                return False
            continue
        if not isinstance(rule, list):
            raise ValueError(f'pattern field {key} must be a list or an object')
        if key not in event:
            return False
        value = event[key]
        values = value if isinstance(value, list) else [value]
        if not any(_value_matches(r, v) for r in rule for v in values):
            return False
    return True


def field_names(pattern):
    for key, rule in pattern.items():
        yield key
        if isinstance(rule, dict) and not (len(rule) == 1 and next(iter(rule)) in ('exists',)):
            yield from field_names(rule)


# --------------------------------------------------------------------------- forwarder
def run_forwarder(code, event, account='111122223333', region='ap-south-1'):
    """Runs the forwarder source with a stub boto3; returns the send_message kwargs or None."""
    sent = []

    class Sqs:
        def send_message(self, **kwargs):
            sent.append(kwargs)
            return {'MessageId': 'stub-message-id'}

    boto3 = types.ModuleType('boto3')
    boto3.client = lambda *a, **k: Sqs()
    saved = {k: os.environ.get(k) for k in ('XAMOPS_REGION', 'XAMOPS_SQS_QUEUE_URL', 'CUSTOMER_ACCOUNT_ID')}
    os.environ.update({
        'XAMOPS_REGION': region,
        'XAMOPS_SQS_QUEUE_URL': f'https://sqs.{region}.amazonaws.com/605134457560/xamops-autospotting-events.fifo',
        'CUSTOMER_ACCOUNT_ID': account,
    })
    previous = sys.modules.get('boto3')
    sys.modules['boto3'] = boto3
    try:
        namespace = {'__name__': 'forwarder'}
        exec(compile(code, 'ForwarderFunction', 'exec'), namespace)
        with contextlib.redirect_stdout(io.StringIO()):
            namespace['lambda_handler'](event, None)
    finally:
        if previous is None:
            sys.modules.pop('boto3', None)
        else:
            sys.modules['boto3'] = previous
        for k, v in saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
    return sent[0] if sent else None


# --------------------------------------------------------------------------- fan-out targets
def target_problems(targets):
    """PutTargets constraints EventBridge enforces for the targets the fan-out creates.

    An event-bus target may not carry a RetryPolicy: EventBridge rejects the whole call with
    "Retry policy is not supported for Event bus targets" (phase 23 defect D-23-02).
    """
    problems = []
    for t in targets:
        if ':event-bus/' in str(t.get('Arn', '')) and 'RetryPolicy' in t:
            problems.append(f"target {t.get('Id')} on event bus {t['Arn']} sets RetryPolicy, "
                            'which EventBridge rejects for event-bus targets')
    return problems


def run_fanout_targets(code, patterns, region='us-east-1', account='111122223333'):
    """Runs the fan-out's apply_region with a stub boto3; returns every put_targets Targets list."""
    calls = []

    class Stub:
        def create_queue(self, **kwargs):
            return {'QueueUrl': f"https://sqs.{region}.amazonaws.com/{account}/{kwargs['QueueName']}"}

        def set_queue_attributes(self, **kwargs):
            return {}

        def put_rule(self, **kwargs):
            return {'RuleArn': f"arn:aws:events:{region}:{account}:rule/{kwargs['Name']}"}

        def put_targets(self, **kwargs):
            calls.append(kwargs['Targets'])
            return {'FailedEntryCount': 0, 'FailedEntries': []}

    boto3 = types.ModuleType('boto3')
    boto3.client = lambda *a, **k: Stub()
    previous = sys.modules.get('boto3')
    sys.modules['boto3'] = boto3
    try:
        namespace = {'__name__': 'fanout'}
        exec(compile(code, 'RegionFanOutFunction', 'exec'), namespace)
        with contextlib.redirect_stdout(io.StringIO()):
            namespace['apply_region'](region, account,
                                      f'arn:aws:events:{region}:{account}:event-bus/xamops-autospotting-hub',
                                      f'arn:aws:iam::{account}:role/XamOpsAutoSpottingForwardRole', patterns)
    finally:
        if previous is None:
            sys.modules.pop('boto3', None)
        else:
            sys.modules['boto3'] = previous
    return calls


# apply_region as shipped in rw-2026.11.2: a rejected target left the rule ENABLED without one.
LEGACY_APPLY_REGION = """
def apply_region(region, account, hub_arn, role_arn, patterns):
    dlq_arn = ensure_dlq(region, account)
    events = boto3.client('events', region_name=region)
    for key, pattern in patterns.items():
        name = rule_name(key)
        events.put_rule(Name=name, EventPattern=json.dumps(pattern), State='ENABLED')
        resp = events.put_targets(Rule=name, Targets=[{'Id': TARGET_ID, 'Arn': hub_arn,
                                  'RoleArn': role_arn, 'DeadLetterConfig': {'Arn': dlq_arn}}])
        if resp.get('FailedEntryCount'):
            raise RuntimeError(f"PutTargets {name}: {resp.get('FailedEntries')}")
"""


def run_fanout_rejected_target(code, patterns, failing_key, region='us-east-1', account='111122223333',
                               apply_override=None, disable_only=False):
    """Runs apply_region with PutTargets rejecting failing_key's rule; returns a list of problems."""
    rules = {}  # name -> {'state': str, 'targets': list}

    class Stub:
        def create_queue(self, **kwargs):
            return {'QueueUrl': f"https://sqs.{region}.amazonaws.com/{account}/{kwargs['QueueName']}"}

        def set_queue_attributes(self, **kwargs):
            return {}

        def put_rule(self, **kwargs):
            rules[kwargs['Name']] = {'state': kwargs.get('State', 'ENABLED'), 'targets': []}
            return {'RuleArn': f"arn:aws:events:{region}:{account}:rule/{kwargs['Name']}"}

        def put_targets(self, **kwargs):
            if kwargs['Rule'].startswith(f'xamops-as-{failing_key}'):
                return {'FailedEntryCount': 1, 'FailedEntries': [{'ErrorCode': 'ValidationException'}]}
            rules[kwargs['Rule']]['targets'] = list(kwargs['Targets'])
            return {'FailedEntryCount': 0, 'FailedEntries': []}

        def remove_targets(self, **kwargs):
            if kwargs['Rule'] in rules:
                rules[kwargs['Rule']]['targets'] = []
            return {'FailedEntryCount': 0}

        def delete_rule(self, **kwargs):
            if disable_only:
                raise RuntimeError('delete_rule unavailable')
            rules.pop(kwargs['Name'], None)

        def disable_rule(self, **kwargs):
            if kwargs['Name'] in rules:
                rules[kwargs['Name']]['state'] = 'DISABLED'

    boto3 = types.ModuleType('boto3')
    boto3.client = lambda *a, **k: Stub()
    previous = sys.modules.get('boto3')
    sys.modules['boto3'] = boto3
    problems, raised = [], None
    try:
        namespace = {'__name__': 'fanout'}
        exec(compile(code, 'RegionFanOutFunction', 'exec'), namespace)
        if apply_override:
            exec(compile(apply_override, 'LegacyApplyRegion', 'exec'), namespace)
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                namespace['apply_region'](region, account,
                                          f'arn:aws:events:{region}:{account}:event-bus/xamops-autospotting-hub',
                                          f'arn:aws:iam::{account}:role/XamOpsAutoSpottingForwardRole', patterns)
        except Exception as e:
            raised = e
    finally:
        if previous is None:
            sys.modules.pop('boto3', None)
        else:
            sys.modules['boto3'] = previous
    for name, rule in rules.items():
        if rule['state'] == 'ENABLED' and not rule['targets']:
            problems.append(f'rule {name} is ENABLED without a target after PutTargets was rejected')
    if raised is None:
        problems.append('apply_region did not raise after a rejected target')
    elif failing_key not in str(raised) or failing_key not in getattr(raised, 'rule_keys', [failing_key]):
        problems.append(f'apply_region error does not name rule {failing_key}: {raised}')
    for key in patterns:
        if key != failing_key and not rules.get(f'xamops-as-{key}', {}).get('targets'):
            problems.append(f'rule {key} was not configured after another rule failed')
    return problems


def code_version(code):
    """FanOutCodeVersion: the first 12 hex characters of the SHA-256 of the ZipFile text."""
    return hashlib.sha256(code.encode('utf-8')).hexdigest()[:12]


def main(argv):
    template_path = argv[1] if len(argv) > 1 else DEFAULT_TEMPLATE
    fixtures_dir = argv[2] if len(argv) > 2 else DEFAULT_FIXTURES
    template = load_template(template_path)
    resources = template['Resources']
    patterns = resources['RegionFanOut']['Properties']['RulePatterns']
    code = resources['ForwarderFunction']['Properties']['Code']['ZipFile']
    problems = []
    checked = 0

    # 2. Forbidden fields.
    for rule, pattern in patterns.items():
        bad = FORBIDDEN_FIELDS.intersection(field_names(pattern))
        if bad:
            problems.append(f'rule {rule} filters on {sorted(bad)}, which EC2 does not emit')

    # The validator must reject the legacy forwarder's id, or check 3 proves nothing.
    legacy = 'i-0123456789abcdef0-EC2 Spot Instance Interruption Warning-2026-10-12T09:41:07Z'
    if SQS_ID.match(legacy):
        problems.append('internal: the SQS id validator accepted the legacy deduplication id')

    # 4. The fan-out's PutTargets calls. The validator must first reject the rw-2026.11.1 target
    #    (RetryPolicy on the hub bus), or the check proves nothing.
    defective = [{'Id': 'HubTarget', 'Arn': 'arn:aws:events:us-east-1:111122223333:event-bus/xamops-autospotting-hub',
                  'RoleArn': 'arn:aws:iam::111122223333:role/r',
                  'RetryPolicy': {'MaximumEventAgeInSeconds': 3600, 'MaximumRetryAttempts': 185}}]
    if not target_problems(defective):
        problems.append('internal: the target validator accepted a RetryPolicy on an event-bus target')
    fanout_code = resources['RegionFanOutFunction']['Properties']['Code']['ZipFile']
    target_calls = run_fanout_targets(fanout_code, patterns)
    if len(target_calls) != len(patterns):
        problems.append(f'fan-out made {len(target_calls)} put_targets calls for {len(patterns)} rules')
    for targets in target_calls:
        problems.extend(target_problems(targets))

    # 4b. A rejected PutTargets must not leave an ENABLED rule without a target. The check must
    #     first fail against the previous apply_region, or it proves nothing.
    failing_key = next(iter(patterns))
    if not run_fanout_rejected_target(fanout_code, patterns, failing_key, apply_override=LEGACY_APPLY_REGION):
        problems.append('internal: the rejected-target check passed the previous apply_region')
    problems.extend(run_fanout_rejected_target(fanout_code, patterns, failing_key))
    problems.extend(run_fanout_rejected_target(fanout_code, patterns, failing_key, disable_only=True))

    # 5. FanOutCodeVersion ties the property to the inline code.
    if code_version(fanout_code) == code_version(fanout_code + '\n# edited'):
        problems.append('internal: a modified fan-out code string produced the same version')
    expected = code_version(fanout_code)
    actual = resources['RegionFanOut']['Properties'].get('FanOutCodeVersion')
    if actual != expected:
        problems.append(f"FanOutCodeVersion is {actual!r}, expected '{expected}' "
                        '(first 12 hex of SHA-256 of the RegionFanOutFunction ZipFile)')

    names = sorted(f for f in os.listdir(fixtures_dir) if f.endswith('.json'))
    if not names:
        problems.append(f'no fixtures in {fixtures_dir}')
    for fname in names:
        with open(os.path.join(fixtures_dir, fname), encoding='utf-8') as f:
            fixture = json.load(f)
        event, expect = fixture['event'], sorted(fixture['expect'])
        # 1. Rule patterns against the sample.
        got = sorted(rule for rule, pattern in patterns.items() if matches(pattern, event))
        checked += 1
        if got != expect:
            problems.append(f'{fname}: rules matched {got}, expected {expect}')
        # 3. The forwarder, for samples a rule matches (the hub bus only sees those).
        sent = run_forwarder(code, event)
        if expect:
            if sent is None:
                problems.append(f'{fname}: forwarder sent nothing for a forwarded event')
                continue
            for field in ('MessageDeduplicationId', 'MessageGroupId'):
                value = sent.get(field)
                if not isinstance(value, str) or not SQS_ID.match(value):
                    problems.append(f'{fname}: {field} {value!r} would be rejected by SQS')
            body = json.loads(sent['MessageBody'])
            if body.get('account') != '111122223333' or body.get('detail-type') != event['detail-type']:
                problems.append(f'{fname}: forwarded envelope lost the account or detail-type')
            if len(sent['MessageBody'].encode()) > 256 * 1024:
                problems.append(f'{fname}: message over the 256 KB SQS limit')
        elif sent is not None:
            problems.append(f'{fname}: forwarder sent an event no rule forwards')

    if problems:
        print('FAIL')
        for p in problems:
            print('  ' + p)
        return 1
    print(f'ok  {len(patterns)} rule patterns, {checked} sample events, forwarder ids valid, '
          f'{len(target_calls)} fan-out targets valid')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
