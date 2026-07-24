package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapPromptFragment
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

class TapPromptInjectorSpec extends AnyFunSuite {

    private def frag(
        key: String,
        content: String = "some content",
        aliases: List[String] = Nil,
        enabled: Boolean = true
    ): TapPromptFragment =
        TapPromptFragment(key = key, aliases = aliases.asJava, content = content, enabled = enabled)

    test("keyword matching finds fragments by key and alias, word-boundary, case-insensitive") {
        val fragments = List(frag("NOAA", aliases = List("weather")), frag("Stripe"))
        val byKey = TapPromptInjector.matchFragments("pull noaa data", fragments)
        assert(byKey.map(_.key) == List("NOAA"))
        val byAlias = TapPromptInjector.matchFragments("I need Weather data", fragments)
        assert(byAlias.map(_.key) == List("NOAA"))
        assert(TapPromptInjector.matchFragments("stripes are nice", fragments).isEmpty)
    }

    test("data-sources reserved key is excluded from keyword matching") {
        val fragments = List(frag(TapPromptInjector.DataSourcesKey, aliases = List("sources")))
        assert(TapPromptInjector.matchFragments("use data-sources please", fragments).isEmpty)
        assert(TapPromptInjector.matchFragments("which sources do we have", fragments).isEmpty)
    }

    test("dataSourcesFrom returns the registry content when present and enabled") {
        val fragments = List(frag("AWS"), frag(TapPromptInjector.DataSourcesKey, content = "  - NOAA NWS\n"))
        assert(TapPromptInjector.dataSourcesFrom(fragments).contains("- NOAA NWS"))
    }

    test("dataSourcesFrom is case-insensitive on the reserved key") {
        val fragments = List(frag("Data-Sources", content = "- FRED"))
        assert(TapPromptInjector.dataSourcesFrom(fragments).contains("- FRED"))
    }

    test("dataSourcesFrom returns None when disabled, blank, null, or absent") {
        assert(TapPromptInjector.dataSourcesFrom(Nil).isEmpty)
        assert(TapPromptInjector.dataSourcesFrom(List(frag("AWS"))).isEmpty)
        assert(TapPromptInjector.dataSourcesFrom(List(frag(TapPromptInjector.DataSourcesKey, enabled = false))).isEmpty)
        assert(TapPromptInjector.dataSourcesFrom(List(frag(TapPromptInjector.DataSourcesKey, content = "   \n"))).isEmpty)
        assert(TapPromptInjector.dataSourcesFrom(List(frag(TapPromptInjector.DataSourcesKey, content = null))).isEmpty)
    }
}
