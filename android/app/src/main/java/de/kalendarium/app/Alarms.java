package de.kalendarium.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

/* Plant Erinnerungen und Live-Banner aus der Web-App als exakte Android-Wecker */
public class Alarms {
    static final String PREF = "kal_alarms";
    static final String KEY = "list";
    static final String LIVE = "live_ids";

    static int nid(JSONObject o) { return o.optString("key", "").hashCode(); }

    static PendingIntent intent(Context c, JSONObject o) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.putExtra("json", o.toString());
        return PendingIntent.getBroadcast(c, nid(o), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void fire(Context c, JSONObject o) {
        if ("live".equals(o.optString("type"))) {
            Notify.live(c, nid(o), o.optString("title"), o.optString("body"), o.optString("phase"), o.optLong("when"), o.optLong("until"));
        } else {
            Notify.show(c, nid(o), o.optString("title", "Kalendarium"), o.optString("body", ""), o.optInt("prio", 4), o.optString("vib", "doppelt"));
        }
    }

    static int schedule(Context c, String json) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return 0;
        try {
            JSONArray old = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < old.length(); i++) am.cancel(intent(c, old.getJSONObject(i)));
        } catch (Exception ignored) { }
        JSONArray keep = new JSONArray(), liveNow = new JSONArray();
        long now = System.currentTimeMillis();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                long at = o.optLong("at", 0);
                boolean live = "live".equals(o.optString("type"));
                if (live) {
                    long until = o.optLong("until", 0);
                    if (until <= now) continue;
                    if (at <= now) { fire(c, o); liveNow.put(nid(o)); continue; }
                } else if (at < now - 30000) continue;
                set(c, am, at, intent(c, o));
                keep.put(o);
            }
        } catch (Exception ignored) { }
        /* Banner, die nicht mehr gelten (Termin gelöscht, Zeit geändert), entfernen */
        try {
            JSONArray was = new JSONArray(sp.getString(LIVE, "[]"));
            for (int i = 0; i < was.length(); i++) {
                int id = was.getInt(i); boolean still = false;
                for (int k = 0; k < liveNow.length(); k++) if (liveNow.getInt(k) == id) still = true;
                if (!still) Notify.cancel(c, id);
            }
        } catch (Exception ignored) { }
        sp.edit().putString(KEY, keep.toString()).putString(LIVE, liveNow.toString()).apply();
        return keep.length() + liveNow.length();
    }

    static void markLive(Context c, int id) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        try { JSONArray a = new JSONArray(sp.getString(LIVE, "[]")); a.put(id); sp.edit().putString(LIVE, a.toString()).apply(); } catch (Exception ignored) { }
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
