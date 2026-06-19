package com.yusuf.waantidelete.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class RevokedMessageStore {

    private static final String DB_NAME = "waantidelete.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE_REVOKED = "revoked_messages";

    private static volatile RevokedMessageStore instance;
    private final DatabaseHelper helper;

    private RevokedMessageStore(Context context) {
        helper = new DatabaseHelper(context.getApplicationContext());
    }

    public static RevokedMessageStore getInstance(Context context) {
        if (instance == null) {
            synchronized (RevokedMessageStore.class) {
                if (instance == null) {
                    instance = new RevokedMessageStore(context);
                }
            }
        }
        return instance;
    }

    public void put(String jid, String messageId, long timestamp) {
        if (jid == null || jid.isEmpty() || messageId == null || messageId.isEmpty()) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("jid", jid);
        values.put("message_id", messageId);
        values.put("timestamp", timestamp);
        db.insertWithOnConflict(TABLE_REVOKED, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Set<String> getMessageIdsByJid(String jid) {
        if (jid == null || jid.isEmpty()) return Collections.synchronizedSet(new HashSet<>());
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor cursor = db.query(
                TABLE_REVOKED,
                new String[]{"message_id"},
                "jid = ?",
                new String[]{jid},
                null,
                null,
                null
        );
        HashSet<String> result = new HashSet<>();
        try {
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }
        return Collections.synchronizedSet(result);
    }

    public long getTimestamp(String jid, String messageId) {
        if (jid == null || jid.isEmpty() || messageId == null || messageId.isEmpty()) return 0L;
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor cursor = db.query(
                TABLE_REVOKED,
                new String[]{"timestamp"},
                "jid = ? AND message_id = ?",
                new String[]{jid, messageId},
                null,
                null,
                null,
                "1"
        );
        try {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
            return 0L;
        } finally {
            cursor.close();
        }
    }

    private static final class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(
                    "CREATE TABLE " + TABLE_REVOKED + " (" +
                            "jid TEXT NOT NULL, " +
                            "message_id TEXT NOT NULL, " +
                            "timestamp INTEGER NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY(jid, message_id))"
            );
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_REVOKED);
            onCreate(db);
        }
    }
}
