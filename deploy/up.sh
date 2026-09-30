#!/usr/bin/env bash
# Builds every image, starts a local Kubernetes cluster (kind) and deploys
# stream-hub into it. Needs Docker, kind and kubectl, and the compose
# infrastructure running (docker compose -f infra/docker-compose.yml up -d).
set -euo pipefail
cd "$(dirname "$0")/.."
SERVICES="gateway catalog-service user-service playback-service history-service home-service recs-service assistant-service"

echo "== jars"
mvn -q package -DskipTests

echo "== images"
for m in $SERVICES; do
  docker build -q -t "stream-hub/$m:dev" --build-arg MODULE="$m" -f deploy/Dockerfile . > /dev/null
  echo "   stream-hub/$m:dev"
done

echo "== cluster"
kind get clusters | grep -qx stream-hub || kind create cluster --config deploy/kind-cluster.yaml
for m in $SERVICES; do kind load docker-image "stream-hub/$m:dev" --name stream-hub > /dev/null; done

echo "== infrastructure on kind's network"
for c in infra-postgres-1 infra-redis-1 infra-kafka-1 infra-jaeger-1; do
  docker network connect kind "$c" 2>/dev/null || true      # already connected is fine
done

echo "== metrics-server (the autoscaler's CPU numbers)"
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml > /dev/null
# kind's kubelets have self-signed certificates
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' > /dev/null 2>&1 || true

echo "== secrets (kept out of git)"
kubectl apply -f deploy/k8s/namespace.yaml > /dev/null
if ! kubectl -n stream-hub get secret stream-hub-secrets > /dev/null 2>&1; then
  KEY_FILE="$HOME/.config/stream-hub/anthropic.key"          # your own key; ANTHROPIC_API_KEY is never used
  args=(--from-literal=DB_PASSWORD=streamhub --from-literal=JWT_SECRET="$(openssl rand -base64 48)")
  [ -f "$KEY_FILE" ] && args+=(--from-file=STREAM_HUB_ANTHROPIC_KEY="$KEY_FILE")
  kubectl -n stream-hub create secret generic stream-hub-secrets "${args[@]}" > /dev/null
fi

echo "== deploy"
kubectl apply -k deploy/k8s > /dev/null
kubectl -n stream-hub rollout status deployment --timeout=6m
echo "stream-hub is up: http://localhost:8280 (gateway)"
