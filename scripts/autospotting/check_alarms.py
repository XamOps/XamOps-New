#!/usr/bin/env python3
"""Assert the AutoSpotting CloudWatch alarms exist and all notify on-call.

design-ux-ops.md §6.4 / FR-OBS-03 / AC-OBS-02, plan-phase-10.md task 10.3.

Given the monitoring template and the engine template, this checks:

  1. every FR-OBS-03 alarm condition that exists at this phase is present exactly once
     (the ledger writer conditions are expected from Phase 17; variance and close conditions are added in 25/32 and NOT expected yet); and
  2. every AWS::CloudWatch::Alarm in both templates has a non-empty AlarmActions.

It exits 0 when both hold, else prints the failures and exits 1. It is wired into the
xamops-build-test CI job. No external dependency: a tiny indentation parser reads the
CloudFormation YAML and treats the `!Ref/!Sub/!GetAtt/!If` short forms as opaque scalars,
which is all this check needs (it never resolves an intrinsic).
"""
import sys


# ── a minimal YAML-subset parser (maps, lists, scalars; indentation-based) ──────

def _strip_comment(line):
    # Remove a trailing comment that is not inside quotes. Good enough for these templates.
    in_single = in_double = False
    out = []
    i = 0
    while i < len(line):
        c = line[i]
        if c == "'" and not in_double:
            in_single = not in_single
        elif c == '"' and not in_single:
            in_double = not in_double
        elif c == '#' and not in_single and not in_double:
            # a '#' starting a comment must be preceded by whitespace or be at line start
            if i == 0 or line[i - 1] in ' \t':
                break
        out.append(c)
        i += 1
    return ''.join(out).rstrip()


def _scalar(token):
    token = token.strip()
    if len(token) >= 2 and token[0] in '"\'' and token[-1] == token[0]:
        return token[1:-1]
    return token


def parse(lines):
    """Parse an indentation-based YAML subset into nested dict/list/str. Returns the root dict."""
    # Build a list of (indent, raw_content) for non-blank, non-comment lines.
    rows = []
    for raw in lines:
        stripped = _strip_comment(raw)
        if not stripped.strip():
            continue
        indent = len(raw) - len(raw.lstrip(' '))
        rows.append((indent, stripped.strip(), raw))
    pos = [0]

    def parse_block(min_indent):
        # Decide: is this block a list (lines start with '- ') or a map?
        if pos[0] >= len(rows):
            return None
        indent, content, _ = rows[pos[0]]
        if indent < min_indent:
            return None
        if content.startswith('- '):
            return parse_list(indent)
        return parse_map(indent)

    def parse_map(indent):
        result = {}
        while pos[0] < len(rows):
            cur_indent, content, _ = rows[pos[0]]
            if cur_indent < indent:
                break
            if cur_indent > indent:
                # Shouldn't happen for a well-formed map; skip defensively.
                pos[0] += 1
                continue
            if content.startswith('- '):
                break
            if ':' not in content:
                pos[0] += 1
                continue
            key, _, rest = content.partition(':')
            key = _scalar(key)
            rest = rest.strip()
            pos[0] += 1
            if rest == '' or rest == '|' or rest == '>':
                # Nested block (map or list) or a block scalar; parse what follows if deeper.
                if pos[0] < len(rows) and rows[pos[0]][0] > indent:
                    if rows[pos[0]][1].startswith('- '):
                        result[key] = parse_list(rows[pos[0]][0])
                    elif rest in ('|', '>'):
                        result[key] = parse_block_scalar(indent)
                    else:
                        result[key] = parse_map(rows[pos[0]][0])
                else:
                    result[key] = '' if rest in ('', '|', '>') else rest
            else:
                result[key] = _scalar(rest)
        return result

    def parse_block_scalar(indent):
        parts = []
        while pos[0] < len(rows) and rows[pos[0]][0] > indent:
            parts.append(rows[pos[0]][1])
            pos[0] += 1
        return ' '.join(parts)

    def parse_list(indent):
        items = []
        while pos[0] < len(rows):
            cur_indent, content, _ = rows[pos[0]]
            if cur_indent != indent or not content.startswith('- '):
                break
            item_body = content[2:].strip()
            pos[0] += 1
            if ':' in item_body and not (item_body[0] in '"\''):
                # An inline map entry starting on the dash line; parse the rest as a one-key map,
                # then merge any deeper lines at indent+2 into it.
                key, _, rest = item_body.partition(':')
                entry = {_scalar(key): _scalar(rest.strip()) if rest.strip() else None}
                while pos[0] < len(rows) and rows[pos[0]][0] > indent and not rows[pos[0]][1].startswith('- '):
                    k2, _, r2 = rows[pos[0]][1].partition(':')
                    r2 = r2.strip()
                    pos[0] += 1
                    if r2 == '' and pos[0] < len(rows) and rows[pos[0]][0] > indent + 2:
                        if rows[pos[0]][1].startswith('- '):
                            entry[_scalar(k2)] = parse_list(rows[pos[0]][0])
                        else:
                            entry[_scalar(k2)] = parse_map(rows[pos[0]][0])
                    else:
                        entry[_scalar(k2)] = _scalar(r2)
                items.append(entry)
            else:
                items.append(_scalar(item_body))
        return items

    return parse_map(rows[0][0]) if rows else {}


