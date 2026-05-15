"""
XamOps Marketplace Procurement Service
Exposes AWS Marketplace Agreements API operations (boto3, not yet in Java SDK).

Available boto3 marketplace-agreement operations (1.42.x):
  search_agreements, describe_agreement, get_agreement_terms,
  list_agreement_invoice_line_items, list_agreement_payment_requests,
  send_agreement_cancellation_request, get_agreement_cancellation_request,
  cancel_agreement_cancellation_request

Endpoints (all require roleArn + optional externalId in JSON body):

  POST /procurement/catalog/search          — search/browse available listings
  POST /procurement/agreements/list         — list buyer agreements (with filters)
  POST /procurement/agreements/describe     — get agreement details
  POST /procurement/agreements/terms        — get agreement pricing terms
  POST /procurement/agreements/invoices     — invoice line items
  POST /procurement/agreements/payments     — payment request history
  POST /procurement/agreements/cancel       — send cancellation request
  POST /procurement/agreements/cancel/get   — get cancellation request status
  POST /procurement/agreements/cancel/undo  — revoke a pending cancellation
  GET  /health                              — health check
"""

import logging
import os
from datetime import datetime, timezone

from botocore.exceptions import ClientError
from flask import Flask, jsonify, request
from flask_cors import CORS

from aws_client import marketplace_agreement_client, marketplace_discovery_client
from config import get_config

app = Flask(__name__)
env = os.getenv('FLASK_ENV', 'local')
cfg = get_config(env)
app.config.from_object(cfg)

CORS(app, resources={
    r"/procurement/*": {
        "origins": cfg.ALLOWED_ORIGINS,
        "methods": ["POST", "OPTIONS"],
        "allow_headers": ["Content-Type", "Authorization"],
    },
    r"/health": {"origins": "*", "methods": ["GET"]},
})

logging.basicConfig(level=getattr(logging, cfg.LOG_LEVEL), format=cfg.LOG_FORMAT)
logger = logging.getLogger(__name__)

logger.info(f"Starting {cfg.SERVICE_NAME} v{cfg.VERSION} [{cfg.ENV}]")


# ── Helpers ──────────────────────────────────────────────────────────────────

def _role_params(body: dict):
    role_arn = body.get('roleArn')
    if not role_arn:
        raise ValueError("roleArn is required")
    return role_arn, body.get('externalId')


def _err(msg: str, code: int = 400):
    return jsonify({"error": msg}), code


def _handle_client_error(e: ClientError):
    code = e.response['Error']['Code']
    msg  = e.response['Error']['Message']
    logger.warning("AWS ClientError %s: %s", code, msg)
    http = 403 if code in ('AccessDenied', 'UnauthorizedException') else 422
    return jsonify({"error": f"{code}: {msg}"}), http


# ── Health ────────────────────────────────────────────────────────────────────

@app.route('/health', methods=['GET'])
def health():
    return jsonify({
        "status": "ok",
        "service": cfg.SERVICE_NAME,
        "version": cfg.VERSION,
        "timestamp": datetime.now(timezone.utc).isoformat(),
    })


# ── Catalog: Search / Discover available products ─────────────────────────────

