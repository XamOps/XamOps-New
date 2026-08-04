"""One-shot generator for WarControlCatalog.java from the AWS Cloud Security Advisory workbook.

Kept in the repo so the 86-control baseline can be regenerated when the advisory is
revised, rather than hand-editing 800 lines of Java string literals.

Usage:
    python3 scripts/gen_war_catalog.py <path-to-advisory.xlsx>
"""
import json
import sys
import zipfile
import xml.etree.ElementTree as ET

NS = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'

PATHS = {
    1: ["CREDENTIAL_THEFT", "COST_ABUSE"],
    2: ["CREDENTIAL_THEFT", "ACCOUNT_TAKEOVER"],
    3: ["ACCOUNT_TAKEOVER", "CREDENTIAL_THEFT"],
    4: ["ACCOUNT_TAKEOVER", "CREDENTIAL_THEFT", "CRYPTO_MINING"],
    5: ["PRIVILEGE_ESCALATION", "CRYPTO_MINING"],
    6: ["PRIVILEGE_ESCALATION"],
    7: ["CREDENTIAL_THEFT", "CRYPTO_MINING"],
    8: ["CREDENTIAL_THEFT"],
    9: ["PRIVILEGE_ESCALATION", "LATERAL_MOVEMENT"],
    10: ["BLIND_SPOT"],
    11: ["PUBLIC_EXPOSURE", "DATA_EXFILTRATION"],
    12: ["BLIND_SPOT", "CRYPTO_MINING"],
    13: ["PUBLIC_EXPOSURE", "LATERAL_MOVEMENT"],
    14: ["LATERAL_MOVEMENT"],
    15: ["LATERAL_MOVEMENT"],
    16: ["PUBLIC_EXPOSURE", "CRYPTO_MINING", "LATERAL_MOVEMENT"],
    17: ["PUBLIC_EXPOSURE", "CREDENTIAL_THEFT"],
    18: ["PUBLIC_EXPOSURE", "LATERAL_MOVEMENT"],
    19: ["BLIND_SPOT"],
    20: ["DDOS_AMPLIFICATION", "COST_ABUSE"],
    21: ["PUBLIC_EXPOSURE", "CRYPTO_MINING"],
    22: ["PUBLIC_EXPOSURE", "CRYPTO_MINING"],
    23: ["PUBLIC_EXPOSURE", "RANSOMWARE", "DATA_EXFILTRATION"],
    24: ["PUBLIC_EXPOSURE"],
    25: ["PUBLIC_EXPOSURE", "COST_ABUSE"],
    26: ["PUBLIC_EXPOSURE", "AI_TOKEN_ABUSE"],
    27: ["PUBLIC_EXPOSURE", "COST_ABUSE"],
    28: ["DATA_EXFILTRATION"],
    29: ["DATA_EXFILTRATION"],
    30: ["DATA_EXFILTRATION"],
    31: ["DATA_EXFILTRATION"],
    32: ["PUBLIC_EXPOSURE", "DATA_EXFILTRATION"],
    33: ["RANSOMWARE"],
    34: ["RANSOMWARE"],
    35: ["DATA_EXFILTRATION"],
    36: ["DATA_EXFILTRATION"],
    37: ["DATA_EXFILTRATION"],
    38: ["BLIND_SPOT"],
    39: ["BLIND_SPOT"],
    40: ["BLIND_SPOT"],
    41: ["BLIND_SPOT", "CRYPTO_MINING"],
    42: ["CRYPTO_MINING", "BLIND_SPOT"],
    43: ["CRYPTO_MINING", "RANSOMWARE"],
    44: ["BLIND_SPOT"],
    45: ["BLIND_SPOT"],
    46: ["CREDENTIAL_THEFT", "CRYPTO_MINING"],
    47: ["PRIVILEGE_ESCALATION"],
    48: ["LATERAL_MOVEMENT", "CRYPTO_MINING"],
    49: ["PUBLIC_EXPOSURE", "CRYPTO_MINING"],
    50: ["LATERAL_MOVEMENT"],
    51: ["LATERAL_MOVEMENT"],
    52: ["PRIVILEGE_ESCALATION", "CRYPTO_MINING"],
    53: ["BLIND_SPOT"],
    54: ["PUBLIC_EXPOSURE", "CRYPTO_MINING"],
    55: ["LATERAL_MOVEMENT"],
    56: ["LATERAL_MOVEMENT", "CRYPTO_MINING"],
    57: ["LATERAL_MOVEMENT"],
    58: ["COST_ABUSE"],
    59: ["BLIND_SPOT"],
    60: ["BLIND_SPOT"],
    61: ["COST_ABUSE"],
    62: ["BLIND_SPOT"],
    63: ["BLIND_SPOT"],
    64: ["AI_TOKEN_ABUSE", "PRIVILEGE_ESCALATION"],
    65: ["AI_TOKEN_ABUSE", "DATA_EXFILTRATION"],
    66: ["BLIND_SPOT", "AI_TOKEN_ABUSE"],
    67: ["DATA_EXFILTRATION"],
    68: ["AI_TOKEN_ABUSE"],
    69: ["AI_TOKEN_ABUSE", "COST_ABUSE"],
    70: ["DATA_EXFILTRATION"],
    71: ["DATA_EXFILTRATION"],
    72: ["AI_TOKEN_ABUSE"],
    73: ["COST_ABUSE"],
    74: ["DATA_EXFILTRATION"],
    75: ["DATA_EXFILTRATION"],
    76: ["AI_TOKEN_ABUSE"],
    77: ["DATA_EXFILTRATION"],
    78: ["AI_TOKEN_ABUSE", "BLIND_SPOT"],
    79: ["CRYPTO_MINING"],
    80: ["CRYPTO_MINING", "COST_ABUSE"],
    81: ["CRYPTO_MINING", "BLIND_SPOT"],
    82: ["CRYPTO_MINING"],
    83: ["CRYPTO_MINING", "COST_ABUSE"],
    84: ["CRYPTO_MINING"],
    85: ["CRYPTO_MINING"],
    86: ["CRYPTO_MINING", "COST_ABUSE"],
}

