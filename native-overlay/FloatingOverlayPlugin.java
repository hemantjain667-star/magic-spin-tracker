
package com.hemantjain.magicspintracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "FloatingOverlay")
public class FloatingOverlayPlugin extends Plugin {

    private BroadcastReceiver numberReceiver;

    @Override
    public void load() {
        numberReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (FloatingOverlayService.ACTION_NUMBER_SUBMITTED.equals(intent.getAction())) {
                    int num = intent.getIntExtra(FloatingOverlayService.EXTRA_NUMBER, -1);
                    if (num >= 0 && num <= 36) {
                        JSObject data = new JSObject();
                        data.put("number", num);
                        notifyListeners("spinNumberSubmitted", data);
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(FloatingOverlayService.ACTION_NUMBER_SUBMITTED);
        ContextCompat.registerReceiver(
                getContext(),
                numberReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @PluginMethod
    public void checkPermission(PluginCall call) {
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(getContext());
        JSObject res = new JSObject();
        res.put("granted", granted);
        call.resolve(res);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(getContext())) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName())
            );
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        }
        JSObject res = new JSObject();
        res.put("requested", true);
        call.resolve(res);
    }

    @PluginMethod
    public void startOverlay(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(getContext())) {
            requestPermission(call);
            return;
        }
        Intent serviceIntent = new Intent(getContext(), FloatingOverlayService.class);
        serviceIntent.setAction(FloatingOverlayService.ACTION_SHOW);
        getContext().startService(serviceIntent);
        call.resolve();
    }

    @PluginMethod
    public void stopOverlay(PluginCall call) {
        Intent serviceIntent = new Intent(getContext(), FloatingOverlayService.class);
        serviceIntent.setAction(FloatingOverlayService.ACTION_HIDE);
        getContext().startService(serviceIntent);
        call.resolve();
    }

    @PluginMethod
    public void updateInfo(PluginCall call) {
        int pattern = call.getInt("pattern", 1);
        int multiplier = call.getInt("multiplier", 1);
        boolean isPlaying = call.getBoolean("isPlaying", false);

        pattern = Math.max(0, Math.min(9, pattern));
        multiplier = Math.max(1, multiplier);

        Intent serviceIntent = new Intent(getContext(), FloatingOverlayService.class);
        serviceIntent.setAction(FloatingOverlayService.ACTION_UPDATE_INFO);
        serviceIntent.putExtra(FloatingOverlayService.EXTRA_PATTERN, pattern);
        serviceIntent.putExtra(FloatingOverlayService.EXTRA_MULTIPLIER, multiplier);
        serviceIntent.putExtra(FloatingOverlayService.EXTRA_IS_PLAYING, isPlaying);

        getContext().startService(serviceIntent);
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        if (numberReceiver != null) {
            try {
                getContext().unregisterReceiver(numberReceiver);
            } catch (Exception ignored) {
            }
            numberReceiver = null;
        }
    }
}