@app.route('/procurement/catalog/search', methods=['POST'])
def catalog_search():
    """
    Search the public AWS Marketplace catalog using the Discovery API.
    Body: { roleArn, externalId?, search?, category?, pricingModel?, maxResults?, nextToken? }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    client = marketplace_discovery_client(role_arn, ext_id)
    search      = (body.get('search') or '').strip()
    max_results = min(int(body.get('maxResults', 20)), 50)
    next_token  = body.get('nextToken')

    try:
        kwargs = {'maxResults': max_results}
        if search:
            kwargs['searchText'] = search
        if next_token:
            kwargs['nextToken'] = next_token

        # Optional filters
        filters = []
        if body.get('category'):
            filters.append({'filterType': 'CATEGORY', 'filterValues': [body['category']]})
        if body.get('pricingModel'):
            filters.append({'filterType': 'PRICING_MODEL', 'filterValues': [body['pricingModel']]})
        if body.get('fulfillmentType'):
            filters.append({'filterType': 'FULFILLMENT_OPTION_TYPE', 'filterValues': [body['fulfillmentType']]})
        if filters:
            kwargs['filters'] = filters

        resp = client.search_listings(**kwargs)

        products = []
        for listing in resp.get('listingSummaries', []):
            # Extract seller name from first associated product's manufacturer
            seller_name = None
            entities = listing.get('associatedEntities', [])
            if entities:
                mfr = (entities[0].get('product') or {}).get('manufacturer') or {}
                seller_name = mfr.get('displayName')

            # Extract pricing models
            pricing_models = [p.get('displayName') for p in listing.get('pricingModels', []) if p.get('displayName')]
            categories     = [c.get('displayName') for c in listing.get('categories', []) if c.get('displayName')]
            fulfillments   = [f.get('displayName') for f in listing.get('fulfillmentOptionTypes', []) if f.get('displayName')]

            products.append({
                'listingId':       listing.get('listingId'),
                'name':            listing.get('listingName'),
                'shortDescription': listing.get('shortDescription'),
                'sellerName':      seller_name,
                'categories':      categories,
                'pricingModels':   pricing_models,
                'fulfillmentTypes': fulfillments,
                'averageRating':   listing.get('averageCustomerRating'),
                'badges':          [b.get('displayName') for b in listing.get('badges', []) if b.get('displayName')],
            })

        logger.info("catalog_search: %d listings (search=%r)", len(products), search or None)
        return jsonify({'products': products, 'nextToken': resp.get('nextToken')})

    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("catalog_search error")
        return _err(str(e), 500)


# ── Agreements: List ──────────────────────────────────────────────────────────

@app.route('/procurement/agreements/list', methods=['POST'])
def agreements_list():
    """
    List buyer agreements.
    Body: { roleArn, externalId?, status?, maxResults?, nextToken? }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    client = marketplace_agreement_client(role_arn, ext_id)
    status = body.get('status', 'ACTIVE')

    try:
        kwargs = {
            'Catalog': 'AWSMarketplace',
            'Filters': [
                {'Name': 'PartyType',     'Values': ['Acceptor']},
                {'Name': 'AgreementType', 'Values': ['PurchaseAgreement']},
                {'Name': 'Status',        'Values': [status]},
            ],
            'MaxResults': min(int(body.get('maxResults', 50)), 50),
        }
        if body.get('nextToken'):
            kwargs['NextToken'] = body['nextToken']

        resp = client.search_agreements(**kwargs)
        agreements = []
        for a in resp.get('AgreementViewSummaries', []):
            agreements.append({
                'agreementId':   a.get('AgreementId'),
                'status':        a.get('Status'),
                'startTime':     str(a.get('StartTime', '')),
                'endTime':       str(a.get('EndTime', '')),
                'acceptor':      a.get('Acceptor', {}).get('AccountId'),
                'proposer':      a.get('Proposer', {}).get('AccountId'),
                'agreementType': a.get('AgreementType'),
                'offerId':       (a.get('ProposalSummary') or {}).get('OfferId'),
                'productId':     (((a.get('ProposalSummary') or {}).get('Resources') or [{}])[0]).get('Id'),
            })
        return jsonify({'agreements': agreements, 'nextToken': resp.get('NextToken')})

    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreements_list error")
        return _err(str(e), 500)


# ── Agreements: Describe ──────────────────────────────────────────────────────

@app.route('/procurement/agreements/describe', methods=['POST'])
def agreement_describe():
    """
    Get details for a specific agreement.
    Body: { roleArn, externalId?, agreementId }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    agreement_id = body.get('agreementId')
    if not agreement_id:
        return _err("agreementId is required")

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        resp = client.describe_agreement(AgreementId=agreement_id)
        return jsonify({
            'agreementId':    resp.get('AgreementId'),
            'status':         resp.get('Status'),
            'startTime':      str(resp.get('StartTime', '')),
            'endTime':        str(resp.get('EndTime', '')),
            'acceptanceTime': str(resp.get('AcceptanceTime', '')),
            'acceptor':       (resp.get('Acceptor') or {}).get('AccountId'),
            'proposer':       (resp.get('Proposer') or {}).get('AccountId'),
            'agreementType':  resp.get('AgreementType'),
            'proposalSummary': resp.get('ProposalSummary'),
            'estimatedCharges': resp.get('EstimatedCharges'),
        })
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_describe error")
        return _err(str(e), 500)


# ── Agreements: Terms ─────────────────────────────────────────────────────────

@app.route('/procurement/agreements/terms', methods=['POST'])
def agreement_terms():
    """
    Get pricing/usage terms for an agreement.
    Body: { roleArn, externalId?, agreementId, maxResults?, nextToken? }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    agreement_id = body.get('agreementId')
    if not agreement_id:
        return _err("agreementId is required")

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        kwargs = {'AgreementId': agreement_id,
                  'MaxResults': min(int(body.get('maxResults', 50)), 50)}
        if body.get('nextToken'):
            kwargs['NextToken'] = body['nextToken']
        resp = client.get_agreement_terms(**kwargs)
        return jsonify({
            'acceptedTerms': resp.get('AcceptedTerms', []),
            'nextToken':     resp.get('NextToken'),
        })
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_terms error")
        return _err(str(e), 500)


