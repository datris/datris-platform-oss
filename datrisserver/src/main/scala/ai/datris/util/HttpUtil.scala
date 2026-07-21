package ai.datris.util

import ai.datris.model.DatrisException
import org.apache.http.HttpHeaders
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.util.EntityUtils

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLContext
import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks.{break, breakable}

object HttpUtil {
    def get(url: String, bearerToken: String = null, timeoutMillis: Int = 30000, retry: Boolean = false, retryWaitMillis: Long = 5000): String = {
        if (retry)
            httpWithRetry("get", url, null, null, bearerToken, null, timeoutMillis, retryWaitMillis)
        else
            http("get", url, null, null, bearerToken, null, timeoutMillis)
    }

    def post(
        url: String,
        contentType: String,
        dataToPost: String,
        bearerToken: String = null,
        apiKey: String = null,
        timeoutMillis: Int = 30000,
        retry: Boolean = false,
        retryWaitMillis: Long = 5000
    ): String = {
        if (retry)
            httpWithRetry("post", url, contentType, dataToPost, bearerToken, apiKey, timeoutMillis, retryWaitMillis)
        else
            http("post", url, contentType, dataToPost, bearerToken, apiKey, timeoutMillis)
    }

    private def http(
        method: String,
        url: String,
        contentType: String,
        dataToPost: String,
        bearerToken: String,
        apiKey: String = null,
        timeoutMillis: Int
    ): String = {
        if (method.compareToIgnoreCase("post") == 0)
            doPost(url, contentType, dataToPost, bearerToken, apiKey, timeoutMillis)
        else
            doGet(url, bearerToken, timeoutMillis)
    }

    private def httpWithRetry(
        method: String,
        url: String,
        contentType: String,
        dataToPost: String,
        bearerToken: String,
        apiKey: String = null,
        timeoutMillis: Int,
        retryWaitMillis: Long
    ): String = {
        var response: String = null
        var exception: String = null
        breakable {
            val millisToWait = Seq(retryWaitMillis, retryWaitMillis, 1000)
            for (millis <- millisToWait) {
                try {
                    response = {
                        if (method.compareToIgnoreCase("post") == 0)
                            doPost(url, contentType, dataToPost, bearerToken, apiKey, timeoutMillis)
                        else
                            doGet(url, bearerToken, timeoutMillis)
                    }
                    break
                } catch {
                    case e: Exception =>
                        exception = e.getMessage
                        // Wait and try again
                        Thread.sleep(millis)
                }
            }
        }
        if (response == null)
            throw new DatrisException(exception)
        response
    }

    private def doGet(url: String, bearerToken: String, timeoutMillis: Int): String = {
        val client = getHttpClient(url)

        try {
            val httpGet = {
                val http = new HttpGet(url)
                if (bearerToken != null)
                    http.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                http
            }

            val requestConfig = RequestConfig.custom
            requestConfig.setConnectTimeout(timeoutMillis)
            requestConfig.setConnectionRequestTimeout(timeoutMillis)
            requestConfig.setSocketTimeout(timeoutMillis)
            httpGet.setConfig(requestConfig.build)
            httpGet.addHeader(HttpHeaders.ACCEPT, "*/*")

            val response = client.execute(httpGet)
            if (response.getStatusLine.getStatusCode != 200 && response.getStatusLine.getStatusCode != 201)
                throw new DatrisException("HTTP error, status code: " + response.getStatusLine.getStatusCode.toString)
            EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8.name())
        } finally {
            client.close()
        }
    }

    private def doPost(url: String, contentType: String, dataToPost: String, bearerToken: String, apiKey: String = null, timeoutMillis: Int): String = {
        val client = getHttpClient(url)

        try {
            val httpPost = {
                val http = new HttpPost(url)
                if (bearerToken != null) {
                    http.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                }
                if (apiKey != null) {
                    http.addHeader("x-api-key", apiKey)
                }
                http
            }

            val requestConfig = RequestConfig.custom
            requestConfig.setConnectTimeout(timeoutMillis)
            requestConfig.setConnectionRequestTimeout(timeoutMillis)
            requestConfig.setSocketTimeout(timeoutMillis)
            httpPost.setConfig(requestConfig.build)

            val entity = new StringEntity(dataToPost)
            httpPost.setEntity(entity)
            httpPost.addHeader(HttpHeaders.CONTENT_TYPE, contentType)
            httpPost.addHeader(HttpHeaders.ACCEPT, "*/*")

            val httpResponse = client.execute(httpPost)
            if (httpResponse.getStatusLine.getStatusCode != 200 && httpResponse.getStatusLine.getStatusCode != 201)
                throw new DatrisException("HTTP error, status code: " + httpResponse.getStatusLine.getStatusCode.toString)

            val response = new ListBuffer[String]()
            Loan.withResource(new BufferedReader(new InputStreamReader(httpResponse.getEntity.getContent))) { reader =>
                breakable {
                    while (true) {
                        val line = reader.readLine()
                        if (line == null)
                            break
                        response += line
                    }
                }
            }
            response.mkString
        } finally {
            client.close()
        }
    }

    private def getHttpClient(url: String): CloseableHttpClient = {
        if (url.toLowerCase.startsWith("https")) {
            val sslsf = new SSLConnectionSocketFactory(
                SSLContext.getDefault,
                Array("TLSv1.2"),
                null,
                SSLConnectionSocketFactory.getDefaultHostnameVerifier
            )
            HttpClients.custom().setSSLSocketFactory(sslsf).build()
        } else
            HttpClients.createDefault
    }
}
