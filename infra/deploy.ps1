param(
    [Parameter(Mandatory = $true)][string]$ProjectId,
    [string]$Region = "us-central1",
    [string]$FirestoreLocation = "nam5",
    [switch]$AllowUnauthenticated
)

$ErrorActionPreference = "Stop"
$Service = "project-nia-agent"
$RuntimeAccount = "nia-runtime@$ProjectId.iam.gserviceaccount.com"
$PushAccount = "nia-pubsub-invoker@$ProjectId.iam.gserviceaccount.com"
$Topic = "nia-background-jobs"
$Subscription = "nia-background-worker"

gcloud config set project $ProjectId
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com firestore.googleapis.com pubsub.googleapis.com aiplatform.googleapis.com

gcloud iam service-accounts describe $RuntimeAccount 2>$null
if ($LASTEXITCODE -ne 0) { gcloud iam service-accounts create nia-runtime --display-name "Project Nia runtime" }
gcloud iam service-accounts describe $PushAccount 2>$null
if ($LASTEXITCODE -ne 0) { gcloud iam service-accounts create nia-pubsub-invoker --display-name "Project Nia PubSub invoker" }

gcloud projects add-iam-policy-binding $ProjectId --member "serviceAccount:$RuntimeAccount" --role roles/datastore.user --quiet
gcloud projects add-iam-policy-binding $ProjectId --member "serviceAccount:$RuntimeAccount" --role roles/pubsub.publisher --quiet

gcloud firestore databases describe --database "(default)" 2>$null
if ($LASTEXITCODE -ne 0) { gcloud firestore databases create --database "(default)" --location $FirestoreLocation --type firestore-native }

gcloud pubsub topics describe $Topic 2>$null
if ($LASTEXITCODE -ne 0) { gcloud pubsub topics create $Topic }

$AuthFlag = if ($AllowUnauthenticated) { "--allow-unauthenticated" } else { "--no-allow-unauthenticated" }
gcloud run deploy $Service --source backend --region $Region --service-account $RuntimeAccount --set-env-vars "GOOGLE_CLOUD_PROJECT=$ProjectId,NIA_PUBSUB_TOPIC=$Topic,GOOGLE_GENAI_USE_VERTEXAI=true,GOOGLE_CLOUD_LOCATION=$Region" $AuthFlag
$ServiceUrl = gcloud run services describe $Service --region $Region --format "value(status.url)"

gcloud run services add-iam-policy-binding $Service --region $Region --member "serviceAccount:$PushAccount" --role roles/run.invoker --quiet
gcloud pubsub subscriptions describe $Subscription 2>$null
if ($LASTEXITCODE -ne 0) {
    gcloud pubsub subscriptions create $Subscription --topic $Topic --push-endpoint "$ServiceUrl/pubsub/events" --push-auth-service-account $PushAccount --ack-deadline 60
} else {
    gcloud pubsub subscriptions modify-push-config $Subscription --push-endpoint "$ServiceUrl/pubsub/events" --push-auth-service-account $PushAccount
}

Write-Output "Nia agent URL: $ServiceUrl"
Write-Output "Set NIA_AGENT_BASE_URL=$ServiceUrl in ~/.gradle/gradle.properties for the Android build."

