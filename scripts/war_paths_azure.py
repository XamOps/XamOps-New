"""Attack-path and automation mapping for the Azure WAR baseline.

The workbook is the source of truth for wording; this file is the source of truth for
how WAR interprets each control. Unlike the GCP advisory, the Azure one has no duplicate
rows — all 86 are distinct, and the six shared reference URLs are legitimate (control 5
"avoid Owner sprawl" and control 9 "never grant Owner to managed identities" both cite
the same RBAC page).
"""

SUPERSEDED = set()

PATHS = {
    # Identity & Access Management
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
    # Network Security
    11: ["PUBLIC_EXPOSURE", "DATA_EXFILTRATION"],
    12: ["BLIND_SPOT", "CRYPTO_MINING"],
    13: ["LATERAL_MOVEMENT"],
    14: ["LATERAL_MOVEMENT"],
    15: ["PUBLIC_EXPOSURE", "CREDENTIAL_THEFT"],
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
    # Data Protection
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
    # Logging & Monitoring
    38: ["BLIND_SPOT"],
    39: ["BLIND_SPOT"],
    40: ["BLIND_SPOT"],
    41: ["BLIND_SPOT", "CRYPTO_MINING"],
    42: ["BLIND_SPOT", "CRYPTO_MINING"],
    43: ["CRYPTO_MINING", "RANSOMWARE"],
    44: ["BLIND_SPOT"],
    45: ["BLIND_SPOT"],
    # Compute Security
    46: ["CREDENTIAL_THEFT", "PUBLIC_EXPOSURE"],
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
    # Compliance & Governance
    58: ["COST_ABUSE"],
    59: ["BLIND_SPOT"],
    60: ["BLIND_SPOT"],
    61: ["COST_ABUSE"],
    62: ["BLIND_SPOT"],
    63: ["BLIND_SPOT"],
    # AI Services Security
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
    76: ["AI_TOKEN_ABUSE", "DATA_EXFILTRATION"],
    77: ["DATA_EXFILTRATION"],
    78: ["AI_TOKEN_ABUSE", "BLIND_SPOT"],
    # Cryptocurrency Mining Threat Detection
    79: ["CRYPTO_MINING"],
    80: ["CRYPTO_MINING", "COST_ABUSE"],
    81: ["CRYPTO_MINING", "BLIND_SPOT"],
    82: ["CRYPTO_MINING"],
    83: ["CRYPTO_MINING", "COST_ABUSE"],
    84: ["CRYPTO_MINING"],
    85: ["CRYPTO_MINING"],
    86: ["CRYPTO_MINING", "COST_ABUSE"],
}

# Controls AzureWarService evaluates against live subscription state. The remainder need
# a tenant-scoped Graph API XamOps does not hold (Conditional Access, Entra app
# registrations, M365 Copilot) or a human judgement call, and surface as MANUAL rather
# than a false pass.
AUTOMATED = {
    1, 5, 9,
    12, 16, 18, 19, 20, 21, 22, 23, 25,
    28, 29, 32, 33, 34, 35, 37,
    38, 41, 42, 43,
    46, 47, 48, 51, 52, 53, 54, 55, 57,
    59, 60, 61,
    79, 80, 82, 83, 84, 86,
}
