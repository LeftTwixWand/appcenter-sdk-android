package com.microsoft.appcenter.utils.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteFullException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;

import com.microsoft.appcenter.AppCenter;
import com.microsoft.appcenter.utils.AppCenterLog;

import java.io.Closeable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Database manager for SQLite with fail-over to in-memory.
 */
public class DatabaseManager implements Closeable {

    /**
     * Primary key name.
     */
    public static final String PRIMARY_KEY = "oid";

    /**
     * Application context instance.
     */
    private final Context mContext;

    /**
     * Database name.
     */
    private final String mDatabase;

    /**
     * Table name.
     */
    private final String mTable;

    /**
     * Schema, e.g. a specimen with dummy values to have keys and their corresponding value's type.
     */
    private final ContentValues mSchema;

    /**
     * Listener instance.
     */
    private final Listener mListener;

    /**
     * SQLite helper instance.
     */
    private SQLiteOpenHelper mSQLiteOpenHelper;

    /**
     * In-memory database if SQLite cannot be used.
     */
    @SuppressWarnings("SpellCheckingInspection")
    private Map<Long, ContentValues> mIMDB;

    /**
     * In-memory auto increment.
     */
    private long mIMDBAutoInc;

    /**
     * Maximum storage size of SQLite database.
     */
    private long mMaxStorageSizeInBytes;

    /**
     * Maximum number of entries of in memory database.
     */
    private static final long IN_MEMORY_MAX_SIZE = 300;

    /**
     * Initializes the table in the database.
     *
     * @param context          The application context.
     * @param database         The database name.
     * @param table            The table name.
     * @param version          The version of current schema.
     * @param schema           The schema.
     * @param maxDbSizeInBytes The max size the database is allowed to grow to.
     * @param listener         The error listener.
     */
    DatabaseManager(Context context, String database, String table, int version,
                    ContentValues schema, long maxDbSizeInBytes, Listener listener) {
        mContext = context;
        mDatabase = database;
        mTable = table;
        mSchema = schema;
        mMaxStorageSizeInBytes = maxDbSizeInBytes;
        mListener = listener;
        mSQLiteOpenHelper = new SQLiteOpenHelper(context, database, null, version) {

            @Override
            public void onCreate(SQLiteDatabase db) {
                long maxSize = db.setMaximumSize(mMaxStorageSizeInBytes);
                AppCenterLog.debug(AppCenterLog.LOG_TAG, "SQLite database size is set to " + maxSize + " bytes.");

                /* Generate a schema from specimen. */
                StringBuilder sql = new StringBuilder("CREATE TABLE `");
                sql.append(mTable);
                sql.append("` (oid INTEGER PRIMARY KEY AUTOINCREMENT");
                for (Map.Entry<String, Object> col : mSchema.valueSet()) {
                    sql.append(", `").append(col.getKey()).append("` ");
                    Object val = col.getValue();
                    if (val instanceof Double || val instanceof Float) {
                        sql.append("REAL");
                    } else if (val instanceof Number || val instanceof Boolean) {
                        sql.append("INTEGER");
                    } else if (val instanceof byte[]) {
                        sql.append("BLOB");
                    } else {
                        sql.append("TEXT");
                    }
                }
                sql.append(");");
                db.execSQL(sql.toString());
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

                /* Upgrade by destroying the old table unless managed. */
                if (!mListener.onUpgrade(db, oldVersion, newVersion)) {
                    db.execSQL("DROP TABLE `" + mTable + "`");
                    onCreate(db);
                }
            }
        };
    }

