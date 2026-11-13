"""Development helper: look at what Stash did on the connected phone (debug build only).

    python tools/peek.py notes [N]     the newest N captures (default 10), each with the records made from it
    python tools/peek.py dump [N]      ask the app (debug broadcast) to write every table, newest N rows each, and show it
    python tools/peek.py events [N]    the newest N app events (default 50): component, code, numbers, ms. No words.
    python tools/peek.py rules         the personal word list and learned corrections
    python tools/peek.py log [N]       recent log lines from the app (default 80)
    python tools/peek.py screen PATH   save a screenshot to PATH (png)

Works over USB debugging. `notes` and `rules` read a copy of the app's database through `run-as` (read-only,
debug builds only); they understand database version 2 (capture, record, ...) and still read a version-1 file
(note). `dump` goes through the app and so also works after the database is encrypted (card 14).
Nothing is changed on the phone. Output may contain note text: don't paste it anywhere you wouldn't paste the notes.
"""
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import time

ADB = os.environ.get("ADB", r"C:\Users\v-jemacd\AppData\Local\Android\Sdk\platform-tools\adb.exe")
PKG = "app.stash"
DUMP_PATH = "files/debug/dump.json"


def adb(*args, binary=False):
    out = subprocess.run([ADB, *args], capture_output=True, check=False)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def open_db():
    """Copies the database (and its write-ahead log) to a temp folder and opens that copy."""
    tmp = tempfile.mkdtemp(prefix="stashdb_")
    for name in ("stash.db", "stash.db-wal", "stash.db-shm"):
        data = adb("exec-out", "run-as", PKG, "cat", f"databases/{name}", binary=True)
        if data:
            with open(os.path.join(tmp, name), "wb") as f:
                f.write(data)
    if not os.path.exists(os.path.join(tmp, "stash.db")):
        sys.exit("Couldn't read the database. Is the phone connected, and is this the debug build?")
    return sqlite3.connect(os.path.join(tmp, "stash.db"))


def tables(db):
    return [r[0] for r in db.execute("select name from sqlite_master where type='table' and name not like 'sqlite_%' and name not like 'room_%' and name not like 'search_fts%' and name != 'android_metadata'")]


def stamp(ms):
    if ms is None:
        return ""
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ms / 1000))


def show(row, indent="  "):
    for c, v in row.items():
        if v is not None and v != "":
            print(f"{indent}{c}: {v}")


def rows(db, sql, *params):
    cur = db.execute(sql, params)
    cols = [c[0] for c in cur.description]
    return [dict(zip(cols, r)) for r in cur.fetchall()]


def print_captures(captures, records_by_capture, extra_by_record):
    for c in captures:
        print("-" * 60)
        print(f"  {stamp(c['created_at'])}  id={c['id']}")
        print(f"  text: {c['text']}")
        if c["raw_text"] != c["text"]:
            print(f"  heard: {c['raw_text']}")
        flags = [f"{k}={c[k]}" for k in ("source", "extract_state", "sensitive", "captured_locked", "lat", "lon", "app_version", "deleted_at") if c.get(k) not in (None, "", 0)]
        print("  " + ", ".join(flags))
        for r in records_by_capture.get(c["id"], []):
            print(f"    record {r['id']}  kind={r['kind']}  pinned={r['pinned']}  supersedes={r['supersedes_record_id']}")
            print(f"      title: {r['title']}")
            for k, v in extra_by_record.get(r["id"], {}).items():
                if v not in (None, ""):
                    print(f"      {k}: {v}")


def cmd_notes(n):
    db = open_db()
    names = tables(db)
    if "capture" in names:
        caps = rows(db, "select * from capture where deleted_at is null order by created_at desc limit ?", n)
        recs = {}
        extra = {}
        for r in rows(db, "select * from record"):
            recs.setdefault(r["capture_id"], []).append(r)
        for w in rows(db, "select * from whereabouts"):
            extra.setdefault(w["record_id"], {}).update({"item": w["item_text"], "where": w["where_text"], "person": w["person_text"], "said": w["said_date_text"], "direction": w["direction"]})
        for t in rows(db, "select * from task"):
            extra.setdefault(t["record_id"], {}).update({"task": t["state"], "at_place": t["at_place_text"]})
        print_captures(caps, recs, extra)
        print(f"\n{len(caps)} shown; captures in file: {db.execute('select count(*) from capture').fetchone()[0]}, records: {db.execute('select count(*) from record').fetchone()[0]}")
    elif "note" in names:
        print("(database version 1: the prototype's note table)")
        for r in rows(db, "select * from note order by createdAt desc limit ?", n):
            print("-" * 60)
            show(r)
    else:
        sys.exit(f"No notes table found. Tables: {names}")


