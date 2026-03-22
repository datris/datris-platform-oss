echo "=== Kill Job ==="
echo ""
echo "--- Kill a running job (replace PIPELINE_TOKEN with actual token) ---"
curl --location --request POST 'http://localhost:8080/api/v1/job/kill' \
--header 'Content-Type: application/json' \
--data-raw '{
  "pipelineToken": "PIPELINE_TOKEN"
}'

echo ""
echo ""
echo "--- Kill a non-existent job (should fail) ---"
curl --location --request POST 'http://localhost:8080/api/v1/job/kill' \
--header 'Content-Type: application/json' \
--data-raw '{
  "pipelineToken": "does-not-exist"
}'

echo ""