    /**
     * Converts a cursor to an entry.
     *
     * @param cursor The cursor to be converted to an entry.
     * @return An entry converted from the cursor.
     */
    private static ContentValues buildValues(Cursor cursor, ContentValues schema) {
        ContentValues values = new ContentValues();
        for (int i = 0; i < cursor.getColumnCount(); i++) {
            if (cursor.isNull(i)) {
                continue;
            }
            String key = cursor.getColumnName(i);
            if (key.equals(PRIMARY_KEY)) {
                values.put(key, cursor.getLong(i));
            } else {
                Object specimen = schema.get(key);
                if (specimen instanceof byte[]) {
                    values.put(key, cursor.getBlob(i));
                } else if (specimen instanceof Double) {
                    values.put(key, cursor.getDouble(i));
                } else if (specimen instanceof Float) {
                    values.put(key, cursor.getFloat(i));
                } else if (specimen instanceof Integer) {
                    values.put(key, cursor.getInt(i));
                } else if (specimen instanceof Long) {
                    values.put(key, cursor.getLong(i));
                } else if (specimen instanceof Short) {
                    values.put(key, cursor.getShort(i));
                } else if (specimen instanceof Boolean) {
                    values.put(key, cursor.getInt(i) == 1);
                } else {
                    values.put(key, cursor.getString(i));
                }
            }
        }
        return values;
    }

    /**
     * Stores the entry to the table. If the table is full, the oldest logs are discarded until the
     * new one can fit. If the log is larger than the max table size, database will be cleared and
     * the log is not inserted.
     *
     * @param values The entry to be stored.
     * @return If a log was inserted, the database identifier. Otherwise -1.
     */
    public long put(@NonNull ContentValues values) {

        /* Try SQLite. */
        if (mIMDB == null) {
            try {

                /*
                 * If the log is larger than the max size, all logs will be discarded and then
                 * we will return -1 because there is not enough room.
                 */
                Cursor cursor = getCursor(null, null, true);

                //noinspection TryFinallyCanBeTryWithResources
                try {
                    do {
                        try {

                            /* Insert data. */
                            return getDatabase().insertOrThrow(mTable, null, values);
                        } catch (SQLiteFullException e) {

                            /* Delete the oldest log. */
                            delete(cursor.getLong(0));
                        }
                    } while (cursor.moveToNext());
                } finally {
                    cursor.close();
                }
                return -1;
            } catch (RuntimeException e) {
                switchToInMemory("put", e);
            }
        }

        /* Store the values to in-memory database. */
        values.put(PRIMARY_KEY, mIMDBAutoInc);
        mIMDB.put(mIMDBAutoInc, values);
        return mIMDBAutoInc++;
    }

    /**
     * Deletes the entry by the identifier from the database.
     *
     * @param id The database identifier.
     */
    public void delete(@IntRange(from = 0) long id) {
        delete(PRIMARY_KEY, id);
    }

    /**
     * Deletes the entries by the identifier from the database.
     *
     * @param idList The list of database identifiers.
     */
    public void delete(@NonNull List<Long> idList) {
        if (idList.size() <= 0) {
            return;
        }

        /* Try SQLite. */
        if (mIMDB == null) {
            try {
                getDatabase().execSQL(String.format("DELETE FROM " + mTable + " WHERE " + PRIMARY_KEY + " IN (%s);", TextUtils.join(", ", idList)));
            } catch (RuntimeException e) {
                switchToInMemory("delete", e);
            }
        }

        /* Deletes the values from in-memory database. */
        else {
            for (Long id : idList) {
                mIMDB.remove(id);
            }
        }
    }

