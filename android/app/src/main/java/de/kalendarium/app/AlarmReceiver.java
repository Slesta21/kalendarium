package de.kalendarium.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String key = i.getStringExtra("key");
        Notify.show(c, key != null ? key.hashCode() : (int) (System.currentTimeMillis() & 0x7fffffff),
                i.getStringExtra("title"), i.getStringExtra("body"), i.getIntExtra("prio", 4));
    }
}
