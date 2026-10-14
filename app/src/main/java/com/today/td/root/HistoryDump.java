package com.today.td.root;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

public final class HistoryDump {
    public static void main(String[] args) {
        if (args.length < 1 || args[0].length() == 0) {
            System.out.println("RESULT:NO_DB");
            System.exit(2);
            return;
        }
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = SQLiteDatabase.openDatabase(args[0], null, SQLiteDatabase.OPEN_READONLY);
            cursor = db.rawQuery(
                    "SELECT ifnull(title,''), url, last_visit_time FROM urls " +
                            "WHERE last_visit_time > 0 AND url NOT LIKE 'chrome://%' " +
                            "AND url NOT LIKE 'chrome-native://%' AND url NOT LIKE 'about:%' " +
                            "ORDER BY last_visit_time DESC LIMIT 200",
                    null
            );
            int count = 0;
            while (cursor.moveToNext()) {
                String title = clean(cursor.getString(0));
                String url = clean(cursor.getString(1));
                if (url.length() == 0) continue;
                System.out.println(title + "\t" + url + "\t" + cursor.getLong(2));
                count++;
            }
            System.out.println("RESULT:ROWS:" + count);
            System.exit(0);
        } catch (Throwable error) {
            System.out.println("RESULT:ERROR:" + error.getClass().getSimpleName());
            System.exit(1);
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
