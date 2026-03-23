curl --location --request POST 'http://localhost:8080/api/v1/pipeline' \
--header 'x-api-key: 1847626a-5b46-4d43-827c-25f323d9201b' \
--header 'Content-Type: application/json' \
--data-raw '{
"name" : "stock_price_postgres_ai_transform",
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
  "transformation": {
    "aiTransformation": {
        "instruction": "convert all date values from YYYY-MM-DD format to MM/DD/YYYY format"
    }
  },
  "destination" : {
    "database" : {
       "dbName" : "datris",
       "schema" : "test",
       "table" : "stock_price_transformed",
       "usePostgres": true
    }
  }
}'

curl -X POST http://localhost:8080/api/v1/pipeline/upload \
  -F "file=@./files/stock_price.20170102.small.dataset.csv" \
  -F "pipeline=stock_price_postgres_ai_transform"
