# 2026-09-06 현재 상태: 로컬 Qwen3 분석

GPT-4o 지정 변경을 사용자에게 승인받아 PC 로컬 Qwen3 4B로 전환했다. 실제 Android OCR→Render HTTPS→PC Qwen→분류 저장 검사를 통과했으며, 동의 유지 상태의 앱 전환으로 응답이 유실되던 문제를 수정했다. 모델/API 키 대신 기존 가루 연결 토큰을 사용한다. 실행 및 데이터 경로는 `LOCAL_ANALYSIS.md`를 참고한다. 최종 검증 근거는 `artifacts/live-local-test.txt`, `artifacts/live-local-result.txt`, `artifacts/local-final-ui-test.txt`, `artifacts/local-model-evaluation.json`이다.

아래 내용은 이전 버전 검증 기록으로 보존한다. 과거 OpenAI 미배포/배포·호출 제한 상태는 현재 로컬 모델의 상태가 아니다.

---
# 구현 및 검증 상태 — 가루 0.2.0

작성일: 2026-09-05. 최우선 기준은 `PROJECT_BASELINE.md`, 항목별 대조는 `PRESENTATION_AUDIT.md`.

## 구현

ML Kit OCR을 약 10초마다 실행하고, 성공한 이전 분석과 다른 화면 텍스트만 GPT-4o 중계에 보냅니다. 앱과 중계의 OCR 원문 디스크 저장/본문 로깅은 없으며 이미지 전송도 없습니다. 일부 식별정보 제거, 출력 스키마 검증, 서버 키 분리, HTTPS·연결 토큰, 새 외부 분석 동의를 적용했습니다. 전송된 데이터의 공급자 보관 정책은 별개입니다.

전체/앱별 사용시간, 학습 보호 시간대, 선택적 유해 가능성 개입은 양쪽 동의와 경고·유예·반복 위반 절차를 따릅니다. 일·주·월 집계와 주간 관심사/변화/패턴/대화 질문을 추가하고 공유 이후의 부모 대화 질문도 선택된 주제만 사용합니다. 2026-09-05 사용자의 “가루로 통일해” 승인에 따라 제품명은 가루로 통일했습니다. 패키지 ID와 DB 기록은 보존합니다.

## 검증 결과

- 최종 APK 빌드 성공: Android 11 이상, target/compile SDK 36, 버전 0.2.0.
- Kotlin 코어 16개 통과 (실패/오류 0).
- Python 서버 계약 테스트 5개 통과. 합성 요청·가짜 upstream 사용, 실제 과금 API 호출 없음.
- Android 15(API 35) KidsLock_QA 전용 에뮬레이터 4개 통과. 실제 OCR, 외부 분석 동의 없음일 때 가짜 분류 미생성, FLAG_SECURE 제외, 수집 철회 후 미기록, SQLite 요약/공유 필터, 다섯 탭 시작·렌더링.
- 초기에 에뮬레이터의 시스템/런처 ANR 창이 테스트 화면을 가려 2회 실패했다. 원인을 화면 상태로 확인하고 ANR 창 정리 후 동일 검사가 통과했다. 실패를 앱 분류 정확도 문제로 해석하지 않는다.
- Android Lint 오류 0개, 경고 24개. 문자열 국제화·기존 API 표기·의존성 업데이트 등의 경고가 남아 있다.
- APK 권한: INTERNET 추가 확인, ACCESS_NETWORK_STATE 없음. 알림과 내부 비공개 수신자 권한 유지.
- 다섯 화면 PNG를 육안 확인했다. 이름을 기존 키즈락으로 복구한 최종본도 UI 검사 1개로 다섯 화면을 다시 확인했고 통과했다.

설치 파일: `artifacts/gauroo-0.2.0-debug.apk` (디버그 서명).

SHA-256: `ceb94ce2200982b026dd5d4db7fbfa51b160deebece636cf8aeed0e9d6b959db`

