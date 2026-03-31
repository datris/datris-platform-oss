package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import io.minio._
import io.minio.messages.DeleteObject
import ai.datris.model.DatrisEnvironment

import java.io.{BufferedReader, ByteArrayInputStream, InputStream, InputStreamReader}
import java.net.URI
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

class MinIOUtility(val client: MinioClient) extends ObjectStoreUtility {
    override def getBucket(url: String): String = {
        // Parse s3://bucket/key or s3a://bucket/key
        val uri = new URI(url)
        uri.getHost
    }

    override def getKey(url: String): String = {
        val uri = new URI(url)
        val path = uri.getPath
        if (path.startsWith("/")) path.substring(1) else path
    }

    override def getURI(path: String): URI = {
        val bucket = getBucket(path)
        val key = getKey(path)
        val endpoint = DatrisEnvironment.current.minIOConfig.endpoint
        new URI(endpoint + "/" + bucket + "/" + key)
    }

    override def getObjectMetadata(bucketName: String, key: String): StoredObjectMetadata = {
        val stat = client.statObject(
            StatObjectArgs.builder().bucket(bucketName).`object`(key).build()
        )
        StoredObjectMetadata(stat.size(), stat.contentType())
    }

    override def readBucketObject(bucketName: String, key: String): Option[String] = {
        val stream = getInputStream(bucketName, key)
        try {
            val reader = new BufferedReader(new InputStreamReader(stream))
            val data = Some(Stream.continually(reader.readLine()).takeWhile(_ != null).mkString("\n"))
            reader.close()
            data
        } finally {
            stream.close()
        }
    }

    override def readBucketObjectFirstRow(bucketName: String, key: String): Option[String] = {
        val stream = getInputStream(bucketName, key)
        try {
            val reader = new BufferedReader(new InputStreamReader(stream))
            val firstRow = Some(reader.readLine())
            reader.close()
            firstRow
        } finally {
            stream.close()
        }
    }

    override def getBufferedReader(bucketName: String, key: String): BufferedReader = {
        val stream = getInputStream(bucketName, key)
        new BufferedReader(new InputStreamReader(stream))
    }

    override def getInputStream(bucketName: String, key: String): InputStream = {
        client.getObject(
            GetObjectArgs.builder().bucket(bucketName).`object`(key).build()
        )
    }

    override def copyBucketObject(sourceBucket: String, sourceKey: String, destinationBucket: String, destinationKey: String): Unit = {
        client.copyObject(
            CopyObjectArgs.builder()
                .bucket(destinationBucket)
                .`object`(destinationKey)
                .source(CopySource.builder().bucket(sourceBucket).`object`(sourceKey).build())
                .build()
        )
    }

    override def writeBucketObject(bucketName: String, key: String, content: String): Unit = {
        val bytes = content.getBytes("UTF-8")
        val stream = new ByteArrayInputStream(bytes)
        client.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .`object`(key)
                .stream(stream, bytes.length.toLong, -1)
                .build()
        )
    }

    override def writeBucketObjectFromStream(bucketName: String, key: String, stream: ByteArrayInputStream, contentLength: Long): Unit = {
        client.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .`object`(key)
                .stream(stream, contentLength, -1)
                .build()
        )
    }

    override def deleteFolder(bucketName: String, key: String): Unit = {
        if (keyExists(bucketName, key)) {
            val keys = listObjects(bucketName, key)
            if (keys.nonEmpty) {
                val objects = keys.map(k => new DeleteObject(k)).asJava
                val results = client.removeObjects(
                    RemoveObjectsArgs.builder().bucket(bucketName).objects(objects).build()
                )
                results.forEach(result => result.get()) // Force evaluation to detect errors
            }
        }
    }

    override def deleteBucketObject(bucketName: String, key: String): Unit = {
        client.removeObject(
            RemoveObjectArgs.builder().bucket(bucketName).`object`(key).build()
        )
    }

    override def listObjects(bucketName: String, key: String): List[String] = {
        val results = client.listObjects(
            ListObjectsArgs.builder().bucket(bucketName).prefix(key).recursive(true).build()
        )
        val keys = new ListBuffer[String]
        results.forEach(result => keys += result.get().objectName())
        keys.toList
    }

    override def listSummaries(bucketName: String, key: String): List[StoredObjectSummary] = {
        val results = client.listObjects(
            ListObjectsArgs.builder().bucket(bucketName).prefix(key).recursive(true).build()
        )
        val summaries = new ListBuffer[StoredObjectSummary]
        results.forEach(result => {
            val item = result.get()
            summaries += StoredObjectSummary(item.objectName(), item.size())
        })
        summaries.toList
    }

    override def keyExists(bucketName: String, key: String): Boolean = {
        val results = client.listObjects(
            ListObjectsArgs.builder().bucket(bucketName).prefix(key).maxKeys(1).build()
        )
        results.iterator().hasNext
    }
}

object MinIOUtilBuilder {
    def build(): ObjectStoreUtility = {
        val endpoint  = DatrisEnvironment.current.minIOConfig.endpoint
        val accessKey = DatrisEnvironment.current.minIOConfig.accessKey
        val secretKey = DatrisEnvironment.current.minIOConfig.secretKey

        val minioClient = MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .build()

        new MinIOUtility(minioClient)
    }
}
