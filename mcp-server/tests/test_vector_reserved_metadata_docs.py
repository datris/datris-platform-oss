"""Story: Catalog-scoped user access: a restricted user's vector search returns
only chunks written by pipelines they can see
(plans/stories/catalog-scoped-user-access-shared-containers.md), Acceptance,
test_vector_container_rule_docs.py bullet (kept in a new file).

Pins the agent-facing wording: the `datris://pipeline-config-reference`
vector section says `metadata` keys named `source_pipeline` or
`source_dataset` are reserved and ignored."""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def _norm(text):
    return re.sub(r"\s+", " ", text or "").replace("`", "").lower()


RESERVED = "metadata keys named source_pipeline or source_dataset are reserved and ignored"


def test_the_vector_reference_states_the_reserved_metadata_keys():
    reference = _norm(server.PIPELINE_CONFIG_REFERENCE)
    assert RESERVED in reference, "pipeline-config-reference lacks the reserved-metadata-keys sentence"
    # it sits in the vector databases section, before the first vector destination block
    start = reference.index("### vector databases")
    first_store = reference.index("**qdrant:**", start)
    at = reference.index(RESERVED)
    assert start < at < first_store, "the reserved-keys sentence must be in the vector section intro"
