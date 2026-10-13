package de.kalendarium.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

/* Plant die Erinnerungen aus der Web-App als exakte Android-Wecker */
public class Alarms {
    static final String PREF = "kal_alarms";
    static final String KEY = "list";

    static PendingIntent intent(Context c, JSONObject o) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.putExtra("title", o.optString("title", "Kalendarium"));
        i.putExtra("body", o.optString("body", ""));
        i.putExtra("prio", o.optInt("prio", 4));
        i.putExtra("key", o.optString("key", ""));
        int req = o.optString("key", "").hashCode();
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static int schedule(Context c, String json) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return 0;
        try {
            JSONArray old = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < old.length(); i++) am.cancel(intent(c, old.getJSONObject(i)));
        } catch (Exception ignored) { }
        JSONArray keep = new JSONArray();
        long now = System.currentTimeMillis();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                long at = o.optLong("at", 0);
                if (at < now - 30000) continue;
                set(c, am, at, intent(c, o));
                keep.put(o);
            }
        } catch (Exception ignored) { }
        sp.edit().putString(KEY, keep.toString()).apply();
        return keep.length();
    }

    static void set(Context c, AlarmManager am, long at, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void restore(Context c) {
        schedule(c, c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]"));
    }

    static int count(Context c) {
        try { return new JSONArray(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]")).length(); } catch (Exception e) { return 0; }
    }

    static boolean exactAllowed(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms());
    }
}
