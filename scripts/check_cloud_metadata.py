"""Exercise migration/merge SQL from the actual Kotlin sources with SQLite."""
from pathlib import Path
import re
import sqlite3
import tempfile

root = Path(__file__).resolve().parents[1]
base = root / "app/src/main/java/app/gyrolet/mpvrx"
module = (base / "di/DatabaseModule.kt").read_text(encoding="utf-8")
migration = module.split("val MIGRATION_31_32 =", 1)[1].split("val DatabaseModule =", 1)[0]
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

def write(server=1, size=100, modified=10, duration=0, width=0, height=0):
    db.execute(merge, dict(connectionId=server, path="/same.mp4", size=size,
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
db.commit()
db.close()
reopened = sqlite3.connect(database_path)
assert reopened.execute("SELECT durationMs FROM cloud_video_metadata WHERE connectionId=2").fetchone() == (7000,)
reopened.close()
temporary.cleanup()
print("PASS: migration preserves data; merge, isolation, replacement, empty directories and persistence")