# Controls AwsWarService evaluates against live account state.
AUTOMATED = {
    1, 3, 4, 5, 6, 7, 8, 9, 10,
    11, 12, 13, 16, 18, 20, 21, 22, 23, 25, 26, 27,
    28, 29, 32, 33, 34, 36, 37,
    38, 39, 40, 41, 42, 43, 44,
    46, 47, 48, 49, 50, 52, 53, 54, 56, 57,
    58, 59, 60, 61,
    64, 65, 66, 67, 68, 69, 74,
    79, 80, 81, 82, 83, 84, 85, 86,
}

OUT = ('xamops-service/src/main/java/com/xammer/cloud/service/war/'
       'WarControlCatalog.java')


def read_rows(path):
    with zipfile.ZipFile(path) as z:
        shared = []
        root = ET.fromstring(z.read('xl/sharedStrings.xml'))
        for si in root.findall(NS + 'si'):
            shared.append(''.join(x.text or '' for x in si.iter(NS + 't')))
        sheet = ET.fromstring(z.read('xl/worksheets/sheet1.xml'))

    rows = []
    for row in sheet.iter(NS + 'row'):
        vals = []
        for c in row.findall(NS + 'c'):
            v = c.find(NS + 'v')
            inline = c.find(NS + 'is')
            if c.get('t') == 's' and v is not None:
                vals.append(shared[int(v.text)])
            elif inline is not None:
                vals.append(''.join(x.text or '' for x in inline.iter(NS + 't')))
            elif v is not None:
                vals.append(v.text)
            else:
                vals.append('')
        rows.append([x.strip() for x in vals])
    return rows


def parse(rows):
    header, out = None, []
    for r in rows:
        if r and r[0] == '#':
            header = r
            continue
        if header is None or not r or not r[0]:
            continue
        try:
            num = int(float(r[0]))
        except ValueError:
            continue
        d = dict(zip(header, r))
        out.append({
            'num': num,
            'category': d.get('Category', ''),
            'sub': d.get('Sub-Category', ''),
            'title': d.get('Best Practice / Control', ''),
            'desc': d.get('Description & Implementation Steps', ''),
            'sev': d.get('Severity', ''),
            'tools': d.get('Tools / Services', ''),
            'ref': d.get('References', ''),
            'ctype': d.get('Category Type', ''),
            'impact': d.get('Business Impact if Not Implemented', ''),
            'ilevel': d.get('Impact Level', ''),
        })
    return out


