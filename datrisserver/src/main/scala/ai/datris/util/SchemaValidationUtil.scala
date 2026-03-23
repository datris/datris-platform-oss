package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.DatrisException
import org.everit.json.schema.loader.SchemaLoader
import org.json.{JSONArray, JSONObject, JSONTokener}
import org.xml.sax.SAXException

import java.io.{ByteArrayInputStream, StringReader}
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

object SchemaValidationUtil {
    def validateJson(data: String, schemaFileUrl: String): Unit = {
        val jsonSchema = ObjectStoreUtil.readBucketObject(ObjectStoreUtil.getBucket(schemaFileUrl), ObjectStoreUtil.getKey(schemaFileUrl))
            .getOrElse(throw new DatrisException("Could not read the validation schema file: " + schemaFileUrl))

        val schemaObject = new JSONObject(new JSONTokener(jsonSchema))
        val schema = SchemaLoader.load(schemaObject)

        val trimmed = data.trim
        if (trimmed.startsWith("[")) {
            schema.validate(new JSONArray(new JSONTokener(trimmed)))
        } else {
            schema.validate(new JSONObject(new JSONTokener(trimmed)))
        }
    }

    def validateXml(data: String, schemaFileUrl: String): Unit = {
        val xmlSchema = ObjectStoreUtil.readBucketObject(ObjectStoreUtil.getBucket(schemaFileUrl), ObjectStoreUtil.getKey(schemaFileUrl))
            .getOrElse(throw new DatrisException("Could not read the validation schema: " + schemaFileUrl))
        val xmlDataStream = new ByteArrayInputStream(data.getBytes())

        val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        try {
            val schema = schemaFactory.newSchema(new StreamSource(new StringReader(xmlSchema)))
            val validator = schema.newValidator()
            validator.validate(new StreamSource(xmlDataStream))
        }
        catch {
            case e: SAXException =>
                throw new DatrisException("The XML data did not pass the XML Schema validation against the XML schema: " + schemaFileUrl + ", error: " + e.getMessage)
        }
    }
}
