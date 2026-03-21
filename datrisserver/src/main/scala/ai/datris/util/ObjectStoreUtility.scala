package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import java.io.{BufferedReader, ByteArrayInputStream, InputStream}
import java.net.URI

case class StoredObjectMetadata(contentLength: Long, contentType: String)
case class StoredObjectSummary(key: String, size: Long)

trait ObjectStoreUtility {
    def getBucket(url: String): String

    def getKey(url: String): String

    def getURI(path: String): URI

    def getObjectMetadata(bucketName: String, key: String): StoredObjectMetadata

    def readBucketObject(bucketName: String, key: String): Option[String]

    def readBucketObjectFirstRow(bucketName: String, key: String): Option[String]

    def getBufferedReader(bucketName: String, key: String): BufferedReader

    def getInputStream(bucketName: String, key: String): InputStream

    def copyBucketObject(sourceBucket: String, sourceKey: String, destinationBucket: String, destinationKey: String): Unit

    def writeBucketObject(bucketName: String, key: String, content: String): Unit

    def writeBucketObjectFromStream(bucketName: String, key: String, stream: ByteArrayInputStream, contentLength: Long): Unit

    def deleteFolder(bucketName: String, key: String): Unit

    def deleteBucketObject(bucketName: String, key: String): Unit

    def listObjects(bucketName: String, key: String): List[String]

    def listSummaries(bucketName: String, key: String): List[StoredObjectSummary]

    def keyExists(bucketName: String, key: String): Boolean
}
