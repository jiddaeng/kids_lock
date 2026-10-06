import io
import json
import threading
import time
import unittest
from local_queue import Broker, MODEL, create_local_app, worker_token

class LocalQueueTests(unittest.TestCase):
    def setUp(self):
        self.token='t'*32
        self.broker=Broker(timeout=0.15)
        self.app=create_local_app({'GAUROO_RELAY_TOKEN':self.token}, self.broker)
    def call(self,path,method='GET',data=None,worker=False):
        output={}
        raw=json.dumps(data).encode() if data is not None else b''
        env={'PATH_INFO':path,'REQUEST_METHOD':method,'CONTENT_TYPE':'application/json','CONTENT_LENGTH':str(len(raw)),
             'HTTP_AUTHORIZATION':'Bearer '+(worker_token(self.token) if worker else self.token),'wsgi.input':io.BytesIO(raw)}
        def start(status,headers):output['code']=int(status.split()[0])
        output['body']=json.loads(b''.join(self.app(env,start)))
        return output
    def test_worker_access_is_separate_and_offline_actionable(self):
        self.assertEqual(2,self.call('/healthz')['body']['contract'])
        self.assertEqual(401,self.call('/worker/next')['code'])
        self.assertEqual('local_worker_offline',self.call('/v1/status')['body']['error'])
        self.call('/worker/heartbeat','POST',{},True)
        self.assertEqual(MODEL,self.call('/v1/status')['body']['model'])
    def test_success_has_real_worker_result_and_clears_raw(self):
        self.broker.heartbeat()
        answer=[]
        thread=threading.Thread(target=lambda:answer.append(self.broker.analyze('synthetic lesson')))
        thread.start()
        deadline=time.monotonic()+1
        while not self.broker.job and time.monotonic()<deadline:time.sleep(.001)
        job=self.broker.take()
        self.assertIsNone(self.broker.take())
        self.assertTrue(self.broker.finish({'id':job['id'],'result':{'model':MODEL,'purpose':'STUDY','topics':['수학'],'risk':'NONE','confidence':'high','reason':'교과 개념이나 문제 풀이가 화면에 나타났어요.'}}))
        thread.join()
        self.assertEqual(200,answer[0][0])
        self.assertEqual(MODEL,answer[0][1]['model'])
        self.assertIsNone(self.broker.job)
    def test_timeout_discards_text_and_rejects_late_result(self):
        self.broker.heartbeat()
        self.assertEqual(504,self.broker.analyze('must disappear')[0])
        self.assertIsNone(self.broker.job)
        self.assertFalse(self.broker.finish({'id':'expired','result':None}))
    def test_worker_cannot_persist_arbitrary_model_output(self):
        self.broker.job={'id':'one','claimed':True,'text':'private'}
        with self.assertRaises(ValueError):
            self.broker.finish({'id':'one','result':{'model':MODEL,'purpose':'STUDY','topics':['private name'],'risk':'NONE','confidence':'high','reason':'교과 개념이나 문제 풀이가 화면에 나타났어요.'}})
        self.broker.job={'id':'two','claimed':True,'text':'private'}
        with self.assertRaises(ValueError):
            self.broker.finish({'id':'two','result':{'model':MODEL,'purpose':'STUDY','topics':['수학'],'risk':'NONE','confidence':'high','reason':'개인 원문을 되풀이한 임의 문장'}})

if __name__=='__main__':unittest.main()
