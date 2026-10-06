from coach.memory.db import MIGRATIONS, connect, migrate
from coach.memory.store import normalize_tag

from .conftest import at


def test_migrations_are_idempotent(tmp_path):
    path = str(tmp_path / "c.db")
    conn = connect(path)
    assert migrate(conn) == len(MIGRATIONS)
    versions = conn.execute("SELECT COUNT(*) FROM schema_version").fetchone()[0]
    assert versions == len(MIGRATIONS)
    conn.close()
    conn2 = connect(path)  # reopen: no re-application
    assert conn2.execute("SELECT COUNT(*) FROM schema_version").fetchone()[0] == len(MIGRATIONS)
    assert conn2.execute("PRAGMA journal_mode").fetchone()[0] == "wal"


def test_fts_search(store):
    store.add_message(at("2026-10-05", "10:00"), "in", "text", "Chess club ke paas sev parmal milta hai")
    store.add_message(at("2026-10-05", "10:01"), "in", "text", "Aaj walk ki")
    hits = store.search_messages("sev")
    assert len(hits) == 1 and "parmal" in hits[0]["text"]
    assert store.search_messages("'; DROP TABLE messages; --") == []


def test_normalize_tag():
    assert normalize_tag(" Chess Club ") == "chess_club"
    assert normalize_tag("office-party!!") == "office_party"


def test_profile_rejects_unknown_keys(store):
    import pytest

    with pytest.raises(KeyError):
        store.set_profile(at("2026-10-05", "10:00"), not_a_key=1)
