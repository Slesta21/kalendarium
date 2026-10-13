package de.kalendarium.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            JSONObject o = new JSONObject(i.getStringExtra("json"));
            Alarms.fire(c, o);
            if ("live".equals(o.optString("type"))) Alarms.markLive(c, Alarms.nid(o));
        } catch (Exception ignored) { }
    }
}
