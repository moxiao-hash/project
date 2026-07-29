from unittest.mock import patch

from app.retrieval.factory import get_hybrid_index
from app.retrieval.hybrid_index import FastEmbedProvider


def test_fastembed_models_use_explicit_shared_cache_directory() -> None:
    with (
        patch("app.retrieval.hybrid_index.TextEmbedding") as dense,
        patch("app.retrieval.hybrid_index.SparseTextEmbedding") as sparse,
    ):
        FastEmbedProvider(cache_dir="/cache/fastembed")

    assert dense.call_args.kwargs["cache_dir"] == "/cache/fastembed"
    assert sparse.call_args.kwargs["cache_dir"] == "/cache/fastembed"


def test_shared_index_factory_forwards_cache_directory() -> None:
    get_hybrid_index.cache_clear()
    with patch("app.retrieval.factory.QdrantHybridIndex.persistent") as persistent:
        get_hybrid_index("/data/qdrant", "/cache/fastembed")

    persistent.assert_called_once_with("/data/qdrant", "/cache/fastembed")
    get_hybrid_index.cache_clear()