# ── Agreements: Invoice Line Items ────────────────────────────────────────────

@app.route('/procurement/agreements/invoices', methods=['POST'])
def agreement_invoices():
    """
    List invoice line items for an agreement.
    Body: { roleArn, externalId?, agreementId, maxResults?, nextToken? }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    agreement_id = body.get('agreementId')
    if not agreement_id:
        return _err("agreementId is required")

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        kwargs = {'AgreementId': agreement_id,
                  'MaxResults': min(int(body.get('maxResults', 50)), 50)}
        if body.get('nextToken'):
            kwargs['NextToken'] = body['nextToken']
        resp = client.list_agreement_invoice_line_items(**kwargs)
        return jsonify({
            'invoiceLineItems': resp.get('AgreementInvoiceLineItems', []),
            'nextToken':        resp.get('NextToken'),
        })
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_invoices error")
        return _err(str(e), 500)


# ── Agreements: Payment Requests ──────────────────────────────────────────────

@app.route('/procurement/agreements/payments', methods=['POST'])
def agreement_payments():
    """
    List payment requests for an agreement.
    Body: { roleArn, externalId?, agreementId, maxResults?, nextToken? }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    agreement_id = body.get('agreementId')
    if not agreement_id:
        return _err("agreementId is required")

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        kwargs = {'AgreementId': agreement_id,
                  'MaxResults': min(int(body.get('maxResults', 50)), 50)}
        if body.get('nextToken'):
            kwargs['NextToken'] = body['nextToken']
        resp = client.list_agreement_payment_requests(**kwargs)
        return jsonify({
            'paymentRequests': resp.get('AgreementPaymentRequests', []),
            'nextToken':       resp.get('NextToken'),
        })
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_payments error")
        return _err(str(e), 500)


# ── Agreements: Cancel (Send Cancellation Request) ────────────────────────────

@app.route('/procurement/agreements/cancel', methods=['POST'])
def agreement_cancel():
    """
    Send a cancellation request for an active agreement.
    Body: { roleArn, externalId?, agreementId, reason? }
    Note: uses send_agreement_cancellation_request (available in boto3 1.42.x).
    The agreement stays active until the cancellation is processed by AWS.
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    agreement_id = body.get('agreementId')
    if not agreement_id:
        return _err("agreementId is required")

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        kwargs = {'AgreementId': agreement_id}
        if body.get('reason'):
            kwargs['Reason'] = body['reason']
        resp = client.send_agreement_cancellation_request(**kwargs)
        return jsonify({
            'agreementId':          agreement_id,
            'cancellationRequestId': resp.get('AgreementCancellationRequestId'),
            'status':               resp.get('Status', 'PENDING'),
            'requestedAt':          str(resp.get('RequestedTime', '')),
        })
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_cancel error")
        return _err(str(e), 500)


# ── Agreements: Get Cancellation Request Status ───────────────────────────────

@app.route('/procurement/agreements/cancel/get', methods=['POST'])
def agreement_cancel_get():
    """
    Check the status of a pending cancellation request.
    Body: { roleArn, externalId?, agreementCancellationRequestId }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    cancel_req_id = body.get('agreementCancellationRequestId')
    if not cancel_req_id:
        return _err("agreementCancellationRequestId is required")

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        resp = client.get_agreement_cancellation_request(
            AgreementCancellationRequestId=cancel_req_id
        )
        return jsonify({
            'cancellationRequestId': resp.get('AgreementCancellationRequestId'),
            'agreementId':           resp.get('AgreementId'),
            'status':                resp.get('Status'),
            'requestedAt':           str(resp.get('RequestedTime', '')),
            'cancelledAt':           str(resp.get('CancelledTime', '')),
        })
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_cancel_get error")
        return _err(str(e), 500)