def cmd_dump(n):
    adb("shell", "run-as", PKG, "rm", "-f", DUMP_PATH)
    adb("shell", "am", "broadcast", "-n", f"{PKG}/{PKG}.debug.DumpReceiver", "--ei", "limit", str(n))
    data = b""
    for _ in range(40):
        time.sleep(0.5)
        data = adb("exec-out", "run-as", PKG, "cat", DUMP_PATH, binary=True)
        if data.strip().endswith(b"}"):
            break
    if not data:
        sys.exit("No dump arrived. Is this the debug build, and has the app been opened once since installing?")
    d = json.loads(data.decode("utf-8"))
    t = d["tables"]
    print(f"database version {d['version']}")
    print("rows per table (up to the limit): " + ", ".join(f"{k}={len(v)}" for k, v in sorted(t.items())))
    live = [c for c in t.get("capture", []) if c.get("deleted_at") is None]
    recs = {}
    for r in t.get("record", []):
        recs.setdefault(r["capture_id"], []).append(r)
    extra = {}
    for w in t.get("whereabouts", []):
        extra.setdefault(w["record_id"], {}).update({"item": w["item_text"], "where": w["where_text"], "person": w["person_text"], "said": w["said_date_text"], "direction": w["direction"]})
    for x in t.get("task", []):
        extra.setdefault(x["record_id"], {}).update({"task": x["state"], "at_place": x["at_place_text"]})
    print_captures(live, recs, extra)
    print(f"\n{len(live)} live captures shown")


def cmd_events(n):
    """The app_event table through the dump (works after encryption). Events hold codes and numbers, never words."""
    adb("shell", "run-as", PKG, "rm", "-f", DUMP_PATH)
    adb("shell", "am", "broadcast", "-n", f"{PKG}/{PKG}.debug.DumpReceiver", "--ei", "limit", str(max(n, 1)))
    data = b""
    for _ in range(40):
        time.sleep(0.5)
        data = adb("exec-out", "run-as", PKG, "cat", DUMP_PATH, binary=True)
        if data.strip().endswith(b"}"):
            break
    if not data:
        sys.exit("No dump arrived. Is this the debug build, and has the app been opened once since installing?")
    ev = json.loads(data.decode("utf-8"))["tables"].get("app_event", [])[:n]
    for e in reversed(ev):
        nums = " ".join(f"{k}={e[k]}" for k in ("n1", "n2", "ms") if e.get(k) is not None)
        ref = f" ref={e['ref_id'][:8]}" if e.get("ref_id") else ""
        print(f"{stamp(e['at'])}  {e['component']:<8} {e['code']:<18} {nums}{ref}")
    print(f"\n{len(ev)} events shown")


def main():
    args = sys.argv[1:]
    cmd = args[0] if args else "notes"
    num = int(args[1]) if len(args) > 1 and args[1].isdigit() else None
    if cmd == "notes":
        cmd_notes(num or 10)
    elif cmd == "dump":
        cmd_dump(num or 1000)
    elif cmd == "events":
        cmd_events(num or 50)
    elif cmd == "rules":
        db = open_db()
        for t in tables(db):
            if t in ("vocab_word", "correction"):
                print(f"== {t}")
                for r in rows(db, f"select * from {t}"):
                    print("-" * 60)
                    show(r)
    elif cmd == "log":
        n = args[1] if len(args) > 1 else "80"
        pid = adb("shell", "pidof", PKG).strip()
        text = adb("logcat", "-d", f"--pid={pid}" if pid else "-s", "-t", n) if pid else adb("logcat", "-d", "-t", n)
        print(text)
    elif cmd == "screen":
        path = args[1] if len(args) > 1 else "screen.png"
        with open(path, "wb") as f:
            f.write(adb("exec-out", "screencap", "-p", binary=True))
        print("saved", path)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
