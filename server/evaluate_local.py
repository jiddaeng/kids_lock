import json,time
from pathlib import Path
from local_worker import ollama_classify
cases=[
 ('study','수학 수업에서 일차방정식 2x+3=7의 풀이 과정을 공부하고 있습니다.','STUDY'),
 ('homework','학교 과제: 마인크래프트로 만든 건축물의 대칭을 설명하는 수학 보고서를 작성해서 제출하세요. 지금 보고서를 작성하고 있습니다.','HOMEWORK'),
 ('game','마인크래프트 게임 플레이 중입니다. 체력 10, 인벤토리, 생존 모드, 나무를 캐서 도구를 만들고 있습니다.','GAME'),
 ('communication','친구와 채팅하는 화면입니다. 안녕! 오늘 같이 운동할래? 좋아, 나중에 만나!','COMMUNICATION')]
results=[]
for name,text,expected in cases:
 t=time.monotonic()
 result=ollama_classify(text)
 row={'case':name,'expected':expected,'actual':result,'seconds':round(time.monotonic()-t,2),'pass':result['purpose']==expected}
 results.append(row)
 print(json.dumps(row),flush=True)
Path('../artifacts/local-model-evaluation.json').write_text(json.dumps({'synthetic_only':True,'not_general_accuracy_benchmark':True,'results':results},ensure_ascii=False,indent=2),encoding='utf-8')
