#!/usr/bin/env python3
"""Two independent HTTPS clients: certificate login, then token-only access."""
import argparse
import json
import os
from pathlib import Path
import ssl
import urllib.request

BASE = Path(__file__).resolve().parent
ADDR = os.environ.get('VAULT_ADDR', 'https://127.0.0.1:8200').rstrip('/')
CA = os.environ.get('VAULT_CACERT', str(BASE / '.local/server-tls/vault-ca.pem'))
CERT = BASE / '.local/client/client.crt'
KEY = BASE / '.local/client/client.key'


def request(path, *, data=None, token=None, certificate=None, key=None):
    context = ssl.create_default_context(cafile=CA)
    if certificate:
        context.load_cert_chain(str(certificate), str(key))
    # A new context and opener per request prevent reuse of the mTLS session.
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['X-Vault-Token'] = token
    req = urllib.request.Request(ADDR + '/v1/' + path,
                                 data=None if data is None else json.dumps(data).encode(),
                                 headers=headers)
    with opener.open(req, timeout=20) as response:
        body = response.read()
        return json.loads(body) if body else {}


def login():
    # No pre-existing token is used for login.
    return request('auth/cert/login', data={'name': 'spring-boot-demo'},
                   certificate=CERT, key=KEY)['auth']


def credentials(token):
    # Only the server CA is loaded here; no client certificate or private key.
    return request('database/static-creds/hcv-demo', token=token)['data']


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['login', 'read', 'demo'])
    args = parser.parse_args()
    if args.action == 'login':
        print(login()['client_token'])
    elif args.action == 'read':
        print(json.dumps(credentials(os.environ['VAULT_TOKEN']), indent=2))
    else:
        auth = login()
        data = credentials(auth['client_token'])
        print(json.dumps({'login': 'client certificate verified',
                          'token_ttl_seconds': auth['lease_duration'],
                          'policies': auth['policies'],
                          'credential_request': 'HTTPS + token; no client certificate',
                          'credentials': {k: v for k, v in data.items() if k != 'password'}}, indent=2))
