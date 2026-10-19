package de.kalendarium.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* Minütlicher Standortpunkt während eines geplanten Weges, Stopp der Nachtmessung */
public class SenseReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Sense.TRACE.equals(a)) { Sense.tracePoint(c); Sense.planTrace(c); }
        else if (Sense.LOC.equals(a)) { android.location.Location l = i.getParcelableExtra(android.location.LocationManager.KEY_LOCATION_CHANGED); Sense.passive(c, l); }
        else if (Light.ACT.equals(a)) Light.onAlarm(c, goAsync());
    }
}
