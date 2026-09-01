#!/usr/bin/env python3
"""
Adds an ownership check to every AutoSpotting handler that takes an account identifier.

Walks each `public ResponseEntity<...> name(...) {` declaration with a balanced-paren
scan (signatures span lines and contain `)` inside annotations like
@RequestParam(defaultValue = "false"), so a regex on the param list does not work),
appends the @AuthenticationPrincipal parameter, and inserts the guard call as the first
statement of the body.
"""
import re
import sys

GUARD_LINE = {
    "Long": "        accountAccessService.requireAccess(userDetails, %s);",
    "String": "        accountAccessService.requireAccessByIdentifier(userDetails, %s);",
}

PRINCIPAL = "@AuthenticationPrincipal ClientUserDetails userDetails"


def find_sig_end(s, open_paren):
    """Index of the `)` matching the `(` at open_paren."""
    depth = 0
    i = open_paren
    while i < len(s):
        if s[i] == "(":
            depth += 1
        elif s[i] == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ValueError("unbalanced signature")


def process(path, ident):
    s = open(path).read()
    out = []
    pos = 0
    count = 0

    for m in re.finditer(r"\n    public ResponseEntity<[^\n]*?>\s+(\w+)\(", s):
        name = m.group(1)
        open_paren = m.end() - 1
        close_paren = find_sig_end(s, open_paren)
        params = s[open_paren + 1:close_paren]

        # Only guard handlers that actually take an account identifier.
        am = re.search(r"@PathVariable(?:\([^)]*\))?\s+(Long|String)\s+%s\b"
                       r"|@RequestParam(?:\([^)]*\))?\s+(Long|String)\s+%s\b" % (ident, ident),
                       params)
        if not am:
            continue
        kind = am.group(1) or am.group(2)

        brace = s.index("{", close_paren)

        out.append(s[pos:close_paren])
        sep = "," if params.strip() else ""
        out.append("%s\n            %s" % (sep, PRINCIPAL))
        out.append(s[close_paren:brace + 1])
        out.append("\n" + GUARD_LINE[kind] % ident)
        pos = brace + 1
        count += 1

    out.append(s[pos:])
    open(path, "w").write("".join(out))
    print("%s: guarded %d handlers" % (path.split("/")[-1], count))


for arg in sys.argv[1:]:
    path, ident = arg.split("=")
    process(path, ident)
