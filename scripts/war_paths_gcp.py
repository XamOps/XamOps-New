"""Attack-path and automation mapping for the GCP WAR baseline.

Kept beside gen_war_catalog.py so the advisory workbook stays the source of truth for
wording, and this file stays the source of truth for how WAR interprets each control.
"""

# Superseded rows. The workbook's AI section was drafted twice: rows 63-71 are an
# earlier, shorter pass that the 72-88 block replaces. Every one of the nine shares its
# reference URL with a later entry and differs only in trivial wording (63→72, 67→79,
# 69→82 where 82's title adds the column-level half). Keeping both would double-count
# AI controls in the score and inflate the AI Token Abuse path.
SUPERSEDED = {63, 64, 65, 66, 67, 68, 69, 70, 71}

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
    11: ["DATA_EXFILTRATION", "PUBLIC_EXPOSURE"],
    12: ["BLIND_SPOT", "CRYPTO_MINING"],
    13: ["PUBLIC_EXPOSURE", "DATA_EXFILTRATION"],
    14: ["PUBLIC_EXPOSURE", "LATERAL_MOVEMENT"],
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
    # Data Protection
    28: ["DATA_EXFILTRATION"],
    29: ["DATA_EXFILTRATION"],
    30: ["DATA_EXFILTRATION"],
    31: ["DATA_EXFILTRATION"],
    32: ["PUBLIC_EXPOSURE", "DATA_EXFILTRATION"],
    33: ["PUBLIC_EXPOSURE", "DATA_EXFILTRATION"],
    34: ["RANSOMWARE"],
    35: ["RANSOMWARE"],
    36: ["DATA_EXFILTRATION"],
    # Logging & Monitoring
    37: ["BLIND_SPOT"],
    38: ["BLIND_SPOT"],
    39: ["BLIND_SPOT"],
    40: ["BLIND_SPOT", "CRYPTO_MINING"],
    41: ["BLIND_SPOT", "CRYPTO_MINING"],
    42: ["BLIND_SPOT"],
    43: ["CRYPTO_MINING", "BLIND_SPOT"],
    44: ["CRYPTO_MINING", "LATERAL_MOVEMENT"],
    # Compute Security
    45: ["CREDENTIAL_THEFT", "PUBLIC_EXPOSURE"],
    46: ["PRIVILEGE_ESCALATION", "CRYPTO_MINING"],
    47: ["CREDENTIAL_THEFT", "LATERAL_MOVEMENT"],
    48: ["LATERAL_MOVEMENT"],
    49: ["PUBLIC_EXPOSURE", "CREDENTIAL_THEFT"],
    50: ["LATERAL_MOVEMENT", "CRYPTO_MINING"],
    51: ["PRIVILEGE_ESCALATION", "CRYPTO_MINING"],
    52: ["LATERAL_MOVEMENT", "CRYPTO_MINING"],
    53: ["BLIND_SPOT"],
    54: ["LATERAL_MOVEMENT"],
    55: ["PUBLIC_EXPOSURE", "CRYPTO_MINING"],
    56: ["LATERAL_MOVEMENT"],
    # Compliance & Governance
    57: ["LATERAL_MOVEMENT", "COST_ABUSE"],
    58: ["BLIND_SPOT"],
    59: ["PRIVILEGE_ESCALATION"],
    60: ["COST_ABUSE"],
    61: ["BLIND_SPOT"],
    62: ["BLIND_SPOT"],
    # AI Services Security (72-88; 63-71 superseded)
    72: ["AI_TOKEN_ABUSE", "PRIVILEGE_ESCALATION"],
    73: ["AI_TOKEN_ABUSE", "DATA_EXFILTRATION"],
    74: ["BLIND_SPOT", "AI_TOKEN_ABUSE"],
    75: ["DATA_EXFILTRATION"],
    76: ["AI_TOKEN_ABUSE"],
    77: ["COST_ABUSE"],
    78: ["DATA_EXFILTRATION"],
    79: ["AI_TOKEN_ABUSE", "COST_ABUSE"],
    80: ["AI_TOKEN_ABUSE"],
    81: ["AI_TOKEN_ABUSE"],
    82: ["DATA_EXFILTRATION"],
    83: ["DATA_EXFILTRATION"],
    84: ["DATA_EXFILTRATION"],
    85: ["DATA_EXFILTRATION", "BLIND_SPOT"],
    86: ["AI_TOKEN_ABUSE", "DATA_EXFILTRATION"],
    87: ["DATA_EXFILTRATION"],
    88: ["AI_TOKEN_ABUSE", "BLIND_SPOT"],
    # Cryptocurrency Mining Threat Detection
    89: ["CRYPTO_MINING"],
    90: ["CRYPTO_MINING", "COST_ABUSE"],
    91: ["CRYPTO_MINING", "BLIND_SPOT"],
    92: ["CRYPTO_MINING"],
    93: ["CRYPTO_MINING", "COST_ABUSE"],
    94: ["CRYPTO_MINING"],
    95: ["CRYPTO_MINING"],
    96: ["CRYPTO_MINING", "COST_ABUSE"],
}

# Controls GcpWarService evaluates against live project state. The remainder need an
# API XamOps does not hold (Cloud Identity, Access Context Manager, Workspace admin)
# or a human judgement call, and surface as MANUAL rather than a false pass.
AUTOMATED = {
    1, 5, 6, 7, 8, 9, 10,
    12, 13, 14, 16, 18, 19, 20, 21, 22, 23, 25, 27,
    28, 29, 32, 33, 34, 35,
    37, 38, 40, 41, 43, 44,
    45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56,
    58, 59, 60,
    72, 74,
    89, 90, 91, 92, 93, 94, 96,
}
