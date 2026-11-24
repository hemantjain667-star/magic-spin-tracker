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
