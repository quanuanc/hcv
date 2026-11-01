#!/usr/bin/env python3
"""Run the opt-in client end-to-end test; temporarily shorten certificate Token TTL."""
import os
from pathlib import Path
import subprocess
import hcv_demo as db

BASE = Path(__file__).resolve().parent
os.environ['VAULT_TOKEN'] = (BASE / '.local/admin-token').read_text().strip()
ROLE = 'auth/cert/certs/spring-boot-demo'
original = db.vault(ROLE)['data']
short = dict(original)
short.update(token_ttl=20, token_max_ttl=20, token_explicit_max_ttl=20)
try:
    db.set_period('10s')
    db.vault(ROLE, short)
    print('Test configuration: password rotation 10s, new certificate Token TTL 20s.', flush=True)
    env = os.environ.copy()
    env.pop('VAULT_TOKEN', None)  # The Java client never receives the admin token.
    result = subprocess.run(['./mvnw', '-q', '-Dtest=VaultRotationE2ETests',
                             '-Dhcv.rotation.e2e=true', 'test'],
                            cwd=BASE.parent / 'vault_client', env=env)
finally:
    db.vault(ROLE, original)
    restored = db.vault(ROLE)['data']
    for field in ('token_ttl', 'token_max_ttl', 'token_explicit_max_ttl'):
        if restored[field] != original[field]:
            raise RuntimeError('Certificate Token TTL was not restored')
    print('Restored certificate Token TTL; password rotation remains 10s.', flush=True)
raise SystemExit(result.returncode)
