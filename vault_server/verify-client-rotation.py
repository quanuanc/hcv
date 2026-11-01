#!/usr/bin/env python3
"""Run the opt-in client end-to-end test; temporarily shorten certificate Token TTL."""
import os
from pathlib import Path
import subprocess
import hcv_demo as db
import service_secret

BASE = Path(__file__).resolve().parent
os.environ['VAULT_TOKEN'] = (BASE / '.local/admin-token').read_text().strip()
ROLE = 'auth/cert/certs/spring-boot-demo'
original = db.vault(ROLE)['data']
short = dict(original)
short.update(token_ttl=20, token_max_ttl=20, token_explicit_max_ttl=20)
try:
    service_secret.setup()
    db.set_period('10s')
    db.vault(ROLE, short)
    print('Test configuration: password rotation 10s, new certificate Token TTL 20s.', flush=True)
    env = os.environ.copy()
    env.pop('VAULT_TOKEN', None)  # The Java client never receives the admin token.
    with subprocess.Popen(['./vault_client/mvnw', '-q', '-pl', 'vault_client', '-am',
                           '-Dsurefire.failIfNoSpecifiedTests=false', '-Dtest=VaultRotationE2ETests',
                           '-Dhcv.rotation.e2e=true', 'test'],
                          cwd=BASE.parent, env=env,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1) as process:
        updates = 0
        try:
            for line in process.stdout:
                print(line, end='', flush=True)
                if line.strip() in ('E2E KV ready 1', 'E2E KV ready 2'):
                    version = service_secret.rotate()
                    updates += 1
                    print(f'Admin updated demo-service password to version {version}; value not displayed.', flush=True)
            returncode = process.wait()
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
        if returncode == 0 and updates != 2:
            raise RuntimeError('Expected two KV updates during the client test')

finally:
    db.vault(ROLE, original)
    restored = db.vault(ROLE)['data']
    for field in ('token_ttl', 'token_max_ttl', 'token_explicit_max_ttl'):
        if restored[field] != original[field]:
            raise RuntimeError('Certificate Token TTL was not restored')
    print('Restored certificate Token TTL; password rotation remains 10s.', flush=True)
raise SystemExit(returncode)
