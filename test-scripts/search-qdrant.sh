echo "=== Search Qdrant ==="
echo ""
curl --location --request POST 'http://localhost:8080/api/v1/search/qdrant' \
--header 'Content-Type: application/json' \
--data-raw '{
  "query": "quarterly revenue projections",
  "collection": "financial_documents",
  "embeddingSecretName": "oss/openai-embedding",
  "qdrantSecretName": "oss/qdrant",
  "topK": 5
}'

echo ""
