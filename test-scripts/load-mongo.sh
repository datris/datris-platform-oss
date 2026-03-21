curl --location --request POST 'http://localhost:8080/api/v1/dataset' \
--header 'x-api-key: 1847626a-5b46-4d43-827c-25f323d9201b' \
--header 'Content-Type: application/json' \
--data-raw '{
 "name":"stock_price_json_mongodb",
  "destination":{
    "database":{
       "dbName":"testdb",
       "table":"stock_price",
       "useMongoDB": true
    }
  },
  "source":{
    "fileAttributes":{
       "jsonAttributes":{
          "everyRowContainsObject":false
       }
    },
    "schemaProperties":{
       "dbName":"testdb",
       "fields":[
          {
             "name":"_json",
             "type":"string"
          }
       ]
    }
  }
}'

curl -X POST http://localhost:8080/api/v1/dataset/upload \
  -F "file=@./files/stock_price_json.20170102.dataset.json" \
  -F "dataset=stock_price_json_mongodb"