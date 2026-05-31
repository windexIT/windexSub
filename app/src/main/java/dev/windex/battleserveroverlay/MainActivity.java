package dev.windex.battleserveroverlay;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PCAPDROID_PACKAGE = "com.emanuelef.remote_capture";
    private static final String PCAPDROID_CAPTURE_ACTIVITY = "com.emanuelef.remote_capture.activities.CaptureCtrl";
    private static final String BRAWL_STARS_PACKAGE = "com.supercell.brawlstars";
    private static final int REQUEST_OVERLAY = 2001;
    private static final int REQUEST_CAPTURE = 2002;
    private static final int REQUEST_NOTIFICATIONS = 2003;

    private TextView statusView;
    private EditText apiKeyInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        requestNotificationsIfNeeded();
        refreshStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY) {
            refreshStatus();
            if (Settings.canDrawOverlays(this)) {
                startOverlayService();
            }
        } else if (requestCode == REQUEST_CAPTURE) {
            String message = resultCode == RESULT_OK
                    ? "PCAPdroid начал захват Brawl Stars"
                    : "PCAPdroid не подтвердил запуск захвата";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private View buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 48, 36, 36);
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Battle IP Overlay");
        title.setTextSize(28);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("Приложение запускает PCAPdroid в UDP Exporter только для Brawl Stars, слушает пакеты на 127.0.0.1:5123 и показывает найденный IP поверх игры. Первый запуск PCAPdroid может попросить разрешение на захват.");
        description.setTextSize(16);
        description.setPadding(0, 24, 0, 24);
        root.addView(description);

        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("API key PCAPdroid (необязательно)");
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setText(getPreferences(MODE_PRIVATE).getString("pcapdroid_api_key", ""));
        apiKeyInput.setPadding(0, 0, 0, 18);
        root.addView(apiKeyInput);

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setPadding(0, 0, 0, 24);
        root.addView(statusView);

        Button permissionButton = new Button(this);
        permissionButton.setText("1. Разрешить окно поверх игры");
        permissionButton.setOnClickListener(v -> requestOverlayPermission());
        root.addView(permissionButton);

        Button startButton = new Button(this);
        startButton.setText("2. Запустить оверлей и PCAPdroid");
        startButton.setOnClickListener(v -> startEverything());
        root.addView(startButton);

        Button gameButton = new Button(this);
        gameButton.setText("3. Открыть Brawl Stars");
        gameButton.setOnClickListener(v -> openBrawlStars());
        root.addView(gameButton);

        Button stopButton = new Button(this);
        stopButton.setText("Остановить оверлей");
        stopButton.setOnClickListener(v -> stopOverlayService());
        root.addView(stopButton);

        return scrollView;
    }

    private void startEverything() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        startOverlayService();
        startPcapdroidCapture();
        refreshStatus();
    }

    private void startOverlayService() {
        Intent intent = new Intent(this, BattleServerOverlayService.class);
        intent.setAction(BattleServerOverlayService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopOverlayService() {
        Intent intent = new Intent(this, BattleServerOverlayService.class);
        intent.setAction(BattleServerOverlayService.ACTION_STOP);
        startService(intent);
        Toast.makeText(this, "Оверлей остановлен", Toast.LENGTH_SHORT).show();
    }

    private void startPcapdroidCapture() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(PCAPDROID_PACKAGE, PCAPDROID_CAPTURE_ACTIVITY);
        intent.putExtra("action", "start");
        intent.putExtra("pcap_dump_mode", "udp_exporter");
        intent.putExtra("collector_host", "127.0.0.1");
        intent.putExtra("collector_port", BattleServerOverlayService.COLLECTOR_PORT);
        intent.putExtra("app_filter", BRAWL_STARS_PACKAGE);
        intent.putExtra("dump_extensions", true);
        intent.putExtra("full_payload", true);

        String apiKey = apiKeyInput == null ? "" : apiKeyInput.getText().toString().trim();
        getPreferences(MODE_PRIVATE).edit().putString("pcapdroid_api_key", apiKey).apply();
        if (!apiKey.isEmpty()) {
            intent.putExtra("api_key", apiKey);
        }

        try {
            startActivityForResult(intent, REQUEST_CAPTURE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Установи PCAPdroid, чтобы получать пакеты", Toast.LENGTH_LONG).show();
            openMarket(PCAPDROID_PACKAGE);
        }
    }

    private void openBrawlStars() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(BRAWL_STARS_PACKAGE);
        if (launchIntent != null) {
            startActivity(launchIntent);
            return;
        }
        Toast.makeText(this, "Brawl Stars не найден", Toast.LENGTH_LONG).show();
        openMarket(BRAWL_STARS_PACKAGE);
    }

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разрешение уже выдано", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQUEST_OVERLAY);
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void openMarket(String packageName) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName)));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)));
        }
    }

    private void refreshStatus() {
        if (statusView == null) {
            return;
        }
        String overlay = Settings.canDrawOverlays(this) ? "разрешён" : "нужно выдать";
        boolean pcapdroidInstalled = isInstalled(PCAPDROID_PACKAGE);
        boolean brawlInstalled = isInstalled(BRAWL_STARS_PACKAGE);
        statusView.setText("Статус:\n"
                + "• Окно поверх других приложений: " + overlay + "\n"
                + "• PCAPdroid: " + (pcapdroidInstalled ? "установлен" : "не найден") + "\n"
                + "• Brawl Stars: " + (brawlInstalled ? "установлен" : "не найден") + "\n"
                + "• Фильтр захвата: " + BRAWL_STARS_PACKAGE + "\n"
                + "• Порт приёма: " + BattleServerOverlayService.COLLECTOR_PORT);
    }

    private boolean isInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