검증 로그: `artifacts/device-tests.txt`, `artifacts/final-ui-test.txt`, `artifacts/verification-0.2.0.json`, `core/build/test-results/test`, `app/build/reports/lint-results-debug.html`.

## 미검증 및 운영 연결

- 실제 GPT-4o API 호출과 의미 분류 정확도: 실제 키·운영 HTTPS 서버 주소를 제공받지 않아 미검증. 합성 upstream 테스트는 정확도 검증이 아닙니다.
- 별도 부모 기기 연결·계정·실서비스 보호자 확인·결제·B2B 집계·iOS·배포는 미연결입니다. 발표자료의 확장 로드맵으로 보존했습니다.
- 실제 유튜브/숏폼 앱 OCR 수집률, 실제 휴대폰 배터리/발열, OEM 절전·재부팅·우회 방지 및 출시 심사는 미검증입니다.
- 현재 학습 보호·유해 가능성 개입은 합의한 앱의 경고·유예·반복 휴식입니다. 특정 유해 영상만 정확하게 차단하는 기능이나 의료·안전 진단이 아닙니다.
- 단일 가족 개발용 중계 토큰/메모리 요청 한도입니다. 운영 HTTPS, 지속형 한도, 토큰 발급/회수, 계정 시스템은 별도 운영 구성이 필요합니다.

변경 전 파일은 `artifacts/before-presentation-*`에 보존했습니다. 원본 HWP와 PDF는 수정하지 않았습니다.



## 가루 명칭 승인 반영 (2026-09-05)

사용자 승인 후 앱 이름·헤더·알림·접근성 서비스와 현재 제품 문서를 가루로 통일했다. APK 재빌드 및 Lint 오류 0개를 확인했다. 명칭만 변경해 기존 기능 테스트 결과는 유지하며 기기 기능 테스트를 중복 실행하지 않았다. 최신 설치 파일은 `artifacts/gauroo-0.2.0-debug.apk`, 명칭 검증 기록은 `artifacts/verification-gauroo-name.json`이다. 이전 이름의 APK/검증 기록은 과거 결과로 보존한다.

## Render 실제 배포 (2026-09-06)

- 분석 서버: https://gauroo-analysis.onrender.com
- Render 서비스: srv-daedkqn40ujc73eqkuo0 (Singapore, Free)
- 비공개 소스: https://github.com/jiddaeng/gauroo-analysis, 배포 커밋 bd70ed4d391a3f15988544f364675eec3643c1a6.
- Render Live 상태, 공개 GET /healthz HTTP 200, 토큰 없는 GET /v1/status HTTP 401을 확인했다. 서버 단위 테스트 10개 통과.
- 사용자가 OPENAI_API_KEY를 Render에 직접 입력했고 GAUROO_RELAY_TOKEN은 Render에서 생성했다. 키/토큰을 소스 또는 APK에 포함하지 않는다.
- 앱 0.2.1은 위 주소를 기본 표시한다. 기존에 입력한 다른 주소는 유지한다. Render Environment의 GAUROO_RELAY_TOKEN 값을 앱의 분석 서버 연결 토큰 칸에 넣고 부모·자녀 외부 분석 동의를 완료해야 한다.
- 실제 GPT-4o 호출 성공/계정 잔액/모델 분류 정확도와 휴대폰의 종단 간 분석은 아직 미검증이다. HTTP 상태 검사만으로 이를 검증했다고 표현하지 않는다.
- 무료 서비스는 유휴 시 잠들며 첫 응답에 50초 이상 걸릴 수 있다. 앱의 실패 후 재시도 때 연결될 수 있다. 메모리 요청 한도는 재시작 시 초기화되므로 상용 다가구 비용 제어용은 아니다.
- 최신 APK: artifacts/gauroo-0.2.1-debug.apk. 배포 사실은 이전의 서버 미배포 기록을 대체하며 다른 미구현 항목은 유지한다.