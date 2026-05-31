package dev.windex.battleserveroverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BattleServerOverlayService extends Service {
    public static final String ACTION_START = "dev.windex.battleserveroverlay.START";
    public static final String ACTION_STOP = "dev.windex.battleserveroverlay.STOP";
    public static final int COLLECTOR_PORT = 5123;

    private static final String CHANNEL_ID = "battle_server_overlay";
    private static final int NOTIFICATION_ID = 1001;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, Integer> packetCounts = new HashMap<>();

    private WindowManager windowManager;
    private TextView overlayView;
    private DatagramSocket socket;
    private volatile boolean running;
    private String currentAddress = "ждём пакеты";
    private int currentPort = -1;
    private String currentProtocol = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        startOverlay();
        startReceiver();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (socket != null) {
            socket.close();
        }
        executor.shutdownNow();
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startReceiver() {
        if (running) {
            return;
        }
        running = true;
        executor.execute(() -> {
            try {
                socket = new DatagramSocket(COLLECTOR_PORT);
                byte[] buffer = new byte[65535];
                while (running) {
                    DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                    socket.receive(datagram);
                    PacketInfo info = PcapIpParser.parse(datagram.getData(), datagram.getLength());
                    if (info != null) {
                        handlePacket(info);
                    }
                }
            } catch (SocketException ignored) {
                // Expected when the service is stopped and the socket is closed.
            } catch (Exception e) {
                updateOverlay("ошибка UDP: " + e.getClass().getSimpleName());
            }
        });
    }

    private void handlePacket(PacketInfo info) {
        String key = info.remoteAddress + ":" + info.remotePort + ":" + info.protocol;
        int count = packetCounts.containsKey(key) ? packetCounts.get(key) + 1 : 1;
        packetCounts.put(key, count);

        if (count >= packetCounts.getOrDefault(currentAddress + ":" + currentPort + ":" + currentProtocol, 0)) {
            currentAddress = info.remoteAddress;
            currentPort = info.remotePort;
            currentProtocol = info.protocol;
            updateOverlay(formatOverlayText(count));
        }
    }

    private String formatOverlayText(int count) {
        String portText = currentPort > 0 ? ":" + currentPort : "";
        return String.format(Locale.US, "Battle IP\n%s%s\n%s · %d pkt", currentAddress, portText, currentProtocol, count);
    }

    private void startOverlay() {
        if (overlayView != null || !Settings.canDrawOverlays(this)) {
            return;
        }

        overlayView = new TextView(this);
        overlayView.setText("Battle IP\nждём пакеты\nPCAPdroid UDP 5123");
        overlayView.setTextColor(Color.WHITE);
        overlayView.setTextSize(14);
        overlayView.setGravity(Gravity.CENTER);
        overlayView.setPadding(22, 14, 22, 14);
        overlayView.setBackgroundColor(0xcc101820);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 32;
        params.y = 120;

        overlayView.setOnTouchListener(new DragTouchListener(params));
        windowManager.addView(overlayView, params);
    }

    private void removeOverlay() {
        if (overlayView != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
    }

    private void updateOverlay(String text) {
        mainHandler.post(() -> {
            if (overlayView != null) {
                overlayView.setText(text);
            }
        });
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, BattleServerOverlayService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Battle IP Overlay работает")
                .setContentText("Слушает UDP-поток PCAPdroid на 127.0.0.1:" + COLLECTOR_PORT)
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", stopPendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Battle IP Overlay",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Показывает IP батл-сервера поверх игры");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private final class DragTouchListener implements View.OnTouchListener {
        private final WindowManager.LayoutParams params;
        private int startX;
        private int startY;
        private float touchStartX;
        private float touchStartY;

        DragTouchListener(WindowManager.LayoutParams params) {
            this.params = params;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x;
                    startY = params.y;
                    touchStartX = event.getRawX();
                    touchStartY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = startX + Math.round(event.getRawX() - touchStartX);
                    params.y = startY + Math.round(event.getRawY() - touchStartY);
                    windowManager.updateViewLayout(view, params);
                    return true;
                default:
                    return true;
            }
        }
    }
}
