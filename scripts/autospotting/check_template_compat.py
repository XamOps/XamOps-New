#!/usr/bin/env python3
"""Check that a new CloudFormation template version is a safe in-place update of the old one.

Rules (design-aws.md section 10.2): "Update stack" must change the stack in place, so

* no parameter is removed or renamed;
* every new parameter has a Default;
* no logical id is renamed or removed, except resources listed with --allow-remove (the legacy
  forwarder resources the new version retires on purpose);
* a Type change on a kept logical id is refused (it replaces the resource);
* the replacement-sensitive fields of the kept resources are unchanged: RoleName, Path,
  AssumeRolePolicyDocument, ManagedPolicyName and Description of IAM resources, and FunctionName
  of Lambda functions;
* the TemplateVersion in Metadata.XamOps and in Outputs.TemplateVersion are equal and different
  from the old version.

Needs PyYAML (installed with cfn-lint). Usage:
  python3 scripts/autospotting/check_template_compat.py OLD NEW [--allow-remove ID[,ID...]]
"""
import json
import sys

import yaml


class _Loader(yaml.SafeLoader):
    pass


def _tag(loader, suffix, node):
    if isinstance(node, yaml.ScalarNode):
        return {suffix: loader.construct_scalar(node)}
    if isinstance(node, yaml.SequenceNode):
        return {suffix: loader.construct_sequence(node, deep=True)}
    return {suffix: loader.construct_mapping(node, deep=True)}


_Loader.add_multi_constructor('!', _tag)

SENSITIVE = {
    'AWS::IAM::Role': ['RoleName', 'Path', 'AssumeRolePolicyDocument'],
    'AWS::IAM::ManagedPolicy': ['ManagedPolicyName', 'Description', 'Path'],
    'AWS::Lambda::Function': ['FunctionName'],
}


def load(path):
    with open(path, encoding='utf-8') as f:
        return yaml.load(f, Loader=_Loader) or {}


def version_of(template):
    meta = ((template.get('Metadata') or {}).get('XamOps') or {}).get('TemplateVersion')
    out = ((template.get('Outputs') or {}).get('TemplateVersion') or {}).get('Value')
    return meta, out


def compare(old, new, allow_remove):
    problems = []
    old_params, new_params = old.get('Parameters') or {}, new.get('Parameters') or {}
    for name in old_params:
        if name not in new_params:
            problems.append(f'parameter {name} was removed or renamed')
    for name, spec in new_params.items():
        if name not in old_params and 'Default' not in (spec or {}):
            problems.append(f'new parameter {name} has no Default')
    old_res, new_res = old.get('Resources') or {}, new.get('Resources') or {}
    for lid, spec in old_res.items():
        if lid not in new_res:
            if lid not in allow_remove:
                problems.append(f'resource {lid} was removed or renamed (not in --allow-remove)')
            continue
        kept = new_res[lid]
        if spec.get('Type') != kept.get('Type'):
            problems.append(f'resource {lid} changed type {spec.get("Type")} -> {kept.get("Type")}')
            continue
        old_props, new_props = spec.get('Properties') or {}, kept.get('Properties') or {}
        for field in SENSITIVE.get(spec.get('Type'), []):
            if json.dumps(old_props.get(field), sort_keys=True) != json.dumps(new_props.get(field), sort_keys=True):
                problems.append(f'resource {lid}: {field} changed, which replaces the resource')
    for lid in allow_remove:
        if lid not in old_res:
            problems.append(f'--allow-remove names {lid}, which the old template does not have')
        if lid in new_res:
            problems.append(f'--allow-remove names {lid}, which the new template still has')
    old_meta, old_out = version_of(old)
    new_meta, new_out = version_of(new)
    if not new_meta or new_meta != new_out:
        problems.append(f'new template version markers differ or are missing: metadata={new_meta} output={new_out}')
    if new_meta and new_meta == old_meta:
        problems.append(f'template version {new_meta} was not bumped')
    return problems


def main(argv):
    args = argv[1:]
    allow = set()
    if '--allow-remove' in args:
        i = args.index('--allow-remove')
        allow = {x for x in args[i + 1].split(',') if x}
        del args[i:i + 2]
    if len(args) != 2:
        print(__doc__)
        return 2
    old, new = load(args[0]), load(args[1])
    problems = compare(old, new, allow)
    if problems:
        print('FAIL')
        for p in problems:
            print('  ' + p)
        return 1
    added = sorted(set(new.get('Resources') or {}) - set(old.get('Resources') or {}))
    print(f'ok  {version_of(old)[0]} -> {version_of(new)[0]}; no parameter or logical id removed; '
          f'new resources: {", ".join(added) or "none"}')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
