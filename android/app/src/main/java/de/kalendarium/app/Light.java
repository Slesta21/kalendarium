package de.kalendarium.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import org.json.JSONArray;
import java.util.Calendar;

/* Helligkeit um das Handy herum, ohne Kamera: der Lichtsensor vorne oben (Lux).
   Tagsüber alle ~5 min, nachts alle ~10 min ein Messwert von 1–2 Sekunden.
   Dazu der Näherungssensor: Liegt etwas direkt davor (Hosentasche, Tasche,
   Display nach unten), ist der Wert nicht aussagekräftig und wird markiert.
   Zeile: [Zeit, Lux, verdeckt (1/0, -1 = kein Sensor), Bildschirm an (1/0)] */
public class Light {
    static final String ACT = "de.kalendarium.app.LIGHT";

    static boolean sensor(Context c) {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null;
    }
    static boolean on(Context c) { return sensor(c) && c.getSharedPreferences(Sense.PREF, Context.MODE_PRIVATE).getBoolean("lightOn", true); }
    static void set(Context c, boolean v) { c.getSharedPreferences(Sense.PREF, Context.MODE_PRIVATE).edit().putBoolean("lightOn", v).apply(); plan(c); }

    static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 8300, new Intent(c, SenseReceiver.class).setAction(ACT), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static void plan(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE); if (am == null) return;
            PendingIntent p = pi(c); am.cancel(p);
            if (!on(c)) return;
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            long step = (h >= 6 && h < 23) ? 5 * 60000L : 10 * 60000L;
            /* ungenau reicht, spart Akku; Android bündelt das mit anderen Weckern */
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + step, p);
        } catch (Exception ignored) { }
    }

    /* Ein Messwert. done läuft immer, auch ohne Sensor. */
    static void sample(Context c, Runnable done) {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor ls = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (ls == null) { if (done != null) done.run(); return; }
        Sensor ps = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        final float[] lux = {-1}; final int[] prox = {ps == null ? -1 : -2};
        final boolean[] fin = {false};
        Handler h = new Handler(Looper.getMainLooper());
        final SensorEventListener[] ref = new SensorEventListener[1];
        Runnable finish = () -> {
            if (fin[0]) return; fin[0] = true;
            try { sm.unregisterListener(ref[0]); } catch (Exception ignored) { }
            if (lux[0] >= 0) {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                int scr = pm != null && pm.isInteractive() ? 1 : 0;
                try { Sense.append(c, "light", new JSONArray().put(System.currentTimeMillis()).put(Math.round(lux[0] * 10) / 10.0).put(prox[0] == -2 ? -1 : prox[0]).put(scr)); } catch (Exception ignored) { }
            }
            if (done != null) done.run();
        };
        ref[0] = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                if (e.sensor.getType() == Sensor.TYPE_LIGHT) lux[0] = e.values[0];
                else if (e.sensor.getType() == Sensor.TYPE_PROXIMITY) prox[0] = e.values[0] < Math.min(e.sensor.getMaximumRange(), 5f) ? 1 : 0;
                /* ersten Lichtwert kurz abwarten, der zweite ist oft genauer */
                if (lux[0] >= 0 && prox[0] != -2) h.postDelayed(finish, 600);
            }
            @Override public void onAccuracyChanged(Sensor s, int a) { }
        };
        sm.registerListener(ref[0], ls, SensorManager.SENSOR_DELAY_NORMAL);
        if (ps != null) sm.registerListener(ref[0], ps, SensorManager.SENSOR_DELAY_NORMAL);
        h.postDelayed(finish, 2500);
    }

    static void onAlarm(Context c, BroadcastReceiver.PendingResult pr) {
        plan(c);
        if (!on(c)) { if (pr != null) pr.finish(); return; }
        sample(c, () -> { if (pr != null) pr.finish(); });
    }
}
