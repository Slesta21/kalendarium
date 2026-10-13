package de.kalendarium.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/* Benachrichtigungen: ein Kanal je Vibrationsmuster (Android spielt das Muster
   selbst ab), dazu ein stiller Kanal für das Live-Banner mit Countdown. */
public class Notify {
    static final String[] IDS = {"wecker", "doppelt", "kurz", "puls", "herz", "lang", "sos", "aus"};
    static final String[] NAMES = {"Wecker", "Doppelt", "Kurz", "Puls", "Herzschlag", "Lang", "SOS", "Ohne Vibration"};
    static final String CH_LIVE = "kal_live_v1";

    static long[] pattern(String id) {
        if (id == null) id = "doppelt";
        switch (id) {
            case "wecker": return new long[]{0, 600, 250, 600, 250, 900};
            case "kurz": return new long[]{0, 250};
            case "puls": return new long[]{0, 150, 100, 150, 100, 150, 100, 150};
            case "herz": return new long[]{0, 120, 90, 240, 500, 120, 90, 240};
            case "lang": return new long[]{0, 1200};
            case "sos": return new long[]{0, 150, 100, 150, 100, 150, 300, 450, 150, 450, 150, 450, 300, 150, 100, 150, 100, 150};
            case "aus": return new long[0];
            default: return new long[]{0, 250, 150, 250};
        }
    }

    static String channel(String id) {
        for (String k : IDS) if (k.equals(id)) return "kal_vib_" + k + "_v1";
        return "kal_vib_doppelt_v1";
    }

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        try { nm.deleteNotificationChannel("kal_losgehen_v1"); nm.deleteNotificationChannel("kal_termine_v1"); } catch (Exception ignored) { }
        AudioAttributes snd = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        for (int i = 0; i < IDS.length; i++) {
            if (nm.getNotificationChannel(channel(IDS[i])) != null) continue;
            NotificationChannel ch = new NotificationChannel(channel(IDS[i]), "Erinnerung · " + NAMES[i], NotificationManager.IMPORTANCE_HIGH);
            long[] p = pattern(IDS[i]);
            ch.enableVibration(p.length > 0);
            if (p.length > 0) ch.setVibrationPattern(p);
            ch.enableLights(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), snd);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_LIVE) == null) {
            NotificationChannel live = new NotificationChannel(CH_LIVE, "Live-Banner", NotificationManager.IMPORTANCE_LOW);
            live.setDescription("Countdown bis zum Losgehen, dauerhaft sichtbar");
            live.enableVibration(false);
            live.setSound(null, null);
            live.setShowBadge(false);
            live.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(live);
        }
    }

    static PendingIntent open(Context c, int id) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void show(Context c, int id, String title, String body, int prio, String vib) {
        ensureChannels(c);
        String chId = channel(vib);
        Notification.Builder b = new Notification.Builder(c, chId)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(open(c, id))
                .setShowWhen(true)
                .setColor(0xFF3B4CCA)
                .setCategory(prio >= 5 ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try { if (nm != null) nm.notify(id, b.build()); } catch (SecurityException e) { /* keine Erlaubnis */ }
        /* Falls Android die Kanal-Vibration unterdrückt (lautlos, Kanal ohne Vibration), selbst vibrieren */
        long[] p = pattern(vib);
        if (p.length == 0) return;
        boolean chVib = true;
        try { NotificationChannel ch = nm != null ? nm.getNotificationChannel(chId) : null; chVib = ch != null && ch.shouldVibrate() && ch.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT; } catch (Exception ignored) { }
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        boolean silent = am != null && am.getRingerMode() == AudioManager.RINGER_MODE_SILENT;
        if (!chVib || silent) vibrate(c, p);
    }

    /* Live-Banner: dauerhaft, still, mit live laufendem Countdown bis "when" */
    static void live(Context c, int id, String title, String body, String phase, long when, long until) {
        ensureChannels(c);
        long now = System.currentTimeMillis();
        if (until <= now) { cancel(c, id); return; }
        Notification.Builder b = new Notification.Builder(c, CH_LIVE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setSubText(phase)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(when)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setColor(0xFF3B4CCA)
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open(c, id))
                .setTimeoutAfter(until - now);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try { if (nm != null) nm.notify(id, b.build()); } catch (SecurityException e) { /* keine Erlaubnis */ }
    }

    static void cancel(Context c, int id) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(id);
    }

    /* Eigene Vibration als Wecker: vibriert auch im Lautlos-Modus */
    static void vibrate(Context c, long[] pattern) {
        try {
            if (pattern == null || pattern.length == 0) return;
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null || !v.hasVibrator()) return;
            VibrationEffect e = VibrationEffect.createWaveform(pattern, -1);
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
            else v.vibrate(e, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        } catch (Exception ignored) { }
    }
}
