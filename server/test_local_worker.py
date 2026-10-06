import json
import logging
import os
import stat
import tempfile
import unittest
from unittest.mock import patch

from local_worker import apply_safety_backstop, model_logger


class LocalWorkerLogTests(unittest.TestCase):
    def test_context_free_harm_words_cannot_become_study(self):
        wrong = {'purpose': 'STUDY', 'topics': ['게임'], 'risk': 'NONE', 'confidence': 'medium'}
        fixed = apply_safety_backstop('협박, 납치, 감금', wrong)
        self.assertEqual('UNKNOWN', fixed['purpose'])
        self.assertEqual([], fixed['topics'])
        self.assertEqual('POSSIBLE', fixed['risk'])
        self.assertEqual('medium', fixed['confidence'])

    def test_explicit_activity_context_is_left_for_model_classification(self):
        classified = {'purpose': 'STUDY', 'topics': [], 'risk': 'POSSIBLE', 'confidence': 'high'}
        fixed = apply_safety_backstop('학교 안전교육에서 납치 예방과 대처 방법을 공부합니다', classified)
        self.assertEqual('STUDY', fixed['purpose'])
        self.assertEqual('NONE', fixed['risk'])

    def test_crime_news_keeps_context_but_cannot_hide_visible_risk(self):
        wrong = {'purpose': 'INFORMATION', 'topics': ['수학', '과학'], 'risk': 'NONE', 'confidence': 'low'}
        fixed = apply_safety_backstop('뉴스 기사: 납치와 감금 혐의로 체포했습니다', wrong)
        self.assertEqual('INFORMATION', fixed['purpose'])
        self.assertEqual('POSSIBLE', fixed['risk'])
        self.assertEqual('medium', fixed['confidence'])
        self.assertEqual([], fixed['topics'])

    def test_topics_require_visible_support(self):
        proposed = {'purpose': 'STUDY', 'topics': ['수학', '과학'], 'risk': 'NONE', 'confidence': 'high'}
        self.assertEqual([], apply_safety_backstop('학교 안전교육에서 납치 예방을 공부합니다', proposed)['topics'])
        self.assertEqual(['수학'], apply_safety_backstop('일차방정식 수학 강의', proposed)['topics'])

    def test_model_log_is_opt_in_and_owner_only(self):
        self.assertIsNone(model_logger())
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, 'private', 'model.log')
            with patch.dict(os.environ, {'GAUROO_LOG_MODEL_CONTEXT': '1', 'GAUROO_MODEL_LOG_PATH': path}):
                logger = model_logger()
                logger.info(json.dumps({'ocr_text': '합성 수학 화면', 'purpose': 'STUDY'}, ensure_ascii=False))
                for handler in logger.handlers:
                    handler.flush()
                with open(path, encoding='utf-8') as model_log:
                    self.assertIn('합성 수학 화면', model_log.read())
                if os.name != 'nt':
                    self.assertEqual(0o600, stat.S_IMODE(os.stat(path).st_mode))
                for handler in list(logger.handlers):
                    handler.close()
                    logger.removeHandler(handler)


if __name__ == '__main__':
    unittest.main()