# ── Agreements: Undo Cancellation Request ─────────────────────────────────────

@app.route('/procurement/agreements/cancel/undo', methods=['POST'])
def agreement_cancel_undo():
    """
    Revoke a pending cancellation request (before it is processed).
    Body: { roleArn, externalId?, agreementCancellationRequestId }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    cancel_req_id = body.get('agreementCancellationRequestId')
    if not cancel_req_id:
        return _err("agreementCancellationRequestId is required")

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        resp = client.cancel_agreement_cancellation_request(
            AgreementCancellationRequestId=cancel_req_id
        )
        return jsonify({
            'cancellationRequestId': cancel_req_id,
            'status':                resp.get('Status', 'CANCELLED'),
        })
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_cancel_undo error")
        return _err(str(e), 500)


# ── Catalog: Search Facets (filter options for Discover tab) ──────────────────

@app.route('/procurement/catalog/facets', methods=['POST'])
def catalog_facets():
    """
    Returns available filter values for the Discover tab (categories, pricing models, etc.)
    Body: { roleArn, externalId?, search?, facetTypes?: [] }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    client = marketplace_discovery_client(role_arn, ext_id)
    facet_types = body.get('facetTypes') or ['CATEGORY', 'PRICING_MODEL', 'FULFILLMENT_OPTION_TYPE', 'PUBLISHER']

    try:
        kwargs = {'facetTypes': facet_types}
        if body.get('search'):
            kwargs['searchText'] = body['search']

        resp = client.search_facets(**kwargs)
        facets = {}
        for facet in resp.get('facets', []):
            ftype = facet.get('facetType')
            values = [
                {'value': v.get('value'), 'count': v.get('count')}
                for v in facet.get('facetValues', [])
                if v.get('value')
            ]
            if values:
                facets[ftype] = values

        return jsonify({'facets': facets})

    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("catalog_facets error")
        return _err(str(e), 500)


# ── Product Detail: GetProduct ───────────────────────────────────────────────

@app.route('/procurement/catalog/product', methods=['POST'])
def catalog_product():
    """
    Get full product details.
    Body: { roleArn, externalId?, productId }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    product_id = body.get('productId')
    if not product_id:
        return _err("productId is required")

    client = marketplace_discovery_client(role_arn, ext_id)
    try:
        resp = client.get_product(productId=product_id)
        product = resp.get('product', {})
        return jsonify({'product': product})
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("catalog_product error")
        return _err(str(e), 500)


# ── Product Detail: GetListing ────────────────────────────────────────────────

@app.route('/procurement/catalog/listing', methods=['POST'])
def catalog_listing():
    """
    Get full listing details (includes associated products, all offers).
    Body: { roleArn, externalId?, listingId }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    listing_id = body.get('listingId')
    if not listing_id:
        return _err("listingId is required")

    client = marketplace_discovery_client(role_arn, ext_id)
    try:
        resp = client.get_listing(listingId=listing_id)
        # GetListing returns fields at the top level (no 'listing' wrapper key)
        resp.pop('ResponseMetadata', None)
        entities = resp.get('associatedEntities', [])
        logger.info("get_listing keys: %s", list(resp.keys()))
        logger.info("associatedEntities count: %d, first: %s", len(entities), entities[0] if entities else None)
        return jsonify({'listing': resp})
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("catalog_listing error")
        return _err(str(e), 500)


# ── Product Detail: ListFulfillmentOptions ────────────────────────────────────

