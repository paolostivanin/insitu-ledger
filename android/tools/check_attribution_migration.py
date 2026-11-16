#!/usr/bin/env python3
"""Exercise the additive Room migrations (9→10, 10→11) against exported schemas using SQLite."""
import json
import re
import sqlite3
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[1]
SCHEMAS = ANDROID / "app/schemas/com.insituledger.app.data.local.db.AppDatabase"
SOURCE = (ANDROID / "app/src/main/java/com/insituledger/app/data/local/db/AppDatabase.kt").read_text()


def database(version):
    connection = sqlite3.connect(":memory:")
    entities = json.loads((SCHEMAS / f"{version}.json").read_text())["database"]["entities"]
    for entity in entities:
        table = entity["tableName"]
        connection.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity["indices"]:
            connection.execute(index["createSql"].replace("${TABLE_NAME}", table))
    return connection, entities


def migration_statements(start, end):
    # The body of `val MIGRATION_<start>_<end> = object : Migration(...) { ... }`,
    # up to the next migration declaration (or the end of the companion).
    block = SOURCE.split(f"val MIGRATION_{start}_{end} =", 1)[1]
    block = re.split(r"val MIGRATION_\d+_\d+ =", block, maxsplit=1)[0]
    return re.findall(r'db\.execSQL\("([^"]+)"\)', block)


def check(start, end, expected_statements, new_null_columns):
    """Seed one row per table at `start`, run the migration, compare with `end`.

    new_null_columns maps table → columns the migration adds; existing rows
    must read NULL there.
    """
    old, entities = database(start)
    new, _ = database(end)
    for entity in entities:
        fields = entity["fields"]
        values = [None if not field["notNull"] else 0 if field["affinity"] in ("INTEGER", "REAL") else "cached" for field in fields]
        columns = ",".join(f'"{field["columnName"]}"' for field in fields)
        marks = ",".join("?" for _ in fields)
        old.execute(f'INSERT INTO "{entity["tableName"]}" ({columns}) VALUES ({marks})', values)
    old.commit()
    before = {entity["tableName"]: old.execute(f'SELECT * FROM "{entity["tableName"]}"').fetchall() for entity in entities}
    statements = migration_statements(start, end)
    assert len(statements) == expected_statements, (start, end, statements)
    with old:
        for statement in statements:
            old.execute(statement)
    for entity in entities:
        table = entity["tableName"]
        assert old.execute(f'PRAGMA table_info("{table}")').fetchall() == new.execute(f'PRAGMA table_info("{table}")').fetchall(), (start, end, table)
        columns = ",".join(f'"{field["columnName"]}"' for field in entity["fields"])
        assert old.execute(f'SELECT {columns} FROM "{table}"').fetchall() == before[table], (start, end, table)
        assert old.execute(f'PRAGMA index_list("{table}")').fetchall() == new.execute(f'PRAGMA index_list("{table}")').fetchall(), (start, end, table)
        assert old.execute(f'PRAGMA foreign_key_list("{table}")').fetchall() == new.execute(f'PRAGMA foreign_key_list("{table}")').fetchall(), (start, end, table)
        added = new_null_columns.get(table)
        if added:
            selected = ",".join(f'"{column}"' for column in added)
            assert old.execute(f'SELECT {selected} FROM "{table}"').fetchone() == (None,) * len(added), (start, end, table)
    print(f"Room {start}→{end} matches the exported schema and preserves every table, index and pending operation.")


check(9, 10, 2, {
    "transactions": ["created_by_name"],
    "scheduled_transactions": ["created_by_name"],
})
check(10, 11, 3, {
    "transactions": ["scheduled_id", "occurrence", "occurrence_total"],
})
