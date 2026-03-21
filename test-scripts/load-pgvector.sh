curl --location --request POST 'http://localhost:8080/api/v1/dataset' \
--header 'x-api-key: 1847626a-5b46-4d43-827c-25f323d9201b' \
--header 'Content-Type: application/json' \
--data-raw '{
"name" : "apple_10q_pgvector",
  "source" : {
    "fileAttributes" : {
       "unstructuredAttributes" : {
          "fileExtension" : "pdf",
          "preserveFilename" : true
       }
    }
  },
  "destination" : {
    "pgvector": {
        "tableName": "financial_documents",
        "schemaName": "public",
        "chunking": {
            "strategy": "recursive",
            "chunkSize": 500,
            "chunkOverlap": 50
        },
        "metadata": {
            "company": "Apple Inc",
            "document_type": "10-Q",
            "filing_date": "2026-01-30"
        },
        "embeddingSecretName": "oss/embedding",
        "postgresSecretName": "oss/pgvector"
    }
  }
}'

curl -X POST http://localhost:8080/api/v1/dataset/upload \
  -F "file=@./files/apple-10-Q-jan-30-2026.pdf" \
  -F "dataset=apple_10q_pgvector"
