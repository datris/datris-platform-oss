package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.{PipelineMetadata, DatrisEnvironment, DatrisException}
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.utils.IOUtils

import java.io.{BufferedInputStream, ByteArrayInputStream}
import scala.util.control.Breaks._

class PipelineMetadataUtil(statusUtil: StatusUtil) {
    def read(bucket: String, key: String): PipelineMetadata = {
        if(key.endsWith(".metadata.json")) {
            // Read the metadata file and create the PipelineMetadata object
            val json = ObjectStoreUtil.readBucketObject(bucket, key).getOrElse(
                throw new DatrisException("Could not read metadata file: " + key + ", from bucket: " + bucket))
            val gson = new Gson
            val metadata = gson.fromJson(json, classOf[PipelineMetadata])
            if (metadata == null)
                throw new DatrisException("Could not parse json metadata in the file: " + key)
            if(metadata.dataFilePath != null)
                metadata.copy(bulkUpload = true)
            else
                metadata.copy(bulkUpload = false)
        }
        else if(key.toLowerCase.endsWith(".zip") ||
            key.toLowerCase.endsWith(".gz") ||
            key.toLowerCase.endsWith(".tar") ||
            key.toLowerCase.endsWith(".jar"))
        {
            uncompress(bucket, key)
        }
        else {
            // Pull the metadata from the data filename.  [dataset-name].[publisher-token].[whatever].pipeline.[csv|json|xml|...]
            try {
                val filename = key.substring(key.lastIndexOf('/') + 1)
                val filepath = "s3://" + bucket + "/" + key.substring(0, key.lastIndexOf('/')) + "/"
                val (pipeline, publisherToken) = parseDatasetPublisherTokenFromKey(key)
                PipelineMetadata(pipeline,
                    filename,
                    filepath,
                    publisherToken,
                    bulkUpload = false)
            } catch {
                case e: Exception =>
                    throw new DatrisException("Could not parse the pipeline and/or filename from the bucket key name.  The format required: [dataset-name].[publisher-token].[whatever].pipeline.[csv|json|xml|...]")
            }
        }
    }

    def getFiles(metadata: PipelineMetadata): List[String] = {
        if(metadata.bulkUpload) {
            statusUtil.info("processing", "Bulk file upload")
            val keys = ObjectStoreUtil.listObjects(ObjectStoreUtil.getBucket(metadata.dataFilePath), ObjectStoreUtil.getKey(metadata.dataFilePath))
            keys.map(key => "s3://" + ObjectStoreUtil.getBucket(metadata.dataFilePath) + "/" + key)
                .filterNot(_.endsWith("/"))
                .filterNot(_.endsWith(".metadata.json"))
        }
        else
            List(metadata.dataFilePath + metadata.dataFileName)
    }

    private def uncompress(bucket: String, key: String): PipelineMetadata = {
        statusUtil.info("processing","Uncompressing bucket: " + bucket + ", key: " + key)

        val (pipeline, publisherToken) = parseDatasetPublisherTokenFromKey(key)
        statusUtil.info("processing","Pipeline name: " + pipeline)

        val inputStream = ObjectStoreUtil.getInputStream(bucket, key)
        val tempWriteDirectory = "s3://" + DatrisEnvironment.values.environment + "-raw/temp/" + GuidV5.nameUUIDFrom(System.currentTimeMillis().toString).toString + "/"

        // .gz files extract to only one file
        if(key.toLowerCase.endsWith(".gz")) {
            val bufferedInputStream = new BufferedInputStream(inputStream)
            val gZipInputStream = new GzipCompressorInputStream(bufferedInputStream)
            val byteArray = gZipInputStream.readAllBytes()

            writeArchivedFile(byteArray, tempWriteDirectory)

            bufferedInputStream.close()
            gZipInputStream.close()
        }
        else {
            val bufferedInputStream = new BufferedInputStream(inputStream)
            val archiveInputStream: ArchiveInputStream[_ <: org.apache.commons.compress.archivers.ArchiveEntry] = {
                try {
                    new ArchiveStreamFactory().createArchiveInputStream(bufferedInputStream)
                } catch {
                    case _: Exception =>
                        throw new DatrisException("Archive type for key: " + key + " is not supported")
                }
            }

            // Write out all of the files in the archive to the temp directory
            breakable {
                while(true) {
                    val archiveEntry = archiveInputStream.getNextEntry
                    if(archiveEntry == null)
                        break

                    // Ignore directories and junk entries for compressed files
                    if(! archiveEntry.isDirectory &&
                        ! archiveEntry.getName.startsWith("__MAC") &&
                        ! archiveEntry.getName.startsWith("META-INF") &&
                        ! archiveEntry.getName.startsWith("./._"))
                    {
                        statusUtil.info("processing","Archive file: " + archiveEntry.getName)
                        val byteArray = archiveInputStream.readAllBytes()
                        writeArchivedFile(byteArray, tempWriteDirectory)
                    }
                }
            }
        }

        inputStream.close()

        PipelineMetadata(
            pipeline,
            null,
            tempWriteDirectory,
            publisherToken,
            bulkUpload = true)
    }

    private def writeArchivedFile(byteArray: Array[Byte], writeDirectory: String): Unit = {
        val byteArrayInputStream = new ByteArrayInputStream(byteArray)

        // Write the temp file name
        val tempFilename = GuidV5.nameUUIDFrom(System.currentTimeMillis().toString).toString + ".tmp"
        statusUtil.info("processing","Writing archive file to : " + writeDirectory + tempFilename)
        ObjectStoreUtil.writeBucketObjectFromStream(
            ObjectStoreUtil.getBucket(writeDirectory),
            ObjectStoreUtil.getKey(writeDirectory + tempFilename),
            byteArrayInputStream,
            byteArray.length.toLong
        )
        byteArrayInputStream.close()
    }

    private def parseDatasetPublisherTokenFromKey(key: String): (String, String) = {
        // Pull the metadata from the data filename.  [dataset-name].[publisher-token].[whatever].pipeline.[csv|json|xml|...]
        val filename = key.substring(key.lastIndexOf('/') + 1)
        val tokens = filename.split("\\.")
        val pipeline = tokens(0)

        val publisherToken = {
            if (GuidV5.isValidUUID(tokens(1)))
                tokens(1)
            else
                GuidV5.nameUUIDFrom(System.currentTimeMillis().toString).toString
        }
        (pipeline, publisherToken)
    }
}
