echo "=== Search Weaviate ==="
echo ""
curl --location --request POST 'http://localhost:8080/api/v1/search/weaviate' \
--header 'Content-Type: application/json' \
--data-raw '{
  "query": "quarterly revenue projections",
  "className": "FinancialDocuments",
  "embeddingSecretName": "oss/embedding",
  "weaviateSecretName": "oss/weaviate",
  "topK": 5
}'

echo ""
