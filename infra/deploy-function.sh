set -euo pipefail
: "${GCP_PROJECT_ID:?}" "${GCP_REGION:?}" "${DEPLOY_ENV:?}"

if [[ ! -f functions/document-check/Dockerfile ]]; then
  echo "::notice::functions/document-check/Dockerfile does not exist yet, so document-check-${DEPLOY_ENV} keeps its placeholder"
  exit 0
fi

if [[ -z "${PORTAL_URL:-}" ]]; then
  echo "::warning::PORTAL_URL is not known (the PORTAL_URL_STAGING or PORTAL_URL_PRODUCTION variable), so document-check-${DEPLOY_ENV} was not deployed"
  exit 0
fi

IMAGE="${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT_ID}/unicloud/document-check:${GITHUB_SHA:-local}"
gcloud auth configure-docker "${GCP_REGION}-docker.pkg.dev" --quiet
docker build -t "$IMAGE" functions/document-check
docker push "$IMAGE"

gcloud run deploy "document-check-${DEPLOY_ENV}" --image="$IMAGE" --region="$GCP_REGION" --quiet \
  --set-env-vars="INCOMING_BUCKET=${GCP_PROJECT_ID}-incoming-${DEPLOY_ENV},DOCUMENTS_BUCKET=${GCP_PROJECT_ID}-documents-${DEPLOY_ENV},PORTAL_URL=${PORTAL_URL}"

echo "document-check-${DEPLOY_ENV} runs ${IMAGE} and reports to ${PORTAL_URL}" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"