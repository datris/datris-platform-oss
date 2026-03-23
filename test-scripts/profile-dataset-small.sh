curl -s -X POST http://localhost:8080/api/v1/pipeline/profile \
  -F "file=@./files/stock_price.20170102.small.dataset.csv" \
  -F "delimiter=," \
  -F "header=true" | python3 -m json.tool
