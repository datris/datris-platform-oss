package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import org.slf4j.{Logger, LoggerFactory}

object PyPIContextUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Fallback library-context loader for /tap/fix when web search is OFF. Hits
      * pypi.org's JSON metadata for up to 3 third-party imports, then scrapes the
      * project_urls Documentation/Homepage page, strips HTML, and truncates to 2000
      * chars per package. The output is prepended to the AI fix prompt as
      * `PACKAGE INFO:` blocks so the model has *some* current context for stale-API
      * errors. When web search is on, the model does this itself far more
      * effectively (real search, not a regex scrape) — this exists for offline-key
      * deployments and as a graceful degradation. */
    def fetchPackageContextFromPyPI(script: String): String = {
        val importPattern = """(?:import|from)\s+(\w+)""".r
        val imports = importPattern.findAllMatchIn(script).map(_.group(1)).toSet
        val standardLibs = Set("os", "sys", "json", "datetime", "io", "re", "math", "time", "urllib", "collections", "itertools", "functools")
        val thirdPartyImports = imports -- standardLibs

        val contextParts = thirdPartyImports.take(3).flatMap { pkg =>
            try {
                logger.info("Fetching PyPI info for package: " + pkg)
                val client = org.apache.http.impl.client.HttpClients.createDefault()
                try {
                    val pypiReq = new org.apache.http.client.methods.HttpGet("https://pypi.org/pypi/" + pkg + "/json")
                    pypiReq.setHeader("User-Agent", "datris-platform/1.0")
                    val pypiResp = client.execute(pypiReq)
                    val pypiBody = org.apache.http.util.EntityUtils.toString(pypiResp.getEntity)

                    if (pypiResp.getStatusLine.getStatusCode == 200) {
                        val gson3 = new Gson
                        val pypiData = gson3.fromJson(pypiBody, classOf[java.util.Map[String, Any]])
                        val info = pypiData.get("info").asInstanceOf[java.util.Map[String, Any]]
                        val version = Option(info.get("version")).map(_.toString).getOrElse("unknown")
                        val summary = Option(info.get("summary")).map(_.toString).getOrElse("")
                        val description = Option(info.get("description")).map(_.toString).getOrElse("")
                        val homePage = Option(info.get("home_page")).map(_.toString).getOrElse("")
                        val pipName = Option(info.get("name")).map(_.toString).getOrElse(pkg)

                        val docsUrl = {
                            val projectUrls =
                                Option(info.get("project_urls")).map(_.asInstanceOf[java.util.Map[String, Any]]).getOrElse(new java.util.HashMap())
                            Option(projectUrls.get("Documentation")).map(_.toString)
                                .orElse(Option(projectUrls.get("Docs")).map(_.toString))
                                .orElse(Option(projectUrls.get("Homepage")).map(_.toString))
                                .orElse(if (homePage.nonEmpty) Some(homePage) else None)
                        }

                        val docsExcerpt = docsUrl.flatMap { url =>
                            try {
                                val docsReq = new org.apache.http.client.methods.HttpGet(url)
                                docsReq.setHeader("User-Agent", "datris-platform/1.0")
                                val docsResp = client.execute(docsReq)
                                if (docsResp.getStatusLine.getStatusCode == 200) {
                                    val html = org.apache.http.util.EntityUtils.toString(docsResp.getEntity)
                                    val text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim
                                    Some(text.take(2000))
                                } else None
                            } catch {
                                case e: Exception =>
                                    logger.debug("Docs page fetch failed for " + url, e)
                                    None
                            }
                        }.getOrElse("")

                        val contextText = if (docsExcerpt.nonEmpty) docsExcerpt else description.take(2000)

                        Some(s"PACKAGE INFO: $pipName v$version (pip install $pipName)\n$summary\n$contextText")
                    } else None
                } finally {
                    client.close()
                }
            } catch {
                case e: Exception =>
                    logger.warn("Failed to fetch PyPI info for " + pkg + ": " + e.getMessage)
                    None
            }
        }
        if (contextParts.nonEmpty) contextParts.mkString("\n\n") + "\n\n" else ""
    }
}
