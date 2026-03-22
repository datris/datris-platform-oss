echo "=== Query MongoDB ==="
echo ""
echo "--- List all datasets (query the dataset config collection) ---"
curl --location --request POST 'http://localhost:8080/api/v1/query/mongodb' \
--header 'Content-Type: application/json' \
--data-raw '{
  "collection": "oss-dataset",
  "limit": 10
}'

echo ""
echo ""
echo "--- Query with filter ---"
curl --location --request POST 'http://localhost:8080/api/v1/query/mongodb' \
--header 'Content-Type: application/json' \
--data-raw '{
  "collection": "oss-dataset",
  "filter": {"name": "stock_price_postgres"},
  "limit": 1
}'

echo ""
echo ""
echo "--- Query with projection (only return name field) ---"
curl --location --request POST 'http://localhost:8080/api/v1/query/mongodb' \
--header 'Content-Type: application/json' \
--data-raw '{
  "collection": "oss-dataset",
  "projection": {"name": 1, "_id": 0},
  "limit": 10
}'

echo ""
echo ""
echo "--- Blocked query (should fail - uses $where) ---"
curl --location --request POST 'http://localhost:8080/api/v1/query/mongodb' \
--header 'Content-Type: application/json' \
--data-raw '{
  "collection": "oss-dataset",
  "filter": {"$where": "this.name.length > 5"}
}'

echo ""
