package de.kalendarium.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
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

/* Startbildschirm-Widget.
   Die Web-App schickt die nächsten Termine mit Losgehzeit, Linie und Strecke.
   Das Widget wählt selbst den Zustand (Losgehen, Unterwegs, Als Nächstes, Läuft)
   und nutzt, wenn erlaubt, den Standort: Bist du schon los, wo bist du gerade,
   wann kommst du an. Während eines Weges aktualisiert es sich jede Minute. */
public class Widget extends AppWidgetProvider {
    static final String PREF = "kal_widget";
    static final String TICK = "de.kalendarium.app.WIDGET_TICK";

    static void save(Context c, String json) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("data", json).putLong("at", System.currentTimeMillis()).apply();
        try {
            JSONObject o = new JSONObject(json); JSONObject p = o.optJSONObject("pos");
            if (p != null) savePos(c, p.optDouble("lat"), p.optDouble("lon"), p.optLong("at"));
        } catch (Exception ignored) { }
        updateAll(c);
        Sense.planTrace(c);
    }

    static void savePos(Context c, double lat, double lon, long at) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (at <= sp.getLong("posAt", 0)) return;
        sp.edit().putString("pos", lat + "," + lon).putLong("posAt", at).apply();
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
        if (TICK.equals(i.getAction())) { refreshLocation(c); updateAll(c); LiveRefresh.run(c, goAsync()); }
    }

    /* ---------- Standort ---------- */
    static boolean locOk(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    static boolean bgOk(Context c) {
        if (!locOk(c)) return false;
        return Build.VERSION.SDK_INT < 29 || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    @SuppressWarnings("MissingPermission")
    static void refreshLocation(Context c) {
        if (!bgOk(c)) return;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        Location best = null;
        for (String p : new String[]{"fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try { Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; } catch (Exception ignored) { }
        }
        if (best != null) savePos(c, best.getLatitude(), best.getLongitude(), best.getTime());
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                String prov = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
                lm.getCurrentLocation(prov, null, c.getMainExecutor(), l -> { if (l != null) { savePos(c, l.getLatitude(), l.getLongitude(), l.getTime()); renderOnly(c); } });
            } catch (Exception ignored) { }
        }
    }
    static void renderOnly(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, Widget.class))) render(c, m, id);
    }
    static double dist(double a1, double o1, double a2, double o2) {
        double r = Math.PI / 180, dLa = (a2 - a1) * r, dLo = (o2 - o1) * r;
        double h = Math.sin(dLa / 2) * Math.sin(dLa / 2) + Math.cos(a1 * r) * Math.cos(a2 * r) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 12742000 * Math.asin(Math.sqrt(h));
    }

    /* ---------- Text ---------- */
    static String hm(long t) { return new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(t)); }
    static String day(long t, long now) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance(); a.setTimeInMillis(now); b.setTimeInMillis(t);
        int d = (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 400 + b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR);
        if (d == 0) return "";
        if (d == 1) return "Morgen ";
        return new SimpleDateFormat("EEEE ", Locale.GERMANY).format(new Date(t));
    }
    static String mins(long ms) { long m = Math.max(0, Math.round(ms / 60000.0)); return m < 60 ? m + " min" : (m / 60) + " h " + (m % 60) + " min"; }

    /* ---------- Zeichnen ---------- */
    static long render(Context c, AppWidgetManager m, int id) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis(), next = 0;
        JSONArray items = new JSONArray(); String style = "dunkel"; boolean useLoc = true;
        try {
            String raw = sp.getString("data", sp.getString("items", "[]"));
            if (raw.trim().startsWith("[")) items = new JSONArray(raw);
            else { JSONObject o = new JSONObject(raw); items = o.optJSONArray("items"); if (items == null) items = new JSONArray(); style = o.optString("style", "dunkel"); useLoc = o.optBoolean("loc", true); }
        } catch (Exception ignored) { }

        boolean light = "hell".equals(style);
        int ink = light ? 0xFF111113 : 0xFFFFFFFF, soft = light ? 0xB3111113 : 0xBFFFFFFF, faint = light ? 0x80111113 : 0x8CFFFFFF;
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setInt(R.id.w_root, "setBackgroundResource", light ? R.drawable.widget_bg_light : "farbe".equals(style) ? R.drawable.widget_bg : R.drawable.widget_bg_dark);
        for (int t : new int[]{R.id.w_big, R.id.w_count, R.id.w_title}) v.setTextColor(t, ink);
        for (int t : new int[]{R.id.w_label, R.id.w_l1, R.id.w_l2}) v.setTextColor(t, soft);
        for (int t : new int[]{R.id.w_stand, R.id.w_next}) v.setTextColor(t, faint);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        JSONObject cur = null, after = null;
        for (int i = 0; i < items.length(); i++) { JSONObject o = items.optJSONObject(i); if (o == null || o.optLong("end") <= now) continue; if (cur == null) cur = o; else { after = o; break; } }

        /* Standort, wenn frisch */
        double[] pos = null;
        if (useLoc) {
            long pa = sp.getLong("posAt", 0);
            if (now - pa < 6 * 60000L) { try { String[] p = sp.getString("pos", "").split(","); pos = new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])}; } catch (Exception ignored) { } }
        }

        String label, title = "", big = null, l1 = "", l2 = "", badge = "";
        int badgeColor = 0; long count = 0; String countFmt = "in %s"; int prog = -1; boolean tripWindow = false;
        if (cur == null) {
            label = "Alles erledigt"; big = "Frei"; l1 = "Nichts mehr in den nächsten Tagen";
        } else {
            long start = cur.optLong("start"), end = cur.optLong("end"), dep = cur.optLong("depart", 0), arr = cur.optLong("arrive", 0);
            String line = cur.optString("line", ""), from = cur.optString("from", ""), plat = cur.optString("plat", ""), place = cur.optString("place", ""), exit = cur.optString("exit", ""), dest = cur.optString("dest", "");
            int delay = cur.optInt("delay", 0), changes = cur.optInt("changes", 0);
            long lineDep = cur.optLong("lineDep", 0);
            title = cur.optString("title");
            String when = day(start, now) + hm(start);
            if (!line.isEmpty()) { badge = line; try { badgeColor = Color.parseColor(cur.optString("lineColor", "#5B5B5B")); } catch (Exception e) { badgeColor = 0xFF5B5B5B; } }

            /* Wo bin ich? */
            String where = null; long eta = arr; boolean left = false, home = false;
            if (pos != null && dep > 0) {
                JSONArray fl = cur.optJSONArray("fromLL");
                double dHome = fl != null ? dist(pos[0], pos[1], fl.optDouble(0), fl.optDouble(1)) : -1;
                home = dHome >= 0 && dHome < 160;
                JSONArray pts = cur.optJSONArray("pts");
                if (pts != null && pts.length() > 1 && now > dep - 45 * 60000L) {
                    double bd = 1e12; int bi = -1;
                    for (int i = 0; i < pts.length(); i++) { JSONArray p = pts.optJSONArray(i); double d = dist(pos[0], pos[1], p.optDouble(0), p.optDouble(1)); if (d < bd) { bd = d; bi = i; } }
                    if (bi >= 0 && bd < 450 && !home) {
                        JSONArray p = pts.optJSONArray(bi); long tp = p.optLong(2); String nm = p.optString(3);
                        left = true; eta = arr + (now - tp);
                        where = bd < 200 ? "bei " + nm : "nahe " + nm;
                    } else if (!home && dHome > 300) { left = true; where = "unterwegs"; }
                }
            }

            if (dep > 0 && !left && now < dep) {
                label = (day(dep, now).isEmpty() ? "Losgehen" : "Losgehen " + day(dep, now).trim()) + " · " + hm(dep);
                if (dep - now < 6 * 3600000L) count = dep; else big = hm(dep);
                l1 = (badge.isEmpty() ? cur.optString("how", "") : (lineDep > 0 ? hm(lineDep) : "") + (delay > 0 ? " +" + delay : "") + (from.isEmpty() ? "" : " ab " + from) + (plat.isEmpty() ? "" : " · Gleis " + plat)).trim();
                l2 = "an " + hm(arr) + (changes > 0 ? " · " + changes + "× umsteigen" : "") + " · Beginn " + when;
                long win = 60 * 60000L; prog = (int) Math.max(0, Math.min(100, 100 - (dep - now) * 100 / win));
                next = dep; tripWindow = dep - now < 25 * 60000L;
            } else if (dep > 0 && home && now >= dep && now < start) {
                label = "Noch zuhause?"; big = "+" + mins(now - dep).replace(" min", "") + " min";
                l1 = "Losgehen war " + hm(dep) + (badge.isEmpty() ? "" : " · " + badge + (lineDep > 0 ? " " + hm(lineDep) : ""));
                l2 = "Beginn " + when; next = start; tripWindow = true; prog = 100;
            } else if (dep > 0 && now < start) {
                label = "Unterwegs" + (where != null && !where.equals("unterwegs") ? " · " + where : "");
                count = Math.max(now + 30000, eta); countFmt = "noch %s";
                l1 = (badge.isEmpty() ? "" : "") + (exit.isEmpty() ? (dest.isEmpty() ? place : "bis " + dest) : "aussteigen " + exit);
                long late = eta - arr;
                l2 = "an " + hm(eta) + " · Beginn " + hm(start) + (pos == null ? "" : Math.abs(late) < 90000 ? " · im Plan" : late > 0 ? " · " + mins(late) + " später" : " · " + mins(-late) + " früher");
                prog = (int) Math.max(0, Math.min(100, (now - dep) * 100 / Math.max(60000, eta - dep)));
                next = start; tripWindow = true;
            } else if (now < start) {
                label = day(start, now).isEmpty() ? "Als Nächstes · " + hm(start) : "Als Nächstes · " + when;
                if (start - now < 6 * 3600000L) count = start; else big = hm(start);
                l1 = place; l2 = "bis " + hm(end); next = start; badge = "";
            } else {
                label = "Läuft gerade"; big = "bis " + hm(end); l1 = place;
                prog = (int) Math.max(0, Math.min(100, (now - start) * 100 / Math.max(60000, end - start)));
                next = end; badge = "";
            }
        }
        if (after != null) {
            long s2 = after.optLong("depart", 0) > 0 ? after.optLong("depart") : after.optLong("start");
            v.setTextViewText(R.id.w_next, "Danach: " + day(after.optLong("start"), now) + hm(after.optLong("start")) + " " + after.optString("title") + (after.optLong("depart", 0) > 0 ? " · los " + hm(s2) : ""));
            v.setViewVisibility(R.id.w_next, View.VISIBLE);
        } else v.setViewVisibility(R.id.w_next, View.GONE);

        v.setTextViewText(R.id.w_label, label);
        v.setTextViewText(R.id.w_title, title); v.setViewVisibility(R.id.w_title, title.isEmpty() ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.w_l1, l1); v.setViewVisibility(R.id.w_l1, l1.isEmpty() ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.w_l2, l2); v.setViewVisibility(R.id.w_l2, l2.isEmpty() ? View.GONE : View.VISIBLE);
        if (!badge.isEmpty()) {
            v.setViewVisibility(R.id.w_badge, View.VISIBLE); v.setTextViewText(R.id.w_badge, badge);
            v.setInt(R.id.w_badge, "setBackgroundResource", badgeBg(badgeColor));
        } else v.setViewVisibility(R.id.w_badge, View.GONE);
        int pb = light ? R.id.w_prog_d : R.id.w_prog, pbOff = light ? R.id.w_prog : R.id.w_prog_d;
        v.setViewVisibility(pbOff, View.GONE);
        if (prog >= 0) { v.setViewVisibility(pb, View.VISIBLE); v.setProgressBar(pb, 100, prog, false); }
        else v.setViewVisibility(pb, View.GONE);
        if (count > 0) {
            v.setViewVisibility(R.id.w_count, View.VISIBLE); v.setViewVisibility(R.id.w_big, View.GONE);
            v.setChronometer(R.id.w_count, SystemClock.elapsedRealtime() + (count - now), countFmt, true);
            v.setChronometerCountDown(R.id.w_count, true);
        } else {
            v.setViewVisibility(R.id.w_count, View.GONE); v.setViewVisibility(R.id.w_big, View.VISIBLE);
            v.setTextViewText(R.id.w_big, big == null ? "" : big);
            v.setChronometer(R.id.w_count, SystemClock.elapsedRealtime(), null, false);
        }
        long at = sp.getLong("at", 0), pa = sp.getLong("posAt", 0);
        v.setTextViewText(R.id.w_stand, at > 0 ? (pos != null ? "Standort " + hm(pa) : "Stand " + hm(at)) : "App öffnen");
        m.updateAppWidget(id, v);
        /* Während eines Weges jede Minute neu, sonst zur nächsten Grenze */
        if (tripWindow) next = next > 0 ? Math.min(next, now + 60000L) : now + 60000L;
        else if (next > 0 && next - now > 25 * 60000L && cur != null && cur.optLong("depart", 0) > now) next = Math.min(next, cur.optLong("depart") - 24 * 60000L);
        return next;
    }

    /* Linienplakette mit runden Ecken in der Farbe des Verkehrsmittels */
    static int badgeBg(int col) {
        switch (col & 0xFFFFFF) {
            case 0x1F5AA6: return R.drawable.badge_subway;
            case 0x3E8A3E: return R.drawable.badge_suburban;
            case 0xC3282B: return R.drawable.badge_tram;
            case 0x8F2C8A: return R.drawable.badge_bus;
            case 0xB3242C: return R.drawable.badge_regional;
            case 0x2A7FB8: return R.drawable.badge_ferry;
            default: return R.drawable.badge_express;
        }
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

    static boolean pin(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (!m.isRequestPinAppWidgetSupported()) return false;
        return m.requestPinAppWidget(new ComponentName(c, Widget.class), null, null);
    }
}
