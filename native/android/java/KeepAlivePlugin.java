package de.narrhalla.player;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowManager;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Hält die Wiedergabe am Leben, wenn das Handy gesperrt ist:
 * Vordergrund-Dienst (Medienwiedergabe) mit Wake-Lock, Steuerung über Benachrichtigung und
 * Bluetooth-Tasten, Bildschirm-an, Prüfung von Akku-Optimierung und Benachrichtigungen.
 */
@CapacitorPlugin(name = "KeepAlive")
public class KeepAlivePlugin extends Plugin {
    static KeepAlivePlugin instance;

    @Override
    public void load() {
        instance = this;
    }

    /** Vom Dienst aufgerufen: Taste in Benachrichtigung / am Kopfhörer / an der Box. */
    static void sendAction(String action) {
        KeepAlivePlugin p = instance;
        if (p == null) return;
        JSObject data = new JSObject();
        data.put("action", action);
        p.notifyListeners("action", data, true);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33
            && ContextCompat.checkSelfPermission(getContext(), "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[] { "android.permission.POST_NOTIFICATIONS" }, 4711);
        }
        Intent i = new Intent(getContext(), KeepAliveService.class);
        i.putExtra("text", call.getString("text", "Bluetooth bleibt verbunden"));
        i.putExtra("title", call.getString("title", "Narrhalla Player"));
        i.putExtra("playing", Boolean.TRUE.equals(call.getBoolean("playing", false)));
        try {
            if (Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(i);
            else getContext().startService(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Hintergrund-Dienst konnte nicht starten: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), KeepAliveService.class));
        call.resolve();
    }

    @PluginMethod
    public void screenOn(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        getActivity().runOnUiThread(() -> {
            if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            call.resolve();
        });
    }

    @PluginMethod
    public void batteryStatus(PluginCall call) {
        JSObject ret = new JSObject();
        boolean ignoring = true;
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getContext().getSystemService(android.content.Context.POWER_SERVICE);
            ignoring = pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
        }
        ret.put("unrestricted", ignoring);
        ret.put("notifications", NotificationManagerCompat.from(getContext()).areNotificationsEnabled());
        call.resolve(ret);
    }

    @PluginMethod
    public void openBatterySettings(PluginCall call) {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 23) {
                i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getContext().getPackageName()));
            } else {
                i = new Intent(Settings.ACTION_SETTINGS);
            }
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            try {
                getActivity().startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                call.resolve();
            } catch (Exception e2) {
                call.reject(e2.getMessage());
            }
        }
    }

    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 26) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getContext().getPackageName()));
            }
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }
}
