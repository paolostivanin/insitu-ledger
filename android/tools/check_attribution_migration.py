#!/usr/bin/env python3
"""Exercise the Room 9→10 migration against exported schemas using SQLite."""
import json
import re
import sqlite3
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[1]
SCHEMAS = ANDROID / "app/schemas/com.insituledger.app.data.local.db.AppDatabase"


def database(version):
    connection = sqlite3.connect(":memory:")
    entities = json.loads((SCHEMAS / f"{version}.json").read_text())["database"]["entities"]
    for entity in entities:
        table = entity["tableName"]
        connection.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity["indices"]:
            connection.execute(index["createSql"].replace("${TABLE_NAME}", table))
    return connection, entities


old, entities = database(9)
new, _ = database(10)
for entity in entities:
    fields = entity["fields"]
    values = [None if not field["notNull"] else 0 if field["affinity"] in ("INTEGER", "REAL") else "cached" for field in fields]
    columns = ",".join(f'"{field["columnName"]}"' for field in fields)
    marks = ",".join("?" for _ in fields)
    old.execute(f'INSERT INTO "{entity["tableName"]}" ({columns}) VALUES ({marks})', values)
old.commit()
before = {entity["tableName"]: old.execute(f'SELECT * FROM "{entity["tableName"]}"').fetchall() for entity in entities}
source = (ANDROID / "app/src/main/java/com/insituledger/app/data/local/db/AppDatabase.kt").read_text()
block = source.split("val MIGRATION_9_10 =", 1)[1].split("val MIGRATION_1_2", 1)[0]
statements = re.findall(r'db\.execSQL\("([^"]+)"\)', block)
assert len(statements) == 2
with old:
    for statement in statements:
        old.execute(statement)
for entity in entities:
    table = entity["tableName"]
    assert old.execute(f'PRAGMA table_info("{table}")').fetchall() == new.execute(f'PRAGMA table_info("{table}")').fetchall(), table
    columns = ",".join(f'"{field["columnName"]}"' for field in entity["fields"])
    assert old.execute(f'SELECT {columns} FROM "{table}"').fetchall() == before[table], table
    assert old.execute(f'PRAGMA index_list("{table}")').fetchall() == new.execute(f'PRAGMA index_list("{table}")').fetchall(), table
    if table in ("transactions", "scheduled_transactions"):
        assert old.execute(f'SELECT created_by_name FROM "{table}"').fetchone() == (None,)
print("Room 9→10 matches the exported schema and preserves every table, index and pending operation.")
