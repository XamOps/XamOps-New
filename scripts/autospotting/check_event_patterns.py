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

Needs PyYAML (installed with cfn-lint). Usage:
  python3 scripts/autospotting/check_event_patterns.py [template] [fixtures-dir]
"""
import contextlib
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
    print(f'ok  {len(patterns)} rule patterns, {checked} sample events, forwarder ids valid')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
