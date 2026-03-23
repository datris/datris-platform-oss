curl -X POST http://localhost:8080/api/v1/pipeline/generate \
  --header 'x-api-key: 1847626a-5b46-4d43-827c-25f323d9201b' \
  -F "file=@./files/stock_price.20170102.dataset.csv"
