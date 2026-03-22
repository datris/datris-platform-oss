echo "=== Query PostgreSQL ==="
echo ""
echo "--- SELECT with default limit ---"
curl --location --request POST 'http://localhost:8080/api/v1/query/postgres' \
--header 'Content-Type: application/json' \
--data-raw '{
  "sql": "SELECT * FROM test.stock_price",
  "limit": 5
}'

echo ""
echo ""
echo "--- SELECT with WHERE clause ---"
curl --location --request POST 'http://localhost:8080/api/v1/query/postgres' \
--header 'Content-Type: application/json' \
--data-raw '{
  "sql": "SELECT symbol, date, close, volume FROM test.stock_price WHERE volume > 1000000",
  "limit": 10
}'

echo ""
echo ""
echo "--- SELECT with aggregation ---"
curl --location --request POST 'http://localhost:8080/api/v1/query/postgres' \
--header 'Content-Type: application/json' \
--data-raw '{
  "sql": "SELECT symbol, COUNT(*) as cnt, AVG(close) as avg_close FROM test.stock_price GROUP BY symbol",
  "limit": 10
}'

echo ""
echo ""
echo "--- Blocked query (should fail) ---"
curl --location --request POST 'http://localhost:8080/api/v1/query/postgres' \
--header 'Content-Type: application/json' \
--data-raw '{
  "sql": "DELETE FROM test.stock_price WHERE symbol = '\''AAPL'\''"
}'

echo ""
