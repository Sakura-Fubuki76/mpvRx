"""Regression checks for the fork's SQL migrations (run with Python's standard library).

These exercise the actual SQL literals in DatabaseModule.kt against SQLite; they do
not replace Android/Room schema validation or a device upgrade test.
"""

import json
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "app/src/main/java/app/gyrolet/mpvrx/di/DatabaseModule.kt"
DATABASE = ROOT / "app/src/main/java/app/gyrolet/mpvrx/database/MpvRxDatabase.kt"
SQL_LITERAL = re.compile(
    r'db\.execSQL\(\s*(?:"""(.*?)"""|("(?:\\.|[^"\\])*"))'
    r'(?:\.trimIndent\(\))?\s*\)',
    re.DOTALL,
)


def migration_body(source, start, end):
    name = f"MIGRATION_{start}_{end}"
    matches = list(re.finditer(rf"^val {name}\s*=", source, re.MULTILINE))
    if len(matches) != 1:
        raise AssertionError(f"Expected one definition of {name}, got {len(matches)}")
    remainder = source[matches[0].end():]
    boundary = re.search(r"^val (?:MIGRATION_\d+_\d+|DatabaseModule)\s*=", remainder, re.MULTILINE)
    body = remainder[:boundary.start()] if boundary else remainder
    if not re.search(rf"Migration\({start},\s*{end}\)", body):
        raise AssertionError(f"{name} no longer represents {start} -> {end}")
    return body


def apply_static_sql(db, source, start, end):
    # The 33 -> 34 legacy split-row rewrite is data-dependent Kotlin. Our fixture has
    # no legacy split rows; the static SQL is exactly the statements it executes.
    for triple, quoted in SQL_LITERAL.findall(migration_body(source, start, end)):
        db.execute(triple if triple else json.loads(quoted))


class ForkDatabaseMigrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = MODULE.read_text(encoding="utf-8")

    def new_version_31_database(self):
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.executescript("""
            CREATE TABLE PlaybackStateEntity (
                mediaTitle TEXT NOT NULL PRIMARY KEY,
                aid INTEGER NOT NULL DEFAULT -1,
                lastPosition INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE network_connections (id INTEGER NOT NULL PRIMARY KEY);
            INSERT INTO PlaybackStateEntity VALUES ('cloud-episode', 4, 45000);
            INSERT INTO network_connections VALUES (7);
        """)
        return db

    def upgrade_to_fork_35(self, db):
        for start, end in [(31, 32), (32, 33), (33, 34), (34, 35)]:
            apply_static_sql(db, self.source, start, end)

    def test_original_cloud_anime_migration_chain_is_registered_once(self):
        names = re.findall(r"^val (MIGRATION_\d+_\d+)\s*=", self.source, re.MULTILINE)
        self.assertEqual(len(names), len(set(names)), "Duplicate migration definitions")
        registration = self.source.split(".addMigrations(", 1)[1].split(").build()", 1)[0]
        registered = set(re.findall(r"MIGRATION_\d+_\d+", registration))
        self.assertTrue(registered <= set(names), registered - set(names))
        for start, end in [(31, 32), (32, 33), (33, 34), (34, 35), (35, 36)]:
            migration_body(self.source, start, end)
            self.assertEqual(registration.count(f"MIGRATION_{start}_{end},"), 1)
        self.assertNotIn("MIGRATION_31_34", registration)
        self.assertNotIn("MIGRATION_32_34", registration)
        version = int(re.search(r"\bversion\s*=\s*(\d+)", DATABASE.read_text(encoding="utf-8"))[1])
        self.assertGreaterEqual(version, 36)

    def test_version_31_can_reach_new_audio_schema_without_skipping_fork_tables(self):
        db = self.new_version_31_database()
        self.upgrade_to_fork_35(db)
        apply_static_sql(db, self.source, 35, 36)
        tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertTrue({"cloud_video_metadata", "cloud_directory_items", "cloud_directory_state",
                         "cloud_folder_metadata", "anime_subjects", "anime_folders"} <= tables)
        cloud_columns = {row[1] for row in db.execute("PRAGMA table_info(cloud_video_metadata)")}
        self.assertTrue({"fps", "videoCodec", "hasEmbeddedSubtitles", "subtitleCodec", "technicalVersion"} <= cloud_columns)
        self.assertEqual(db.execute("SELECT isAnime FROM network_connections WHERE id=7").fetchone(), (0,))

    def test_migrated_schema_matches_rooms_exported_version_36(self):
        schema_root = ROOT / "app/schemas/app.gyrolet.mpvrx.database.MpvRxDatabase"
        schemas = {
            version: json.loads((schema_root / f"{version}.json").read_text(encoding="utf-8"))["database"]
            for version in [35, 36]
        }
        databases = {}
        for version, schema in schemas.items():
            db = sqlite3.connect(":memory:")
            self.addCleanup(db.close)
            for entity in schema["entities"]:
                db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
                for index in entity.get("indices", []):
                    db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            databases[version] = db
        apply_static_sql(databases[35], self.source, 35, 36)
        old_tables = {entity["tableName"] for entity in schemas[35]["entities"]}
        new_tables = {entity["tableName"] for entity in schemas[36]["entities"]}
        self.assertEqual(old_tables, new_tables)
        for table in sorted(new_tables):
            # Room compares columns by name, not their physical append/order position.
            columns = [
                {row[1]: row[2:] for row in db.execute(f"PRAGMA table_info(`{table}`)")}
                for db in databases.values()
            ]
            self.assertEqual(columns[0], columns[1], table)
            foreign_keys = [db.execute(f"PRAGMA foreign_key_list(`{table}`)").fetchall() for db in databases.values()]
            self.assertEqual(foreign_keys[0], foreign_keys[1], table)
            indices = [sorted(db.execute(f"PRAGMA index_list(`{table}`)").fetchall()) for db in databases.values()]
            self.assertEqual(indices[0], indices[1], table)

    def test_existing_version_35_data_and_audio_pick_survive_upgrade(self):
        db = self.new_version_31_database()
        self.upgrade_to_fork_35(db)
        db.execute("""INSERT INTO cloud_video_metadata VALUES
            (7, '/Show/01.mkv', 2048, 123, 120000, 1920, 1080, 456,
             23.976, 'h264', 1, 'ass', 1)""")
        db.execute("INSERT INTO anime_subjects VALUES (99, '{\"name\":\"Show\"}', 456)")
        db.execute("""INSERT INTO anime_folders VALUES
            (7, '/Show', 'Show', 99, 1, 0, 456, NULL, NULL)""")
        before = {
            table: db.execute(f"SELECT * FROM {table}").fetchall()
            for table in ["cloud_video_metadata", "anime_subjects", "anime_folders", "network_connections"]
        }
        apply_static_sql(db, self.source, 35, 36)
        for table, rows in before.items():
            self.assertEqual(db.execute(f"SELECT * FROM {table}").fetchall(), rows, table)
        self.assertEqual(
            db.execute("""SELECT aid, lastPosition, hasManualAudioSelection,
                manualAudioId, externalAudio FROM PlaybackStateEntity""").fetchone(),
            (4, 45000, 0, -1, ""),
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
