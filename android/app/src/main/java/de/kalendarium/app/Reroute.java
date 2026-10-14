package de.kalendarium.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/* Nicht am geplanten Ort (z. B. kurz essen), App aber zu:
   Mit dem Standortpunkt aus dem Hintergrund wird der nächste Weg ab dem
   Standort neu gesucht (spätestens pünktlich ankommen). Widget zeigt die
   neue Losgehzeit und Bahn; musst du deshalb früher los, kommt eine
   Benachrichtigung. Öffnest du die App, rechnet sie selbst weiter. */
public class Reroute {
    static void check(Context c, double lat, double lon, float acc) {
        if (acc > 200) return;
        new Thread(() -> {
            try {
                SharedPreferences sp = c.getSharedPreferences(Widget.PREF, Context.MODE_PRIVATE);
                String raw = sp.getString("data", "{}");
                if (!raw.trim().startsWith("{")) return;
                JSONObject o = new JSONObject(raw); JSONArray items = o.optJSONArray("items"); if (items == null) return;
                long now = System.currentTimeMillis(); boolean ch = false;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject e = items.getJSONObject(i);
                    long dep = e.optLong("depart", 0), by = e.optLong("arriveBy", 0);
                    String api = e.optString("api", ""), to = e.optString("toId", "");
                    JSONArray pl = e.optJSONArray("planLL"), tl = e.optJSONArray("toLL");
                    if (dep == 0 || by == 0 || api.isEmpty() || pl == null || e.optInt("noLive", 0) == 1) continue;
                    if (now < dep - 50 * 60000L || now > dep + 5 * 60000L) continue;
                    double d = dist(lat, lon, pl.getDouble(0), pl.getDouble(1));
                    if (d <= Math.max(250, acc + 120)) continue;
                    /* schon auf dem Weg? dann in Ruhe lassen */
                    if (now >= dep - 3 * 60000L && onPath(e, lat, lon)) continue;
                    JSONArray last = e.optJSONArray("rrLL");
                    if (last != null && dist(lat, lon, last.getDouble(0), last.getDouble(1)) < 200 && now - e.optLong("rrAt", 0) < 5 * 60000L) continue;
                    String q = api + "/journeys?from.latitude=" + r5(lat) + "&from.longitude=" + r5(lon) + "&from.address=Standort"
                        + (!to.isEmpty() ? "&to=" + java.net.URLEncoder.encode(to, "UTF-8") : tl != null ? "&to.latitude=" + r5(tl.getDouble(0)) + "&to.longitude=" + r5(tl.getDouble(1)) + "&to.address=Ziel" : "")
                        + "&arrival=" + java.net.URLEncoder.encode(iso(by), "UTF-8") + "&results=4&stopovers=false&remarks=false&polylines=false&language=de";
                    if (to.isEmpty() && tl == null) continue;
                    JSONObject r = LiveRefresh.get(q); if (r == null) continue;
                    JSONArray js = r.optJSONArray("journeys"); if (js == null || js.length() == 0) continue;
                    JSONObject best = null; long bestDep = 0;
                    for (int k = 0; k < js.length(); k++) {
                        JSONArray legs = js.getJSONObject(k).optJSONArray("legs"); if (legs == null || legs.length() == 0) continue;
                        JSONObject l0 = legs.getJSONObject(0), lz = legs.getJSONObject(legs.length() - 1);
                        long a = LiveRefresh.iso(lz.optString("arrival", lz.optString("plannedArrival", ""))), dp = LiveRefresh.iso(l0.optString("departure", l0.optString("plannedDeparture", "")));
                        if (a == 0 || dp == 0 || a > by + 60000) continue;
                        if (best == null || dp > bestDep) { best = js.getJSONObject(k); bestDep = dp; }
                    }
                    if (best == null) continue;
                    JSONArray legs = best.getJSONArray("legs"); JSONObject lz = legs.getJSONObject(legs.length() - 1);
                    long arr = LiveRefresh.iso(lz.optString("arrival", lz.optString("plannedArrival", "")));
                    long old = dep;
                    e.put("depart", bestDep); e.put("arrive", arr); e.put("fromHere", 1); e.put("rrAt", now); e.put("rrLL", new JSONArray().put(r5(lat)).put(r5(lon)));
                    e.remove("pts");
                    for (int k = 0; k < legs.length(); k++) {
                        JSONObject g = legs.getJSONObject(k); if (g.optBoolean("walking", false)) continue;
                        JSONObject ln = g.optJSONObject("line"), fs = g.optJSONObject("origin"), ts = g.optJSONObject("destination");
                        if (ln != null) e.put("line", ln.optString("name", e.optString("line", "")));
                        e.put("lineDep", LiveRefresh.iso(g.optString("departure", g.optString("plannedDeparture", ""))));
                        e.put("tripId", g.optString("tripId", "")); e.put("from", fs != null ? fs.optString("name", "") : ""); e.put("fromId", fs != null ? fs.optString("id", "") : "");
                        e.put("legToId", ts != null ? ts.optString("id", "") : ""); e.put("legArr", LiveRefresh.iso(g.optString("arrival", g.optString("plannedArrival", ""))));
                        e.put("plat", g.optString("departurePlatform", "")); e.put("delay", g.isNull("departureDelay") ? 0 : Math.max(0, Math.round(g.optInt("departureDelay", 0) / 60.0)));
                        break;
                    }
                    ch = true;
                    if (bestDep < old - 2 * 60000L && bestDep - now < 25 * 60000L) {
                        String t = Widget.hm(bestDep), ln = e.optString("line", "");
                        Notify.show(c, ("rr" + e.optString("title", "")).hashCode(), "Früher los: " + t,
                            "Du bist nicht am geplanten Ort. Weg zu " + e.optString("title", "deinem Termin") + " ab hier" + (ln.isEmpty() ? "" : " mit " + ln) + ", an " + Widget.hm(arr) + ".", 5, "doppelt");
                    }
                }
                if (ch) { sp.edit().putString("data", o.toString()).apply(); new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> { try { Widget.updateAll(c); } catch (Exception ignored) { } }); }
            } catch (Exception ignored) { }
        }).start();
    }

    static boolean onPath(JSONObject e, double lat, double lon) {
        try {
            JSONArray pts = e.optJSONArray("pts"); if (pts == null) return false;
            for (int i = 0; i < pts.length(); i++) { JSONArray p = pts.getJSONArray(i); if (dist(lat, lon, p.getDouble(0), p.getDouble(1)) < 250) return true; }
        } catch (Exception ignored) { }
        return false;
    }
    static double r5(double v) { return Math.round(v * 1e5) / 1e5; }
    static String iso(long t) { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new Date(t)); }
    static double dist(double a1, double o1, double a2, double o2) {
        double r = Math.PI / 180, dA = (a2 - a1) * r, dO = (o2 - o1) * r;
        double h = Math.sin(dA / 2) * Math.sin(dA / 2) + Math.cos(a1 * r) * Math.cos(a2 * r) * Math.sin(dO / 2) * Math.sin(dO / 2);
        return 12742000 * Math.asin(Math.sqrt(h));
    }
}
