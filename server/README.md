# Gauroo local-model relay

The production entry point is `app:app`, backed by `local_queue.py`. It relays consented OCR text over HTTPS to the owner's PC running `local_worker.py` and local Ollama `qwen3:4b`. It does not call OpenAI or Gemini. The older `relay.py` classifier and hosted tests remain as legacy regression/reference code.

Public endpoint: https://gauroo-analysis.onrender.com

`GAUROO_RELAY_TOKEN` authenticates the Android app. A separate derived worker credential authenticates `/worker/*`. Worker credentials are not embedded in the APK. The Android project provides start/stop tools with Windows DPAPI-protected worker configuration.

The PC must remain awake and connected. Ollama is bound only to 127.0.0.1:11434; no inbound PC firewall opening is needed. The PC worker polls the relay over HTTPS. The relay holds a single job in memory, waits at most 160 seconds, validates closed-schema results, and clears the job on completion or timeout. Request bodies are not logged or stored in a database.

Run `python -m unittest -v` for broker/authentication/output/timeout tests. `evaluate_local.py` evaluates four synthetic Korean examples against the real local model; it is not a general accuracy benchmark. The Android project contains a separate real OCR-to-local-model instrumentation test.

Render uses one Gunicorn process and a free instance. The queue and budget reset on process restart. This is single-family development infrastructure, not multi-tenant production infrastructure.
