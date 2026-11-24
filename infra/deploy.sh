#!/usr/bin/env bash
# Stop on errors, unset variables and failed pipelines.
set -euo pipefail
# Resolve paths independently of the directory from which you launch this script.
cd "$(dirname "$0")"
# Keep the visible deployment sequence short; the helper handles AWS/SSM details.
HELPER="scripts/deploy.py"
# Use your private configuration, or create it from the documented defaults.
CONFIG="${1:-deploy.config.json}"
# On the first run, create the config and explain which value needs editing.
if [[ ! -f "$CONFIG" ]]; then
  # Copy defaults without putting personal configuration into Git.
  cp deploy.config.example.json "$CONFIG"
  # Stop before contacting AWS so you can enter your email.
  echo "Created $CONFIG. Set budget_email, then run this script again."
  # Require the configured email before permitting a deployment.
  exit 1
fi

security unlock-keychain "$HOME/Library/Keychains/login.keychain-db"

# Validate tools, account, secrets metadata and settings before changing AWS.
python3 "$HELPER" "$CONFIG" preflight
# Install the repository's pinned CDK dependencies.
npm ci
# Check the infrastructure TypeScript before deployment.
npm run build
# Run the existing infrastructure regression checks.
npm test
# Check migration ordering, network restrictions and cleanup without contacting AWS.
python3 -m unittest discover -s test -p 'test_*.py'
# Build the ARM64 release locally before starting the billable EC2 host.
python3 "$HELPER" "$CONFIG" build-image
# Prepare CDK assets/roles, print the diff and deploy through CloudFormation.
python3 "$HELPER" "$CONFIG" deploy
