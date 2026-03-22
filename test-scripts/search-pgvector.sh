echo "=== Search pgvector ==="
echo ""
curl --location --request POST 'http://localhost:8080/api/v1/search/pgvector' \
--header 'Content-Type: application/json' \
--data-raw '{
  "query": "quarterly revenue projections",
  "table": "financial_documents",
  "schema": "public",
  "embeddingSecretName": "oss/openai-embedding",
  "postgresSecretName": "oss/pgvector",
  "topK": 5
}'

echo ""
