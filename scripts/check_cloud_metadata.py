"""Exercise migration/merge SQL from the actual Kotlin sources with SQLite."""
from pathlib import Path
import re
import sqlite3
import tempfile

root = Path(__file__).resolve().parents[1]
base = root / "app/src/main/java/app/gyrolet/mpvrx"
module = (base / "di/DatabaseModule.kt").read_text(encoding="utf-8")
migration = re.split(r"\nval (?:MIGRATION_|DatabaseModule)", module.split("val MIGRATION_31_32 =", 1)[1], maxsplit=1)[0]
statements = re.findall(r'db\.execSQL\((""".*?"""|"[^"\n]*")\)', migration, re.S)
temporary = tempfile.TemporaryDirectory()
database_path = Path(temporary.name) / "cloud.db"
db = sqlite3.connect(database_path)
db.execute("CREATE TABLE existing_data (value TEXT)")
db.execute("INSERT INTO existing_data VALUES ('keep')")
for statement in statements:
    db.execute(statement[3:-3] if statement.startswith('"""') else statement[1:-1])
assert len(statements) == 5
assert db.execute("SELECT value FROM existing_data").fetchone() == ("keep",)

dao = (base / "database/dao/CloudMetadataDao.kt").read_text(encoding="utf-8")
merge = re.search(r'@Query\("""(INSERT OR REPLACE INTO cloud_video_metadata.*?)"""\)', dao, re.S).group(1)

def write(server=1, size=100, modified=10, duration=0, width=0, height=0, path="/same.mp4"):
    db.execute(merge, dict(connectionId=server, path=path, size=size,
                          lastModified=modified, durationMs=duration, width=width,
                          height=height, updatedAt=1000))

def read(server=1):
    return db.execute("SELECT durationMs, width, height FROM cloud_video_metadata WHERE connectionId=?", (server,)).fetchone()

write(duration=42000)
write(width=1920, height=1080)
assert read() == (42000, 1920, 1080), "partial probes must preserve successful fields"
write()
assert read() == (42000, 1920, 1080), "failed probe must not erase results"
write(server=2, duration=7000)
assert read(2) == (7000, 0, 0) and read(1)[0] == 42000, "server identity must isolate paths"
write(size=200, modified=20)
assert read() == (0, 0, 0), "replacement must invalidate old metadata"
db.execute("INSERT INTO cloud_directory_state VALUES (1, '/empty', 1000)")
assert db.execute("SELECT scannedAt FROM cloud_directory_state WHERE path='/empty'").fetchone()
assert not db.execute("SELECT * FROM cloud_directory_items WHERE parentPath='/empty'").fetchall()
db.execute("INSERT INTO cloud_folder_metadata VALUES (1, '/tree', 0, 0, 0, 0, 1, 1000)")
for path, duration in [("/tree/100%_movie.mp4", 6000), ("/tree_other/movie.mp4", 9000)]:
    write(path=path, duration=duration)
    db.execute("INSERT INTO cloud_directory_items VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
               (1, path.rsplit('/', 1)[0], path, path.rsplit('/', 1)[1], 100, 10, 0, 'video/mp4'))
refresh = re.search(r'@Query\("""(UPDATE cloud_folder_metadata.*?)"""\)', dao, re.S).group(1)
db.execute(refresh, {"connectionId": 1, "path": None, "includeAncestors": False})
assert db.execute("SELECT totalDurationMs FROM cloud_folder_metadata WHERE path='/tree'").fetchone() == (6000,), "literal subtree prefix must not include sibling names"
write(path="/tree/100%_movie.mp4", size=200, duration=3000)
db.execute(refresh, {"connectionId": 1, "path": None, "includeAncestors": False})
assert db.execute("SELECT totalDurationMs FROM cloud_folder_metadata WHERE path='/tree'").fetchone() == (0,), "folder duration must exclude mismatched revisions"
for path in ["/", "/tree/nested", "/tree_other"]:
    db.execute("INSERT INTO cloud_folder_metadata VALUES (1, ?, 123, 0, 0, 0, 1, 1000)", (path,))
write(path="/tree/nested/new.mp4", duration=5000)
db.execute("INSERT INTO cloud_directory_items VALUES (1, '/tree/nested', '/tree/nested/new.mp4', 'new.mp4', 100, 10, 0, 'video/mp4')")
db.execute(refresh, {"connectionId": 1, "path": "/tree/nested", "includeAncestors": False})
assert db.execute("SELECT totalDurationMs FROM cloud_folder_metadata WHERE path='/tree/nested'").fetchone() == (5000,)
assert db.execute("SELECT totalDurationMs FROM cloud_folder_metadata WHERE path='/'").fetchone() == (123,), "directory enumeration must only update its own summary"
db.execute(refresh, {"connectionId": 1, "path": "/tree/nested/new.mp4", "includeAncestors": True})
assert db.execute("SELECT totalDurationMs FROM cloud_folder_metadata WHERE path='/tree'").fetchone() == (5000,), "new metadata must update containing ancestors"
assert db.execute("SELECT totalDurationMs FROM cloud_folder_metadata WHERE path='/tree_other'").fetchone() == (123,), "metadata publication must not recompute unrelated directories"
db.execute(refresh, {"connectionId": 1, "path": "/", "includeAncestors": True})
assert db.execute("SELECT totalDurationMs FROM cloud_folder_metadata WHERE path='/tree_other'").fetchone() == (123,), "root summary must not trigger a full-library refresh"
db.execute(refresh, {"connectionId": 1, "path": None, "includeAncestors": False})
assert db.execute("SELECT totalDurationMs FROM cloud_folder_metadata WHERE path='/tree_other'").fetchone() == (9000,), "final reconciliation still updates the entire tree"
subtree = re.search(r'@Query\("(DELETE FROM cloud_video_metadata WHERE connectionId = :id.*?)"\)', dao).group(1)
db.execute(subtree, {"id": 1, "path": "/tree"})
assert db.execute("SELECT durationMs FROM cloud_video_metadata WHERE path='/tree_other/movie.mp4'").fetchone() == (9000,)
assert not db.execute("SELECT durationMs FROM cloud_video_metadata WHERE path='/tree/100%_movie.mp4'").fetchone()
db.commit()
db.close()
reopened = sqlite3.connect(database_path)
assert reopened.execute("SELECT durationMs FROM cloud_video_metadata WHERE connectionId=2").fetchone() == (7000,)
reopened.close()
temporary.cleanup()
print("PASS: migration, merge, isolation, replacement, empty directories, subtree summaries/deletion and persistence")

