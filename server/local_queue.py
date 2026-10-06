"""Single-family, memory-only rendezvous with the owner's local model worker."""
import hashlib
import hmac
import json
import secrets
import threading
import time
from http import HTTPStatus
from relay import Budget, validate

MODEL = 'qwen3:4b'

def worker_token(token):
    return hmac.new(token.encode(), b'gauroo-local-worker-v1', hashlib.sha256).hexdigest()

class Broker:
    def __init__(self, timeout=160):
        self.timeout = timeout
        self.cv = threading.Condition()
        self.job = None
        self.last_seen = 0
    def online(self):
        return time.monotonic() - self.last_seen < 30
    def heartbeat(self):
        with self.cv:
            self.last_seen = time.monotonic()
    def take(self):
        with self.cv:
            self.last_seen = time.monotonic()
            if not self.job or self.job['claimed']:
                return None
            self.job['claimed'] = True
            return {k: self.job[k] for k in ('id', 'text')}
    def finish(self, data):
        with self.cv:
            if not self.job or self.job['id'] != data.get('id') or not self.job['claimed']:
                return False
            result = data.get('result')
            if result is not None:
                if result.get('model') != MODEL:
                    raise ValueError('wrong model')
                result = validate({k: v for k, v in result.items() if k != 'model'})
                result['model'] = MODEL
            self.job['result'] = result
            self.job['done'] = True
            self.job['text'] = ''
            self.cv.notify_all()
            return True
    def analyze(self, text):
        with self.cv:
            if not self.online():
                return 503, {'error': 'local_worker_offline'}
            if self.job:
                return 429, {'error': 'local_worker_busy'}
            self.job = {'id': secrets.token_hex(16), 'text': text, 'claimed': False, 'done': False, 'result': None}
            try:
                done = self.cv.wait_for(lambda: self.job['done'], timeout=self.timeout)
                if not done:
                    return 504, {'error': 'local_worker_timeout'}
                if self.job['result'] is None:
                    return 502, {'error': 'local_analysis_failed'}
                return 200, self.job['result']
            finally:
                self.job = None

def create_local_app(config, broker=None, budget=None):
    broker = broker or Broker()
    budget = budget or Budget(int(config.get('GAUROO_DAILY_REQUEST_LIMIT', '1000')))
    def app(env, start):
        def reply(code, value):
            raw = json.dumps(value, ensure_ascii=False).encode()
            start(f'{code} {HTTPStatus(code).phrase}', [('Content-Type','application/json; charset=utf-8'), ('Content-Length',str(len(raw))), ('Cache-Control','no-store')])
            return [raw]
        path, method = env.get('PATH_INFO'), env.get('REQUEST_METHOD')
        if path == '/healthz':
            return reply(200, {'service':'gauroo-analysis', 'status':'ok', 'provider':'local', 'contract':2})
        routes = {'/v1/status':'GET', '/v1/analyze':'POST', '/worker/next':'GET', '/worker/heartbeat':'POST', '/worker/result':'POST'}
        if path not in routes:
            return reply(404, {'error':'not_found'})
        token = config.get('GAUROO_RELAY_TOKEN','')
        if len(token) < 32:
            return reply(503, {'error':'service_not_configured'})
        expected = worker_token(token) if path.startswith('/worker/') else token
        if not hmac.compare_digest(env.get('HTTP_AUTHORIZATION','').encode(), ('Bearer '+expected).encode()):
            return reply(401, {'error':'unauthorized'})
        if method != routes[path]:
            return reply(405, {'error':'method_not_allowed'})
        if path == '/worker/heartbeat':
            broker.heartbeat()
            return reply(200, {'ok':True})
        if path == '/worker/next':
            return reply(200, {'job':broker.take()})
        if path == '/v1/status':
            if not broker.online():
                return reply(503, {'error':'local_worker_offline'})
            return reply(200, {'ready':True,'model':MODEL,'provider':'local'})
        try:
            length = int(env.get('CONTENT_LENGTH') or 0)
            if not 0 < length <= 32768:
                return reply(413, {'error':'invalid_size'})
            if env.get('CONTENT_TYPE','').split(';')[0] != 'application/json':
                return reply(415, {'error':'json_required'})
            data = json.loads(env['wsgi.input'].read(length))
            if not isinstance(data, dict):
                raise ValueError()
            if path == '/worker/result':
                return reply(200 if broker.finish(data) else 410, {'ok':True})
            if set(data) != {'text'} or not isinstance(data['text'],str) or not 1 <= len(data['text']) <= 6000:
                raise ValueError()
        except (ValueError, TypeError, AttributeError):
            return reply(400, {'error':'invalid_request'})
        if not broker.online():
            return reply(503, {'error':'local_worker_offline'})
        if not budget.accept():
            return reply(429, {'error':'request_budget'})
        code, result = broker.analyze(data['text'])
        return reply(code, result)
    return app
