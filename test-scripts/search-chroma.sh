echo "=== Search Chroma ==="
echo ""
curl --location --request POST 'http://localhost:8080/api/v1/search/chroma' \
--header 'Content-Type: application/json' \
--data-raw '{
  "query": "quarterly revenue projections",
  "collection": "financial_documents",
  "embeddingSecretName": "oss/openai-embedding",
  "chromaSecretName": "oss/chroma",
  "topK": 5
}'

echo ""
