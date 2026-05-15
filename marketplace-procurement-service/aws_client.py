"""
Builds boto3 clients by assuming the customer's IAM role via STS.
All marketplace operations run in the context of the customer account.
"""
import boto3
import logging
import os
from typing import Optional

logger = logging.getLogger(__name__)

AWS_REGION = os.getenv('AWS_REGION', 'us-east-1')
# XamOps platform STS client — uses the platform's own credentials
_sts = boto3.client('sts', region_name=AWS_REGION)


def assume_role(role_arn: str, external_id: Optional[str] = None) -> dict:
    """Assume customer IAM role and return temporary credentials."""
    params = {
        'RoleArn': role_arn,
        'RoleSessionName': 'xamops-marketplace-procurement',
        'DurationSeconds': 3600,
    }
    if external_id:
        params['ExternalId'] = external_id
    resp = _sts.assume_role(**params)
    return resp['Credentials']


def marketplace_agreement_client(role_arn: str, external_id: Optional[str] = None):
    """Return a boto3 marketplace-agreement client for the customer account."""
    creds = assume_role(role_arn, external_id)
    return boto3.client(
        'marketplace-agreement',
        region_name=AWS_REGION,
        aws_access_key_id=creds['AccessKeyId'],
        aws_secret_access_key=creds['SecretAccessKey'],
        aws_session_token=creds['SessionToken'],
    )


def marketplace_catalog_client(role_arn: str, external_id: Optional[str] = None):
    """Return a boto3 marketplace-catalog client for the customer account."""
    creds = assume_role(role_arn, external_id)
    return boto3.client(
        'marketplace-catalog',
        region_name=AWS_REGION,
        aws_access_key_id=creds['AccessKeyId'],
        aws_secret_access_key=creds['SecretAccessKey'],
        aws_session_token=creds['SessionToken'],
    )


def marketplace_discovery_client(role_arn: str, external_id: Optional[str] = None):
    """Return a boto3 marketplace-discovery client for public catalog search."""
    creds = assume_role(role_arn, external_id)
    return boto3.client(
        'marketplace-discovery',
        region_name=AWS_REGION,
        aws_access_key_id=creds['AccessKeyId'],
        aws_secret_access_key=creds['SecretAccessKey'],
        aws_session_token=creds['SessionToken'],
    )
