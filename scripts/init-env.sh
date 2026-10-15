#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -e .env ]; then echo '.env already exists; preserving it.'; exit 0; fi
umask 077
{
  printf 'DB_PASSWORD=%s\n' "$(openssl rand -hex 24)"
  printf 'JWT_SECRET=%s\n' "$(openssl rand -base64 32)"
  printf 'DEMO_USER_PASSWORD=%s\n' "$(openssl rand -hex 16)"
  printf 'DEMO_ADMIN_PASSWORD=%s\n' "$(openssl rand -hex 16)"
} > .env
echo 'Created .env. Read the demo passwords locally from that file.'
