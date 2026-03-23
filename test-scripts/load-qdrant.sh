curl --location --request POST 'http://localhost:8080/api/v1/pipeline' \
--header 'x-api-key: 1847626a-5b46-4d43-827c-25f323d9201b' \
--header 'Content-Type: application/json' \
--data-raw '{
"name" : "apple_10q_qdrant",
  "source" : {
    "fileAttributes" : {
       "unstructuredAttributes" : {
          "fileExtension" : "pdf",
          "preserveFilename" : true
       }
    }
  },
  "destination" : {
    "qdrant": {
        "collectionName": "financial_documents",
        "chunking": {
            "strategy": "recursive",
            "chunkSize": 500,
            "chunkOverlap": 50
        },
        "metadata": {
            "company": "Apple Inc",
            "documentType": "10-Q",
            "filingDate": "2026-01-30"
        },
        "embeddingSecretName": "oss/embedding",
        "qdrantSecretName": "oss/qdrant"
    }
  }
}'

curl -X POST http://localhost:8080/api/v1/pipeline/upload \
  -F "file=@./files/apple-10-Q-jan-30-2026.pdf" \
  -F "pipeline=apple_10q_qdrant"
