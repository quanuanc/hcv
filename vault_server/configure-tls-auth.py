#!/usr/bin/env python3
"""Admin-only bootstrap for the local certificate-auth learning server."""
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.error

BASE = Path(__file__).resolve().parent
os.environ.setdefault('VAULT_ADDR', 'https://127.0.0.1:8200')
os.environ.setdefault('VAULT_CACERT', str(BASE / '.local/server-tls/vault-ca.pem'))
os.environ.setdefault('VAULT_TOKEN', (BASE / '.local/admin-token').read_text().strip())

import hcv_demo as db
import service_secret
from two_step_client import CERT, KEY, credentials, login, request


def denied(label, path, **kwargs):
    try:
        request(path, **kwargs)
    except urllib.error.HTTPError as error:
        if error.code not in (400, 403):
            raise
        print(f'PASS: {label} rejected (HTTP {error.code}).')
    else:
        raise RuntimeError(f'{label} unexpectedly accepted')


db.setup()
service_secret.setup()
methods = db.vault('sys/auth')['data']
if 'cert/' not in methods:
    db.vault('sys/auth/cert', {'type': 'cert'})
elif methods['cert/']['type'] != 'cert':
    raise RuntimeError('cert/ is occupied by another auth method')
db.vault('auth/cert/certs/spring-boot-demo', {
    'certificate': CERT.read_text(),
    'allowed_common_names': ['spring-boot-demo'],
    'token_policies': ['hcv-demo-read'],
    'token_no_default_policy': True,
    'token_ttl': '15m',
    'token_max_ttl': '15m',
    'token_explicit_max_ttl': '15m',
    'display_name': 'spring-boot-demo',
})

denied('Login without certificate', 'auth/cert/login', data={'name': 'spring-boot-demo'})
denied('Read without token', db.CREDS_PATH)
denied('Read with invalid token', db.CREDS_PATH, token='invalid-token')
denied('Read with old dev-only-root token', db.CREDS_PATH, token='dev-only-root')
denied('Read with certificate but no token', db.CREDS_PATH, certificate=CERT, key=KEY)

# Even another certificate with the same CN must fail: trust pins the exact leaf.
with tempfile.TemporaryDirectory() as folder:
    key = Path(folder) / 'wrong.key'
    cert = Path(folder) / 'wrong.crt'
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-noenc',
                    '-sha256', '-days', '1', '-subj', '/CN=spring-boot-demo',
                    '-addext', 'extendedKeyUsage=clientAuth',
                    '-keyout', str(key), '-out', str(cert)],
                   check=True, capture_output=True)
    denied('Login with unregistered certificate (same CN)', 'auth/cert/login',
           data={'name': 'spring-boot-demo'}, certificate=cert, key=key)

auth = login()
token = auth['client_token']
try:
    assert auth['policies'] == ['hcv-demo-read'], auth['policies']
    assert 0 < auth['lease_duration'] <= 900
    assert credentials(token)['username'] == db.APP
    assert request(service_secret.PATH, token=token)['data']['data']['password']
    denied('KV write with read-only token', service_secret.PATH,
           data={'data': {'password': 'permission-test'}}, token=token)
    denied('Other KV path with read-only token', 'app-secrets/data/unrelated', token=token)
    print('PASS: the same token reads the service password; unrelated KV access is denied.')
    print('PASS: registered certificate login issues a 15-minute read-only token.')
    print('PASS: a separate TLS connection without client certificate can read using that token.')
    denied('Password rotation with read-only token', 'database/rotate-role/hcv-demo', data={}, token=token)
    denied('Admin API with read-only token', 'sys/auth', token=token)
    data = credentials(token)
    result = db.psql('SELECT current_user, current_database();',
                     user=db.APP, database=db.DB, password=data['password'])
    assert db.APP + '|' + db.DB in result.stdout
    assert data['rotation_period'] == 10
    print('PASS: retrieved password authenticates to PostgreSQL; rotation period is 10s.')
finally:
    db.vault('auth/token/revoke', {'token': token})
denied('Read with revoked token', db.CREDS_PATH, token=token)
