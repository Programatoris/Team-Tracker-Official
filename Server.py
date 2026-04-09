import os
import sqlite3
import time
from flask import Flask, g, request, jsonify

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATABASE = os.path.join(BASE_DIR, "airsoft.db")

app = Flask(__name__)


def get_db():
    if "db" not in g:
        g.db = sqlite3.connect(DATABASE)
        g.db.row_factory = sqlite3.Row
    return g.db


@app.teardown_appcontext
def close_db(exception):
    db = g.pop("db", None)
    if db is not None:
        db.close()


def init_db():
    conn = sqlite3.connect(DATABASE)
    conn.row_factory = sqlite3.Row

    conn.executescript("""
    CREATE TABLE IF NOT EXISTS sessions (
        session_id TEXT PRIMARY KEY,
        created_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS players (
        player_id TEXT PRIMARY KEY,
        session_id TEXT NOT NULL,
        player_name TEXT NOT NULL,
        lat REAL,
        lng REAL,
        updated_at INTEGER NOT NULL,
        is_eliminated INTEGER NOT NULL DEFAULT 0,
        FOREIGN KEY(session_id) REFERENCES sessions(session_id)
    );

    CREATE INDEX IF NOT EXISTS idx_players_session_id ON players(session_id);
    CREATE INDEX IF NOT EXISTS idx_players_updated_at ON players(updated_at);
    """)

    cols = conn.execute("PRAGMA table_info(players)").fetchall()
    col_names = [row["name"] for row in cols]

    if "is_eliminated" not in col_names:
        conn.execute("""
            ALTER TABLE players
            ADD COLUMN is_eliminated INTEGER NOT NULL DEFAULT 0
        """)

    conn.commit()
    conn.close()


init_db()


def cleanup_inactive_data(db, timeout_seconds=120):
    now = int(time.time())

    db.execute("""
        DELETE FROM players
        WHERE updated_at < ?
    """, (now - timeout_seconds,))

    db.execute("""
        DELETE FROM sessions
        WHERE session_id NOT IN (
            SELECT DISTINCT session_id FROM players
        )
    """)

    db.commit()


@app.route("/api/health", methods=["GET"])
def health():
    return jsonify({"ok": True})


@app.route("/api/session/exists", methods=["GET"])
def session_exists():
    session_id = (request.args.get("session_id") or "").strip()

    if not session_id:
        return jsonify({"ok": False, "error": "missing session_id"}), 400

    db = get_db()
    cleanup_inactive_data(db, 120)

    row = db.execute("""
        SELECT session_id
        FROM sessions
        WHERE session_id = ?
        LIMIT 1
    """, (session_id,)).fetchone()

    return jsonify({
        "ok": True,
        "exists": row is not None
    })


@app.route("/api/session/create", methods=["POST"])
def create_session():
    data = request.get_json(force=True)

    session_id = (data.get("session_id") or "").strip()
    player_id = (data.get("player_id") or "").strip()
    player_name = (data.get("player_name") or "").strip()

    if not session_id or not player_id or not player_name:
        return jsonify({"ok": False, "error": "missing fields"}), 400

    now = int(time.time())
    db = get_db()

    cleanup_inactive_data(db, 120)

    existing = db.execute("""
        SELECT session_id
        FROM sessions
        WHERE session_id = ?
        LIMIT 1
    """, (session_id,)).fetchone()

    if existing is not None:
        return jsonify({"ok": False, "error": "session already exists"}), 409

    db.execute("""
        INSERT INTO sessions(session_id, created_at)
        VALUES(?, ?)
    """, (session_id, now))

    db.execute("""
        INSERT INTO players(player_id, session_id, player_name, lat, lng, updated_at, is_eliminated)
        VALUES(?, ?, ?, NULL, NULL, ?, 0)
    """, (player_id, session_id, player_name, now))

    db.commit()

    return jsonify({
        "ok": True,
        "session_id": session_id
    })


