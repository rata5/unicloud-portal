set -euo pipefail

GITHUB_REPO="rata5/unicloud-portal"  
REGION="us-central1"
PORTAL_ORIGIN_STAGING="https://unicloud-portal-staging-tszc.onrender.com"
PORTAL_ORIGIN_PROD="https://unicloud-portal.<id>.germanywestcentral.azurecontainerapps.io"
BILLING_ACCOUNT=""

PROJECT_ID=$(gcloud config get-value project 2>/dev/null)
if [[ -z "$PROJECT_ID" ]]; then
  echo "Run: gcloud config set project <your project id>" >&2
  exit 1
fi

PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
REPOSITORY="unicloud"
DEPLOYER="github-deployer@${PROJECT_ID}.iam.gserviceaccount.com"
POOL="github"
PROVIDER="unicloud-portal"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
echo "Using project $PROJECT_ID ($PROJECT_NUMBER), region $REGION"

retry() {
  local attempt
  for attempt in 1 2 3 4 5 6; do
    if "$@" > /dev/null 2>"$WORK/err"; then return 0; fi
    if grep -qi "already exists" "$WORK/err"; then return 0; fi
    if [[ $attempt -eq 6 ]]; then cat "$WORK/err" >&2; return 1; fi
    echo "  not ready yet, retrying in 20 s..."
    sleep 20
  done
}

service_account() {   # name, description; prints the email
  local email="$1@${PROJECT_ID}.iam.gserviceaccount.com"
  gcloud iam service-accounts describe "$email" > /dev/null 2>&1 ||
    gcloud iam service-accounts create "$1" --display-name="$2" --quiet > /dev/null 2>&1
  echo "$email"
}

echo "Turning on the APIs (a few minutes the first time)..."
gcloud services enable run.googleapis.com eventarc.googleapis.com storage.googleapis.com \
  pubsub.googleapis.com artifactregistry.googleapis.com iam.googleapis.com \
  iamcredentials.googleapis.com sts.googleapis.com cloudresourcemanager.googleapis.com

echo "Creating the Artifact Registry repository for the function's image..."
gcloud artifacts repositories describe "$REPOSITORY" --location="$REGION" > /dev/null 2>&1 ||
  gcloud artifacts repositories create "$REPOSITORY" --repository-format=docker \
    --location="$REGION" --description="UniCloud images built by GitHub Actions" > /dev/null
cat > "$WORK/cleanup.json" <<'EOF'
[
  {"name": "keep-newest", "action": {"type": "Keep"}, "mostRecentVersions": {"keepCount": 5}},
  {"name": "delete-old", "action": {"type": "Delete"}, "condition": {"tagState": "any", "olderThan": "7d"}}
]
EOF

gcloud artifacts repositories set-cleanup-policies "$REPOSITORY" --location="$REGION" \
  --policy="$WORK/cleanup.json" > /dev/null

GCS_AGENT=$(gcloud storage service-agent --project="$PROJECT_ID")
retry gcloud projects add-iam-policy-binding "$PROJECT_ID" --condition=None \
  --member="serviceAccount:$GCS_AGENT" --role=roles/pubsub.publisher
PUBSUB_AGENT="service-${PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com"
gcloud beta services identity create --service=pubsub.googleapis.com --project="$PROJECT_ID" --quiet < /dev/null > /dev/null 2>&1 || true

echo "Creating the identity GitHub Actions deploys with..."
service_account github-deployer "GitHub Actions deploys the document check" > /dev/null
gcloud iam workload-identity-pools describe "$POOL" --location=global > /dev/null 2>&1 ||
  gcloud iam workload-identity-pools create "$POOL" --location=global --display-name="GitHub Actions" > /dev/null
gcloud iam workload-identity-pools providers describe "$PROVIDER" --location=global \
    --workload-identity-pool="$POOL" > /dev/null 2>&1 ||
  gcloud iam workload-identity-pools providers create-oidc "$PROVIDER" --location=global \
    --workload-identity-pool="$POOL" --display-name="unicloud-portal" \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.environment=assertion.environment" \
    --attribute-condition="assertion.repository == '${GITHUB_REPO}'" > /dev/null
for environment in staging production; do
  retry gcloud iam service-accounts add-iam-policy-binding "$DEPLOYER" --role=roles/iam.workloadIdentityUser \
    --member="principal://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}/subject/repo:${GITHUB_REPO}:environment:${environment}"
done
retry gcloud projects add-iam-policy-binding "$PROJECT_ID" --condition=None \
  --member="serviceAccount:$DEPLOYER" --role=roles/run.developer
retry gcloud artifacts repositories add-iam-policy-binding "$REPOSITORY" --location="$REGION" \
  --member="serviceAccount:$DEPLOYER" --role=roles/artifactregistry.writer