@app.route('/procurement/catalog/fulfillment-options', methods=['POST'])
def catalog_fulfillment_options():
    """
    List available fulfillment options for a product.
    Body: { roleArn, externalId?, productId }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    product_id = body.get('productId')
    if not product_id:
        return _err("productId is required")

    client = marketplace_discovery_client(role_arn, ext_id)
    try:
        resp = client.list_fulfillment_options(productId=product_id)
        # Each item has one populated type-specific key — normalize to flat list
        FULFILLMENT_KEYS = [
            'saasFulfillmentOption', 'amazonMachineImageFulfillmentOption',
            'containerFulfillmentOption', 'cloudFormationFulfillmentOption',
            'helmFulfillmentOption', 'eksAddOnFulfillmentOption',
            'apiFulfillmentOption', 'dataExchangeFulfillmentOption',
            'professionalServicesFulfillmentOption', 'sageMakerAlgorithmFulfillmentOption',
            'sageMakerModelFulfillmentOption', 'ec2ImageBuilderComponentFulfillmentOption',
        ]
        normalized = []
        for item in resp.get('fulfillmentOptions', []):
            for key in FULFILLMENT_KEYS:
                opt = item.get(key)
                if opt:
                    normalized.append({
                        'fulfillmentOptionId':          opt.get('fulfillmentOptionId'),
                        'type':                         opt.get('fulfillmentOptionType'),
                        'displayName':                  opt.get('fulfillmentOptionDisplayName'),
                        'name':                         opt.get('fulfillmentOptionName'),
                        'version':                      opt.get('fulfillmentOptionVersion'),
                        'usageInstructions':            opt.get('usageInstructions'),
                        'fulfillmentUrl':               opt.get('fulfillmentUrl'),
                        'operatingSystems':             opt.get('operatingSystems', []),
                        'recommendedInstanceType':      (opt.get('recommendation') or {}).get('instanceType'),
                    })
                    break
        return jsonify({'fulfillmentOptions': normalized})
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("catalog_fulfillment_options error")
        return _err(str(e), 500)


# ── Product Detail: ListPurchaseOptions ──────────────────────────────────────

@app.route('/procurement/catalog/purchase-options', methods=['POST'])
def catalog_purchase_options():
    """
    List available purchase plans/offers for a product.
    Body: { roleArn, externalId?, productId }
    ListPurchaseOptions uses filters (not productId directly).
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    product_id = body.get('productId')
    if not product_id:
        return _err("productId is required")

    client = marketplace_discovery_client(role_arn, ext_id)
    try:
        resp = client.list_purchase_options(
            filters=[{'filterType': 'PRODUCT_ID', 'filterValues': [product_id]}]
        )
        normalized = []
        for opt in resp.get('purchaseOptions', []):
            normalized.append({
                'purchaseOptionId':   opt.get('purchaseOptionId'),
                'purchaseOptionName': opt.get('purchaseOptionName'),
                'purchaseOptionType': opt.get('purchaseOptionType'),
                'sellerName':         (opt.get('sellerOfRecord') or {}).get('displayName'),
                'availableFrom':      str(opt.get('availableFromTime', '')),
                'expiresAt':          str(opt.get('expirationTime', '')),
                'badges':             [b.get('displayName') for b in opt.get('badges', []) if b.get('displayName')],
            })
        return jsonify({'purchaseOptions': normalized})
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("catalog_purchase_options error")
        return _err(str(e), 500)


# ── Product Detail: GetOffer ─────────────────────────────────────────────────

