package de.kalendarium.app;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import org.json.JSONArray;

/* Schlaf erkennen: Wann war der Bildschirm an, wann wurde entsperrt.
   Android speichert diese Ereignisse einige Tage lang (Nutzungsdaten).
   Die Auswertung macht die Web-App, hier werden nur Rohdaten gelesen. */
public class Sleep {
    static boolean ok(Context ctx) {
        try {
            AppOpsManager ao = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            int m = Build.VERSION.SDK_INT >= 29
                ? ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName())
                : ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
            return m == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    static void open(Context ctx) {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { i.setData(android.net.Uri.parse("package:" + ctx.getPackageName())); ctx.startActivity(i); return; } catch (Exception ignored) { }
        try { ctx.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }

    /* [[zeit, typ], …] mit typ: 1 Bildschirm an, 0 aus, 2 entsperrt, 3 gesperrt, 4 App im Vordergrund */
    static String events(Context ctx, long from, long to) {
        JSONArray out = new JSONArray();
        if (Build.VERSION.SDK_INT < 28 || !ok(ctx)) return out.toString();
        try {
            UsageStatsManager um = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents ev = um.queryEvents(from, to);
            UsageEvents.Event e = new UsageEvents.Event();
            long lastApp = 0;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int t = e.getEventType(), k;
                if (t == UsageEvents.Event.SCREEN_INTERACTIVE) k = 1;
                else if (t == UsageEvents.Event.SCREEN_NON_INTERACTIVE) k = 0;
                else if (t == UsageEvents.Event.KEYGUARD_HIDDEN) k = 2;
                else if (t == UsageEvents.Event.KEYGUARD_SHOWN) k = 3;
                else if (t == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (e.getTimeStamp() - lastApp < 60000) continue;
                    lastApp = e.getTimeStamp(); k = 4;
                } else continue;
                JSONArray a = new JSONArray(); a.put(e.getTimeStamp()); a.put(k); out.put(a);
            }
        } catch (Exception ignored) { }
        return out.toString();
    }
}
