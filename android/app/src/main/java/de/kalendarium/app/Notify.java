package de.kalendarium.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/* Benachrichtigungen mit eigener, kräftiger Vibration (wie ein Wecker) */
public class Notify {
    static final String CH_HIGH = "kal_losgehen_v1";
    static final String CH_DEF = "kal_termine_v1";
    static final long[] STRONG = {0, 600, 250, 600, 250, 900};
    static final long[] NORMAL = {0, 350, 180, 350};

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        AudioAttributes snd = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        NotificationChannel h = new NotificationChannel(CH_HIGH, "Losgehen und wichtige Erinnerungen", NotificationManager.IMPORTANCE_HIGH);
        h.setDescription("Kurz vor dem Losgehen, mit starker Vibration");
        h.enableVibration(true);
        h.setVibrationPattern(STRONG);
        h.enableLights(true);
        h.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        h.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), snd);
        nm.createNotificationChannel(h);
        NotificationChannel d = new NotificationChannel(CH_DEF, "Termine und Fristen", NotificationManager.IMPORTANCE_HIGH);
        d.enableVibration(true);
        d.setVibrationPattern(NORMAL);
        d.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(d);
    }

    static void show(Context c, int id, String title, String body, int prio) {
        ensureChannels(c);
        boolean strong = prio >= 5;
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(c, strong ? CH_HIGH : CH_DEF)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setShowWhen(true)
                .setCategory(strong ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try { if (nm != null) nm.notify(id, b.build()); } catch (SecurityException e) { /* keine Erlaubnis */ }
        vibrate(c, strong ? STRONG : NORMAL);
    }

    /* Eigene Vibration als Wecker: vibriert auch im Lautlos-Modus */
    static void vibrate(Context c, long[] pattern) {
        try {
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
