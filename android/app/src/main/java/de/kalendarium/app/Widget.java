package de.kalendarium.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/* Startbildschirm-Widget: zeigt, was als Nächstes ansteht.
   Die Web-App schickt die nächsten Termine mit Losgehzeit und Linie;
   das Widget wählt selbst den passenden Zustand und zählt live herunter. */
public class Widget extends AppWidgetProvider {
    static final String PREF = "kal_widget";
    static final String TICK = "de.kalendarium.app.WIDGET_TICK";

    static void save(Context c, String json) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("items", json).putLong("at", System.currentTimeMillis()).apply();
        updateAll(c);
    }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, Widget.class));
        if (ids.length == 0) return;
        long next = 0;
        for (int id : ids) { long n = render(c, m, id); if (n > 0 && (next == 0 || n < next)) next = n; }
        schedule(c, next);
    }

    static boolean any(Context c) { return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, Widget.class)).length > 0; }

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { updateAll(c); }
    @Override public void onEnabled(Context c) { updateAll(c); }
    @Override public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (TICK.equals(i.getAction())) updateAll(c);
    }

    static String hm(long t) { return new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(t)); }
    static String day(long t, long now) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance(); a.setTimeInMillis(now); b.setTimeInMillis(t);
        int d = (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 400 + b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR);
        if (d == 0) return "";
        if (d == 1) return "Morgen ";
        return new SimpleDateFormat("EEEE ", Locale.GERMANY).format(new Date(t));
    }

    /* Zeichnet ein Widget und gibt den nächsten Zeitpunkt zurück, an dem sich der Zustand ändert */
    static long render(Context c, AppWidgetManager m, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        long now = System.currentTimeMillis(), next = 0;
        JSONObject cur = null;
        try {
            JSONArray a = new JSONArray(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); if (o.optLong("end") > now) { cur = o; break; } }
        } catch (Exception ignored) { }

        String label, title = "", big = null, meta = "";
        long count = 0;
        if (cur == null) {
            label = "Alles erledigt"; big = "Frei"; meta = "Nichts mehr in den nächsten Tagen";
        } else {
            long start = cur.optLong("start"), end = cur.optLong("end"), dep = cur.optLong("depart", 0), arr = cur.optLong("arrive", 0);
            String line = cur.optString("line", ""), from = cur.optString("from", ""), plat = cur.optString("plat", ""), place = cur.optString("place", "");
            int delay = cur.optInt("delay", 0);
            String how = line.isEmpty() ? cur.optString("how", "") : line + " " + hm(cur.optLong("lineDep", dep)) + (delay > 0 ? " +" + delay : "") + (from.isEmpty() ? "" : " ab " + from) + (plat.isEmpty() ? "" : ", Gleis " + plat);
            title = cur.optString("title") + " · " + day(start, now) + hm(start);
            if (dep > 0 && now < dep) {
                label = day(dep, now).isEmpty() ? "Losgehen" : "Losgehen " + day(dep, now).trim();
                if (dep - now < 6 * 3600000L) count = dep; else big = hm(dep);
                meta = how; next = dep;
            } else if (dep > 0 && now < start) {
                label = "Unterwegs"; big = "an " + hm(arr > 0 ? arr : start);
                meta = how.isEmpty() ? place : how; next = start;
            } else if (now < start) {
                label = day(start, now).isEmpty() ? "Als Nächstes" : "Als Nächstes · " + day(start, now).trim();
                if (start - now < 6 * 3600000L) count = start; else big = hm(start);
                meta = place; next = start;
                title = cur.optString("title");
            } else {
                label = "Läuft gerade"; big = "bis " + hm(end); meta = place; next = end;
                title = cur.optString("title");
            }
        }
        v.setTextViewText(R.id.w_label, label);
        v.setTextViewText(R.id.w_title, title);
        v.setViewVisibility(R.id.w_title, title.isEmpty() ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.w_meta, meta);
        v.setViewVisibility(R.id.w_meta, meta.isEmpty() ? View.GONE : View.VISIBLE);
        if (count > 0) {
            v.setViewVisibility(R.id.w_count, View.VISIBLE); v.setViewVisibility(R.id.w_big, View.GONE);
            v.setChronometer(R.id.w_count, SystemClock.elapsedRealtime() + (count - now), "in %s", true);
            v.setChronometerCountDown(R.id.w_count, true);
        } else {
            v.setViewVisibility(R.id.w_count, View.GONE); v.setViewVisibility(R.id.w_big, View.VISIBLE);
            v.setTextViewText(R.id.w_big, big == null ? "" : big);
            v.setChronometer(R.id.w_count, SystemClock.elapsedRealtime(), null, false);
        }
        long at = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("at", 0);
        v.setTextViewText(R.id.w_stand, at > 0 ? "Stand " + hm(at) : "App einmal öffnen");
        m.updateAppWidget(id, v);
        return next;
    }

    static void schedule(Context c, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, Widget.class).setAction(TICK);
        PendingIntent pi = PendingIntent.getBroadcast(c, 7700, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        if (at <= 0) return;
        Alarms.set(c, am, at + 1000, pi);
    }

    /* Widget vom Handy aus auf den Startbildschirm legen lassen */
    static boolean pin(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (Build.VERSION.SDK_INT < 26 || !m.isRequestPinAppWidgetSupported()) return false;
        return m.requestPinAppWidget(new ComponentName(c, Widget.class), null, null);
    }
}
