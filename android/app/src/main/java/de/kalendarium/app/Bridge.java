package de.kalendarium.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONObject;

/* Schnittstelle für die Web-App: window.KalNative */
public class Bridge {
    final Context ctx;
    Bridge(Context c) { ctx = c.getApplicationContext(); }

    @JavascriptInterface public String version() {
        try { return String.valueOf(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode()); } catch (Exception e) { return "0"; }
    }

    /* Was diese App-Version kann, damit die Web-App passend reagiert */
    @JavascriptInterface public String features() { return "vib,live,widget,widget2,sleep"; }

    /* Startbildschirm-Widget mit den nächsten Terminen füttern */
    @JavascriptInterface public void widget(String json) { Widget.save(ctx, json); }
    @JavascriptInterface public boolean widgetPin() { return Widget.pin(ctx); }
    @JavascriptInterface public boolean widgetOn() { return Widget.any(ctx); }
    @JavascriptInterface public boolean bgLocationOk() { return Widget.bgOk(ctx); }
    /* Standort „Immer erlauben“: Android lässt das nur über die App-Einstellungen zu */
    @JavascriptInterface public void bgLocation() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); } catch (Exception ignored) { }
    }

    /* Schlaf aus Bildschirmzeiten erkennen (Nutzungsdatenzugriff) */
    @JavascriptInterface public boolean usageOk() { return Sleep.ok(ctx); }
    @JavascriptInterface public void usageOpen() { Sleep.open(ctx); }
    @JavascriptInterface public String screenEvents(double from, double to) { return Sleep.events(ctx, (long) from, (long) to); }

    @JavascriptInterface public void vibrateNamed(String id) { Notify.vibrate(ctx, Notify.pattern(id)); }

    @JavascriptInterface public int schedule(String json) { return Alarms.schedule(ctx, json); }

    @JavascriptInterface public void vibrate(String csv) {
        try {
            String[] p = csv.split(",");
            long[] w = new long[p.length];
            for (int i = 0; i < p.length; i++) w[i] = Long.parseLong(p[i].trim());
            Notify.vibrate(ctx, w);
        } catch (Exception ignored) { }
    }

    @JavascriptInterface public void notifyNow(String title, String body, int prio) {
        Notify.show(ctx, (int) (System.currentTimeMillis() & 0x7fffffff), title, body, prio, prio >= 5 ? "wecker" : "doppelt");
    }

    @JavascriptInterface public void notifyVib(String title, String body, String vib) {
        Notify.show(ctx, (int) (System.currentTimeMillis() & 0x7fffffff), title, body, 5, vib);
    }

    /* Live-Banner sofort zeigen (Vorschau) */
    @JavascriptInterface public void liveNow(String title, String body, String phase, double when, double until) {
        Notify.live(ctx, 4242, title, body, phase, (long) when, (long) until);
    }

    /* Test-Wecker in X Sekunden (Handy sperren und abwarten) */
    @JavascriptInterface public void testIn(int seconds, String title, String body) {
        try {
            JSONObject o = new JSONObject();
            o.put("key", "test:" + System.currentTimeMillis());
            o.put("at", System.currentTimeMillis() + seconds * 1000L);
            o.put("title", title); o.put("body", body); o.put("prio", 5); o.put("vib", "wecker");
            android.app.AlarmManager am = (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) Alarms.set(ctx, am, o.getLong("at"), Alarms.intent(ctx, o));
        } catch (Exception ignored) { }
    }

    @JavascriptInterface public String status() {
        JSONObject o = new JSONObject();
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            o.put("notif", nm != null && nm.areNotificationsEnabled());
            NotificationChannel ch = nm != null ? nm.getNotificationChannel(Notify.channel("wecker")) : null;
            o.put("channel", ch != null ? ch.getImportance() : -1);
            o.put("channelVib", ch != null && ch.shouldVibrate());
            o.put("exact", Alarms.exactAllowed(ctx));
            o.put("planned", Alarms.count(ctx));
        } catch (Exception ignored) { }
        return o.toString();
    }

    @JavascriptInterface public void openSettings() {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); } catch (Exception ignored) { }
    }
}
