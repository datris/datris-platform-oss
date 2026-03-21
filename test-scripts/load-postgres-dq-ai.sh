curl --location --request POST 'http://localhost:8080/api/v1/dataset' \
--header 'x-api-key: 1847626a-5b46-4d43-827c-25f323d9201b' \
--header 'Content-Type: application/json' \
--data-raw '{
"name" : "stock_price_postgres_dq_ai",
  "source" : {
    "fileAttributes" : {
       "csvAttributes" : {
          "delimiter" : ",",
          "encoding" : "UTF-8",
          "header" : true
       }
    },
    "schemaProperties" : {
       "dbName" : "testdb",
       "fields" : [
          {
             "name" : "symbol",
             "type" : "string"
          },
          {
             "name" : "date",
             "type" : "string"
          },
          {
             "name" : "open",
             "type" : "double"
          },
          {
             "name" : "high",
             "type" : "double"
          },
          {
             "name" : "low",
             "type" : "double"
          },
          {
             "name" : "close",
             "type" : "double"
          },
          {
             "name" : "volume",
             "type" : "int"
          },
          {
             "name" : "adj_close",
             "type" : "double"
          }
       ]
    }
  },
  "dataQuality": {
    "aiRule": {
        "instruction": "all price columns (open, high, low, close, adj_close) must be positive and not exceed $1,000,000, volume must be positive, and high must be greater than or equal to low",
        "onFailureIsError": false,
        "sample": true,
        "sampleSize": 200
    }
  },
  "destination" : {
    "database" : {
       "dbName" : "idata",
       "schema" : "test",
       "table" : "stock_price",
       "usePostgres": true
    }
  }
}'

curl -X POST http://localhost:8080/api/v1/dataset/upload \
  -F "file=@./files/stock_price.20170102.dataset.csv" \
  -F "dataset=stock_price_postgres_dq_ai"