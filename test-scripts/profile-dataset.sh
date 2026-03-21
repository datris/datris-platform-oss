curl -s -X POST http://localhost:8080/api/v1/dataset/profile \
  -F "file=@./files/stock_price.20170102.dataset.csv" \
  -F "delimiter=," \
  -F "header=true" \
  -F "sampleSize=200" | python3 -m json.tool