setup_environment() {   # env (staging|prod), portal origin
  local env="$1" origin="$2"
  local incoming="${PROJECT_ID}-incoming-${env}"
  local documents="${PROJECT_ID}-documents-${env}"
  local service="document-check-${env}"
  echo
  echo "== ${env} =="

  echo "Creating the buckets (private, one region, uniform access)..."
  gcloud storage buckets describe "gs://$incoming" > /dev/null 2>&1 ||
    gcloud storage buckets create "gs://$incoming" --location="$REGION" --default-storage-class=STANDARD \
      --uniform-bucket-level-access --public-access-prevention --soft-delete-duration=0 > /dev/null
  gcloud storage buckets describe "gs://$documents" > /dev/null 2>&1 ||
    gcloud storage buckets create "gs://$documents" --location="$REGION" --default-storage-class=STANDARD \
      --uniform-bucket-level-access --public-access-prevention > /dev/null
  cat > "$WORK/lifecycle.json" <<'EOF'
{"rule": [{"action": {"type": "Delete"}, "condition": {"age": 1}}]}
EOF
  cat > "$WORK/cors.json" <<EOF
[{"origin": ["${origin}"], "method": ["PUT"],
  "responseHeader": ["Content-Type", "x-goog-content-length-range", "x-goog-meta-kind"],
  "maxAgeSeconds": 3600}]
EOF
  gcloud storage buckets update "gs://$incoming" --lifecycle-file="$WORK/lifecycle.json" \
    --cors-file="$WORK/cors.json" > /dev/null

  echo "Creating the service accounts..."
  local portal checker
  portal=$(service_account "portal-${env}" "UniCloud portal (${env}): uploads and downloads")
  checker=$(service_account "document-check-${env}" "document-check (${env}): checks uploads")

  retry gcloud storage buckets add-iam-policy-binding "gs://$incoming" \
    --member="serviceAccount:$portal" --role=roles/storage.objectCreator
  retry gcloud storage buckets add-iam-policy-binding "gs://$documents" \
    --member="serviceAccount:$portal" --role=roles/storage.objectViewer
  retry gcloud storage buckets add-iam-policy-binding "gs://$incoming" \
    --member="serviceAccount:$checker" --role=roles/storage.objectUser
  retry gcloud storage buckets add-iam-policy-binding "gs://$documents" \
    --member="serviceAccount:$checker" --role=roles/storage.objectUser
  retry gcloud projects add-iam-policy-binding "$PROJECT_ID" --condition=None \
    --member="serviceAccount:$checker" --role=roles/eventarc.eventReceiver
  retry gcloud iam service-accounts add-iam-policy-binding "$checker" \
    --member="serviceAccount:$DEPLOYER" --role=roles/iam.serviceAccountUser
  retry gcloud iam service-accounts add-iam-policy-binding "$checker" \
    --member="serviceAccount:$PUBSUB_AGENT" --role=roles/iam.serviceAccountTokenCreator

  echo "Creating the document-check service and its trigger..."
  gcloud run services describe "$service" --region="$REGION" > /dev/null 2>&1 ||
    gcloud run deploy "$service" --image=us-docker.pkg.dev/cloudrun/container/hello --region="$REGION" \
      --service-account="$checker" --no-allow-unauthenticated --ingress=internal \
      --min-instances=0 --max-instances=3 --memory=512Mi --cpu=1 --timeout=300 --concurrency=8 --quiet > /dev/null
  retry gcloud run services add-iam-policy-binding "$service" --region="$REGION" \
    --member="serviceAccount:$checker" --role=roles/run.invoker
  gcloud eventarc triggers describe "$service" --location="$REGION" > /dev/null 2>&1 ||
    retry gcloud eventarc triggers create "$service" --location="$REGION" \
      --destination-run-service="$service" --destination-run-region="$REGION" \
      --event-filters="type=google.cloud.storage.object.v1.finalized" --event-filters="bucket=$incoming" \
      --service-account="$checker"

  SUMMARY+="
${env}:
  APP_DOCUMENTS_INCOMING_BUCKET = $incoming
  APP_DOCUMENTS_BUCKET          = $documents
  APP_DOCUMENTS_CHECKER         = $checker
  GCP_CREDENTIALS_JSON          = the key of $portal (below)
"
  KEYS+="  gcloud iam service-accounts keys create portal-${env}-key.json --iam-account=$portal
"
}

SUMMARY=""
KEYS=""
setup_environment staging "$PORTAL_ORIGIN_STAGING"
setup_environment prod "$PORTAL_ORIGIN_PROD"

if [[ -n "$BILLING_ACCOUNT" ]]; then
  echo
  echo "Creating the budget alert..."
  gcloud services enable billingbudgets.googleapis.com
  if ! gcloud billing budgets list --billing-account="$BILLING_ACCOUNT" --format='value(displayName)' | grep -qx "UniCloud 0 BGN"; then
    gcloud billing budgets create --billing-account="$BILLING_ACCOUNT" --display-name="UniCloud 0 BGN" \
      --budget-amount=1 --filter-projects="projects/${PROJECT_ID}" \
      --threshold-rule=percent=0.01 --threshold-rule=percent=0.5 --threshold-rule=percent=1.0 > /dev/null
  fi
fi