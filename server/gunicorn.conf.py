import os

bind = '0.0.0.0:' + os.environ.get('PORT', '10000')
# Single process preserves the shared in-memory request budget.
workers = 1
worker_class = 'gthread'
threads = 4
timeout = 180
graceful_timeout = 30
keepalive = 2
accesslog = None
errorlog = '-'
loglevel = 'warning'
capture_output = False
limit_request_line = 2048
limit_request_fields = 30
limit_request_field_size = 4096
