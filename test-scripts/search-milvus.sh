echo "=== Search Milvus ==="
echo ""
curl --location --request POST 'http://localhost:8080/api/v1/search/milvus' \
--header 'Content-Type: application/json' \
--data-raw '{
  "query": "quarterly revenue projections",
  "collection": "financial_documents",
  "embeddingSecretName": "oss/embedding",
  "milvusSecretName": "oss/milvus",
  "topK": 5
}'

echo ""
