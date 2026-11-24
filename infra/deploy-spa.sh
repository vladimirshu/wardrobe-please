#!/usr/bin/env bash
# Use AWS_PROFILE/AWS_REGION from the caller, independently of the working directory.
set -euo pipefail
cd "$(dirname "$0")"
python3 scripts/deploy_spa.py
