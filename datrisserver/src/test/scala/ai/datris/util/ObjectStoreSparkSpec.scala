package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.ObjectStore
import org.scalatest.funsuite.AnyFunSuite

class ObjectStoreSparkSpec extends AnyFunSuite {

    // All fixtures pin destinationBucketOverride so resolveBucket never falls
    // back to DatrisEnvironment.current (not initialized in unit tests).
    private def dest(prefix: String, bucket: String = "bucket-a"): ObjectStore =
        ObjectStore(prefixKey = prefix, destinationBucketOverride = bucket)

    test("normalizePrefix strips leading/trailing slashes and whitespace, null-safe") {
        assert(ObjectStoreSpark.normalizePrefix(null) == "")
        assert(ObjectStoreSpark.normalizePrefix("") == "")
        assert(ObjectStoreSpark.normalizePrefix(" /city-forecasts/ ") == "city-forecasts")
        assert(ObjectStoreSpark.normalizePrefix("a/b/c") == "a/b/c")
    }

    test("equal prefixes in the same bucket overlap") {
        assert(ObjectStoreSpark.destinationsOverlap(dest("city-forecasts"), dest("city-forecasts")))
        assert(ObjectStoreSpark.destinationsOverlap(dest("city-forecasts/"), dest("/city-forecasts")))
    }

    test("nested prefixes overlap in both directions") {
        assert(ObjectStoreSpark.destinationsOverlap(dest("city"), dest("city/2026")))
        assert(ObjectStoreSpark.destinationsOverlap(dest("city/2026"), dest("city")))
    }

    test("sibling prefixes sharing a string prefix do not overlap") {
        assert(!ObjectStoreSpark.destinationsOverlap(dest("city"), dest("city-forecasts")))
        assert(!ObjectStoreSpark.destinationsOverlap(dest("city-forecasts"), dest("city-forecasts-v2")))
    }

    test("same prefix in different buckets does not overlap") {
        assert(!ObjectStoreSpark.destinationsOverlap(dest("city-forecasts", "bucket-a"), dest("city-forecasts", "bucket-b")))
    }

    test("an empty prefix spans the bucket and overlaps everything in it") {
        assert(ObjectStoreSpark.destinationsOverlap(dest(""), dest("city-forecasts")))
        assert(ObjectStoreSpark.destinationsOverlap(dest("city-forecasts"), dest(null)))
        assert(!ObjectStoreSpark.destinationsOverlap(dest("", "bucket-a"), dest("city", "bucket-b")))
    }
}
