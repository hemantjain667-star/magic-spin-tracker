package com.hemantjain.magicspintracker;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.result.ActivityResult;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.List;

@CapacitorPlugin(name = "FloatingOverlay")
public class FloatingOverlayPlugin extends Plugin {

    private BroadcastReceiver overlayReceiver;

    @Override
    public void load() {
        ReminderReceiver.cancel(getContext());

        // Request Android 13+ notification permission for 1-hour Best Wish reminder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED && getActivity() != null) {
                ActivityCompat.requestPermissions(
                        getActivity(),
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        7011
                );
            }
        }

        overlayReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                String act = intent.getAction();

                if (FloatingOverlayService.ACTION_NUMBER_SUBMITTED.equals(act)) {
                    int num = intent.getIntExtra(FloatingOverlayService.EXTRA_NUMBER, -1);
                    String source = intent.getStringExtra(FloatingOverlayService.EXTRA_SOURCE);
                    if (num >= 0 && num <= 36) {
                        JSObject data = new JSObject();
                        data.put("number", num);
                        data.put("source", source != null ? source : "manual");
                        notifyListeners("spinNumberSubmitted", data);
                    }
                } else if (FloatingOverlayService.ACTION_UNDO_REQUESTED.equals(act)) {
                    notifyListeners("undoRequested", new JSObject());
                } else if (FloatingOverlayService.ACTION_CAPTURE_STATUS.equals(act)) {
                    JSObject data = new JSObject();
                    data.put("capturing", intent.getBooleanExtra(FloatingOverlayService.EXTRA_CAPTURING, false));
                    data.put("scanning", intent.getBooleanExtra(FloatingOverlayService.EXTRA_SCANNING, false));
                    notifyListeners("captureStatusChanged", data);
                } else if (FloatingOverlayService.ACTION_SIREN_STATUS.equals(act)) {
                    JSObject data = new JSObject();
                    data.put("playing", intent.getBooleanExtra(FloatingOverlayService.EXTRA_SIREN_PLAYING, false));
                    String type = intent.getStringExtra(FloatingOverlayService.EXTRA_SIREN_TYPE);
                    data.put("type", type != null ? type : SirenPlayer.TYPE_NONE);
                    notifyListeners("sirenStatusChanged", data);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(FloatingOverlayService.ACTION_NUMBER_SUBMITTED);
        filter.addAction(FloatingOverlayService.ACTION_UNDO_REQUESTED);
        filter.addAction(FloatingOverlayService.ACTION_CAPTURE_STATUS);
        filter.addAction(FloatingOverlayService.ACTION_SIREN_STATUS);
        ContextCompat.registerReceiver(
                getContext(),
                overlayReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        ReminderReceiver.cancel(getContext());
    }

    @Override
    protected void handleOnStop() {
        super.handleOnStop();
        ReminderReceiver.scheduleOneHour(getContext());
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
        ContextCompat.startForegroundService(getContext(), serviceIntent);
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
    public void startScreenCapture(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(getContext())) {
            JSObject res = new JSObject();
            res.put("started", false);
            call.resolve(res);
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager)
                getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            call.reject("MediaProjectionManager unavailable");
            return;
        }
        startActivityForResult(call, mpm.createScreenCaptureIntent(), "handleScreenCaptureResult");
    }

    @ActivityCallback
    private void handleScreenCaptureResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            Intent serviceIntent = new Intent(getContext(), FloatingOverlayService.class);
            serviceIntent.setAction(FloatingOverlayService.ACTION_START_CAPTURE);
            serviceIntent.putExtra(FloatingOverlayService.EXTRA_RESULT_CODE, result.getResultCode());
            serviceIntent.putExtra(FloatingOverlayService.EXTRA_RESULT_DATA, result.getData());
            ContextCompat.startForegroundService(getContext(), serviceIntent);
            JSObject res = new JSObject();
            res.put("started", true);
            call.resolve(res);
        } else {
            JSObject res = new JSObject();
            res.put("started", false);
            call.resolve(res);
        }
    }

    @PluginMethod
    public void stopScreenCapture(PluginCall call) {
        Intent serviceIntent = new Intent(getContext(), FloatingOverlayService.class);
        serviceIntent.setAction(FloatingOverlayService.ACTION_STOP_CAPTURE);
        getContext().startService(serviceIntent);
        call.resolve();
    }

    @PluginMethod
    public void toggleTargetBox(PluginCall call) {
        Intent serviceIntent = new Intent(getContext(), FloatingOverlayService.class);
        serviceIntent.setAction(FloatingOverlayService.ACTION_TOGGLE_TARGET_BOX);
        getContext().startService(serviceIntent);
        JSObject res = new JSObject();
        res.put("visible", true);
        call.resolve(res);
    }

    @PluginMethod
    public void startSiren(PluginCall call) {
        SirenPlayer.start8x(getContext());
        refreshOverlayUi();
        call.resolve();
    }

    @PluginMethod
    public void startSiren32x(PluginCall call) {
        SirenPlayer.start32x(getContext());
        refreshOverlayUi();
        call.resolve();
    }

    @PluginMethod
    public void stopSiren(PluginCall call) {
        SirenPlayer.stop(getContext());
        refreshOverlayUi();
        call.resolve();
    }

    private void refreshOverlayUi() {
        if (FloatingOverlayService.isRunning) {
            Intent serviceIntent = new Intent(getContext(), FloatingOverlayService.class);
            serviceIntent.setAction(FloatingOverlayService.ACTION_REFRESH_UI);
            getContext().startService(serviceIntent);
        }
    }

    @PluginMethod
    public void getSirenState(PluginCall call) {
        JSObject res = new JSObject();
        res.put("playing", SirenPlayer.isPlaying());
        res.put("type", SirenPlayer.getActiveType());
        call.resolve(res);
    }

    @PluginMethod
    public void drainPendingSpins(PluginCall call) {
        List<Integer> pending = FloatingOverlayService.drainPendingSpinsSync(getContext());
        JSArray jsSpins = new JSArray();
        for (Integer n : pending) if (n != null && n >= 0 && n <= 36) jsSpins.put(n);
        JSObject res = new JSObject();
        res.put("spins", jsSpins);
        call.resolve(res);
    }

    @PluginMethod
    public void drainPendingActions(PluginCall call) {
        FloatingOverlayService.PendingActions act = FloatingOverlayService.drainPendingActionsSync(getContext());
        JSArray jsSpins = new JSArray();
        for (Integer n : act.spins) if (n != null && n >= 0 && n <= 36) jsSpins.put(n);
        JSObject res = new JSObject();
        res.put("spins", jsSpins);
        res.put("undoCount", act.undoCount);
        call.resolve(res);
    }

    @PluginMethod
    public void syncState(PluginCall call) {
        int pattern = Math.max(0, Math.min(9, call.getInt("pattern", 1)));
        int multiplier = Math.max(1, call.getInt("multiplier", 1));
        boolean isPlaying = call.getBoolean("isPlaying", false);
        int totalUnit = call.getInt("totalUnit", 0);
        int hits = call.getInt("hits", 0);
        int misses = call.getInt("misses", 0);
        int lastSpin = call.getInt("lastSpin", -1);
        int targetProfit = Math.max(0, call.getInt("targetProfit", 0));
        int stopLoss = Math.max(0, call.getInt("stopLoss", 0));
        String spinsJson = call.getString("spinsJson", "[]");
        String patternsJson = call.getString("patternsJson", "{}");

        SharedPreferences prefs = getContext().getSharedPreferences(
                FloatingOverlayService.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt("pattern", pattern)
                .putInt("multiplier", multiplier)
                .putBoolean("isPlaying", isPlaying)
                .putInt("totalUnit", totalUnit)
                .putInt("hits", hits)
                .putInt("misses", misses)
                .putInt("lastSpin", lastSpin)
                .putInt(FloatingOverlayService.KEY_TARGET_PROFIT, targetProfit)
                .putInt(FloatingOverlayService.KEY_STOP_LOSS, stopLoss)
                .putString(FloatingOverlayService.KEY_SPINS_JSON, spinsJson)
                .putString(FloatingOverlayService.KEY_PATTERNS_JSON, patternsJson)
                .apply();

        if (FloatingOverlayService.isRunning) {
            Intent serviceIntent = new Intent(getContext(), FloatingOverlayService.class);
            serviceIntent.setAction(FloatingOverlayService.ACTION_UPDATE_INFO);
            serviceIntent.putExtra(FloatingOverlayService.EXTRA_PATTERN, pattern);
            serviceIntent.putExtra(FloatingOverlayService.EXTRA_MULTIPLIER, multiplier);
            serviceIntent.putExtra(FloatingOverlayService.EXTRA_IS_PLAYING, isPlaying);
            serviceIntent.putExtra(FloatingOverlayService.EXTRA_TOTAL_UNIT, totalUnit);
            getContext().startService(serviceIntent);
        }

        call.resolve();
    }

    @PluginMethod
    public void updateInfo(PluginCall call) {
        syncState(call);
    }

    @Override
    protected void handleOnDestroy() {
        ReminderReceiver.scheduleOneHour(getContext());
        if (overlayReceiver != null) {
            try { getContext().unregisterReceiver(overlayReceiver); } catch (Exception ignored) {}
            overlayReceiver = null;
        }
    }
}