@app.route('/procurement/catalog/offer', methods=['POST'])
def catalog_offer():
    """
    Get offer details (pricing model, seller, expiry, badges).
    Body: { roleArn, externalId?, offerId }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    offer_id = body.get('offerId')
    if not offer_id:
        return _err("offerId is required")

    client = marketplace_discovery_client(role_arn, ext_id)
    try:
        resp = client.get_offer(offerId=offer_id)
        resp.pop('ResponseMetadata', None)
        return jsonify({'offer': resp})
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("catalog_offer error")
        return _err(str(e), 500)


# ── Product Detail: GetOfferTerms ─────────────────────────────────────────────

TERM_KEYS = [
    'fixedUpfrontPricingTerm', 'recurringPaymentTerm', 'usageBasedPricingTerm',
    'paymentScheduleTerm', 'configurableUpfrontPricingTerm', 'freeTrialPricingTerm',
    'byolPricingTerm', 'variablePaymentTerm', 'legalTerm', 'supportTerm',
    'renewalTerm', 'validityTerm',
]

@app.route('/procurement/catalog/offer-terms', methods=['POST'])
def catalog_offer_terms():
    """
    Get pricing, legal, support, and validity terms for an offer.
    Body: { roleArn, externalId?, offerId }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    offer_id = body.get('offerId')
    if not offer_id:
        return _err("offerId is required")

    client = marketplace_discovery_client(role_arn, ext_id)
    try:
        resp = client.get_offer_terms(offerId=offer_id)
        normalized = []
        for term in resp.get('offerTerms', []):
            for key in TERM_KEYS:
                t = term.get(key)
                if t:
                    entry = {'termType': t.get('type', key), 'id': t.get('id')}
                    # Pricing fields
                    if t.get('currencyCode'):  entry['currencyCode'] = t['currencyCode']
                    if t.get('price'):          entry['price'] = t['price']
                    if t.get('duration'):       entry['duration'] = t['duration']
                    if t.get('billingPeriod'):  entry['billingPeriod'] = t['billingPeriod']
                    if t.get('refundPolicy'):   entry['refundPolicy'] = t['refundPolicy']
                    if t.get('maxTotalChargeAmount'): entry['maxTotalChargeAmount'] = t['maxTotalChargeAmount']
                    # Agreement validity
                    if t.get('agreementDuration'): entry['agreementDuration'] = t['agreementDuration']
                    if t.get('agreementEndDate'):   entry['agreementEndDate'] = str(t['agreementEndDate'])
                    if t.get('agreementStartDate'): entry['agreementStartDate'] = str(t['agreementStartDate'])
                    # Legal docs
                    if t.get('documents'):
                        entry['documents'] = [
                            {'name': d.get('displayName', d.get('type', '')), 'url': d.get('url', '')}
                            for d in t['documents']
                        ]
                    # Rate cards summary
                    if t.get('rateCards'):
                        entry['rateCardCount'] = len(t['rateCards'])
                    # Free trial grants
                    if t.get('grants'):
                        entry['grants'] = t['grants']
                    normalized.append(entry)
                    break
        return jsonify({'terms': normalized})
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("catalog_offer_terms error")
        return _err(str(e), 500)


# ── Agreements: Billing Adjustments ──────────────────────────────────────────

@app.route('/procurement/agreements/adjustments', methods=['POST'])
def agreement_adjustments():
    """
    List billing adjustments (credits/discounts) on an agreement.
    Body: { roleArn, externalId?, agreementId?, maxResults?, nextToken? }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        kwargs = {'MaxResults': min(int(body.get('maxResults', 50)), 50)}
        if body.get('agreementId'):
            kwargs['AgreementId'] = body['agreementId']
        if body.get('nextToken'):
            kwargs['NextToken'] = body['nextToken']

        resp = client.list_billing_adjustment_requests(**kwargs)
        return jsonify({
            'adjustments': resp.get('BillingAdjustmentRequests', []),
            'nextToken':   resp.get('NextToken'),
        })
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_adjustments error")
        return _err(str(e), 500)


# ── Agreements: Get Single Billing Adjustment ─────────────────────────────────

@app.route('/procurement/agreements/adjustments/get', methods=['POST'])
def agreement_adjustment_get():
    """
    Get details of a single billing adjustment request.
    Body: { roleArn, externalId?, agreementId, billingAdjustmentRequestId }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    agreement_id = body.get('agreementId')
    adjustment_id = body.get('billingAdjustmentRequestId')
    if not agreement_id or not adjustment_id:
        return _err("agreementId and billingAdjustmentRequestId are required")

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        resp = client.get_billing_adjustment_request(
            AgreementId=agreement_id,
            BillingAdjustmentRequestId=adjustment_id,
        )
        resp.pop('ResponseMetadata', None)
        return jsonify(resp)
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_adjustment_get error")
        return _err(str(e), 500)


# ── Agreements: Batch Create Billing Adjustments ──────────────────────────────

