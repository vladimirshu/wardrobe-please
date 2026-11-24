#!/usr/bin/env bash
# Run after the edge distribution exists. Uses the caller's AWS_PROFILE/AWS_REGION.
set -euo pipefail
cd "$(dirname "$0")"
DISTRIBUTION_ID="${1:?Usage: bash infra/bind-control.sh DISTRIBUTION_ID}"
if [[ ! "$DISTRIBUTION_ID" =~ ^E[A-Z0-9]+$ ]]; then
  echo 'Invalid CloudFront distribution ID' >&2
  exit 1
fi
# Resolve the actual distribution domain; never accept a caller-supplied probe URL.
DISTRIBUTION_DOMAIN="$(aws cloudfront get-distribution --id "$DISTRIBUTION_ID" --query Distribution.DomainName --output text)"
if [[ ! "$DISTRIBUTION_DOMAIN" =~ ^d[a-z0-9]+\.cloudfront\.net$ ]]; then
  echo 'Expected the default CloudFront distribution domain' >&2
  exit 1
fi
CONTROL_PARAMETERS=(--parameters "DistributionId=$DISTRIBUTION_ID" --parameters "HealthcheckUrl=https://$DISTRIBUTION_DOMAIN/api/v1/health")
# Review the diff, then apply only control configuration. Schedules retain their prior state.
npx cdk diff WardrobeControlPlane "${CONTROL_PARAMETERS[@]}"
npx cdk deploy WardrobeControlPlane --exclusively --require-approval never "${CONTROL_PARAMETERS[@]}"