    /**
     * Deletes the entries that matches key == value.
     *
     * @param key   The optional key for query.
     * @param value The optional value for query.
     */
    public void delete(@Nullable String key, @Nullable Object value) {

        /* Try SQLite. */
        if (mIMDB == null) {
            try {
                getDatabase().delete(mTable, key + " = ?", new String[]{String.valueOf(value)});
            } catch (RuntimeException e) {
                switchToInMemory("delete", e);
            }
        }

        /* Deletes the values from in-memory database. */
        else if (PRIMARY_KEY.equals(key)) {
            if (value == null || !(value instanceof Number)) {
                throw new IllegalArgumentException("Primary key should be a number type and cannot be null");
            }
            mIMDB.remove(((Number) value).longValue());
        } else {
            for (Iterator<Map.Entry<Long, ContentValues>> iterator = mIMDB.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<Long, ContentValues> entry = iterator.next();
                Object object = entry.getValue().get(key);
                if (object != null && object.equals(value)) {
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Gets the entry by the identifier.
     *
     * @param id The database identifier.
     * @return An entry for the identifier or null if not found.
     */
    public ContentValues get(@IntRange(from = 0) long id) {
        return get(PRIMARY_KEY, id);
    }

    /**
     * Gets the entry that matches key == value.
     *
     * @param key   The optional key for query.
     * @param value The optional value for query.
     * @return A matching entry.
     */
    public ContentValues get(@Nullable String key, @Nullable Object value) {

        /* Try SQLite. */
        if (mIMDB == null) {
            try {
                Cursor cursor = getCursor(key, value, false);
                ContentValues values = cursor.moveToFirst() ? buildValues(cursor, mSchema) : null;
                cursor.close();
                return values;
            } catch (RuntimeException e) {
                switchToInMemory("get", e);
            }
        }

        /* Get the values from in-memory database. */
        else if (PRIMARY_KEY.equals(key)) {
            if (value == null || !(value instanceof Number)) {
                throw new IllegalArgumentException("Primary key should be a number type and cannot be null");
            }
            return mIMDB.get(((Number) value).longValue());
        } else {
            for (ContentValues values : mIMDB.values()) {
                Object object = values.get(key);
                if (object != null && object.equals(value)) {
                    return values;
                }
            }
        }

        return null;
    }

    /**
     * Gets a scanner to iterate all values those match key == value.
     *
     * @param key    The optional key for query.
     * @param value  The optional value for query.
     * @param idOnly true to return only identifier, false to return all fields.
     *               This flag is ignored if using in memory database.
     * @return A scanner to iterate all values.
     */
    Scanner getScanner(String key, Object value, boolean idOnly) {
        return new Scanner(key, value, idOnly);
    }

    /**
     * Clears the table in the database.
     */
    public void clear() {

        /* Try SQLite. */
        if (mIMDB == null) {
            try {
                getDatabase().delete(mTable, null, null);
            } catch (RuntimeException e) {
                switchToInMemory("clear", e);
            }
        }

        /* Clear in-memory database. */
        else {
            mIMDB.clear();
        }
    }

    /**
     * Closes database and clean up in-memory database.
     */
    @Override
    public void close() {

        /* Try SQLite. */
        if (mIMDB == null) {
            try {
                getDatabase().close();
            } catch (RuntimeException e) {
                switchToInMemory("close", e);
            }
        }

        /* Close in-memory database. */
        else {
            mIMDB.clear();
            mIMDB = null;
        }
    }

    /**
     * Gets the count of records in the table.
     *
     * @return The number of records in the table.
     */
    final long getRowCount() {

        /* Try SQLite. */
        if (mIMDB == null) {
            try {
                return DatabaseUtils.queryNumEntries(getDatabase(), mTable);
            } catch (RuntimeException e) {
                switchToInMemory("count", e);
            }
        }

        /* Get row count of in-memory database. */
        return mIMDB.size();
    }

    /**
     * Gets a cursor for all rows in the table, all rows where key matches value if specified.
     *
     * @param key    The optional key for query.
     * @param value  The optional value for query.
     * @param idOnly Return only row identifier if true, return all fields otherwise.
     * @return A cursor for all rows that matches the given criteria.
     * @throws RuntimeException If an error occurs.
     */
    Cursor getCursor(String key, Object value, boolean idOnly) throws RuntimeException {

        /* Build a query to get values. */
        SQLiteQueryBuilder builder = SQLiteUtils.newSQLiteQueryBuilder();
        builder.setTables(mTable);
        String[] selectionArgs;
        if (key == null) {
            selectionArgs = null;
        } else if (value == null) {
            builder.appendWhere(key + " IS NULL");
            selectionArgs = null;
        } else {
            builder.appendWhere(key + " = ?");
            selectionArgs = new String[]{String.valueOf(value.toString())};
        }

        /* Query database. */
        String[] projectionIn = idOnly ? new String[]{PRIMARY_KEY} : null;
        return builder.query(getDatabase(), projectionIn, null, selectionArgs, null, null, PRIMARY_KEY);
    }

    /**
     * Gets SQLite database.
     *
     * @return SQLite database.
     * @throws RuntimeException if an error occurs.
     */
    @VisibleForTesting
    SQLiteDatabase getDatabase() throws RuntimeException {

        /* Try opening database. */
        try {
            return mSQLiteOpenHelper.getWritableDatabase();
        } catch (RuntimeException e) {

            /* First error, try to delete database (may be corrupted). */
            mContext.deleteDatabase(mDatabase);

            /* Retry, let exception thrown if it fails this time. */
            return mSQLiteOpenHelper.getWritableDatabase();
        }
    }

    /**
     * Switches to in-memory management, triggers error listener.
     *
     * @param operation The operation that triggered the error.
     * @param exception The exception that triggered the switch.
     */
    @VisibleForTesting
    void switchToInMemory(String operation, RuntimeException exception) {

        /* Create an in-memory database. */
        mIMDB = new LinkedHashMap<Long, ContentValues>() {

            @Override
            protected boolean removeEldestEntry(Entry<Long, ContentValues> eldest) {
                return IN_MEMORY_MAX_SIZE < size();
            }
        };

        /* Trigger error listener. */
        if (mListener != null) {
            mListener.onError(operation, exception);
        }
    }

    /**
     * Sets {@link SQLiteOpenHelper} instance.
     *
     * @param helper A {@link SQLiteOpenHelper} instance to be used for accessing database.
     */
    @VisibleForTesting
    void setSQLiteOpenHelper(@NonNull SQLiteOpenHelper helper) {
        mSQLiteOpenHelper.close();
        mSQLiteOpenHelper = helper;
    }

    /**
     * Set maximum SQLite database size.
     *
     * @param maxStorageSizeInBytes Maximum SQLite database size.
     * @return true if database size was set, otherwise false.
     */
    boolean setMaxStorageSize(long maxStorageSizeInBytes) {
        SQLiteDatabase db = getDatabase();
        long currentMaxSize = db.getMaximumSize();
        long newMaxSize = db.setMaximumSize(maxStorageSizeInBytes);
        if (currentMaxSize == newMaxSize && maxStorageSizeInBytes != mMaxStorageSizeInBytes) {
            AppCenterLog.error(AppCenter.LOG_TAG, "Unable to set database maximum size. Current maximum size is " + currentMaxSize + " bytes.");
            return false;
        }
        mMaxStorageSizeInBytes = maxStorageSizeInBytes;
        return true;
    }

    /**
     * Gets the maximum size of the database.
     *
     * @return The maximum size of database in bytes.
     */
    public long getMaxSize() {
        return getDatabase().getMaximumSize();
    }

    /**
     * Database listener.
     */
    public interface Listener {

        /**
         * Called when upgrade is performed on the database.
         * You can use this callback to alter table schema without losing data.
         * If schema is not migrated, return false and table will be recreated with new schema,
         * deleting old data.
         *
         * @param db         database being upgraded.
         * @param oldVersion version of the schema the database was at open time.
         * @param newVersion new version of the schema.
         * @return true if upgrade was managed, false to drop/create table.
         */
        @SuppressWarnings({"BooleanMethodIsAlwaysInverted", "unused"})
        boolean onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion);

        /**
         * Notifies an exception, before switching to in memory storage.
         *
         * @param operation A name of operation that caused the error.
         * @param e         A runtime exception for the error.
         */
        void onError(String operation, RuntimeException e);
    }

    /**
     * Scanner specification.
     */
    class Scanner implements Iterable<ContentValues>, Closeable {

        /**
         * Filter key.
         */
        private final String key;

        /**
         * Filter value.
         */
        private final Object value;

        /**
         * Return only IDs flags (SQLite implementation only).
         */
        private final boolean idOnly;

        /**
         * SQLite cursor.
         */
        private Cursor cursor;

        /**
         * Initializes a cursor with optional filter.
         */
        private Scanner(String key, Object value, boolean idOnly) {
            this.key = key;
            this.value = value;
            this.idOnly = idOnly;
        }

        @Override
        public void close() {

            /* Close cursor. */
            if (cursor != null) {
                try {
                    cursor.close();
                    cursor = null;
                } catch (RuntimeException e) {
                    switchToInMemory("scan.close", e);
                }
            }
        }

        @NonNull
        @Override
        public Iterator<ContentValues> iterator() {

            /* Try SQLite. */
            if (mIMDB == null) {
                try {
                    /* Close cursor first if it was being used. */
                    close();
                    cursor = getCursor(key, value, idOnly);

                    /* Wrap cursor as iterator. */
                    return new Iterator<ContentValues>() {

                        /**
                         * If null, cursor needs to be moved to next.
                         */
                        Boolean hasNext;

                        @Override
                        public boolean hasNext() {
                            if (hasNext == null) {
                                try {
                                    hasNext = cursor.moveToNext();
                                } catch (RuntimeException e) {

                                    /* Consider no next on errors. */
                                    hasNext = false;

                                    /* Close cursor. */
                                    try {
                                        cursor.close();
                                    } catch (RuntimeException e1) {
                                        AppCenterLog.warn(AppCenter.LOG_TAG, "Closing cursor failed", e1);
                                    }
                                    cursor = null;

                                    /* Switch to in-memory database. */
                                    switchToInMemory("scan.hasNext", e);
                                }
                            }
                            return hasNext;
                        }

                        @Override
                        public ContentValues next() {

                            /* Check next. */
                            if (!hasNext()) {
                                throw new NoSuchElementException();
                            }
                            hasNext = null;

                            /* Build object. */
                            return buildValues(cursor, mSchema);
                        }

                        @Override
                        public void remove() {
                            throw new UnsupportedOperationException();
                        }
                    };
                } catch (RuntimeException e) {
                    switchToInMemory("scan.iterator", e);
                }
            }

            /* Scanner for in-memory database. */
            return new Iterator<ContentValues>() {

                /** In memory map iterator that we wrap because of the filter logic. */
                final Iterator<ContentValues> iterator = mIMDB.values().iterator();

                /** True if we moved the iterator but not retrieved the value. */
                boolean advanced;

                /** Next value. */
                ContentValues next;

                @Override
                public boolean hasNext() {

                    /* Iterator needs to be moved to the next. */
                    if (!advanced) {
                        next = null;
                        while (iterator.hasNext()) {
                            ContentValues nextCandidate = iterator.next();
                            Object candidateValue = nextCandidate.get(key);
                            if (key == null || (value != null && value.equals(candidateValue)) || (value == null && candidateValue == null)) {
                                next = nextCandidate;
                                break;
                            }
                        }
                        advanced = true;
                    }
                    return next != null;
                }

                @Override
                public ContentValues next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    advanced = false;
                    return next;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        public int getCount() {
            if (mIMDB == null) {
                try {
                    if (cursor == null) {
                        cursor = getCursor(key, value, idOnly);
                    }
                    return cursor.getCount();
                } catch (RuntimeException e) {
                    switchToInMemory("scan.count", e);
                }
            }
            int count = 0;
            for (Iterator<ContentValues> iterator = iterator(); iterator.hasNext(); iterator.next()) {
                count++;
            }
            return count;
        }
    }
}
