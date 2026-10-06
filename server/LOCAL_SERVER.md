# 가루 맥락 분석 중계

Python 3.11 이상, 외부 라이브러리 없음. `relay.py`는 GPT-4o Chat Completions에 strict JSON schema와 `store:false`를 사용한다. 앱에 OpenAI 키를 넣지 않는다.

서버 실행 환경에 `OPENAI_API_KEY`와 독립적인 32자 이상 무작위 `GAUROO_RELAY_TOKEN`을 비밀 설정으로 제공한 뒤 `python relay.py`를 실행한다. 실제 키는 코드·APK·채팅·로그·버전 관리에 기록하지 않는다. 토큰 예시는 제공하지 않으며 직접 생성한 값을 사용한다. 주소/기기 연결 토큰은 앱 **설정 → 분석 서버 연결**에 입력한다. 서버 변경 시 외부 분석 동의가 해제된다.

기본 바인딩은 `127.0.0.1:8787`. Android 연결에는 신뢰할 수 있는 HTTPS 인증서를 가진 리버스 프록시가 필요하다. 경로 `/v1/analyze`를 중계하고 요청/응답 본문 로깅을 끈다. 프록시가 본문·Authorization을 기록하지 않는지 확인한다. `GAUROO_TLS_CERT`, `GAUROO_TLS_KEY`를 함께 지정하면 Python 서버에도 TLS를 적용한다. Android는 HTTP·리디렉션을 허용하지 않는다.

요청: `POST /v1/analyze`, `Authorization: Bearer <기기 연결 토큰>`, JSON `{"text":"식별정보를 줄인 OCR 텍스트"}`. 본문은 32KB/텍스트 6,000자로 제한한다. 앱 ID·타임스탬프·원본 이미지는 보내지 않는다. 반환값은 `purpose`, `topics`, `risk`, `confidence`, `reason`, `model`이다. `reason`도 개인정보가 섞일 수 있는 자유 문장이 아니라 서버와 앱에 정의된 고정 문구 중 하나다. 응답 거절·잘림·비정상 스키마는 502로 처리하고 앱이 분석 결과를 만들어 저장하지 않는다.

인증 실패 401, 형식 오류 400, 과대 본문 413, 요청 한도 429. 기본 10초에 1회 및 UTC 하루 1,000회, `GAUROO_DAILY_REQUEST_LIMIT`로 변경한다. 현재 한도는 단일 프로세스 메모리이며 재시작으로 초기화된다. 운영 시 별도 지속형 한도·회계·토큰 발급/회수 시스템이 필요하다. 발표자료의 월 API 비용 추정에 맞는 실제 요금 수준을 검증한 것은 아니다.

앱과 서버는 텍스트의 일부 식별정보를 제거하지만 완전 익명화를 보장하지 않는다. 공급자의 데이터 보관은 해당 API 프로젝트 정책을 확인해야 한다. `store:false`만으로 모든 공급자 로그의 무보관을 보장하지 않는다.

검증: `python -m unittest -v test_relay.py`. 모든 테스트는 합성 입력과 가짜 upstream 응답을 사용하며 실제 과금 API 호출은 하지 않는다.
