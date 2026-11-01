#!/usr/bin/env python3
"""Admin-only setup/update of a demo external-service password in Vault KV v2."""
import argparse
import os
from pathlib import Path
import secrets
import urllib.error
import hcv_demo as db
from two_step_client import request

BASE = Path(__file__).resolve().parent
MOUNT = 'app-secrets'
PATH = MOUNT + '/data/demo-service'


def read():
    return request(PATH, token=os.environ['VAULT_TOKEN'])['data']


def setup():
    mounts = db.vault('sys/mounts')['data']
    existing = mounts.get(MOUNT + '/')
    if existing is None:
        db.vault('sys/mounts/' + MOUNT, {'type': 'kv', 'options': {'version': '2'}})
    elif existing['type'] != 'kv' or existing.get('options', {}).get('version') != '2':
        raise RuntimeError('app-secrets/ is occupied by another engine')
    try:
        value = read()
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        # CAS=0 prevents overwriting an existing secret during concurrent setup.
        db.vault(PATH, {'options': {'cas': 0}, 'data': {'password': secrets.token_urlsafe(32)}})
        value = read()
    if not isinstance(value.get('data', {}).get('password'), str) or not value['data']['password']:
        raise RuntimeError('Existing demo-service entry has no valid password; refusing to overwrite')
    db.vault('sys/policies/acl/hcv-demo-read', {'policy': (BASE / 'hcv-demo-read.hcl').read_text()})
    return value['metadata']['version']


def rotate():
    before = read()
    result = db.vault(PATH, {'options': {'cas': before['metadata']['version']},
                            'data': {**before['data'], 'password': secrets.token_urlsafe(32)}})
    return result['data']['version']


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['setup', 'rotate', 'status'])
    args = parser.parse_args()
    os.environ['VAULT_TOKEN'] = (BASE / '.local/admin-token').read_text().strip()
    if args.action == 'setup':
        version = setup()
    elif args.action == 'rotate':
        version = rotate()
    else:
        version = read()['metadata']['version']
    print(f'demo-service password: version={version}; value not displayed.')