@app.route('/procurement/agreements/adjustments/batch', methods=['POST'])
def agreement_adjustment_batch():
    """
    Batch create billing adjustment requests (credits).
    Body: { roleArn, externalId?, entries: [{ agreementId, originalInvoiceId,
            adjustmentAmount, currencyCode, adjustmentReasonCode, description?, clientToken }] }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    entries = body.get('entries', [])
    if not entries:
        return _err("entries array is required")

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        formatted = []
        for e in entries:
            entry = {
                'AgreementId':           e['agreementId'],
                'OriginalInvoiceId':     e['originalInvoiceId'],
                'AdjustmentAmount':      str(e['adjustmentAmount']),
                'CurrencyCode':          e['currencyCode'],
                'AdjustmentReasonCode':  e['adjustmentReasonCode'],
                'ClientToken':           e['clientToken'],
            }
            if e.get('description'):
                entry['Description'] = e['description']
            formatted.append(entry)

        resp = client.batch_create_billing_adjustment_request(
            BillingAdjustmentRequestEntries=formatted
        )
        resp.pop('ResponseMetadata', None)
        return jsonify(resp)
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_adjustment_batch error")
        return _err(str(e), 500)


# ── Agreements: List Cancellation Requests ────────────────────────────────────

@app.route('/procurement/agreements/cancellations/list', methods=['POST'])
def agreement_cancellations_list():
    """
    List all cancellation requests for buyer agreements.
    Body: { roleArn, externalId?, agreementId?, status?, maxResults?, nextToken? }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        kwargs = {
            'partyType':  'Acceptor',
            'catalog':    'AWSMarketplace',
            'maxResults': min(int(body.get('maxResults', 50)), 50),
        }
        if body.get('agreementId'): kwargs['agreementId'] = body['agreementId']
        if body.get('status'):      kwargs['status']      = body['status']
        if body.get('nextToken'):   kwargs['nextToken']   = body['nextToken']

        resp = client.list_agreement_cancellation_requests(**kwargs)
        resp.pop('ResponseMetadata', None)
        return jsonify({
            'cancellationRequests': resp.get('agreementCancellationRequestSummaries', resp.get('AgreementCancellationRequestSummaries', [])),
            'nextToken': resp.get('nextToken', resp.get('NextToken')),
        })
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_cancellations_list error")
        return _err(str(e), 500)


# ── Agreements: Payment Requests (send / get / cancel) ───────────────────────

@app.route('/procurement/agreements/payments/send', methods=['POST'])
def agreement_payment_send():
    """
    Initiate a payment on an agreement.
    Body: { roleArn, externalId?, agreementId, amount, currencyCode }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    agreement_id = body.get('agreementId')
    if not agreement_id:
        return _err("agreementId is required")

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        kwargs = {'AgreementId': agreement_id}
        if body.get('amount'):
            kwargs['PaymentAmount'] = {'Amount': str(body['amount']), 'CurrencyCode': body.get('currencyCode', 'USD')}
        resp = client.send_agreement_payment_request(**kwargs)
        resp.pop('ResponseMetadata', None)
        return jsonify(resp)
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_payment_send error")
        return _err(str(e), 500)


@app.route('/procurement/agreements/payments/get', methods=['POST'])
def agreement_payment_get():
    """
    Get status of a specific payment request.
    Body: { roleArn, externalId?, paymentRequestId }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    payment_request_id = body.get('paymentRequestId')
    if not payment_request_id:
        return _err("paymentRequestId is required")

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        resp = client.get_agreement_payment_request(PaymentRequestId=payment_request_id)
        resp.pop('ResponseMetadata', None)
        return jsonify(resp)
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_payment_get error")
        return _err(str(e), 500)


@app.route('/procurement/agreements/payments/cancel', methods=['POST'])
def agreement_payment_cancel():
    """
    Cancel a pending payment request.
    Body: { roleArn, externalId?, paymentRequestId }
    """
    body = request.get_json(force=True) or {}
    try:
        role_arn, ext_id = _role_params(body)
    except ValueError as e:
        return _err(str(e))

    payment_request_id = body.get('paymentRequestId')
    if not payment_request_id:
        return _err("paymentRequestId is required")

    client = marketplace_agreement_client(role_arn, ext_id)
    try:
        resp = client.cancel_agreement_payment_request(PaymentRequestId=payment_request_id)
        resp.pop('ResponseMetadata', None)
        return jsonify(resp)
    except ClientError as e:
        return _handle_client_error(e)
    except Exception as e:
        logger.exception("agreement_payment_cancel error")
        return _err(str(e), 500)


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == '__main__':
    port = int(os.getenv('PORT', 5003))
    app.run(host='0.0.0.0', port=port, debug=cfg.DEBUG)
