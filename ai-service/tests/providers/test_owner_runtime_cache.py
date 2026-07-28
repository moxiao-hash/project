from app.providers.owner_runtime_cache import OwnerRuntimeCache


def test_cache_preserves_recent_values_and_evicts_idle_entries() -> None:
    now = [0.0]
    cache = OwnerRuntimeCache[str](max_entries=2, idle_ttl_seconds=10, clock=lambda: now[0])
    cache.put("a", "A")
    now[0] = 5
    assert cache.get("a") == "A"
    now[0] = 16
    assert cache.get("a") is None


def test_cache_is_bounded_and_discards_least_recently_used() -> None:
    now = [0.0]
    cache = OwnerRuntimeCache[str](max_entries=2, idle_ttl_seconds=100, clock=lambda: now[0])
    cache.put("a", "A")
    now[0] = 1
    cache.put("b", "B")
    now[0] = 2
    assert cache.get("a") == "A"
    now[0] = 3
    cache.put("c", "C")

    assert cache.get("a") == "A"
    assert cache.get("b") is None
    assert cache.get("c") == "C"
