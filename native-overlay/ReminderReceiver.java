package com.hemantjain.magicspintracker;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.util.Random;

public class ReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "magic_best_wish_channel";
    private static final int NOTIF_ID = 5201;
    private static final int REQ_CODE = 5201;
    private static final long ONE_HOUR_MS = 60L * 60L * 1000L;

    private static final String[] WISH_MESSAGES = {
            "Best of the Day! Aapka din shubh aur khushiyon se bhara rahe — fresh mind ke saath fir miliye!",
            "Best Wishes! Har naya pal ek naya mauka hai — sukoon aur discipline se aage badhein!",
            "Good Luck & Best Wishes! Aapka samay shubh ho, hamesha muskurate rahein!",
            "Shubh Kamnayein! Thoda aaram ke baad fresh energy ke saath shuruat karein!"
    };

    public static void scheduleOneHour(Context ctx) {
        if (ctx == null) return;
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = buildPendingIntent(ctx);
            long triggerAt = System.currentTimeMillis() + ONE_HOUR_MS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Exception ignored) {}
    }

    public static void cancel(Context ctx) {
        if (ctx == null) return;
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(buildPendingIntent(ctx));
        } catch (Exception ignored) {}
    }

    private static PendingIntent buildPendingIntent(Context ctx) {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQ_CODE, i, flags);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ctx == null) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        "Best Wish Reminders",
                        NotificationManager.IMPORTANCE_HIGH
                );
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }

            Intent openIntent = new Intent(ctx, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int pFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pFlags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent contentPi = PendingIntent.getActivity(ctx, 0, openIntent, pFlags);

            String msg = WISH_MESSAGES[new Random().nextInt(WISH_MESSAGES.length)];

            NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Magic Spin Tracker — Best Wishes!")
                    .setContentText(msg)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(msg))
                    .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(contentPi);

            nm.notify(NOTIF_ID, nb.build());
            SirenPlayer.playBestWishSong();
        } catch (Exception ignored) {}
    }
}
