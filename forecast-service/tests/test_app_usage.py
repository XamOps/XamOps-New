import pytest

from tests.conftest import hourly, daily_pattern

TOP_KEYS = {'status', 'forecast', 'trend_direction', 'method', 'fallback_reason', 'resource', 'metadata'}
META_KEYS = {'data_points', 'forecast_periods', 'processing_time_seconds', 'method', 'fallback_reason',
             'resource', 'history_points_used', 'quantile_semantics', 'model_params'}
ENTRY_KEYS = {'ds', 'yhat', 'yhat_lower', 'yhat_upper', 'p50', 'p90', 'p95'}


def post(client, n=96, **extra):
    body = {'data': hourly(daily_pattern(n)), 'periods': 24}
    body.update(extra)
    return client.post('/forecast/usage', json=body)


@pytest.mark.parametrize('resource,method,reason,resolved', [
    ('memory', 'ets', None, 'memory'),
    ('cpu', 'histogram', None, 'cpu'),
    (None, 'histogram', 'resource_unspecified', 'unspecified'),
    ('MEM', 'ets', None, 'memory'),
])
def test_usage_schema_per_resource(client, resource, method, reason, resolved):
    extra = {} if resource is None else {'resource': resource}
    r = post(client, **extra)
    assert r.status_code == 200
    j = r.get_json()
    assert set(j) == TOP_KEYS
    assert META_KEYS <= set(j['metadata'])
    assert j['status'] == 'success'
    assert j['method'] == j['metadata']['method'] == method
    assert j['fallback_reason'] == j['metadata']['fallback_reason'] == reason
    assert j['resource'] == j['metadata']['resource'] == resolved
    assert j['trend_direction'] in ('GROWING', 'STABLE', 'DECLINING')
    assert 'NOT p95' in j['metadata']['quantile_semantics']
    assert len(j['forecast']) == 24
    for e in j['forecast']:
        assert set(e) == ENTRY_KEYS
        assert e['yhat_lower'] <= e['p50'] <= e['p90'] <= e['p95']
        assert e['yhat'] == e['p50'] and e['yhat_upper'] == e['p90']


def test_explicit_null_resource_defaults(client):
    j = post(client, resource=None).get_json()
    assert j['method'] == 'histogram' and j['fallback_reason'] == 'resource_unspecified'


def test_invalid_resource_400(client):
    r = post(client, resource='disk')
    assert r.status_code == 400
    assert r.get_json() == {'status': 'error', 'message': 'resource must be one of cpu, memory'}


def test_too_few_points_400(client):
    assert post(client, n=23, resource='cpu').status_code == 400


def test_short_history_uses_histogram_fallback(client):
    r = post(client, n=30, resource='memory')
    assert r.status_code == 200
    j = r.get_json()
    assert j['method'] == 'histogram' and j['fallback_reason'] == 'insufficient_history'


@pytest.mark.parametrize('periods', [0, 721])
def test_periods_out_of_range_400(client, periods):
    assert post(client, resource='cpu', periods=periods).status_code == 400


def test_missing_data_400(client):
    r = client.post('/forecast/usage', json={'periods': 24})
    assert r.status_code == 400
    assert r.get_json()['message'] == 'Missing required field: data'
