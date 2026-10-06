import io
import json
import unittest
import threading
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from http.server import ThreadingHTTPServer
from relay import classify, validate, handler, Budget

class RelayTests(unittest.TestCase):
    def result(self):
        return {'purpose': 'STUDY', 'topics': ['언어'], 'risk': 'NONE', 'confidence': 'medium', 'reason': '교과 개념이나 문제 풀이가 화면에 나타났어요.'}
    def test_gpt4o_contract_redaction_and_no_storage(self):
        def fake(request, timeout):
            body = json.loads(request.data)
            self.assertEqual('https://api.openai.com/v1/chat/completions', request.full_url)
            self.assertEqual('gpt-4o', body['model'])
            self.assertFalse(body['store'])
            self.assertTrue(body['response_format']['json_schema']['strict'])
            self.assertNotIn('010-1234-5678', body['messages'][1]['content'])
            return io.BytesIO(json.dumps({'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps(self.result())}}]}).encode())
        self.assertEqual('STUDY', classify('영어 강의 010-1234-5678', 'test', fake)['purpose'])
    def test_output_cannot_persist_personal_text(self):
        for update in [{'topics': ['홍길동 010-1234-5678']}, {'purpose': 'invented'}, {'risk': 'SAFE'},
                       {'reason': 'OCR 원문을 그대로 포함한 임의의 이유'},
                       {'reason': '게임 플레이나 조작 단서가 화면에 나타났어요.'}, {'quote': 'private'}]:
            with self.assertRaises(ValueError): validate(dict(self.result(), **update))
    def test_refusal_and_truncation_do_not_turn_into_safe_classifications(self):
        for choice in [{'finish_reason': 'length', 'message': {'content': '{}'}}, {'finish_reason': 'stop', 'message': {'refusal': 'no'}}]:
            with self.assertRaises(ValueError):
                classify('text', 'test', lambda *a, **k: io.BytesIO(json.dumps({'choices': [choice]}).encode()))
    def test_rate_and_daily_budget(self):
        budget = Budget(2)
        self.assertTrue(budget.accept(100))
        self.assertFalse(budget.accept(101))
        self.assertTrue(budget.accept(110))
        self.assertFalse(budget.accept(120))
        self.assertTrue(budget.accept(86500))
    def test_http_authentication_and_upstream_failure(self):
        calls = []
        def fake(text, key): calls.append(text); raise RuntimeError('private text must not appear')
        server = ThreadingHTTPServer(('127.0.0.1', 0), handler('test', 't'*32, fake))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            url = f'http://127.0.0.1:{server.server_port}/v1/analyze'
            for token, expected in [('', 401), ('t'*32, 502)]:
                request = Request(url, data=b'{"text":"hello"}', headers={'Authorization': 'Bearer '+token})
                with self.assertRaises(HTTPError) as error: urlopen(request)
                self.assertEqual(expected, error.exception.code)
                self.assertNotIn(b'private', error.exception.read())
            self.assertEqual(['hello'], calls)
        finally: server.shutdown(); server.server_close()

if __name__ == '__main__': unittest.main()
