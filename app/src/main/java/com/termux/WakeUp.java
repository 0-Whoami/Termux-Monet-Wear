package com.termux;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.termux.terminal.SessionManager;

public final class WakeUp extends Service {
    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if ("1".equals(intent.getAction())) this.startForeground();
        else this.stop();
        return Service.START_NOT_STICKY;
    }

    private void startForeground() {
        final var channel = new NotificationChannel("id", "channel", NotificationManager.IMPORTANCE_DEFAULT);
        this.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        final var notification = new Notification.Builder(this, "id").setContentTitle("Terminal Running").setSmallIcon(R.drawable.icon).setColor(0x000000).setOngoing(true).setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, NyxActivity.class), PendingIntent.FLAG_IMMUTABLE)).build();
        this.startForeground(1, notification);
    }

    private void stop() {
        this.stopForeground(Service.STOP_FOREGROUND_REMOVE);
        this.stopSelf();
        SessionManager.removeAll();
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }
}
