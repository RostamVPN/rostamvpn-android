package com.rostamvpn.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

/**
 * Refused and notified connections, kept on the device so the Guardian
 * timeline survives restarts and the status line can say "N blocked today".
 *
 * Fed from the core's recent ring (getBlockStats), which is where the exit's
 * blk1 verdicts land too. Capped at [MAX_ROWS] rows and [MAX_AGE_MS]; pruned
 * on every insert. PRIVACY: this is a list of places the user tried to reach.
 * It never leaves the device and is never logged.
 *
 * Plain SQLiteOpenHelper rather than Room: one table, no new annotation
 * processor in the build.
 */
class GuardStore private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "guard.db", null, 2) {

    data class Row(
        val host: String,
        val port: Int,
        val bits: Int,
        val reason: String,
        val uid: Int?,
        val ts: Long,
        val refused: Boolean,
        /** Who the address belongs to and why (ShieldCtx), when it was known at the time. */
        val ctx: ShieldCtx? = null,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, host TEXT NOT NULL, " +
                "port INTEGER NOT NULL, bits INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '', " +
                "uid INTEGER, ts INTEGER NOT NULL, refused INTEGER NOT NULL, ctx TEXT)"
        )
        db.execSQL("CREATE INDEX events_ts ON events(ts)")
    }

    /** v2 (2026-10-26): `ctx`, the destination's owner and reason as JSON. Old rows keep NULL. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) runCatching { db.execSQL("ALTER TABLE events ADD COLUMN ctx TEXT") }
    }

    /**
     * Insert ring entries not already stored. The ring reports `secs_ago`, so
     * the same event read twice lands within a couple of seconds of itself;
     * (host, port, refused, ts ± 2 s) is treated as one event.
     */
    fun addAll(rows: List<Row>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                val dupe = db.rawQuery(
                    "SELECT 1 FROM events WHERE host=? AND port=? AND refused=? AND ts BETWEEN ? AND ? LIMIT 1",
                    arrayOf(r.host, r.port.toString(), if (r.refused) "1" else "0",
                        (r.ts - 2000).toString(), (r.ts + 2000).toString()),
                ).use { it.moveToFirst() }
                if (dupe) continue
                db.insert("events", null, ContentValues().apply {
                    put("host", r.host); put("port", r.port); put("bits", r.bits)
                    put("reason", r.reason); r.uid?.let { put("uid", it) }
                    put("ts", r.ts); put("refused", if (r.refused) 1 else 0)
                    r.ctx?.let { put("ctx", it.toJson().toString()) }
                })
            }
            db.delete("events", "ts < ?", arrayOf((System.currentTimeMillis() - MAX_AGE_MS).toString()))
            db.execSQL(
                "DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY ts DESC LIMIT $MAX_ROWS)"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recent(limit: Int = 200): List<Row> =
        readableDatabase.rawQuery(
            "SELECT host, port, bits, reason, uid, ts, refused, ctx FROM events ORDER BY ts DESC LIMIT $limit", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Row(c.getString(0), c.getInt(1), c.getInt(2), c.getString(3),
                        if (c.isNull(4)) null else c.getInt(4), c.getLong(5), c.getInt(6) == 1,
                        if (c.isNull(7)) null else ShieldCtx.parse(c.getString(7)))
                )
            }
        }

    /** Refused since local midnight. */
    fun refusedToday(): Int {
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE refused=1 AND ts >= ?", arrayOf(midnight.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    companion object {
        const val MAX_ROWS = 2000
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        @Volatile private var instance: GuardStore? = null
        fun get(ctx: Context): GuardStore =
            instance ?: synchronized(this) { instance ?: GuardStore(ctx).also { instance = it } }
    }
}
