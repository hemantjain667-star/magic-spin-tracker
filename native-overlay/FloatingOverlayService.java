package com.hemantjain.magicspintracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FloatingOverlayService extends Service {

    public static volatile boolean isRunning = false;

    public static final String ACTION_SHOW = "com.hemantjain.magicspintracker.ACTION_SHOW_OVERLAY";
    public static final String ACTION_HIDE = "com.hemantjain.magicspintracker.ACTION_HIDE_OVERLAY";
    public static final String ACTION_UPDATE_INFO = "com.hemantjain.magicspintracker.ACTION_UPDATE_INFO";
    public static final String ACTION_START_CAPTURE = "com.hemantjain.magicspintracker.ACTION_START_CAPTURE";
    public static final String ACTION_STOP_CAPTURE = "com.hemantjain.magicspintracker.ACTION_STOP_CAPTURE";
    public static final String ACTION_TOGGLE_TARGET_BOX = "com.hemantjain.magicspintracker.ACTION_TOGGLE_TARGET_BOX";

    public static final String ACTION_NUMBER_SUBMITTED = "com.hemantjain.magicspintracker.NUMBER_SUBMITTED";
    public static final String ACTION_CAPTURE_STATUS = "com.hemantjain.magicspintracker.CAPTURE_STATUS";

    public static final String EXTRA_NUMBER = "extra_number";
    public static final String EXTRA_SOURCE = "extra_source";
    public static final String EXTRA_PATTERN = "extra_pattern";
    public static final String EXTRA_MULTIPLIER = "extra_multiplier";
    public static final String EXTRA_IS_PLAYING = "extra_is_playing";
    public static final String EXTRA_TOTAL_UNIT = "extra_total_unit";
    public static final String EXTRA_RESULT_CODE = "extra_result_code";
    public static final String EXTRA_RESULT_DATA = "extra_result_data";
    public static final String EXTRA_CAPTURING = "extra_capturing";
    public static final String EXTRA_SCANNING = "extra_scanning";

    public static final String PREFS_NAME = "floating_overlay_state";
    public static final String KEY_PENDING_SPINS = "pending_spins_json";
    public static final String KEY_SPINS_JSON = "synced_spins_json";
    public static final String KEY_PATTERNS_JSON = "synced_patterns_json";

    private static final String CHANNEL_ID = "magic_spin_overlay_channel";
    private static final int NOTIF_ID = 4109;
    private static final long OCR_INTERVAL_MS = 750L;

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private FrameLayout rootContainer;
    private LinearLayout pillView;
    private LinearLayout expandedView;
    private TextView pillHeadingText;
    private TextView pillInfoText;
    private TextView pillOcrText;
    private TextView expandedInfoText;
    private TextView selectedNumberText;
    private Button scanToggleBtn;
    private Button lockBoxBtn;

    private FrameLayout scanBoxView;
    private WindowManager.LayoutParams scanBoxParams;
    private boolean isBoxVisible = false;
    private boolean isBoxLocked = false;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private TextRecognizer textRecognizer;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);

    private boolean isCapturing = false;
    private boolean isAutoScanning = false;

    private Integer candidateNumber = null;
    private int stableFrameCount = 0;
    private Integer lastSubmittedOcrNumber = null;
    private int blankFramesSinceSubmit = 0;

    private Integer selectedNumber = null;
    private int currentPattern = 1;
    private int currentMultiplier = 1;
    private boolean currentIsPlaying = false;
    private int currentTotalUnit = 0;
    private String lastOcrStatus = "MANUAL";

    private static final int[][] DEFAULT_PATTERNS = {
            {10, 20, 30, 0, 1, 11, 21, 31, 2, 12, 22, 32, 5, 15, 25, 35, 6, 16, 26, 36, 7, 17, 27},
            {1, 11, 21, 31, 2, 12, 22, 32, 3, 13, 23, 33, 6, 16, 26, 36, 7, 17, 27, 8, 18, 28},
            {2, 12, 22, 32, 3, 13, 23, 33, 4, 14, 24, 34, 7, 17, 27, 8, 18, 28, 9, 19, 29},
            {3, 13, 23, 33, 4, 14, 24, 34, 5, 15, 25, 35, 8, 18, 28, 9, 19, 29, 10, 20, 30, 0},
            {4, 14, 24, 34, 5, 15, 25, 35, 6, 16, 26, 36, 9, 19, 29, 10, 20, 30, 0, 1, 11, 21, 31},
            {5, 15, 25, 35, 6, 16, 26, 36, 7, 17, 27, 10, 20, 30, 0, 1, 11, 21, 31, 2, 12, 22, 32},
            {6, 16, 26, 36, 7, 17, 27, 8, 18, 28, 1, 11, 21, 31, 2, 12, 22, 32, 3, 13, 23, 33},
            {7, 17, 27, 8, 18, 28, 9, 19, 29, 2, 12, 22, 32, 3, 13, 23, 33, 4, 14, 24, 34},
            {8, 18, 28, 9, 19, 29, 10, 20, 30, 0, 3, 13, 23, 33, 4, 14, 24, 34, 5, 15, 25, 35},
            {9, 19, 29, 10, 20, 30, 0, 1, 11, 21, 31, 4, 14, 24, 34, 5, 15, 25, 35, 6, 16, 26, 36}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        createNotificationChannel();
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_SHOW;

        if (ACTION_HIDE.equals(action)) {
            stopScreenCaptureInternal();
            removeScanBox();
            removeOverlay();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP_CAPTURE.equals(action)) {
            stopScreenCaptureInternal();
            removeScanBox();
            updateInfoLabels();
            return START_STICKY;
        }

        if (ACTION_TOGGLE_TARGET_BOX.equals(action)) {
            if (isBoxVisible) {
                removeScanBox();
            } else {
                createScanBox();
            }
            return START_STICKY;
        }

        if (ACTION_UPDATE_INFO.equals(action) && intent != null) {
            currentPattern = Math.max(0, Math.min(9, intent.getIntExtra(EXTRA_PATTERN, currentPattern)));
            currentMultiplier = Math.max(1, intent.getIntExtra(EXTRA_MULTIPLIER, currentMultiplier));
            currentIsPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, currentIsPlaying);
            currentTotalUnit = intent.getIntExtra(EXTRA_TOTAL_UNIT, currentTotalUnit);
            updateInfoLabels();
            return START_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Display over other apps permission chahiye", Toast.LENGTH_SHORT).show();
            stopSelf();
            return START_NOT_STICKY;
        }

        loadOverlayState();

        if (ACTION_START_CAPTURE.equals(action) && intent != null) {
            startAsForeground(true);
            if (rootContainer == null) {
                createOverlay();
            }
            if (!isBoxVisible) {
                createScanBox();
            }
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultData != null) {
                startScreenCaptureInternal(resultCode, resultData);
            }
            return START_STICKY;
        }

        startAsForeground(false);
        if (rootContainer == null) {
            createOverlay();
        }
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Magic Spin Overlay & Auto Capture",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void startAsForeground(boolean withMediaProjection) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Magic Spin Tracker Active")
                .setContentText(withMediaProjection
                        ? "Auto Screen Capture + Roulette OCR chalu hai"
                        : "Floating Overlay active hai")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            int type = withMediaProjection
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            startForeground(NOTIF_ID, notification, type);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && withMediaProjection) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void loadOverlayState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentPattern = prefs.getInt("pattern", 1);
        currentMultiplier = prefs.getInt("multiplier", 1);
        currentIsPlaying = prefs.getBoolean("isPlaying", false);
        currentTotalUnit = prefs.getInt("totalUnit", 0);
    }

    private void startScreenCaptureInternal(int resultCode, Intent data) {
        stopScreenCaptureInternal();

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) return;

        mediaProjection = mpm.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Toast.makeText(this, "Screen Capture start nahi ho paya", Toast.LENGTH_SHORT).show();
            return;
        }

        captureThread = new HandlerThread("MagicOcrCaptureThread");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                mainHandler.post(() -> {
                    stopScreenCaptureInternal();
                    updateInfoLabels();
                });
            }
        }, captureHandler);

        DisplayMetrics metrics = new DisplayMetrics();
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        windowManager.getDefaultDisplay().getRealMetrics(metrics);

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "MagicRouletteCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );

        isCapturing = true;
        isAutoScanning = true;
        lastOcrStatus = "AUTO: ON";
        candidateNumber = null;
        stableFrameCount = 0;
        blankFramesSinceSubmit = 2;

        broadcastCaptureStatus();
        updateInfoLabels();
        scheduleNextOcrTick();

        Toast.makeText(this, "Green Box ko Roulette Number par rakhein!", Toast.LENGTH_LONG).show();
    }

    private void stopScreenCaptureInternal() {
        isCapturing = false;
        isAutoScanning = false;
        lastOcrStatus = "MANUAL";

        if (captureHandler != null) {
            captureHandler.removeCallbacksAndMessages(null);
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }
        if (captureThread != null) {
            try { captureThread.quitSafely(); } catch (Exception ignored) {}
            captureThread = null;
            captureHandler = null;
        }

        broadcastCaptureStatus();
    }

    private void broadcastCaptureStatus() {
        Intent statusIntent = new Intent(ACTION_CAPTURE_STATUS);
        statusIntent.setPackage(getPackageName());
        statusIntent.putExtra(EXTRA_CAPTURING, isCapturing);
        statusIntent.putExtra(EXTRA_SCANNING, isAutoScanning);
        sendBroadcast(statusIntent);
    }

    private void scheduleNextOcrTick() {
        if (!isCapturing || captureHandler == null) return;
        captureHandler.postDelayed(() -> {
            if (!isCapturing) return;
            if (isAutoScanning && isBoxVisible && !ocrBusy.get()) {
                captureAndRunOcr();
            }
            scheduleNextOcrTick();
        }, OCR_INTERVAL_MS);
    }

    private void captureAndRunOcr() {
        if (imageReader == null || scanBoxView == null || !isBoxVisible) return;

        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return;

            int imgWidth = image.getWidth();
            int imgHeight = image.getHeight();
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * imgWidth;

            Bitmap fullBitmap = Bitmap.createBitmap(
                    imgWidth + rowPadding / pixelStride,
                    imgHeight,
                    Bitmap.Config.ARGB_8888
            );
            fullBitmap.copyPixelsFromBuffer(buffer);
            image.close();
            image = null;

            int[] loc = new int[2];
            scanBoxView.getLocationOnScreen(loc);
            int boxX = loc[0];
            int boxY = loc[1];
            int boxW = scanBoxView.getWidth();
            int boxH = scanBoxView.getHeight();

            int inset = dpToPx(5);
            int cropX = Math.max(0, Math.min(imgWidth - 2, boxX + inset));
            int cropY = Math.max(0, Math.min(imgHeight - 2, boxY + inset));
            int cropW = Math.max(4, Math.min(imgWidth - cropX, boxW - inset * 2));
            int cropH = Math.max(4, Math.min(imgHeight - cropY, boxH - inset * 2));

            Bitmap cropped = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH);
            fullBitmap.recycle();

            Bitmap scaled = Bitmap.createScaledBitmap(cropped, cropW * 2, cropH * 2, true);
            cropped.recycle();

            ocrBusy.set(true);
            InputImage inputImage = InputImage.fromBitmap(scaled, 0);
            textRecognizer.process(inputImage)
                    .addOnSuccessListener(visionText -> {
                        Integer detected = extractRouletteNumber(visionText.getText());
                        mainHandler.post(() -> handleOcrDetection(detected));
                    })
                    .addOnCompleteListener(task -> {
                        scaled.recycle();
                        ocrBusy.set(false);
                    });

        } catch (Exception e) {
            if (image != null) {
                try { image.close(); } catch (Exception ignored) {}
            }
            ocrBusy.set(false);
        }
    }

    private Integer extractRouletteNumber(String rawText) {
        if (rawText == null) return null;
        String cleaned = rawText.trim()
                .replace('O', '0')
                .replace('o', '0')
                .replace('I', '1')
                .replace('l', '1')
                .replace('|', '1')
                .replace('S', '5')
                .replace('B', '8');

        Matcher matcher = Pattern.compile("\\b([0-9]{1,2})\\b").matcher(cleaned);
        while (matcher.find()) {
            try {
                int val = Integer.parseInt(matcher.group(1));
                if (val >= 0 && val <= 36) {
                    return val;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private void handleOcrDetection(Integer detected) {
        if (!isAutoScanning) return;

        if (detected == null) {
            candidateNumber = null;
            stableFrameCount = 0;
            blankFramesSinceSubmit++;
            lastOcrStatus = "SCANNING...";
            updateInfoLabels();
            return;
        }

        if (candidateNumber != null && candidateNumber.equals(detected)) {
            stableFrameCount++;
        } else {
            candidateNumber = detected;
            stableFrameCount = 1;
        }

        lastOcrStatus = "OCR: " + detected;

        if (stableFrameCount >= 2) {
            boolean isDifferentFromLast = (lastSubmittedOcrNumber == null) || !lastSubmittedOcrNumber.equals(detected);
            boolean isRepeatAfterGap = (lastSubmittedOcrNumber != null)
                    && lastSubmittedOcrNumber.equals(detected)
                    && blankFramesSinceSubmit >= 2;

            if (isDifferentFromLast || isRepeatAfterGap) {
                lastSubmittedOcrNumber = detected;
                blankFramesSinceSubmit = 0;
                submitSpinNumber(detected, "ocr");
            }
        }

        updateInfoLabels();
    }

    private void submitSpinNumber(int num, String source) {
        if (num < 0 || num > 36) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        try {
            JSONArray pending = new JSONArray(prefs.getString(KEY_PENDING_SPINS, "[]"));
            pending.put(num);
            prefs.edit().putString(KEY_PENDING_SPINS, pending.toString()).apply();
        } catch (Exception ignored) {
        }

        recalculateNativePreview();
        updateInfoLabels();

        Intent broadcast = new Intent(ACTION_NUMBER_SUBMITTED);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(EXTRA_NUMBER, num);
        broadcast.putExtra(EXTRA_SOURCE, source);
        sendBroadcast(broadcast);

        String tag = "ocr".equals(source) ? "Auto OCR: " : "Spin Added: ";
        Toast.makeText(this, tag + num + " → " + currentPattern + "P | " + currentMultiplier + "X",
                Toast.LENGTH_SHORT).show();
    }

    private void recalculateNativePreview() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        List<Integer> allSpins = new ArrayList<>();
        try {
            JSONArray base = new JSONArray(prefs.getString(KEY_SPINS_JSON, "[]"));
            for (int i = 0; i < base.length(); i++) {
                int v = base.optInt(i, -1);
                if (v >= 0 && v <= 36) allSpins.add(v);
            }
            JSONArray pending = new JSONArray(prefs.getString(KEY_PENDING_SPINS, "[]"));
            for (int i = 0; i < pending.length(); i++) {
                int v = pending.optInt(i, -1);
                if (v >= 0 && v <= 36) allSpins.add(v);
            }
        } catch (Exception ignored) {
        }

        Map<Integer, Set<Integer>> patternMap = loadNativePatterns(prefs);

        int mult = 1;
        int stepsRem = 0;
        int totalUnit = 0;
        int baseCounting = allSpins.isEmpty() ? 1 : allSpins.get(0);

        for (int i = 0; i < allSpins.size(); i++) {
            int spin = allSpins.get(i);
            int counting = baseCounting + i;
            int pettan = counting % 10;
            Set<Integer> inPettan = patternMap.get(pettan);
            int inCount = inPettan != null ? inPettan.size() : 0;
            int cutCount = 37 - inCount;
            boolean isHit = inPettan == null || !inPettan.contains(spin);

            int unit = (isHit ? (36 - cutCount) : -cutCount) * mult;
            totalUnit += unit;

            if (isHit) {
                if (mult == 1) {
                    mult = 2;
                    stepsRem = 2;
                } else {
                    mult = 1;
                    stepsRem = 0;
                }
            } else if (mult > 1) {
                stepsRem--;
                if (stepsRem == 0) {
                    mult = mult * 2;
                    stepsRem = 2;
                }
            }
        }

        int nextCounting = allSpins.isEmpty() ? 1 : baseCounting + allSpins.size();
        currentPattern = nextCounting % 10;
        currentMultiplier = mult;
        currentIsPlaying = mult > 1;
        currentTotalUnit = totalUnit;
    }

    private Map<Integer, Set<Integer>> loadNativePatterns(SharedPreferences prefs) {
        Map<Integer, Set<Integer>> map = new HashMap<>();
        for (int d = 0; d <= 9; d++) {
            Set<Integer> set = new HashSet<>();
            for (int n : DEFAULT_PATTERNS[d]) set.add(n);
            map.put(d, set);
        }
        try {
            String raw = prefs.getString(KEY_PATTERNS_JSON, "{}");
            JSONObject obj = new JSONObject(raw);
            for (int d = 0; d <= 9; d++) {
                JSONArray arr = obj.optJSONArray(String.valueOf(d));
                if (arr != null) {
                    Set<Integer> custom = new HashSet<>();
                    for (int i = 0; i < arr.length(); i++) {
                        int n = arr.optInt(i, -1);
                        if (n >= 0 && n <= 36) custom.add(n);
                    }
                    map.put(d, custom);
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    @SuppressWarnings("deprecation")
    private int getOverlayWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void createScanBox() {
        if (scanBoxView != null) return;
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (windowManager == null) return;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (isBoxLocked) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        scanBoxParams = new WindowManager.LayoutParams(
                dpToPx(74),
                dpToPx(64),
                getOverlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT
        );
        scanBoxParams.gravity = Gravity.TOP | Gravity.START;
        scanBoxParams.x = dpToPx(130);
        scanBoxParams.y = dpToPx(240);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            scanBoxParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        scanBoxView = new FrameLayout(this);
        scanBoxView.setBackground(makeRoundedBg("#1A22C55E", "#22C55E", 2, 8));

        TextView label = new TextView(this);
        label.setText("NO.");
        label.setTextColor(Color.parseColor("#4ADE80"));
        label.setTextSize(8f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        FrameLayout.LayoutParams lblLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lblLp.setMargins(dpToPx(4), dpToPx(2), 0, 0);
        scanBoxView.addView(label, lblLp);

        View resizeHandle = new View(this);
        resizeHandle.setBackground(makeRoundedBg("#22C55E", "#FFFFFF", 1, 4));
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(
                dpToPx(16), dpToPx(16), Gravity.BOTTOM | Gravity.END);
        scanBoxView.addView(resizeHandle, handleLp);

        scanBoxView.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isBoxLocked) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = scanBoxParams.x;
                        startY = scanBoxParams.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        scanBoxParams.x = Math.max(0, startX + Math.round(event.getRawX() - touchX));
                        scanBoxParams.y = Math.max(0, startY + Math.round(event.getRawY() - touchY));
                        windowManager.updateViewLayout(scanBoxView, scanBoxParams);
                        return true;
                }
                return false;
            }
        });

        resizeHandle.setOnTouchListener(new View.OnTouchListener() {
            private int startW, startH;
            private float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isBoxLocked) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startW = scanBoxParams.width;
                        startH = scanBoxParams.height;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        scanBoxParams.width = Math.max(dpToPx(42), startW + Math.round(event.getRawX() - touchX));
                        scanBoxParams.height = Math.max(dpToPx(38), startH + Math.round(event.getRawY() - touchY));
                        windowManager.updateViewLayout(scanBoxView, scanBoxParams);
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(scanBoxView, scanBoxParams);
        isBoxVisible = true;
    }

    private void toggleLockScanBox() {
        if (scanBoxView == null || scanBoxParams == null || windowManager == null) return;
        isBoxLocked = !isBoxLocked;
        if (isBoxLocked) {
            scanBoxParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            scanBoxView.setBackground(makeRoundedBg("#0D22C55E", "#38BDF8", 2, 8));
        } else {
            scanBoxParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            scanBoxView.setBackground(makeRoundedBg("#1A22C55E", "#22C55E", 2, 8));
        }
        windowManager.updateViewLayout(scanBoxView, scanBoxParams);
        updateInfoLabels();
    }

    private void removeScanBox() {
        if (scanBoxView != null && windowManager != null) {
            try { windowManager.removeView(scanBoxView); } catch (Exception ignored) {}
            scanBoxView = null;
            scanBoxParams = null;
        }
        isBoxVisible = false;
    }

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        params = new WindowManager.LayoutParams(
                dpToPx(188),
                dpToPx(64),
                getOverlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dpToPx(14);
        params.y = dpToPx(90);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        rootContainer = new FrameLayout(this);
        pillView = buildPillView();
        expandedView = buildExpandedKeypadView();
        expandedView.setVisibility(View.GONE);

        rootContainer.addView(pillView);
        rootContainer.addView(expandedView);

        attachDragListener(pillView);
        attachDragListener(expandedView);

        windowManager.addView(rootContainer, params);
    }

    private LinearLayout buildPillView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setLayoutParams(new FrameLayout.LayoutParams(dpToPx(188), dpToPx(64)));
        box.setPadding(dpToPx(6), dpToPx(3), dpToPx(6), dpToPx(3));

        pillHeadingText = new TextView(this);
        pillHeadingText.setTextSize(9f);
        pillHeadingText.setTypeface(Typeface.DEFAULT_BOLD);
        pillHeadingText.setGravity(Gravity.CENTER);

        pillInfoText = new TextView(this);
        pillInfoText.setTextColor(Color.parseColor("#F8FAFC"));
        pillInfoText.setTextSize(12.5f);
        pillInfoText.setTypeface(Typeface.DEFAULT_BOLD);
        pillInfoText.setGravity(Gravity.CENTER);
        pillInfoText.setMaxLines(1);

        pillOcrText = new TextView(this);
        pillOcrText.setTextColor(Color.parseColor("#94A3B8"));
        pillOcrText.setTextSize(9f);
        pillOcrText.setTypeface(Typeface.DEFAULT_BOLD);
        pillOcrText.setGravity(Gravity.CENTER);

        box.addView(pillHeadingText);
        box.addView(pillInfoText);
        box.addView(pillOcrText);

        box.setOnClickListener(v -> toggleExpand(true));
        updateInfoLabels();
        return box;
    }

    private LinearLayout buildExpandedKeypadView() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutParams(new FrameLayout.LayoutParams(dpToPx(224), dpToPx(312)));
        panel.setBackground(makeRoundedBg("#0F172A", "#38BDF8", 2, 12));
        panel.setPadding(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(30));
        topParams.bottomMargin = dpToPx(3);
        topBar.setLayoutParams(topParams);

        Button collapseBtn = new Button(this);
        collapseBtn.setText("—");
        collapseBtn.setTextColor(Color.WHITE);
        collapseBtn.setTextSize(11f);
        collapseBtn.setBackground(makeRoundedBg("#334155", "#475569", 1, 6));
        collapseBtn.setPadding(0, 0, 0, 0);
        collapseBtn.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(28), dpToPx(28)));
        collapseBtn.setOnClickListener(v -> toggleExpand(false));

        selectedNumberText = new TextView(this);
        selectedNumberText.setText("NO: --");
        selectedNumberText.setTextColor(Color.parseColor("#FACC15"));
        selectedNumberText.setTextSize(11.5f);
        selectedNumberText.setTypeface(Typeface.DEFAULT_BOLD);
        selectedNumberText.setGravity(Gravity.CENTER);
        selectedNumberText.setLayoutParams(new LinearLayout.LayoutParams(0, dpToPx(28), 1f));

        Button goBtn = new Button(this);
        goBtn.setText("GO");
        goBtn.setTextColor(Color.parseColor("#052E16"));
        goBtn.setTextSize(11f);
        goBtn.setTypeface(Typeface.DEFAULT_BOLD);
        goBtn.setBackground(makeRoundedBg("#22C55E", "#16A34A", 1, 6));
        goBtn.setPadding(0, 0, 0, 0);
        goBtn.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(44), dpToPx(28)));
        goBtn.setOnClickListener(v -> {
            if (selectedNumber == null) {
                Toast.makeText(this, "0 se 36 ke beech number chunein", Toast.LENGTH_SHORT).show();
                return;
            }
            submitSpinNumber(selectedNumber, "manual");
            selectedNumber = null;
            selectedNumberText.setText("NO: --");
        });

        topBar.addView(collapseBtn);
        topBar.addView(selectedNumberText);
        topBar.addView(goBtn);
        panel.addView(topBar);

        LinearLayout ocrRow = new LinearLayout(this);
        ocrRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ocrRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(26));
        ocrRowLp.bottomMargin = dpToPx(3);
        ocrRow.setLayoutParams(ocrRowLp);

        scanToggleBtn = new Button(this);
        scanToggleBtn.setText("AUTO: OFF");
        scanToggleBtn.setTextColor(Color.WHITE);
        scanToggleBtn.setTextSize(9.5f);
        scanToggleBtn.setTypeface(Typeface.DEFAULT_BOLD);
        scanToggleBtn.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(0, dpToPx(26), 1f);
        scanLp.rightMargin = dpToPx(3);
        scanToggleBtn.setLayoutParams(scanLp);
        scanToggleBtn.setOnClickListener(v -> {
            if (!isCapturing) {
                Toast.makeText(this, "App se START AUTO CAPTURE dabaiye", Toast.LENGTH_SHORT).show();
                return;
            }
            isAutoScanning = !isAutoScanning;
            lastOcrStatus = isAutoScanning ? "AUTO: ON" : "PAUSED";
            broadcastCaptureStatus();
            updateInfoLabels();
        });

        lockBoxBtn = new Button(this);
        lockBoxBtn.setText("LOCK BOX");
        lockBoxBtn.setTextColor(Color.WHITE);
        lockBoxBtn.setTextSize(9.5f);
        lockBoxBtn.setTypeface(Typeface.DEFAULT_BOLD);
        lockBoxBtn.setPadding(0, 0, 0, 0);
        lockBoxBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dpToPx(26), 1f));
        lockBoxBtn.setOnClickListener(v -> {
            if (!isBoxVisible) {
                createScanBox();
            } else {
                toggleLockScanBox();
            }
            updateInfoLabels();
        });

        ocrRow.addView(scanToggleBtn);
        ocrRow.addView(lockBoxBtn);
        panel.addView(ocrRow);

        expandedInfoText = new TextView(this);
        expandedInfoText.setTextColor(Color.parseColor("#F8FAFC"));
        expandedInfoText.setTextSize(11.5f);
        expandedInfoText.setTypeface(Typeface.DEFAULT_BOLD);
        expandedInfoText.setGravity(Gravity.CENTER);
        expandedInfoText.setBackground(makeRoundedBg("#1E293B", "#334155", 1, 6));
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(26));
        infoParams.bottomMargin = dpToPx(3);
        expandedInfoText.setLayoutParams(infoParams);
        panel.addView(expandedInfoText);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(7);
        grid.setRowCount(6);
        grid.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int cellW = dpToPx(28);
        int cellH = dpToPx(31);

        for (int n = 0; n <= 36; n++) {
            final int num = n;
            Button btn = new Button(this);
            btn.setText(String.valueOf(num));
            btn.setTextSize(10f);
            btn.setTextColor(Color.WHITE);
            btn.setPadding(0, 0, 0, 0);
            btn.setFocusable(false);
            btn.setFocusableInTouchMode(false);

            String bgHex = (num == 0) ? "#15803D" : (isRed(num) ? "#B91C1C" : "#1E293B");
            btn.setBackground(makeRoundedBg(bgHex, "#334155", 1, 4));

            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.width = cellW;
            glp.height = cellH;
            glp.setMargins(dpToPx(1), dpToPx(1), dpToPx(1), dpToPx(1));
            btn.setLayoutParams(glp);

            btn.setOnClickListener(v -> {
                selectedNumber = num;
                selectedNumberText.setText("NO: " + num);
            });

            grid.addView(btn);
        }

        Button clrBtn = new Button(this);
        clrBtn.setText("C");
        clrBtn.setTextSize(10f);
        clrBtn.setTextColor(Color.WHITE);
        clrBtn.setPadding(0, 0, 0, 0);
        clrBtn.setBackground(makeRoundedBg("#475569", "#64748B", 1, 4));
        GridLayout.LayoutParams clrLp = new GridLayout.LayoutParams();
        clrLp.width = cellW;
        clrLp.height = cellH;
        clrLp.setMargins(dpToPx(1), dpToPx(1), dpToPx(1), dpToPx(1));
        clrBtn.setLayoutParams(clrLp);
        clrBtn.setOnClickListener(v -> {
            selectedNumber = null;
            selectedNumberText.setText("NO: --");
        });

        grid.addView(clrBtn);
        panel.addView(grid);

        updateInfoLabels();
        return panel;
    }

    private void updateInfoLabels() {
        String mode = currentIsPlaying ? "PLAY NOW" : "BASE MODE";
        String info = currentPattern + "P   •   BET " + currentMultiplier + "X";
        String unitSign = currentTotalUnit > 0 ? "+" + currentTotalUnit : String.valueOf(currentTotalUnit);

        if (pillView != null) {
            pillView.setBackground(makeRoundedBg(
                    currentIsPlaying ? "#0B2922" : "#0F172A",
                    currentIsPlaying ? "#22C55E" : "#38BDF8",
                    2,
                    12
            ));
        }
        if (pillHeadingText != null) {
            pillHeadingText.setText(mode + "   |   U: " + unitSign);
            pillHeadingText.setTextColor(Color.parseColor(currentIsPlaying ? "#4ADE80" : "#38BDF8"));
        }
        if (pillInfoText != null) {
            pillInfoText.setText(info);
        }
        if (pillOcrText != null) {
            pillOcrText.setText(lastOcrStatus);
            pillOcrText.setTextColor(Color.parseColor(isAutoScanning ? "#FACC15" : "#94A3B8"));
        }
        if (expandedInfoText != null) {
            expandedInfoText.setText(mode + " | " + info + " | U:" + unitSign);
        }
        if (scanToggleBtn != null) {
            scanToggleBtn.setText(isAutoScanning ? "AUTO: ON" : "AUTO: OFF");
            scanToggleBtn.setBackground(makeRoundedBg(
                    isAutoScanning ? "#15803D" : "#334155",
                    isAutoScanning ? "#22C55E" : "#475569",
                    1,
                    6
            ));
        }
        if (lockBoxBtn != null) {
            lockBoxBtn.setText(isBoxLocked ? "UNLOCK BOX" : "LOCK BOX");
            lockBoxBtn.setBackground(makeRoundedBg(
                    isBoxLocked ? "#0369A1" : "#334155",
                    isBoxLocked ? "#38BDF8" : "#475569",
                    1,
                    6
            ));
        }
    }

    private void toggleExpand(boolean expand) {
        if (rootContainer == null || params == null || windowManager == null) return;
        if (expand) {
            pillView.setVisibility(View.GONE);
            expandedView.setVisibility(View.VISIBLE);
            params.width = dpToPx(224);
            params.height = dpToPx(312);
        } else {
            expandedView.setVisibility(View.GONE);
            pillView.setVisibility(View.VISIBLE);
            params.width = dpToPx(188);
            params.height = dpToPx(64);
        }
        windowManager.updateViewLayout(rootContainer, params);
    }

    private void attachDragListener(View target) {
        target.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        moved = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - initialTouchX);
                        int dy = Math.round(event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                            moved = true;
                            params.x = Math.max(0, initialX + dx);
                            params.y = Math.max(0, initialY + dy);
                            if (rootContainer != null && windowManager != null) {
                                windowManager.updateViewLayout(rootContainer, params);
                            }
                        }
                        return moved;
                    case MotionEvent.ACTION_UP:
                        if (moved) return true;
                        v.performClick();
                        return true;
                }
                return false;
            }
        });
    }

    private boolean isRed(int n) {
        int[] reds = {1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36};
        for (int r : reds) if (r == n) return true;
        return false;
    }

    private GradientDrawable makeRoundedBg(String fillHex, String strokeHex, int strokeDp, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(fillHex));
        gd.setStroke(dpToPx(strokeDp), Color.parseColor(strokeHex));
        gd.setCornerRadius(dpToPx(radiusDp));
        return gd;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void removeOverlay() {
        if (rootContainer != null && windowManager != null) {
            try { windowManager.removeView(rootContainer); } catch (Exception ignored) {}
            rootContainer = null;
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        stopScreenCaptureInternal();
        removeScanBox();
        removeOverlay();
        if (textRecognizer != null) {
            try { textRecognizer.close(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
