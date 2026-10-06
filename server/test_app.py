import io
import json
import unittest
from app import create_app
from relay import Budget

class HostedTests(unittest.TestCase):
    def setUp(self):
        self.config = {'OPENAI_API_KEY': 'test-key', 'GAUROO_RELAY_TOKEN': 't'*32}
        self.calls = []
        def classify(text, key):
            self.calls.append(text)
            return {'purpose': 'STUDY', 'topics': ['언어'], 'risk': 'NONE', 'confidence': 'medium', 'reason': '교과 개념이나 문제 풀이가 화면에 나타났어요.', 'model': 'gpt-4o'}
        self.app = create_app(self.config, classify, Budget())
    def request(self, path='/v1/analyze', method='POST', body=b'{"text":"English lesson"}', token='t'*32, content_type='application/json', app=None):
        result = {}
        def start(status, headers): result.update(code=int(status.split()[0]), headers=dict(headers))
        env = {'PATH_INFO': path, 'REQUEST_METHOD': method, 'CONTENT_LENGTH': str(len(body)), 'CONTENT_TYPE': content_type,
               'HTTP_AUTHORIZATION': 'Bearer '+token, 'wsgi.input': io.BytesIO(body)}
        raw = b''.join((app or self.app)(env, start))
        result['body'] = json.loads(raw) if raw else None
        return result
    def test_health_does_not_require_keys_or_spend_budget(self):
        result = self.request('/healthz', 'GET', app=create_app({}))
        self.assertEqual(200, result['code'])
        self.assertEqual([], self.calls)
        self.assertEqual(503, self.request(app=create_app({}))['code'])
    def test_auth_precedes_body_and_upstream(self):
        self.assertEqual(401, self.request(token='bad')['code'])
        self.assertEqual([], self.calls)
        self.assertEqual(200, self.request('/v1/status', 'GET')['code'])
        self.assertEqual([], self.calls)
    def test_successful_contract_and_budget(self):
        result = self.request()
        self.assertEqual(200, result['code'])
        self.assertEqual('gpt-4o', result['body']['model'])
        self.assertEqual('no-store', result['headers']['Cache-Control'])
        self.assertEqual(429, self.request()['code'])
        self.assertEqual(1, len(self.calls))
    def test_bad_requests_do_not_call_model(self):
        self.assertEqual(405, self.request(method='GET')['code'])
        self.assertEqual(415, self.request(content_type='text/plain')['code'])
        self.assertEqual(400, self.request(body=b'{bad')['code'])
        self.assertEqual(400, self.request(body=b'{"text":42}')['code'])
        self.assertEqual(400, self.request(body=b'{"text":"ok","private":"data"}')['code'])
        self.assertEqual(413, self.request(body=b'x'*32769)['code'])
        self.assertEqual([], self.calls)
    def test_upstream_private_details_never_leak(self):
        def fail(*args): raise ValueError('private OCR and API key')
        result = self.request(app=create_app(self.config, fail))
        self.assertEqual(502, result['code'])
        self.assertNotIn('private', str(result))

    def test_upstream_quota_is_actionable_without_private_error_body(self):
        from urllib.error import HTTPError
        def fail(*args):
            raise HTTPError('https://api.openai.com', 429, 'private', {}, io.BytesIO(b'{"error":{"code":"insufficient_quota","message":"private OCR key"}}'))
        result = self.request(app=create_app(self.config, fail))
        self.assertEqual({'error': 'upstream_quota'}, result['body'])
        self.assertNotIn('private', str(result))

if __name__ == '__main__': unittest.main()