@app.route("/api/session/join", methods=["POST"])
def join_session():
    data = request.get_json(force=True)

    session_id = (data.get("session_id") or "").strip()
    player_id = (data.get("player_id") or "").strip()
    player_name = (data.get("player_name") or "").strip()

    if not session_id or not player_id or not player_name:
        return jsonify({"ok": False, "error": "missing fields"}), 400

    now = int(time.time())
    db = get_db()

    cleanup_inactive_data(db, 120)

    existing = db.execute("""
        SELECT session_id
        FROM sessions
        WHERE session_id = ?
        LIMIT 1
    """, (session_id,)).fetchone()

    if existing is None:
        return jsonify({"ok": False, "error": "session does not exist"}), 404

    db.execute("""
        INSERT INTO players(player_id, session_id, player_name, lat, lng, updated_at, is_eliminated)
        VALUES(?, ?, ?, NULL, NULL, ?, 0)
        ON CONFLICT(player_id) DO UPDATE SET
            session_id = excluded.session_id,
            player_name = excluded.player_name,
            updated_at = excluded.updated_at
    """, (player_id, session_id, player_name, now))

    db.commit()
    return jsonify({"ok": True})


@app.route("/api/location/update", methods=["POST"])
def update_location():
    data = request.get_json(force=True)

    session_id = (data.get("session_id") or "").strip()
    player_id = (data.get("player_id") or "").strip()
    player_name = (data.get("player_name") or "").strip()
    lat = data.get("lat")
    lng = data.get("lng")

    if not session_id or not player_id or not player_name:
        return jsonify({"ok": False, "error": "missing fields"}), 400

    if lat is None or lng is None:
        return jsonify({"ok": False, "error": "missing lat/lng"}), 400

    now = int(time.time())
    db = get_db()

    cleanup_inactive_data(db, 120)

    existing = db.execute("""
        SELECT session_id
        FROM sessions
        WHERE session_id = ?
        LIMIT 1
    """, (session_id,)).fetchone()

    if existing is None:
        return jsonify({"ok": False, "error": "session does not exist"}), 404

    db.execute("""
        INSERT INTO players(player_id, session_id, player_name, lat, lng, updated_at, is_eliminated)
        VALUES(?, ?, ?, ?, ?, ?, 0)
        ON CONFLICT(player_id) DO UPDATE SET
            session_id = excluded.session_id,
            player_name = excluded.player_name,
            lat = excluded.lat,
            lng = excluded.lng,
            updated_at = excluded.updated_at
    """, (player_id, session_id, player_name, lat, lng, now))

    db.commit()
    return jsonify({"ok": True, "server_time": now})


@app.route("/api/player/eliminated", methods=["POST"])
def update_eliminated():
    data = request.get_json(force=True)

    session_id = (data.get("session_id") or "").strip()
    player_id = (data.get("player_id") or "").strip()
    is_eliminated = bool(data.get("is_eliminated", False))

    if not session_id or not player_id:
        return jsonify({"ok": False, "error": "missing fields"}), 400

    now = int(time.time())
    db = get_db()

    cleanup_inactive_data(db, 120)

    cur = db.execute("""
        UPDATE players
        SET is_eliminated = ?, updated_at = ?
        WHERE session_id = ? AND player_id = ?
    """, (1 if is_eliminated else 0, now, session_id, player_id))

    if cur.rowcount == 0:
        return jsonify({"ok": False, "error": "player not found"}), 404

    db.commit()
    return jsonify({"ok": True, "is_eliminated": is_eliminated})


@app.route("/api/locations", methods=["GET"])
def get_locations():
    session_id = (request.args.get("session_id") or "").strip()
    current_player_id = (request.args.get("player_id") or "").strip()

    if not session_id:
        return jsonify({"ok": False, "error": "missing session_id"}), 400

    db = get_db()
    cleanup_inactive_data(db, 120)

    rows = db.execute("""
        SELECT player_id, player_name, lat, lng, updated_at, is_eliminated
        FROM players
        WHERE session_id = ?
          AND player_id != ?
          AND lat IS NOT NULL
          AND lng IS NOT NULL
    """, (session_id, current_player_id)).fetchall()

    players = []
    for row in rows:
        players.append({
            "player_id": row["player_id"],
            "player_name": row["player_name"],
            "lat": row["lat"],
            "lng": row["lng"],
            "updated_at": row["updated_at"],
            "is_eliminated": bool(row["is_eliminated"])
        })

    return jsonify({"players": players})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)