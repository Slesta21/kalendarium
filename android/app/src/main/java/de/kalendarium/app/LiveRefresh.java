package de.kalendarium.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/* Hält die Abfahrt im Widget aktuell, auch wenn die App zu ist:
   Während eines Weges höchstens einmal pro Minute die Fahrt beim
   Fahrplan-Server abfragen (transport.rest /trips/:id) und Abfahrt,
   Verspätung und Ankunft im gespeicherten Plan nachziehen. */
public class LiveRefresh {
    static void run(Context c, BroadcastReceiver.PendingResult pr) {
        new Thread(() -> {
            try {
                SharedPreferences sp = c.getSharedPreferences(Widget.PREF, Context.MODE_PRIVATE);
                String raw = sp.getString("data", "{}");
                if (raw.trim().startsWith("{")) {
                    JSONObject o = new JSONObject(raw); JSONArray items = o.optJSONArray("items");
                    long now = System.currentTimeMillis(); boolean ch = false;
                    if (items != null) for (int i = 0; i < items.length(); i++) {
                        JSONObject e = items.getJSONObject(i);
                        String trip = e.optString("tripId", ""), api = e.optString("api", ""), from = e.optString("fromId", "");
                        long d0 = e.optLong("depart", 0), a0 = e.optLong("arrive", 0);
                        if (trip.isEmpty() || api.isEmpty() || from.isEmpty() || d0 == 0) continue;
                        if (now < d0 - 25 * 60000L || now > a0 + 5 * 60000L) continue;
                        if (now - e.optLong("liveAt", 0) < 55000) continue;
                        JSONObject t = get(api + "/trips/" + URLEncoder.encode(trip, "UTF-8") + "?stopovers=true&remarks=false&polyline=false&language=de");
                        if (t == null) continue;
                        JSONObject tr = t.optJSONObject("trip") != null ? t.getJSONObject("trip") : t;
                        JSONArray st = tr.optJSONArray("stopovers"); if (st == null) continue;
                        String to = e.optString("legToId", "");
                        for (int k = 0; k < st.length(); k++) {
                            JSONObject s = st.getJSONObject(k); JSONObject stop = s.optJSONObject("stop"); if (stop == null) continue;
                            String id = stop.optString("id", "");
                            if (id.equals(from)) {
                                long nd = iso(s.optString("departure", s.optString("plannedDeparture", "")));
                                if (nd > 0) { e.put("lineDep", nd); e.put("delay", s.isNull("departureDelay") ? 0 : Math.max(0, Math.round(s.optInt("departureDelay", 0) / 60.0))); }
                            }
                            if (!to.isEmpty() && id.equals(to)) {
                                long na = iso(s.optString("arrival", s.optString("plannedArrival", ""))), old = e.optLong("legArr", 0);
                                if (na > 0 && old > 0 && na != old) { e.put("arrive", a0 + (na - old)); e.put("legArr", na); }
                            }
                        }
                        e.put("liveAt", now); ch = true;
                    }
                    if (ch) sp.edit().putString("data", o.toString()).apply();
                }
            } catch (Exception ignored) {
            } finally {
                new Handler(Looper.getMainLooper()).post(() -> { try { Widget.updateAll(c); } catch (Exception ignored) { } if (pr != null) pr.finish(); });
            }
        }).start();
    }

    static long iso(String s) {
        if (s == null || s.isEmpty() || "null".equals(s)) return 0;
        try { return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (Exception e) { return 0; }
    }

    static JSONObject get(String url) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(10000); h.setRequestProperty("User-Agent", "Kalendarium-App");
            if (h.getResponseCode() >= 400) return null;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
            try (java.io.InputStream in = h.getInputStream()) { while ((n = in.read(b)) > 0 && bo.size() < 3_000_000) bo.write(b, 0, n); }
            return new JSONObject(bo.toString("UTF-8"));
        } catch (Exception e) { return null; }
    }
}
