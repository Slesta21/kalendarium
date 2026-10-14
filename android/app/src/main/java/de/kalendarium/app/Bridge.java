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
    final Context ctx; final android.app.Activity act;
    Bridge(android.app.Activity a) { ctx = a.getApplicationContext(); act = a; }
    void ask(String... perms) { try { act.runOnUiThread(() -> act.requestPermissions(perms, 7)); } catch (Exception ignored) { } }

    @JavascriptInterface public String version() {
        try { return String.valueOf(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode()); } catch (Exception e) { return "0"; }
    }

    /* Was diese App-Version kann, damit die Web-App passend reagiert */
    @JavascriptInterface public String features() { return "vib,live,widget,widget2,sleep,sense,fetch"; }

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

    /* Sensoren: Ladekabel, Schritte, Spur, App-Nutzung, Lärm (alles lokal) */
    @JavascriptInterface public String charge(double from, double to) { return Sense.read(ctx, "charge", (long) from, (long) to); }
    @JavascriptInterface public String steps(double from, double to) { return Sense.read(ctx, "steps", (long) from, (long) to); }
    @JavascriptInterface public String trace(double from, double to) { return Sense.read(ctx, "trace", (long) from, (long) to); }
    @JavascriptInterface public String noise(double from, double to) { return Sense.read(ctx, "noise", (long) from, (long) to); }
    @JavascriptInterface public String apps(double from, double to) { return Sense.apps(ctx, (long) from, (long) to); }
    @JavascriptInterface public boolean stepsOk() { return Sense.stepsOk(ctx); }
    @JavascriptInterface public boolean stepsSensor() { android.hardware.SensorManager sm = (android.hardware.SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE); return sm != null && sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER) != null; }
    @JavascriptInterface public void askSteps() { if (android.os.Build.VERSION.SDK_INT >= 29) ask(android.Manifest.permission.ACTIVITY_RECOGNITION); Sense.ensure(ctx); }
    @JavascriptInterface public boolean micOk() { return ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED; }
    @JavascriptInterface public void askMic() { ask(android.Manifest.permission.RECORD_AUDIO); }
    @JavascriptInterface public boolean noiseStart(double until) { if (!micOk()) return false; try { act.runOnUiThread(() -> NoiseService.start(act, (long) until)); return true; } catch (Exception e) { return false; } }
    @JavascriptInterface public void noiseStop() { try { NoiseService.stop(ctx); } catch (Exception ignored) { } }
    @JavascriptInterface public double noiseUntil() { return NoiseService.running ? ctx.getSharedPreferences(Sense.PREF, Context.MODE_PRIVATE).getLong("noiseUntil", 0) : 0; }
    @JavascriptInterface public boolean charging() { return Sense.charging(ctx); }
    /* Bild teilen (Monatsrückblick): in „Bilder/Kalendarium“ ablegen und das Teilen-Menü öffnen */
    @JavascriptInterface public boolean shareImage(String dataUrl, String text) {
        try {
            byte[] png = android.util.Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), android.util.Base64.DEFAULT);
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "kalendarium-" + System.currentTimeMillis() + ".png");
            v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (android.os.Build.VERSION.SDK_INT >= 29) v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kalendarium");
            android.net.Uri u = ctx.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return false;
            try (java.io.OutputStream os = ctx.getContentResolver().openOutputStream(u)) { os.write(png); }
            Intent s = new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, u).putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.runOnUiThread(() -> act.startActivity(Intent.createChooser(s, "Rückblick teilen")));
            return true;
        } catch (Exception e) { return false; }
    }

    /* Text von einer https-Adresse holen (z. B. ISIS-Kalender), ohne die Sperre des Browsers. Antwort kommt über window.__kalFetch */
    @JavascriptInterface public void fetchText(String url, String id) {
        new Thread(() -> {
            String res; boolean ok = false;
            try {
                if (url == null || !url.startsWith("https://")) throw new Exception("Nur https-Adressen");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(12000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Kalendarium-App");
                int code = c.getResponseCode();
                java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
                while (in != null && (n = in.read(buf)) > 0 && bo.size() < 4_000_000) bo.write(buf, 0, n);
                res = bo.toString("UTF-8"); ok = code < 400; if (!ok) res = "HTTP " + code;
            } catch (Exception e) { res = String.valueOf(e.getMessage()); }
            final String js = "window.__kalFetch && window.__kalFetch(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(res) + ")";
            act.runOnUiThread(() -> { try { ((MainActivity) act).web.evaluateJavascript(js, null); } catch (Exception ignored) { } });
        }).start();
    }

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
