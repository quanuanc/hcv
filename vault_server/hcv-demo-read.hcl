# Application read access to the database credentials and one service secret.
path "database/static-creds/hcv-demo" {
  capabilities = ["read"]
}

# Only this KV v2 data path; no write, list, or metadata-management permissions.
path "app-secrets/data/demo-service" {
  capabilities = ["read"]
}
