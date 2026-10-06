"""WSGI entry point for managed HTTPS hosting. No OCR or credential logging."""
import os
import json
import hmac
from urllib.error import HTTPError, URLError
import socket
from http import HTTPStatus
from relay import Budget, classify, validate

def create_app(config=None, classifier=classify, budget=None):
    config = os.environ if config is None else config
    budget = budget or Budget(int(config.get('GAUROO_DAILY_REQUEST_LIMIT', '1000')))

    def application(environ, start_response):
        def respond(code, payload, extra=()):
            data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
            start_response(f'{code} {HTTPStatus(code).phrase}', [
                ('Content-Type', 'application/json; charset=utf-8'),
                ('Content-Length', str(len(data))), ('Cache-Control', 'no-store'),
                ('X-Content-Type-Options', 'nosniff'), *extra])
            return [data]

        path, method = environ.get('PATH_INFO', ''), environ.get('REQUEST_METHOD', '')
        if path == '/healthz' and method in ('GET', 'HEAD'):
            if method == 'HEAD':
                start_response('200 OK', [('Content-Length', '0'), ('Cache-Control', 'no-store')])
                return [b'']
            return respond(200, {'service': 'gauroo-analysis', 'status': 'ok'})
        if path not in ('/v1/analyze', '/v1/status'):
            return respond(404, {'error': 'not_found'})
        if (path == '/v1/analyze' and method != 'POST') or (path == '/v1/status' and method != 'GET'):
            return respond(405, {'error': 'method_not_allowed'})
        key = config.get('OPENAI_API_KEY', '')
        token = config.get('GAUROO_RELAY_TOKEN', '')
        if not key or len(token) < 32 or token.startswith('sk-') or any(c.isspace() for c in token):
            return respond(503, {'error': 'service_not_configured'})
        if not hmac.compare_digest(environ.get('HTTP_AUTHORIZATION', '').encode(), ('Bearer ' + token).encode()):
            return respond(401, {'error': 'unauthorized'})
        if path == '/v1/status':
            return respond(200, {'ready': True, 'model': 'gpt-4o'})
        if environ.get('CONTENT_TYPE', '').split(';')[0].strip().lower() != 'application/json':
            return respond(415, {'error': 'json_required'})
        try:
            length = int(environ.get('CONTENT_LENGTH') or '0')
            if not 0 < length <= 32768:
                return respond(413, {'error': 'invalid_size'})
            raw = environ['wsgi.input'].read(length)
            if len(raw) != length:
                return respond(400, {'error': 'incomplete_request'})
            data = json.loads(raw)
            if not isinstance(data, dict) or set(data) != {'text'} or not isinstance(data['text'], str) or not 1 <= len(data['text']) <= 6000:
                return respond(400, {'error': 'invalid_request'})
        except (ValueError, UnicodeError):
            return respond(400, {'error': 'invalid_request'})
        if not budget.accept():
            return respond(429, {'error': 'request_budget'}, [('Retry-After', '60')])
        try:
            result = classifier(data['text'], key)
            clean = validate({k: v for k, v in result.items() if k != 'model'})
            if result.get('model') != 'gpt-4o':
                raise ValueError('unexpected model')
            return respond(200, clean)
        except HTTPError as exc:
            code = 'upstream_unavailable'
            try:
                error = json.loads(exc.read(8192)).get('error', {})
                if error.get('code') in ('insufficient_quota', 'billing_hard_limit_reached', 'billing_not_active') or error.get('type') == 'insufficient_quota':
                    code = 'upstream_quota'
                elif exc.code == 401:
                    code = 'upstream_auth'
                elif exc.code == 429:
                    code = 'upstream_rate_limit'
                elif exc.code in (403, 404):
                    code = 'upstream_model_access'
                elif exc.code == 400:
                    code = 'upstream_request'
            except Exception:
                pass
            return respond(502, {'error': code})
        except (TimeoutError, socket.timeout, URLError):
            return respond(502, {'error': 'upstream_connection'})
        except Exception:
            return respond(502, {'error': 'analysis_unavailable'})
    return application

from local_queue import create_local_app
app = create_local_app(os.environ)
