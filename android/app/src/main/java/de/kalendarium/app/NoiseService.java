package de.kalendarium.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import org.json.JSONArray;

/* Nachtmessung: liest nur die Lautstärke des Mikrofons (Pegel), nimmt nichts auf und
   speichert keinen Ton. Alle 30 s ein Wert: Mittel und Spitze in dB (nicht geeicht). */
public class NoiseService extends Service {
    static final String CH = "kal_noise_v1", STOP = "de.kalendarium.app.NOISE_STOP";
    static volatile boolean running = false;
    Thread th; PowerManager.WakeLock wl;

    static void start(Context c, long until) {
        Intent i = new Intent(c, NoiseService.class).putExtra("until", until);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    static void stop(Context c) { c.startService(new Intent(c, NoiseService.class).setAction(STOP)); }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && STOP.equals(i.getAction())) { running = false; stopSelf(); return START_NOT_STICKY; }
        long until = i != null ? i.getLongExtra("until", 0) : 0;
        if (until <= System.currentTimeMillis()) until = System.currentTimeMillis() + 9 * 3600000L;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Nachtmessung", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Läuft, solange die Lautstärke in der Nacht gemessen wird"); ch.enableVibration(false); ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, NoiseService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Nachtmessung läuft").setContentText("Nur die Lautstärke, keine Aufnahme. Endet um " + Widget.hm(until) + ".")
            .setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Beenden", stop).build()).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(4300, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); else startForeground(4300, n);
        } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        running = true;
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) { wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kal:noise"); wl.acquire(Math.max(60000L, until - System.currentTimeMillis() + 60000L)); }
        final long end = until;
        getSharedPreferences(Sense.PREF, MODE_PRIVATE).edit().putLong("noiseUntil", end).putLong("noiseFrom", System.currentTimeMillis()).apply();
        th = new Thread(() -> loop(end)); th.start();
        return START_NOT_STICKY;
    }

    @SuppressWarnings("MissingPermission")
    void loop(long until) {
        int rate = 8000, min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = null;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
            ar.startRecording();
            short[] buf = new short[rate / 4];
            double sum = 0, peak = 0; int n = 0, k2 = 0; long bucket = System.currentTimeMillis();
            while (running && System.currentTimeMillis() < until) {
                int r = ar.read(buf, 0, buf.length); if (r <= 0) continue;
                double s = 0; for (int k = 0; k < r; k++) s += (double) buf[k] * buf[k];
                double db = 20 * Math.log10(Math.sqrt(s / r) / 32767.0 + 1e-9) + 90;
                sum += db; n++; if (db > peak) peak = db;
                if (System.currentTimeMillis() - bucket >= 30000) {
                    Sense.append(this, "noise", new JSONArray().put(bucket).put(Math.round(sum / n)).put(Math.round(peak)));
                    sum = 0; peak = 0; n = 0; bucket = System.currentTimeMillis();
                    if (++k2 % 2 == 0 && Light.on(this)) Light.sample(this, null);
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (ar != null) { ar.stop(); ar.release(); } } catch (Exception ignored) { }
            running = false;
            getSharedPreferences(Sense.PREF, MODE_PRIVATE).edit().putLong("noiseUntil", 0).apply();
            stopSelf();
        }
    }

    @Override public void onDestroy() { running = false; try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) { } super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
