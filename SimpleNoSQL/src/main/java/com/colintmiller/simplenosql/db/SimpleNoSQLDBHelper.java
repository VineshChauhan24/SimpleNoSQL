package com.colintmiller.simplenosql.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.colintmiller.simplenosql.BaseModel;
import com.colintmiller.simplenosql.DataDeserializer;
import com.colintmiller.simplenosql.DataFilter;
import com.colintmiller.simplenosql.DataSerializer;
import com.colintmiller.simplenosql.NoSQLEntity;

import java.util.ArrayList;
import java.util.List;

import static com.colintmiller.simplenosql.db.SimpleNoSQLContract.EntityEntry;

/**
 * The NoSQL datastore is in fact backed by SQL. This might seem counter to the ideals of the project at first. However,
 * the framework prevents the user from having to interact with SQL directly and deals purely with documents.
 * The database is still useful in implementation however for it's indexing retrieval and storage options.
 */
public class SimpleNoSQLDBHelper extends SQLiteOpenHelper {

    private static final String TAG = SimpleNoSQLDBHelper.class.getCanonicalName();
    private DataSerializer serializer;
    private DataDeserializer deserializer;

    public static int DATABASE_VERSION = 3;
    public static String DATABASE_NAME = "simplenosql.db";

    // DB Creation
    private static final String TEXT_TYPE = " TEXT";
    private static final String COMMA_SEP = ",";
    public static final String NOT_NULL = " NOT NULL";
    private static final String SQL_CREATE_ENTRIES =
            "CREATE TABLE IF NOT EXISTS " + EntityEntry.TABLE_NAME + " (" +
            EntityEntry._ID + " INTEGER PRIMARY KEY," +
            EntityEntry.COLUMN_NAME_BUCKET_ID + TEXT_TYPE + COMMA_SEP +
            EntityEntry.COLUMN_NAME_ENTITY_ID + TEXT_TYPE + NOT_NULL + COMMA_SEP +
            EntityEntry.COLUMN_NAME_DATA + TEXT_TYPE + COMMA_SEP +
            " UNIQUE(" + EntityEntry.COLUMN_NAME_BUCKET_ID + COMMA_SEP + EntityEntry.COLUMN_NAME_ENTITY_ID + ") ON CONFLICT REPLACE"
            + " )";
    private static final String SQL_DELETE_ENTRIES =
            "DROP TABLE " + EntityEntry.TABLE_NAME;


    public SimpleNoSQLDBHelper(Context context, DataSerializer serializer, DataDeserializer deserializer) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.serializer = serializer;
        this.deserializer = deserializer;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_ENTRIES);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // TODO: Be non-destructive when doing a real upgrade.
        Log.d(TAG, "onUpgrade: " + oldVersion + "->" + newVersion);
        if (oldVersion == 2 && newVersion == 3) {
            // delete any null albums that dont have file saved
            try {
                int rowsDeleted = db.delete("simplenosql", "entityid is NULL and data NOT LIKE '%filePath%'", null);
                Log.i(TAG, "deleted " + rowsDeleted + " rows");
            } catch (Exception e) {
                Log.e(TAG, e.getMessage(), e);
            }

        }
    }

    public <T> void saveEntity(NoSQLEntity<T> entity) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(EntityEntry.COLUMN_NAME_BUCKET_ID, entity.getBucket());
        values.put(EntityEntry.COLUMN_NAME_ENTITY_ID, entity.getId());
        values.put(EntityEntry.COLUMN_NAME_DATA, serializer.serialize(entity.getData()));
        db.insertWithOnConflict(EntityEntry.TABLE_NAME, EntityEntry.COLUMN_NAME_BUCKET_ID, values, SQLiteDatabase.CONFLICT_REPLACE);
        db.close();
    }

    public void deleteEntity(String bucket, String entityId) {
        SQLiteDatabase db = getWritableDatabase();
        String[] args = {bucket, entityId};
        db.delete(EntityEntry.TABLE_NAME, EntityEntry.COLUMN_NAME_BUCKET_ID + "=? and " + EntityEntry.COLUMN_NAME_ENTITY_ID + "=?", args);
        db.close();
    }

    public void deleteBucket(String bucket) {
        SQLiteDatabase db = getWritableDatabase();
        String[] args = {bucket};
        db.delete(EntityEntry.TABLE_NAME, EntityEntry.COLUMN_NAME_BUCKET_ID + "=?", args);
        db.close();
    }

    public <T> List<NoSQLEntity<T>> getEntities(String bucket, String entityId, Class<T> clazz, DataFilter<T> filter) {
        if (bucket == null || entityId == null) {
            return new ArrayList<NoSQLEntity<T>>(0);
        }
        String selection = EntityEntry.COLUMN_NAME_BUCKET_ID + "=? AND " + EntityEntry.COLUMN_NAME_ENTITY_ID + "=?";
        String[] selectionArgs = {bucket, entityId};
        return getEntities(selection, selectionArgs, clazz, filter);
    }

    public <T> List<NoSQLEntity<T>> getEntities(String bucket, String entityId, Class<T> clazz) {
        return getEntities(bucket, entityId, clazz, null);
    }

    public <T> List<NoSQLEntity<T>> getEntities(String bucket, Class<T> clazz, DataFilter<T> filter) {
        if (bucket == null) {
            return new ArrayList<NoSQLEntity<T>>(0);
        }
        String selection = EntityEntry.COLUMN_NAME_BUCKET_ID + "=?";
        String[] selectionArgs = {bucket};
        return getEntities(selection, selectionArgs, clazz, filter);
    }

    public <T> List<NoSQLEntity<T>> getEntities(String bucket, Class<T> clazz) {
        return getEntities(bucket, clazz, null);
    }

    private <T> List<NoSQLEntity<T>> getEntities(String selection, String[] selectionArgs, Class<T> clazz, DataFilter<T> filter) {
        List<NoSQLEntity<T>> results = new ArrayList<NoSQLEntity<T>>();
        SQLiteDatabase db = getReadableDatabase();

        String[] columns = {EntityEntry.COLUMN_NAME_BUCKET_ID, EntityEntry.COLUMN_NAME_ENTITY_ID, EntityEntry.COLUMN_NAME_DATA};

        Cursor cursor = db.query(EntityEntry.TABLE_NAME, columns, selection, selectionArgs, null, null, null);
        try {
            while (cursor.moveToNext()) {
                String bucketId = cursor.getString(cursor.getColumnIndex(EntityEntry.COLUMN_NAME_BUCKET_ID));
                String entityId = cursor.getString(cursor.getColumnIndex(EntityEntry.COLUMN_NAME_ENTITY_ID));
                String data = cursor.getString(cursor.getColumnIndex(EntityEntry.COLUMN_NAME_DATA));

                NoSQLEntity<T> entity = new NoSQLEntity<T>(bucketId, entityId);
                T dataObj = deserializer.deserialize(data, clazz);
                if(dataObj instanceof BaseModel) {
                    ((BaseModel) dataObj).setId(entityId);
                }
                entity.setData(dataObj);
                if (filter != null && !filter.isIncluded(entity)) {
                    // skip this item, it's been filtered out.
                    continue;
                }
                results.add(entity);
            }
        } finally {
            cursor.close();
            db.close();
        }
        return results;
    }
}
