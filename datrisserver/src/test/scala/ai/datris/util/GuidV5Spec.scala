package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import org.scalatest.funsuite.AnyFunSuite

import java.util.UUID

class GuidV5Spec extends AnyFunSuite {

    test("random UUID strings validate") {
        assert(GuidV5.isValidUUID(UUID.randomUUID().toString))
    }

    test("canonical fixed UUID validates") {
        assert(GuidV5.isValidUUID("123e4567-e89b-12d3-a456-426614174000"))
    }

    test("non-UUID strings do not validate") {
        assert(!GuidV5.isValidUUID("not-a-uuid"))
        assert(!GuidV5.isValidUUID(""))
        assert(!GuidV5.isValidUUID("123e4567e89b12d3a456426614174000")) // no dashes
    }
}