# ── alarm extraction ────────────────────────────────────────────────────────

def alarms_in(template):
    resources = template.get('Resources', {}) or {}
    found = {}
    for name, body in resources.items():
        if isinstance(body, dict) and body.get('Type') == 'AWS::CloudWatch::Alarm':
            found[name] = body.get('Properties', {}) or {}
    return found


def has_nonempty_alarm_actions(props):
    actions = props.get('AlarmActions')
    if actions is None:
        return False
    if isinstance(actions, list):
        return len(actions) > 0
    return bool(str(actions).strip())


def metric_name(props):
    if 'MetricName' in props:
        return props['MetricName']
    # Metric-math alarm: look at the Metrics list for a returned expression or the first metric.
    metrics = props.get('Metrics')
    if isinstance(metrics, list):
        for m in metrics:
            if isinstance(m, dict) and 'MetricStat' in m and isinstance(m['MetricStat'], dict):
                metric = m['MetricStat'].get('Metric', {})
                if isinstance(metric, dict) and 'MetricName' in metric:
                    return metric['MetricName']
    return None


# The FR-OBS-03 conditions expected at THIS phase (ledger writer rows from Phase 17; variance and close rows excluded; §6.4).
# Each entry: a human label and a predicate over (metric_name, props) identifying its alarm.
def _dim(props, key, value):
    dims = props.get('Dimensions')
    if not isinstance(dims, list):
        return False
    for d in dims:
        if isinstance(d, dict) and d.get('Name') == key and d.get('Value') == value:
            return True
    return False


def _dim_has(props, key, fragment):
    """True when a dimension named key has a value containing fragment (values can be !Sub/!If scalars)."""
    dims = props.get('Dimensions')
    if not isinstance(dims, list):
        return False
    for d in dims:
        if isinstance(d, dict) and d.get('Name') == key:
            if fragment in str(d.get('Value')):
                return True
            # An !If value parses as a list of scalars.
            if isinstance(d.get('Value'), list) and any(fragment in str(v) for v in d['Value']):
                return True
    return False


def _events_queue(props):
    return _dim_has(props, 'QueueName', 'xamops-autospotting-events')


