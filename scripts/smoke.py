#!/usr/bin/env python3
"""Exercise the deployed same-origin API. Never print credentials or tokens."""
import http.cookiejar
import json
import os
from pathlib import Path
import urllib.error
import urllib.request

base = os.environ.get('SMOKE_URL', 'http://localhost:8081')
settings = dict(line.split('=', 1) for line in Path('.env').read_text().splitlines() if line and not line.startswith('#'))

def client():
    jar = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

def request(opener, path, method='GET', data=None, token=None, expected=200, csrf=None):
    headers = {'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if csrf:
        headers[csrf['headerName']] = csrf['token']
    body = None if data is None else json.dumps(data).encode()
    if body is not None:
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(base + '/api' + path, data=body, headers=headers, method=method)
    try:
        response = opener.open(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    assert response.status == expected, f'{method} {path}: expected {expected}, got {response.status}'
    raw = response.read()
    return json.loads(raw) if raw else None

def auth_post(opener, path, data=None, expected=200):
    csrf = request(opener, '/auth/csrf')
    return request(opener, path, 'POST', data, expected=expected, csrf=csrf)

anonymous = client()
request(anonymous, '/status', expected=401)
request(anonymous, '/auth/login', 'POST', {}, expected=403)
for role in ('user', 'admin'):
    opener = client()
    session = auth_post(opener, '/auth/login', {'username': role, 'password': settings[f'DEMO_{role.upper()}_PASSWORD']})
    token = session['accessToken']
    assert request(opener, '/status', token=token)['status'] == 'UP'
    request(opener, '/data', expected=401)  # refresh cookie cannot authenticate CRUD
    if role == 'user':
        request(opener, '/data', token=token, expected=403)
    else:
        row = request(opener, '/data', 'POST', {'success': False, 'payload': 'smoke-check'}, token, 201)
        listing = request(opener, '/data', token=token)
        assert listing['total'] >= 1 and len(listing['items']) <= 10
        changed = request(opener, '/data/' + row['id'], 'PUT', {'success': True, 'payload': 'updated'}, token)
        assert changed['createdAt'] == row['createdAt']
        request(opener, '/data/' + row['id'], 'DELETE', token=token, expected=204)
        request(opener, '/data/' + row['id'], token=token, expected=404)
    session = auth_post(opener, '/auth/refresh')
    assert request(opener, '/status', token=session['accessToken'])['status'] == 'UP'
    auth_post(opener, '/auth/logout', expected=204)
    auth_post(opener, '/auth/refresh', expected=401)
print('Smoke passed: CSRF, USER/ADMIN, CRUD, refresh and logout through /api proxy.')
