package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.CodeRepoConfig
import ai.datris.util.{CodeRepoConfigIO, TapReferenceScan, TapScriptOwnership}
import com.google.gson.{Gson, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** `GET /code-repo` always carries `earlierPathPrefixes`, an empty list when
  * none are recorded, including for repository settings stored before the
  * field existed; reading never rewrites the stored document.
  */
class CodeRepoEarlierPrefixesResponseSpec extends AnyFunSuite {

    private val StoredWithoutField =
        "{\"name\":\"default\",\"provider\":\"github\",\"repo\":\"o/r\",\"apiBaseUrl\":\"https://api.github.com\"," +
            "\"branch\":\"main\",\"pathPrefix\":\"taps/\",\"authSecretName\":\"s\",\"enabled\":true}"

    private val StoredWithList =
        "{\"name\":\"default\",\"repo\":\"o/r\",\"pathPrefix\":\"datris-taps/\",\"authSecretName\":\"s\",\"enabled\":true," +
            "\"earlierPathPrefixes\":[\"taps/\",\"old/\"]}"

    private def listIn(responseBody: String): Option[List[String]] = {
        val obj = JsonParser.parseString(responseBody).getAsJsonObject
        if (!obj.has("earlierPathPrefixes")) None
        else Some(obj.getAsJsonArray("earlierPathPrefixes").asScala.map(_.getAsString).toList)
    }

    test("settings stored without the field produce a response with an empty list") {
        val stored = CodeRepoConfigIO.parse(StoredWithoutField)
        assert(stored.earlierPathPrefixes != null && stored.earlierPathPrefixes.isEmpty)
        assert(stored.pathPrefix == "taps/" && stored.repo == "o/r" && stored.enabled)
        assert(listIn(CodeRepoAPIController.getBody(Some(stored))) == Some(Nil))
    }

    test("the response carries an empty list when nothing is stored, or a value with a null list is shown") {
        assert(listIn(CodeRepoAPIController.getBody(None)) == Some(Nil))
        val raw = new Gson().fromJson(StoredWithoutField, classOf[CodeRepoConfig])
        assert(raw.earlierPathPrefixes == null, "Gson leaves the absent field null")
        assert(listIn(CodeRepoAPIController.getBody(Some(raw))) == Some(Nil))
    }

    test("a stored list round-trips into the response") {
        val stored = CodeRepoConfigIO.parse(StoredWithList)
        assert(listIn(CodeRepoAPIController.getBody(Some(stored))) == Some(List("taps/", "old/")))
    }

    test("a PUT body without the field keeps the stored list") {
        val stored = CodeRepoConfigIO.parse(StoredWithList)
        val incoming = new Gson().fromJson("{\"repo\":\"o/r\",\"pathPrefix\":\"datris-taps/\",\"enabled\":true}", classOf[CodeRepoConfig])
        val saved = CodeRepoAPIController.withEarlierPrefixes(Some(stored), incoming)
        assert(saved.earlierPathPrefixes.asScala.toList == List("taps/", "old/"))
        // and over settings stored without the field, the saved list is empty, not null
        val fromOld = CodeRepoAPIController.withEarlierPrefixes(Some(CodeRepoConfigIO.parse(StoredWithoutField)), incoming.copy(pathPrefix = "taps/"))
        assert(fromOld.earlierPathPrefixes != null && fromOld.earlierPathPrefixes.isEmpty)
    }

    test("the other readers treat settings stored without the field as no earlier prefixes") {
        val raw = new Gson().fromJson(StoredWithoutField, classOf[CodeRepoConfig])
        assert(TapScriptOwnership.prefixesOf(raw).toList == List("taps/"))
        assert(TapScriptOwnership.earlierOf(raw).isEmpty)
        assert(TapReferenceScan.storedOwnPrefixes(Some(raw)).map(_.toList) == Some(List("taps/")))
        assert(TapReferenceScan.storedOwnPrefixes(Some(CodeRepoConfigIO.parse(StoredWithoutField))).map(_.toList) == Some(List("taps/")))
    }
}