EXPECTED = [
    ('Group needs a human (groups state=FAILED)',
     lambda mn, p: mn == 'autospotting.groups' and _dim(p, 'state', 'FAILED')),
    ('Stuck operation (ops.stuck)',
     lambda mn, p: mn == 'autospotting.ops.stuck'),
    ('Reconciler behind (reconcile.staleness_seconds)',
     lambda mn, p: mn == 'autospotting.reconcile.staleness_seconds'),
    ('Conversion failure rate (conversions.completed metric math)',
     lambda mn, p: mn == 'autospotting.conversions.completed'),
    ('Degraded too long (groups state=DEGRADED)',
     lambda mn, p: mn == 'autospotting.groups' and _dim(p, 'state', 'DEGRADED')),
    ('Fallback entered (fallbacks direction=enter)',
     lambda mn, p: mn == 'autospotting.fallbacks' and _dim(p, 'direction', 'enter')),
    ('Fallback too long (groups state=FALLBACK)',
     lambda mn, p: mn == 'autospotting.groups' and _dim(p, 'state', 'FALLBACK')),
    ('Interruption spike (interruptions)',
     lambda mn, p: mn == 'autospotting.interruptions'),
    ('Drift (groups.drift severity=MATERIAL)',
     lambda mn, p: mn == 'autospotting.groups.drift' and _dim(p, 'severity', 'MATERIAL')),
    ('Reconciler not running (reconcile.last_success_age)',
     lambda mn, p: mn == 'autospotting.reconcile.last_success_age'),
    ('Lease store errors (lease.failures reason=store_error)',
     lambda mn, p: mn == 'autospotting.lease.failures' and _dim(p, 'reason', 'store_error')),
    ('Ledger writer missed run (ledger.last_success_age)',
     lambda mn, p: mn == 'autospotting.ledger.last_success_age'),
    ('UNKNOWN price ratio (ledger.unknown / ledger.entries.all metric math)',
     lambda mn, p: mn == 'autospotting.ledger.unknown'),
    ('DLQ depth (engine stack ApproximateNumberOfMessagesVisible)',
     lambda mn, p: mn == 'ApproximateNumberOfMessagesVisible' and not _events_queue(p)),
    ('Event lag (engine stack ApproximateAgeOfOldestMessage)',
     lambda mn, p: mn == 'ApproximateAgeOfOldestMessage' and not _events_queue(p)),
    ('Events DLQ depth (xamops-autospotting-events DLQ ApproximateNumberOfMessagesVisible)',
     lambda mn, p: mn == 'ApproximateNumberOfMessagesVisible' and _events_queue(p)),
    ('Events queue age (xamops-autospotting-events ApproximateAgeOfOldestMessage)',
     lambda mn, p: mn == 'ApproximateAgeOfOldestMessage' and _events_queue(p)),
    ('Events sender mismatch (events.security reason=sender_mismatch)',
     lambda mn, p: mn == 'autospotting.events.security' and _dim(p, 'reason', 'sender_mismatch')),
    ('Events forwarder failure (rule xamops-as-forwarder-failure invocations)',
     lambda mn, p: mn == 'Invocations' and _dim_has(p, 'RuleName', 'xamops-as-forwarder-failure')),
]


def main(argv):
    if len(argv) < 2:
        print('usage: check_alarms.py <monitoring.yaml> <engine.yaml> [...]', file=sys.stderr)
        return 2

    all_alarms = {}  # logical name -> props
    for path in argv[1:]:
        with open(path) as fh:
            template = parse(fh.read().splitlines())
        for name, props in alarms_in(template).items():
            all_alarms[f'{path}:{name}'] = props

    failures = []

    # 2. every alarm has non-empty AlarmActions.
    for name, props in sorted(all_alarms.items()):
        if not has_nonempty_alarm_actions(props):
            failures.append(f'alarm {name} has no (non-empty) AlarmActions')

    # 1. every expected FR-OBS-03 condition is present exactly once.
    for label, pred in EXPECTED:
        matches = [name for name, props in all_alarms.items() if pred(metric_name(props), props)]
        if len(matches) == 0:
            failures.append(f'no alarm found for FR-OBS-03 condition: {label}')
        elif len(matches) > 1:
            failures.append(f'multiple alarms ({len(matches)}) for FR-OBS-03 condition: {label} -> {matches}')

    if failures:
        print('check_alarms: FAIL')
        for f in failures:
            print('  - ' + f)
        return 1

    print(f'check_alarms: OK  ({len(all_alarms)} alarms, {len(EXPECTED)} FR-OBS-03 conditions, all with AlarmActions)')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
