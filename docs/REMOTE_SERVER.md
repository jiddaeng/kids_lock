# 가루 원격 분석 서버 운영

가루의 Qwen3 4B 모델 작업기는 `gina` 계정 홈에서 실행한다. Android 앱의 HTTPS 주소는 계속 `https://gauroo-analysis.onrender.com`을 사용하며, Render는 인증된 메모리 중계만 담당한다. 실제 모델 추론은 원격 서버의 RTX 8000에서 수행한다.

## 서버 경로

- 코드: `~/just/jiddaeng/gauroo-analysis`
- 모델: `~/just/jiddaeng/gauroo-analysis/models`
- 비밀 환경 파일: `~/just/jiddaeng/gauroo-analysis/.worker-env` (권한 600)
- OCR·모델 맥락 로그: `~/just/jiddaeng/gauroo-analysis/var/model.log` (권한 600, 5MB × 현재 파일과 백업 3개)
- Ollama: `~/.local/bin/ollama`
- 사용자 서비스: `~/.config/systemd/user/gauroo-ollama.service`, `gauroo-worker.service`

## 상태와 로그 확인

```sh
systemctl --user status gauroo-ollama.service gauroo-worker.service
tail -f ~/just/jiddaeng/gauroo-analysis/var/model.log
```

모델 로그에는 앱에서 식별정보를 줄인 OCR 텍스트와 모델의 목적, 관심 주제, 위험 가능성, 확신도, 고정 판단 이유가 기록된다. 원본 이미지는 기록하지 않는다. 서비스는 사용자 linger 설정으로 SSH에서 로그아웃하거나 서버가 재부팅된 뒤에도 자동 시작한다.
