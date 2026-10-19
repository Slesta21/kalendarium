package de.kalendarium.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/* Sensoren im Hintergrund. Alles bleibt auf dem Gerät, die Web-App liest es über die Bridge.
   · Ladekabel: Zeitpunkt, an dem das Handy angesteckt bzw. abgesteckt wurde
   · Schritte: Stand des Schrittzählers alle ~15 min
   · Spur: Standort jede Minute, nur während geplanter Wege (aus dem Widget-Plan)
   · App-Nutzung: welche App wann im Vordergrund war (für „Handy in Vorlesungen“) */
public class Sense extends JobService {
    static final String PREF = "kal_sense";
    static final int JOB_TICK = 8101, JOB_PLUG = 8102;
    static final String TRACE = "de.kalendarium.app.TRACE";
    static final String LOC = "de.kalendarium.app.LOC";
    static final long DAY = 86400000L;

    /* ---------- Planung ---------- */
    static void ensure(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            ComponentName cn = new ComponentName(c, Sense.class);
            if (js.getPendingJob(JOB_TICK) == null)
                js.schedule(new JobInfo.Builder(JOB_TICK, cn).setPeriodic(15 * 60000L).setPersisted(true).build());
            if (charging(c)) logCharge(c, true, System.currentTimeMillis());
            else if (js.getPendingJob(JOB_PLUG) == null) schedulePlug(c);
        } catch (Exception ignored) { }
        planTrace(c);
        startPassive(c);
        Light.plan(c);
    }
    static void schedulePlug(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            js.schedule(new JobInfo.Builder(JOB_PLUG, new ComponentName(c, Sense.class)).setRequiresCharging(true).setPersisted(true).build());
        } catch (Exception ignored) { }
    }

    @Override public boolean onStartJob(JobParameters p) {
        long now = System.currentTimeMillis();
        if (p.getJobId() == JOB_PLUG) { logCharge(this, true, now); return false; }
        boolean ch = charging(this);
        logCharge(this, ch, now);
        if (!ch) { JobScheduler js = getSystemService(JobScheduler.class); if (js != null && js.getPendingJob(JOB_PLUG) == null) schedulePlug(this); }
        planTrace(this);
        startPassive(this);
        if (Widget.bgOk(this) && System.currentTimeMillis() - getSharedPreferences(Widget.PREF, Context.MODE_PRIVATE).getLong("posAt", 0) > 10 * 60000L) fix(this, false);
        return readSteps(this, p);
    }
    @Override public boolean onStopJob(JobParameters p) { return false; }

    /* ---------- Ladekabel ---------- */
    static boolean charging(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }
    static void logCharge(Context c, boolean on, long t) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (sp.contains("chg") && sp.getBoolean("chg", false) == on) return;
        sp.edit().putBoolean("chg", on).apply();
        append(c, "charge", new JSONArray().put(t).put(on ? 1 : 0));
    }

    /* ---------- Schritte ---------- */
    static boolean stepsOk(Context c) {
        if (Build.VERSION.SDK_INT >= 29 && c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return false;
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
    }
    boolean readSteps(Context c, JobParameters p) {
        if (!stepsOk(c)) return false;
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        Handler h = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};
        SensorEventListener l = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                if (done[0]) return; done[0] = true;
                append(c, "steps", new JSONArray().put(System.currentTimeMillis()).put((long) e.values[0]));
                sm.unregisterListener(this); jobFinished(p, false);
            }
            @Override public void onAccuracyChanged(Sensor s, int a) { }
        };
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL);
        h.postDelayed(() -> { if (!done[0]) { done[0] = true; sm.unregisterListener(l); jobFinished(p, false); } }, 10000);
        return true;
    }

    /* ---------- Spur während Wegen ---------- */
    static void planTrace(Context c) {
        try {
            String raw = c.getSharedPreferences(Widget.PREF, Context.MODE_PRIVATE).getString("data", "{}");
            JSONArray items = raw.trim().startsWith("[") ? new JSONArray(raw) : new JSONObject(raw).optJSONArray("items");
            long now = System.currentTimeMillis(), next = 0;
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                long d = o.optLong("depart", 0), a = o.optLong("arrive", 0); if (d == 0 || a == 0) continue;
                long s = d - 50 * 60000L, e = a + 10 * 60000L;
                /* bis 15 min vor dem Losgehen alle 3 min (ob du am geplanten Ort bist), danach jede Minute */
                long t = now >= s && now < e ? now + (now < d - 15 * 60000L ? 180000L : 60000L) : now < s ? s : 0;
                if (t > 0 && (next == 0 || t < next)) next = t;
            }
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(c, 8200, new Intent(c, SenseReceiver.class).setAction(TRACE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
            if (next > 0 && Widget.bgOk(c)) Alarms.set(c, am, next, pi);
        } catch (Exception ignored) { }
    }
    /* Bester verfügbarer Anbieter: Fused (Android 12+, kombiniert GPS, WLAN, Mobilfunk), sonst GPS, sonst Netz */
    static String provider(LocationManager lm, boolean precise) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) return LocationManager.FUSED_PROVIDER;
        } catch (Exception ignored) { }
        boolean gps = false, net = false;
        try { gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) { }
        try { net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) { }
        if (precise && gps) return LocationManager.GPS_PROVIDER;
        if (net) return LocationManager.NETWORK_PROVIDER;
        return gps ? LocationManager.GPS_PROVIDER : null;
    }
    static void tracePoint(Context c) { fix(c, true); }
    /* Einmal orten. trace = während eines Weges (genau, in die Spur), sonst sparsam nur für „wo bin ich“ */
    @SuppressWarnings("MissingPermission")
    static void fix(Context c, boolean trace) {
        if (!Widget.bgOk(c)) return;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            /* Ein frischer Punkt von einer anderen App reicht oft schon (kostet keinen Akku) */
            Location fresh = null;
            for (String p : new String[]{"fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try { Location l = lm.getLastKnownLocation(p); if (l != null && System.currentTimeMillis() - l.getTime() < (trace ? 45000L : 180000L) && l.getAccuracy() <= (trace ? 60 : 250) && (fresh == null || l.getAccuracy() < fresh.getAccuracy())) fresh = l; } catch (Exception ignored) { }
            }
            if (fresh != null) { use(c, fresh, trace); return; }
            String prov = provider(lm, trace);
            if (prov == null) return;
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(prov, null, c.getMainExecutor(), l -> {
                    if (l == null) return;
                    use(c, l, trace);
                    /* Ungenau (z. B. nur Funkzelle) während eines Weges: einmal mit GPS nachlegen */
                    try { if (trace && l.getAccuracy() > 80 && !LocationManager.GPS_PROVIDER.equals(prov) && lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                        lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null, c.getMainExecutor(), g -> { if (g != null && g.getAccuracy() < l.getAccuracy()) use(c, g, true); }); } catch (Exception ignored) { }
                });
            } else {
                Location best = null;
                for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
                if (best != null) use(c, best, trace);
            }
        } catch (Exception ignored) { }
    }
    static void use(Context c, Location l, boolean trace) {
        if (trace) savePoint(c, l);
        else { Widget.savePos(c, l.getLatitude(), l.getLongitude(), l.getTime(), l.getAccuracy()); Widget.renderOnly(c); }
    }
    /* Passiv: Standorte, die ohnehin für andere Apps bestimmt werden, kostenlos mitlesen */
    @SuppressWarnings("MissingPermission")
    static void startPassive(Context c) {
        if (!Widget.bgOk(c)) return;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(c, 8300, new Intent(c, SenseReceiver.class).setAction(LOC), fl);
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 60000L, 30f, pi);
        } catch (Exception ignored) { }
    }
    static void passive(Context c, Location l) {
        if (l == null || l.getAccuracy() > 250) return;
        SharedPreferences sp = c.getSharedPreferences(Widget.PREF, Context.MODE_PRIVATE);
        /* Nicht durch einen ungenaueren, kaum neueren Punkt ersetzen */
        if (l.getTime() - sp.getLong("posAt", 0) < 60000L && l.getAccuracy() > sp.getFloat("posAcc", 9999f)) return;
        Widget.savePos(c, l.getLatitude(), l.getLongitude(), l.getTime(), l.getAccuracy());
        /* Während eines geplanten Weges zählt jeder gute Punkt auch für die Spur */
        if (inTrip(c, l.getTime()) && l.getAccuracy() <= 80) savePoint(c, l);
    }
    static boolean inTrip(Context c, long now) {
        try {
            String raw = c.getSharedPreferences(Widget.PREF, Context.MODE_PRIVATE).getString("data", "{}");
            JSONArray items = raw.trim().startsWith("[") ? new JSONArray(raw) : new JSONObject(raw).optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) { JSONObject o = items.getJSONObject(i); long d = o.optLong("depart", 0), a = o.optLong("arrive", 0); if (d > 0 && now >= d - 20 * 60000L && now <= a + 10 * 60000L) return true; }
        } catch (Exception ignored) { }
        return false;
    }
    static void savePoint(Context c, Location l) {
        try { append(c, "trace", new JSONArray().put(l.getTime()).put(Math.round(l.getLatitude() * 1e5) / 1e5).put(Math.round(l.getLongitude() * 1e5) / 1e5).put(Math.round(l.getAccuracy()))); } catch (Exception ignored) { }
        Widget.savePos(c, l.getLatitude(), l.getLongitude(), l.getTime(), l.getAccuracy());
        Reroute.check(c, l.getLatitude(), l.getLongitude(), l.getAccuracy());
    }

    /* ---------- App-Nutzung ---------- */
    static String apps(Context c, long from, long to) {
        JSONArray out = new JSONArray();
        if (!Sleep.ok(c)) return out.toString();
        try {
            PackageManager pm = c.getPackageManager();
            String home = "";
            ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            if (ri != null && ri.activityInfo != null) home = ri.activityInfo.packageName;
            UsageStatsManager um = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents ev = um.queryEvents(from, to);
            UsageEvents.Event e = new UsageEvents.Event();
            Map<String, Long> open = new HashMap<>();
            Map<String, String> names = new HashMap<>();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                String pkg = e.getPackageName(); int t = e.getEventType();
                if (pkg == null || pkg.equals(c.getPackageName()) || pkg.equals(home) || pkg.startsWith("com.android.systemui")) continue;
                if (t == UsageEvents.Event.ACTIVITY_RESUMED) { if (!open.containsKey(pkg)) open.put(pkg, e.getTimeStamp()); }
                else if (t == UsageEvents.Event.ACTIVITY_PAUSED || t == UsageEvents.Event.ACTIVITY_STOPPED) {
                    Long s = open.remove(pkg); if (s == null) continue;
                    long d = e.getTimeStamp() - s; if (d < 5000) continue;
                    String nm = names.get(pkg);
                    if (nm == null) { try { nm = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception x) { nm = pkg.substring(pkg.lastIndexOf('.') + 1); } names.put(pkg, nm); }
                    out.put(new JSONArray().put(s).put(e.getTimeStamp()).put(pkg).put(nm));
                }
            }
        } catch (Exception ignored) { }
        return out.toString();
    }

    /* ---------- Speicher ---------- */
    static synchronized void append(Context c, String key, JSONArray row) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(sp.getString(key, "[]")), keep = new JSONArray();
            long cut = System.currentTimeMillis() - 45 * DAY;
            int start = Math.max(0, a.length() - 8000);
            for (int i = start; i < a.length(); i++) { JSONArray r = a.getJSONArray(i); if (r.getLong(0) >= cut) keep.put(r); }
            keep.put(row);
            sp.edit().putString(key, keep.toString()).apply();
        } catch (Exception e) { sp.edit().putString(key, new JSONArray().put(row).toString()).apply(); }
    }
    static String read(Context c, String key, long from, long to) {
        JSONArray out = new JSONArray();
        try {
            JSONArray a = new JSONArray(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) { JSONArray r = a.getJSONArray(i); long t = r.getLong(0); if (t >= from && t <= to) out.put(r); }
        } catch (Exception ignored) { }
        return out.toString();
    }
}