def jstr(s):
    """Emit a Java string literal, wrapped so no source line runs long."""
    s = (s or '').replace('\\', '\\\\').replace('"', '\\"')
    s = ' '.join(s.split())
    if len(s) <= 88:
        return '"%s"' % s
    lines, cur = [], ''
    for w in s.split(' '):
        # `cur and` guards against emitting an empty leading literal when a single
        # token (a long documentation URL) already exceeds the wrap width.
        if cur and len(cur) + len(w) + 1 > 88:
            lines.append(cur)
            cur = w
        else:
            cur = (cur + ' ' + w) if cur else w
    if cur:
        lines.append(cur)
    parts = ['"%s%s"' % (ln, ' ' if i < len(lines) - 1 else '')
             for i, ln in enumerate(lines)]
    return ('\n' + ' ' * 28 + '+ ').join(parts)


HEADER = '''package com.xammer.cloud.service.war;

import com.xammer.cloud.dto.war.WarAttackPath;
import com.xammer.cloud.dto.war.WarControl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The AWS hardening baseline WAR evaluates an account against.
 *
 * <p>
 * GENERATED by scripts/gen_war_catalog.py from the AWS Cloud Security Advisory
 * workbook (86 controls across 8 families). Do not hand-edit; regenerate instead.
 *
 * <p>
 * This is reference data, not logic. The wording, severity, source URL and
 * business-impact narrative all come from the advisory so the page a customer reads
 * matches the document their security team signed off on.
 *
 * <p>
 * {@code automated == true} means {@code AwsWarService} evaluates the control
 * against live account state. The remainder are surfaced as MANUAL rather than
 * quietly dropped — an org-level control like Control Tower adoption is still the
 * difference between a contained incident and an unbounded one, whether or not a
 * member-account API can prove it.
 */
public final class WarControlCatalog {

    private WarControlCatalog() {
    }

    public static final List<WarControl> AWS = List.of('''

FOOTER = ''');

    /** Indexed by control id for O(1) lookup when merging check results. */
    public static final Map<String, WarControl> AWS_BY_ID = AWS.stream()
            .collect(Collectors.toUnmodifiableMap(c -> c.id(), c -> c));

    /** Category display order, matching the source advisory. */
    public static final List<String> AWS_CATEGORY_ORDER = AWS.stream()
            .map(c -> c.category())
            .distinct()
            .toList();

    /** GCP and Azure baselines land here as their advisories are transcribed. */
    public static List<WarControl> forProvider(String provider) {
        return AWS;
    }
}
'''


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else 'AWS_Cloud_Security_Advisory.xlsx'
    controls = parse(read_rows(src))
    items = []
    for c in controls:
        n = c['num']
        paths = ', '.join('WarAttackPath.%s' % p for p in PATHS.get(n, []))
        items.append('''            new WarControl("AWS-%d", %d,
                    %s,
                    %s,
                    %s,
                    %s,
                    %s,
                    %s,
                    %s,
                    %s,
                    %s,
                    %s,
                    List.of(%s), %s)''' % (
            n, n,
            jstr(c['category']), jstr(c['sub']), jstr(c['title']), jstr(c['desc']),
            jstr(c['sev']), jstr(c['tools']), jstr(c['ref']), jstr(c['ctype']),
            jstr(c['impact']), jstr(c['ilevel']),
            paths, 'true' if n in AUTOMATED else 'false'))

    with open(OUT, 'w') as fh:
        fh.write(HEADER + '\n' + ',\n\n'.join(items) + FOOTER)
    print('wrote %d controls to %s' % (len(items), OUT))
    print(json.dumps({'automated': len([c for c in controls if c['num'] in AUTOMATED]),
                      'manual': len([c for c in controls if c['num'] not in AUTOMATED])}))


if __name__ == '__main__':
    main()
