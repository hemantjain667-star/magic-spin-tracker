package com.hemantjain.magicspintracker;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;
import androidx.core.app.NotificationCompat;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class FloatingOverlayService extends Service {
    public static volatile boolean isRunning = false;
    public static final String ACTION_SHOW = "com.hemantjain.magicspintracker.ACTION_SHOW_OVERLAY";
    public static final String ACTION_HIDE = "com.hemantjain.magicspintracker.ACTION_HIDE_OVERLAY";
    public static final String ACTION_UPDATE_INFO = "com.hemantjain.magicspintracker.ACTION_UPDATE_INFO";
    public static final String ACTION_REFRESH_UI = "com.hemantjain.magicspintracker.ACTION_REFRESH_UI";
    public static final String ACTION_START_CAPTURE = "com.hemantjain.magicspintracker.ACTION_START_CAPTURE";
    public static final String ACTION_STOP_CAPTURE = "com.hemantjain.magicspintracker.ACTION_STOP_CAPTURE";
    public static final String ACTION_TOGGLE_TARGET_BOX = "com.hemantjain.magicspintracker.ACTION_TOGGLE_TARGET_BOX";
    public static final String ACTION_NUMBER_SUBMITTED = "com.hemantjain.magicspintracker.NUMBER_SUBMITTED";
    public static final String ACTION_CAPTURE_STATUS = "com.hemantjain.magicspintracker.CAPTURE_STATUS";
    public static final String ACTION_SIREN_STATUS = "com.hemantjain.magicspintracker.SIREN_STATUS";

    public static final String EXTRA_NUMBER = "extra_number", EXTRA_SOURCE = "extra_source";
    public static final String EXTRA_PATTERN = "extra_pattern", EXTRA_MULTIPLIER = "extra_multiplier";
    public static final String EXTRA_IS_PLAYING = "extra_is_playing", EXTRA_TOTAL_UNIT = "extra_total_unit";
    public static final String EXTRA_RESULT_CODE = "extra_result_code", EXTRA_RESULT_DATA = "extra_result_data";
    public static final String EXTRA_CAPTURING = "extra_capturing", EXTRA_SCANNING = "extra_scanning";
    public static final String EXTRA_SIREN_PLAYING = "extra_siren_playing";

    public static final String PREFS_NAME = "floating_overlay_state";
    public static final String KEY_PENDING_SPINS = "pending_spins_json";
    public static final String KEY_SPINS_JSON = "synced_spins_json";
    public static final String KEY_PATTERNS_JSON = "synced_patterns_json";

    private static final String CHANNEL_ID = "magic_spin_overlay_channel";
    private static final int NOTIF_ID = 4109;
    private static final long OCR_INTERVAL_MS = 700L;      // 0.7s per scan
    private static final long MIN_SPIN_GAP_MS = 22000L;    // 22s strict lock (30-40s roulette round!)
    private static final int STABLE_FRAMES_NEEDED = 2;     // 2 steady matching frames (~1.4s)
    private static final int REPEAT_BLANK_FRAMES = 8;      // ~5.6s empty popup for auto-repeat
    private static final int COL_NONE = 0, COL_RED = 1, COL_BLACK = 2, COL_GREEN = 3;

    private static final Object QUEUE_LOCK = new Object();
    private static final Object SIREN_LOCK = new Object();
    private static volatile boolean sirenRunning = false;
    private static Thread sirenThread = null;
    private static AudioTrack sirenTrack = null;

    public static void appendPendingSpinSync(Context ctx, int num) {
        if (ctx == null || num < 0 || num > 36) return;
        synchronized (QUEUE_LOCK) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            try {
                JSONArray a = new JSONArray(p.getString(KEY_PENDING_SPINS, "[]"));
                a.put(num);
                p.edit().putString(KEY_PENDING_SPINS, a.toString()).commit();
            } catch (Exception ignored) {}
        }
    }

    public static List<Integer> drainPendingSpinsSync(Context ctx) {
        List<Integer> out = new ArrayList<>();
        if (ctx == null) return out;
        synchronized (QUEUE_LOCK) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String raw = p.getString(KEY_PENDING_SPINS, "[]");
            p.edit().putString(KEY_PENDING_SPINS, "[]").commit();
            try {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) { int n = a.optInt(i, -1); if (n >= 0 && n <= 36) out.add(n); }
            } catch (Exception ignored) {}
        }
        return out;
    }

    public static boolean isSirenPlaying() { return sirenRunning; }

    public static void startPoliceSiren(Context ctx) {
        synchronized (SIREN_LOCK) {
            if (sirenRunning) return;
            sirenRunning = true;
            sirenThread = new Thread(() -> {
                final int sr = 22050, n = 1024;
                short[] buf = new short[n];
                int minB = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioTrack tr = null;
                try {
                    AudioAttributes at = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
                    AudioFormat fm = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
                    tr = new AudioTrack(at, fm, Math.max(minB, n * 4), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
                    synchronized (SIREN_LOCK) { sirenTrack = tr; }
                    tr.play();
                    double ph = 0, lfo = 0, lfoStep = 2 * Math.PI * 0.85 / sr;
                    while (sirenRunning) {
                        for (int i = 0; i < n; i++) {
                            double freq = 1015.0 + 365.0 * Math.sin(lfo);
                            lfo = (lfo + lfoStep) % (2 * Math.PI);
                            ph = (ph + 2 * Math.PI * freq / sr) % (2 * Math.PI);
                            buf[i] = (short) ((0.75 * Math.sin(ph) + 0.25 * Math.sin(3 * ph)) * 24000.0);
                        }
                        if (!sirenRunning) break;
                        tr.write(buf, 0, n);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (tr != null) { try { tr.stop(); tr.release(); } catch (Exception ignored) {} }
                    synchronized (SIREN_LOCK) { if (sirenTrack == tr) sirenTrack = null; sirenRunning = false; }
                }
            }, "Magic8xSiren");
            sirenThread.start();
        }
        sendSirenBroadcast(ctx, true);
    }

    public static void stopPoliceSiren(Context ctx) {
        synchronized (SIREN_LOCK) {
            sirenRunning = false;
            if (sirenTrack != null) { try { sirenTrack.pause(); sirenTrack.flush(); sirenTrack.stop(); } catch (Exception ignored) {} }
            if (sirenThread != null) { sirenThread.interrupt(); sirenThread = null; }
        }
        sendSirenBroadcast(ctx, false);
    }

    private static void sendSirenBroadcast(Context ctx, boolean on) {
        if (ctx == null) return;
        try { ctx.sendBroadcast(new Intent(ACTION_SIREN_STATUS).setPackage(ctx.getPackageName()).putExtra(EXTRA_SIREN_PLAYING, on)); } catch (Exception ignored) {}
    }

    private WindowManager wm;
    private WindowManager.LayoutParams params, boxParams;
    private FrameLayout root, scanBox;
    private View resizeHandle;
    private LinearLayout pillView, expandedView;
    private TextView pillHead, pillInfo, pillOcr, expInfo, selText;
    private Button scanBtn, lockBtn, sirenBtn;
    private ImageView previewImg;
    private Bitmap lastPreviewBmp = null;

    private volatile int boxSX = 0, boxSY = 0, boxSW = 0, boxSH = 0;
    private boolean boxVisible = false, boxLocked = false, capturing = false, autoScan = false;
    private MediaProjection proj;
    private VirtualDisplay vDisp;
    private ImageReader reader;
    private int capW = 0, capH = 0;
    private TextRecognizer ocr;
    private HandlerThread capThread;
    private Handler capHandler;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private volatile long ocrStartMs = 0L;

    private Integer candNum = null, lastOcrNum = null, selNum = null;
    private int stableCnt = 0, blankCnt = 0, lastSlot2Col = COL_NONE;
    private boolean sawLongBlank = false;
    private long lastSubMs = 0L;
    private int curPat = 1, curMult = 1, curUnit = 0;
    private boolean curPlay = false;
    private String ocrStatus = "MANUAL";

    private static final int[][] DEF_PAT = {
            {10,20,30,0,1,11,21,31,2,12,22,32,5,15,25,35,6,16,26,36,7,17,27},
            {1,11,21,31,2,12,22,32,3,13,23,33,6,16,26,36,7,17,27,8,18,28},
            {2,12,22,32,3,13,23,33,4,14,24,34,7,17,27,8,18,28,9,19,29},
            {3,13,23,33,4,14,24,34,5,15,25,35,8,18,28,9,19,29,10,20,30,0},
            {4,14,24,34,5,15,25,35,6,16,26,36,9,19,29,10,20,30,0,1,11,21,31},
            {5,15,25,35,6,16,26,36,7,17,27,10,20,30,0,1,11,21,31,2,12,22,32},
            {6,16,26,36,7,17,27,8,18,28,1,11,21,31,2,12,22,32,3,13,23,33},
            {7,17,27,8,18,28,9,19,29,2,12,22,32,3,13,23,33,4,14,24,34},
            {8,18,28,9,19,29,10,20,30,0,3,13,23,33,4,14,24,34,5,15,25,35},
            {9,19,29,10,20,30,0,1,11,21,31,4,14,24,34,5,15,25,35,6,16,26,36}
    };

    @Override public void onCreate() {
        super.onCreate();
        isRunning = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Magic Spin Overlay", NotificationManager.IMPORTANCE_LOW));
        }
        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent != null ? intent.getAction() : ACTION_SHOW;
        if (ACTION_HIDE.equals(act)) { stopPoliceSiren(this); stopCapture(); removeScanBox(); removeOverlay(); stopForeground(true); stopSelf(); return START_NOT_STICKY; }
        if (ACTION_REFRESH_UI.equals(act)) { updateUI(); return START_STICKY; }
        if (ACTION_STOP_CAPTURE.equals(act)) { stopCapture(); removeScanBox(); updateUI(); return START_STICKY; }
        if (ACTION_TOGGLE_TARGET_BOX.equals(act)) { if (boxVisible) removeScanBox(); else createScanBox(); return START_STICKY; }
        if (ACTION_UPDATE_INFO.equals(act) && intent != null) {
            curPat = Math.max(0, Math.min(9, intent.getIntExtra(EXTRA_PATTERN, curPat)));
            curMult = Math.max(1, intent.getIntExtra(EXTRA_MULTIPLIER, curMult));
            curPlay = intent.getBooleanExtra(EXTRA_IS_PLAYING, curPlay);
            curUnit = intent.getIntExtra(EXTRA_TOTAL_UNIT, curUnit);
            updateUI(); return START_STICKY;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission chahiye", Toast.LENGTH_SHORT).show();
            stopSelf(); return START_NOT_STICKY;
        }
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        curPat = p.getInt("pattern", 1); curMult = p.getInt("multiplier", 1);
        curPlay = p.getBoolean("isPlaying", false); curUnit = p.getInt("totalUnit", 0);

        if (ACTION_START_CAPTURE.equals(act) && intent != null) {
            startFg(true);
            if (root == null) createOverlay();
            if (!boxVisible) createScanBox();
            Intent rd = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (rd != null) startCapture(intent.getIntExtra(EXTRA_RESULT_CODE, -1), rd);
            return START_STICKY;
        }
        startFg(false);
        if (root == null) createOverlay();
        return START_STICKY;
    }

    private void startFg(boolean mp) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Magic Spin Tracker").setContentText(mp ? "Auto Screen Capture + Color OCR Active" : "Floating Overlay Active")
                .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, mp ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mp) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else startForeground(NOTIF_ID, n);
    }

    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    private void updateBoxCoordsOnUi() {
        if (scanBox == null) return;
        int[] loc = new int[2];
        scanBox.getLocationOnScreen(loc);
        boxSX = loc[0]; boxSY = loc[1]; boxSW = scanBox.getWidth(); boxSH = scanBox.getHeight();
    }

    private void setupDisplay(int w, int h, int d) {
        if (vDisp != null) { try { vDisp.release(); } catch (Exception ignored) {} vDisp = null; }
        if (reader != null) { try { reader.close(); } catch (Exception ignored) {} reader = null; }
        if (proj == null || w <= 0 || h <= 0) return;
        capW = w; capH = h;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        vDisp = proj.createVirtualDisplay("MagicCap", w, h, d, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, capHandler);
    }

    private void startCapture(int rc, Intent data) {
        stopCapture();
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) return;
        proj = mpm.getMediaProjection(rc, data);
        if (proj == null) return;
        capThread = new HandlerThread("MagicOcr"); capThread.start();
        capHandler = new Handler(capThread.getLooper());
        proj.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { uiHandler.post(() -> { stopCapture(); updateUI(); }); }
        }, capHandler);
        DisplayMetrics dm = realMetrics();
        setupDisplay(dm.widthPixels, dm.heightPixels, dm.densityDpi);
        capturing = true; autoScan = true; ocrStatus = "READY";
        candNum = null; stableCnt = 0; blankCnt = 0; sawLongBlank = false; lastSlot2Col = COL_NONE;
        ocrBusy.set(false);
        sendCapBroadcast(); updateUI(); tickOcr();
        Toast.makeText(this, "Box ko 1st number par rakh kar LOCK BOX dabayein!", Toast.LENGTH_LONG).show();
    }

    private void stopCapture() {
        capturing = false; autoScan = false; ocrStatus = "MANUAL"; ocrBusy.set(false);
        if (capHandler != null) capHandler.removeCallbacksAndMessages(null);
        if (vDisp != null) { try { vDisp.release(); } catch (Exception ignored) {} vDisp = null; }
        if (reader != null) { try { reader.close(); } catch (Exception ignored) {} reader = null; }
        if (proj != null) { try { proj.stop(); } catch (Exception ignored) {} proj = null; }
        if (capThread != null) { try { capThread.quitSafely(); } catch (Exception ignored) {} capThread = null; capHandler = null; }
        capW = 0; capH = 0;
        sendCapBroadcast();
    }

    private void sendCapBroadcast() {
        sendBroadcast(new Intent(ACTION_CAPTURE_STATUS).setPackage(getPackageName()).putExtra(EXTRA_CAPTURING, capturing).putExtra(EXTRA_SCANNING, autoScan));
    }

    private void tickOcr() {
        if (!capturing || capHandler == null) return;
        capHandler.postDelayed(() -> {
            if (!capturing) return;
            // Watchdog: never allow ocrBusy to deadlock > 4s
            if (ocrBusy.get() && (System.currentTimeMillis() - ocrStartMs > 4000L)) ocrBusy.set(false);
            if (autoScan && boxVisible && !ocrBusy.get()) {
                uiHandler.post(this::updateBoxCoordsOnUi);
                runOcrFrame();
            }
            tickOcr();
        }, OCR_INTERVAL_MS);
    }

    private void runOcrFrame() {
        if (proj == null || !boxVisible || boxSW <= 0 || boxSH <= 0) return;

        // Hard 22-second lock after ANY spin (matches 30-40s Auto Roulette round!)
        long elapsed = System.currentTimeMillis() - lastSubMs;
        if (lastOcrNum != null && elapsed < MIN_SPIN_GAP_MS) {
            final long remSec = (MIN_SPIN_GAP_MS - elapsed + 999L) / 1000L;
            candNum = null; stableCnt = 0;
            uiHandler.post(() -> { ocrStatus = "WAIT " + remSec + "s (" + lastOcrNum + ")"; updateUI(); });
            return;
        }

        DisplayMetrics dm = realMetrics();
        if (dm.widthPixels != capW || dm.heightPixels != capH) { setupDisplay(dm.widthPixels, dm.heightPixels, dm.densityDpi); return; }
        if (reader == null) return;
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return;
            int iw = img.getWidth(), ih = img.getHeight();
            Image.Plane pl = img.getPlanes()[0];
            int ps = pl.getPixelStride(), pad = pl.getRowStride() - ps * iw;
            Bitmap full = Bitmap.createBitmap(iw + pad / ps, ih, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(pl.getBuffer());
            img.close(); img = null;

            float sx = (float) iw / Math.max(1, dm.widthPixels), sy = (float) ih / Math.max(1, dm.heightPixels);
            int ins = dp(4);
            int cx = Math.max(0, Math.min(iw - 4, Math.round((boxSX + ins) * sx)));
            int cy = Math.max(0, Math.min(ih - 4, Math.round((boxSY + ins) * sy)));
            int cw = Math.max(4, Math.min(iw - cx, Math.round((boxSW - ins * 2) * sx)));
            int ch = Math.max(4, Math.min(ih - cy, Math.round((boxSH - ins * 2) * sy)));

            // Also check Slot #2 badge color (just to the right) for repeat detection after 22s!
            int s2x = cx + cw + Math.max(4, cw / 5);
            int slot2Col = COL_NONE;
            if (s2x + cw < iw) {
                Bitmap s2Bmp = Bitmap.createBitmap(full, s2x, cy, cw, ch);
                slot2Col = detectBadgeColor(s2Bmp);
                s2Bmp.recycle();
            }
            final int fSlot2Col = slot2Col;

            Bitmap crop = Bitmap.createBitmap(full, cx, cy, cw, ch);
            full.recycle();

            final Bitmap miniPrev = Bitmap.createScaledBitmap(crop, 72, 52, true);
            uiHandler.post(() -> {
                if (previewImg != null) previewImg.setImageBitmap(miniPrev);
                if (lastPreviewBmp != null && !lastPreviewBmp.isRecycled()) lastPreviewBmp.recycle();
                lastPreviewBmp = miniPrev;
            });

            final int badgeCol = detectBadgeColor(crop);
            if (badgeCol == COL_NONE) {
                crop.recycle();
                uiHandler.post(() -> onOcrResult(null, COL_NONE, fSlot2Col));
                return;
            }

            // Pass 1: Border-erased pure Black-on-White digits (eliminates white Evolution outline!)
            final boolean[] thinOneHint = new boolean[]{false};
            final Bitmap cleanBw = extractWhiteDigitsClean(crop, thinOneHint);
            final Bitmap rawPad = scaleAndPadColor(crop);
            crop.recycle();

            ocrBusy.set(true);
            ocrStartMs = System.currentTimeMillis();
            ocr.process(InputImage.fromBitmap(cleanBw, 0)).addOnSuccessListener(vt -> {
                Integer d1 = parseNumForColor(vt.getText(), badgeCol);
                if (d1 != null) {
                    cleanBw.recycle(); rawPad.recycle();
                    uiHandler.post(() -> { onOcrResult(d1, badgeCol, fSlot2Col); ocrBusy.set(false); });
                } else {
                    cleanBw.recycle();
                    // Pass 2: Raw color padded bitmap
                    ocr.process(InputImage.fromBitmap(rawPad, 0)).addOnSuccessListener(vt2 -> {
                        Integer d2 = parseNumForColor(vt2.getText(), badgeCol);
                        // Fallback for thin single-digit "1" on RED badge (ML Kit often skips lone "1")
                        if (d2 == null && badgeCol == COL_RED && thinOneHint[0]) d2 = 1;
                        final Integer finalDet = d2;
                        uiHandler.post(() -> { onOcrResult(finalDet, badgeCol, fSlot2Col); ocrBusy.set(false); });
                    }).addOnFailureListener(e2 -> ocrBusy.set(false))
                      .addOnCompleteListener(t2 -> rawPad.recycle());
                }
            }).addOnFailureListener(e -> { cleanBw.recycle(); rawPad.recycle(); ocrBusy.set(false); });
        } catch (Exception e) {
            if (img != null) { try { img.close(); } catch (Exception ignored) {} }
            ocrBusy.set(false);
        }
    }

    // Extracts pure white roulette digits & flood-erases outer white highlight border!
    private Bitmap extractWhiteDigitsClean(Bitmap src, boolean[] thinOneHint) {
        int w = src.getWidth(), h = src.getHeight();
        boolean[] mask = new boolean[w * h];
        float[] hsv = new float[3];
        int whiteCount = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = src.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                Color.RGBToHSV(r, g, b, hsv);
                // White / bright silver digit pixels
                if (hsv[2] >= 0.62f && hsv[1] <= 0.34f) {
                    mask[y * w + x] = true;
                    whiteCount++;
                }
            }
        }
        // BFS flood-erase any white pixels connected to the 4 outer edges (removes Evolution's white outline!)
        boolean[] cleaned = mask.clone();
        int[] q = new int[w * h];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) {
            if (cleaned[x]) { cleaned[x] = false; q[tail++] = x; }
            int bot = (h - 1) * w + x;
            if (cleaned[bot]) { cleaned[bot] = false; q[tail++] = bot; }
        }
        for (int y = 1; y < h - 1; y++) {
            int l = y * w, r = y * w + (w - 1);
            if (cleaned[l]) { cleaned[l] = false; q[tail++] = l; }
            if (cleaned[r]) { cleaned[r] = false; q[tail++] = r; }
        }
        while (head < tail) {
            int idx = q[head++], cx = idx % w, cy = idx / w;
            if (cx > 0 && cleaned[idx - 1]) { cleaned[idx - 1] = false; q[tail++] = idx - 1; }
            if (cx + 1 < w && cleaned[idx + 1]) { cleaned[idx + 1] = false; q[tail++] = idx + 1; }
            if (cy > 0 && cleaned[idx - w]) { cleaned[idx - w] = false; q[tail++] = idx - w; }
            if (cy + 1 < h && cleaned[idx + w]) { cleaned[idx + w] = false; q[tail++] = idx + w; }
        }
        // Check remaining digit bounding box
        int minX = w, maxX = -1, minY = h, maxY = -1, rem = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (cleaned[y * w + x]) {
                    rem++;
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                }
            }
        }
        // Fallback if digit touched edge and got erased
        boolean[] use = (rem >= 8) ? cleaned : mask;
        if (rem >= 8) {
            int dw = maxX - minX + 1, dh = maxY - minY + 1;
            if (dh >= Math.max(8, h / 3) && dw >= 2 && dw <= Math.round(dh * 0.45f)) {
                thinOneHint[0] = true; // Single narrow vertical digit "1"
            }
        }
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) px[i] = use[i] ? Color.BLACK : Color.WHITE;
        Bitmap rawBw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        rawBw.setPixels(px, 0, w, 0, 0, w, h);

        float sc = Math.max(3f, Math.min(5f, 200f / Math.max(1, h)));
        int tw = Math.min(440, Math.round(w * sc)), th = Math.min(440, Math.round(h * sc));
        Bitmap up = Bitmap.createScaledBitmap(rawBw, tw, th, false);
        rawBw.recycle();
        int p = 36;
        Bitmap out = Bitmap.createBitmap(tw + p * 2, th + p * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out); c.drawColor(Color.WHITE); c.drawBitmap(up, p, p, null); up.recycle();
        return out;
    }

    private Bitmap scaleAndPadColor(Bitmap s) {
        int sw = Math.max(1, s.getWidth()), sh = Math.max(1, s.getHeight());
        float sc = Math.max(2.5f, Math.min(4f, 180f / sh));
        int tw = Math.min(400, Math.round(sw * sc)), th = Math.min(400, Math.round(sh * sc));
        Bitmap up = Bitmap.createScaledBitmap(s, tw, th, true);
        int p = 28, bg = s.getPixel(sw / 4, sh / 4); // Sample inside badge, not white border!
        Bitmap out = Bitmap.createBitmap(tw + p * 2, th + p * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out); c.drawColor(bg); c.drawBitmap(up, p, p, null); up.recycle();
        return out;
    }

    private int detectBadgeColor(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight(), red = 0, grn = 0, blk = 0, bgTotal = 0;
        float[] hsv = new float[3];
        int step = Math.max(1, Math.min(w, h) / 24);
        // Sample inner 80% so white border does not affect badge color
        int x0 = w / 10, x1 = w - x0, y0 = h / 10, y1 = h - y0;
        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                int c = bmp.getPixel(x, y);
                Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv);
                if (hsv[2] > 0.68f && hsv[1] < 0.30f) continue;
                bgTotal++;
                if ((hsv[0] <= 22f || hsv[0] >= 335f) && hsv[1] >= 0.35f && hsv[2] >= 0.22f) red++;
                else if (hsv[0] >= 85f && hsv[0] <= 165f && hsv[1] >= 0.30f && hsv[2] >= 0.18f) grn++;
                else if (hsv[2] <= 0.35f && hsv[1] <= 0.50f) blk++;
            }
        }
        if (bgTotal < 10) return COL_NONE;
        int rPct = (red * 100) / bgTotal, gPct = (grn * 100) / bgTotal, bPct = (blk * 100) / bgTotal;
        if (rPct >= 26 && rPct >= gPct) return COL_RED;
        if (gPct >= 22 && gPct > rPct) return COL_GREEN;
        if (bPct >= 34) return COL_BLACK;
        return COL_NONE;
    }

    private Integer parseNumForColor(String raw, int badgeCol) {
        if (raw == null || badgeCol == COL_NONE) return null;
        String s = raw.replace("\n", " ").trim();
        if (s.isEmpty() || s.length() > 3 || s.matches(".*[:.,$%xX₹].*")) return null;
        s = s.replace(" ", "");
        if (badgeCol == COL_GREEN) {
            return (s.equals("0") || s.equalsIgnoreCase("O") || s.equalsIgnoreCase("D") || s.equalsIgnoreCase("Q")) ? 0 : null;
        }
        s = s.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').replace('|', '1').replace('!', '1');
        if (!s.matches("^[0-9]{1,2}$") || (s.length() == 2 && s.startsWith("0"))) return null;
        int v = Integer.parseInt(s);
        if (v < 1 || v > 36) return null;
        if (badgeCol == COL_RED && !isRed(v)) return null;
        if (badgeCol == COL_BLACK && isRed(v)) return null;
        return v;
    }

    private String colTag(int col) { return col == COL_RED ? "RED" : (col == COL_BLACK ? "BLK" : (col == COL_GREEN ? "GRN" : "---")); }

    private void onOcrResult(Integer det, int col, int slot2Col) {
        if (!autoScan) return;
        long elapsed = System.currentTimeMillis() - lastSubMs;
        if (lastOcrNum != null && elapsed < MIN_SPIN_GAP_MS) return;

        if (det == null) {
            candNum = null; stableCnt = 0; blankCnt++;
            if (blankCnt >= REPEAT_BLANK_FRAMES) sawLongBlank = true;
            ocrStatus = colTag(col) + ": --"; updateUI(); return;
        }
        blankCnt = 0;

        // Same number repeat check (only after 22s lock: either popup vanished OR Slot #2 color shifted!)
        boolean slot2Shifted = (lastSlot2Col != COL_NONE && slot2Col != COL_NONE && slot2Col != lastSlot2Col);
        if (lastOcrNum != null && lastOcrNum.equals(det) && !sawLongBlank && !slot2Shifted) {
            candNum = null; stableCnt = 0;
            ocrStatus = colTag(col) + " HOLD:" + det;
            updateUI(); return;
        }

        if (candNum != null && candNum.equals(det)) stableCnt++; else { candNum = det; stableCnt = 1; }
        if (stableCnt >= STABLE_FRAMES_NEEDED) {
            candNum = null; stableCnt = 0; lastSlot2Col = slot2Col;
            ocrStatus = colTag(col) + " ADD:" + det;
            submitSpin(det, "ocr");
            return;
        }
        ocrStatus = colTag(col) + " " + det + " (" + stableCnt + "/" + STABLE_FRAMES_NEEDED + ")";
        updateUI();
    }

    private void submitSpin(int num, String src) {
        if (num < 0 || num > 36) return;
        lastOcrNum = num; lastSubMs = System.currentTimeMillis(); sawLongBlank = false; blankCnt = 0; candNum = null; stableCnt = 0;
        appendPendingSpinSync(this, num);
        int prevM = curMult;
        recalcNative();
        if (prevM != 8 && curMult == 8) startPoliceSiren(this);
        updateUI();
        sendBroadcast(new Intent(ACTION_NUMBER_SUBMITTED).setPackage(getPackageName())
                .putExtra(EXTRA_NUMBER, num).putExtra(EXTRA_SOURCE, src));
        Toast.makeText(this, ("ocr".equals(src) ? "Auto: " : "Spin: ") + num + " -> " + curPat + "P | " + curMult + "X", Toast.LENGTH_SHORT).show();
    }

    private void recalcNative() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        List<Integer> all = new ArrayList<>();
        synchronized (QUEUE_LOCK) {
            try {
                JSONArray a = new JSONArray(p.getString(KEY_SPINS_JSON, "[]"));
                for (int i = 0; i < a.length(); i++) { int v = a.optInt(i, -1); if (v >= 0 && v <= 36) all.add(v); }
                JSONArray b = new JSONArray(p.getString(KEY_PENDING_SPINS, "[]"));
                for (int i = 0; i < b.length(); i++) { int v = b.optInt(i, -1); if (v >= 0 && v <= 36) all.add(v); }
            } catch (Exception ignored) {}
        }
        Map<Integer, Set<Integer>> pm = new HashMap<>();
        for (int d = 0; d <= 9; d++) { Set<Integer> s = new HashSet<>(); for (int n : DEF_PAT[d]) s.add(n); pm.put(d, s); }
        try {
            JSONObject o = new JSONObject(p.getString(KEY_PATTERNS_JSON, "{}"));
            for (int d = 0; d <= 9; d++) {
                JSONArray arr = o.optJSONArray(String.valueOf(d));
                if (arr != null) {
                    Set<Integer> cs = new HashSet<>();
                    for (int i = 0; i < arr.length(); i++) { int n = arr.optInt(i, -1); if (n >= 0 && n <= 36) cs.add(n); }
                    pm.put(d, cs);
                }
            }
        } catch (Exception ignored) {}
        int mult = 1, rem = 0, unit = 0, base = all.isEmpty() ? 1 : all.get(0);
        for (int i = 0; i < all.size(); i++) {
            int sp = all.get(i), pat = (base + i) % 10;
            Set<Integer> inP = pm.get(pat);
            int cut = 37 - (inP != null ? inP.size() : 0);
            boolean hit = inP == null || !inP.contains(sp);
            unit += (hit ? (36 - cut) : -cut) * mult;
            if (hit) { if (mult == 1) { mult = 2; rem = 2; } else { mult = 1; rem = 0; } }
            else if (mult > 1 && --rem == 0) { mult *= 2; rem = 2; }
        }
        curPat = (all.isEmpty() ? 1 : base + all.size()) % 10;
        curMult = mult; curPlay = mult > 1; curUnit = unit;
    }

    @SuppressWarnings("deprecation")
    private int winType() { return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE; }

    private void createScanBox() {
        if (scanBox != null) return;
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int fl = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | (boxLocked ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        boxParams = new WindowManager.LayoutParams(dp(44), dp(40), winType(), fl, PixelFormat.TRANSLUCENT);
        boxParams.gravity = Gravity.TOP | Gravity.START; boxParams.x = dp(10); boxParams.y = dp(150);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) boxParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        scanBox = new FrameLayout(this);
        scanBox.setBackground(bg("#00000000", boxLocked ? "#38BDF8" : "#22C55E", 2, 4));
        resizeHandle = new View(this);
        resizeHandle.setBackground(bg("#22C55E", "#FFFFFF", 1, 3));
        resizeHandle.setVisibility(boxLocked ? View.GONE : View.VISIBLE);
        scanBox.addView(resizeHandle, new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.BOTTOM | Gravity.END));

        scanBox.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float tx, ty;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (boxLocked) return false;
                if (e.getAction() == MotionEvent.ACTION_DOWN) { sx = boxParams.x; sy = boxParams.y; tx = e.getRawX(); ty = e.getRawY(); return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    boxParams.x = Math.max(0, sx + Math.round(e.getRawX() - tx));
                    boxParams.y = Math.max(0, sy + Math.round(e.getRawY() - ty));
                    wm.updateViewLayout(scanBox, boxParams); updateBoxCoordsOnUi(); return true;
                }
                return false;
            }
        });
        resizeHandle.setOnTouchListener(new View.OnTouchListener() {
            int sw, sh; float tx, ty;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (boxLocked) return false;
                if (e.getAction() == MotionEvent.ACTION_DOWN) { sw = boxParams.width; sh = boxParams.height; tx = e.getRawX(); ty = e.getRawY(); return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    boxParams.width = Math.max(dp(30), sw + Math.round(e.getRawX() - tx));
                    boxParams.height = Math.max(dp(28), sh + Math.round(e.getRawY() - ty));
                    wm.updateViewLayout(scanBox, boxParams); updateBoxCoordsOnUi(); return true;
                }
                return false;
            }
        });
        wm.addView(scanBox, boxParams);
        boxVisible = true;
        scanBox.post(this::updateBoxCoordsOnUi);
    }

    private void toggleLockBox() {
        if (scanBox == null || boxParams == null || wm == null) return;
        boxLocked = !boxLocked;
        if (boxLocked) boxParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else boxParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        scanBox.setBackground(bg("#00000000", boxLocked ? "#38BDF8" : "#22C55E", 2, 4));
        if (resizeHandle != null) resizeHandle.setVisibility(boxLocked ? View.GONE : View.VISIBLE);
        wm.updateViewLayout(scanBox, boxParams);
        updateBoxCoordsOnUi(); updateUI();
    }

    private void removeScanBox() {
        if (scanBox != null && wm != null) { try { wm.removeView(scanBox); } catch (Exception ignored) {} scanBox = null; boxParams = null; }
        boxVisible = false;
    }

    private void createOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        params = new WindowManager.LayoutParams(dp(220), dp(66), winType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = dp(14); params.y = dp(90);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        root = new FrameLayout(this);
        pillView = buildPill(); expandedView = buildExpanded();
        expandedView.setVisibility(View.GONE);
        root.addView(pillView); root.addView(expandedView);
        addDrag(pillView); addDrag(expandedView);
        wm.addView(root, params);
    }

    private LinearLayout buildPill() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL); b.setGravity(Gravity.CENTER_VERTICAL);
        b.setLayoutParams(new FrameLayout.LayoutParams(dp(220), dp(66))); b.setPadding(dp(6), dp(3), dp(5), dp(3));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL); left.setGravity(Gravity.CENTER);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));
        pillHead = mkTxt(9f); pillInfo = mkTxt(12f); pillInfo.setTextColor(Color.parseColor("#F8FAFC")); pillOcr = mkTxt(9f);
        left.addView(pillHead); left.addView(pillInfo); left.addView(pillOcr);

        Button repBtn = mkBtn("R", "#0284C7", "#38BDF8", dp(30), dp(46));
        repBtn.setOnClickListener(v -> {
            if (lastOcrNum != null) submitSpin(lastOcrNum, "manual");
            else Toast.makeText(this, "Koi last number nahi", Toast.LENGTH_SHORT).show();
        });
        b.addView(left); b.addView(repBtn);
        b.setOnClickListener(v -> {
            if (isSirenPlaying()) { stopPoliceSiren(this); updateUI(); Toast.makeText(this, "8X Siren OFF", Toast.LENGTH_SHORT).show(); }
            else setExpanded(true);
        });
        updateUI(); return b;
    }

    private LinearLayout buildExpanded() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setLayoutParams(new FrameLayout.LayoutParams(dp(224), dp(354)));
        p.setBackground(bg("#0F172A", "#38BDF8", 2, 12)); p.setPadding(dp(5), dp(5), dp(5), dp(5));

        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(32)); tlp.bottomMargin = dp(3); top.setLayoutParams(tlp);
        Button col = mkBtn("-", "#334155", "#475569", dp(26), dp(28)); col.setOnClickListener(v -> setExpanded(false));

        previewImg = new ImageView(this);
        previewImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImg.setBackground(bg("#000000", "#22C55E", 1, 4));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(38), dp(28)); plp.leftMargin = dp(3); previewImg.setLayoutParams(plp);

        selText = mkTxt(11f); selText.setText("NO: --"); selText.setTextColor(Color.parseColor("#FACC15"));
        selText.setLayoutParams(new LinearLayout.LayoutParams(0, dp(28), 1f));
        Button go = mkBtn("GO", "#22C55E", "#16A34A", dp(40), dp(28)); go.setTextColor(Color.parseColor("#052E16"));
        go.setOnClickListener(v -> {
            if (selNum == null) { Toast.makeText(this, "Number chunein", Toast.LENGTH_SHORT).show(); return; }
            submitSpin(selNum, "manual"); selNum = null; selText.setText("NO: --");
        });
        top.addView(col); top.addView(previewImg); top.addView(selText); top.addView(go); p.addView(top);

        sirenBtn = mkBtn("STOP 8X SIREN (OFF)", "#DC2626", "#FCA5A5", -1, dp(26));
        ((LinearLayout.LayoutParams) sirenBtn.getLayoutParams()).bottomMargin = dp(3);
        sirenBtn.setVisibility(View.GONE);
        sirenBtn.setOnClickListener(v -> { stopPoliceSiren(this); updateUI(); Toast.makeText(this, "8X Siren OFF", Toast.LENGTH_SHORT).show(); });
        p.addView(sirenBtn);

        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(26)); rlp.bottomMargin = dp(3); row.setLayoutParams(rlp);
        scanBtn = mkBtn("AUTO: OFF", "#334155", "#475569", 0, dp(26));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(26), 1f); slp.rightMargin = dp(3); scanBtn.setLayoutParams(slp);
        scanBtn.setOnClickListener(v -> {
            if (!capturing) { Toast.makeText(this, "App se START AUTO CAPTURE dabayein", Toast.LENGTH_SHORT).show(); return; }
            autoScan = !autoScan; ocrStatus = autoScan ? "READY" : "PAUSED"; sendCapBroadcast(); updateUI();
        });
        lockBtn = mkBtn("LOCK BOX", "#334155", "#475569", 0, dp(26));
        lockBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(26), 1f));
        lockBtn.setOnClickListener(v -> { if (!boxVisible) createScanBox(); else toggleLockBox(); });
        row.addView(scanBtn); row.addView(lockBtn); p.addView(row);

        expInfo = mkTxt(10.5f); expInfo.setTextColor(Color.parseColor("#F8FAFC")); expInfo.setBackground(bg("#1E293B", "#334155", 1, 6));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, dp(26)); ilp.bottomMargin = dp(3); expInfo.setLayoutParams(ilp);
        p.addView(expInfo);

        GridLayout g = new GridLayout(this); g.setColumnCount(7); g.setRowCount(6);
        g.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        int cw = dp(28), ch = dp(30), m = dp(1);
        for (int n = 0; n <= 36; n++) {
            final int num = n;
            Button bt = mkBtn(String.valueOf(n), n == 0 ? "#15803D" : (isRed(n) ? "#B91C1C" : "#1E293B"), "#334155", cw, ch);
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams(); glp.width = cw; glp.height = ch; glp.setMargins(m, m, m, m); bt.setLayoutParams(glp);
            bt.setOnClickListener(v -> { selNum = num; selText.setText("NO: " + num); });
            g.addView(bt);
        }
        Button rep = mkBtn("R", "#0284C7", "#38BDF8", cw, ch);
        GridLayout.LayoutParams rglp = new GridLayout.LayoutParams(); rglp.width = cw; rglp.height = ch; rglp.setMargins(m, m, m, m); rep.setLayoutParams(rglp);
        rep.setOnClickListener(v -> { if (lastOcrNum != null) submitSpin(lastOcrNum, "manual"); else Toast.makeText(this, "Koi last number nahi", Toast.LENGTH_SHORT).show(); });
        g.addView(rep);
        p.addView(g);
        updateUI(); return p;
    }

    private TextView mkTxt(float sp) { TextView t = new TextView(this); t.setTextSize(sp); t.setTypeface(Typeface.DEFAULT_BOLD); t.setGravity(Gravity.CENTER); t.setMaxLines(1); return t; }
    private Button mkBtn(String txt, String fill, String stroke, int w, int h) {
        Button b = new Button(this); b.setText(txt); b.setTextColor(Color.WHITE); b.setTextSize(9.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD); b.setPadding(0, 0, 0, 0); b.setFocusable(false);
        b.setBackground(bg(fill, stroke, 1, 5)); b.setLayoutParams(new LinearLayout.LayoutParams(w, h)); return b;
    }

    private void updateUI() {
        boolean sir = isSirenPlaying();
        String mode = curMult == 8 ? "8X ALERT" : (curPlay ? "PLAY NOW" : "BASE MODE");
        String info = curPat + "P • BET " + curMult + "X";
        String u = curUnit > 0 ? "+" + curUnit : String.valueOf(curUnit);
        if (pillView != null) pillView.setBackground(sir ? bg("#991B1B", "#FCA5A5", 2, 12)
                : (curMult == 8 ? bg("#450A0A", "#EF4444", 2, 12) : bg(curPlay ? "#0B2922" : "#0F172A", curPlay ? "#22C55E" : "#38BDF8", 2, 12)));
        if (pillHead != null) {
            pillHead.setText(sir ? "8X SIREN - TAP OFF" : (mode + " | U:" + u));
            pillHead.setTextColor(Color.parseColor(sir ? "#FEF08A" : (curMult == 8 ? "#FCA5A5" : (curPlay ? "#4ADE80" : "#38BDF8"))));
        }
        if (pillInfo != null) pillInfo.setText(info);
        if (pillOcr != null) { pillOcr.setText(sir ? "TAP TO STOP SIREN" : ocrStatus); pillOcr.setTextColor(Color.parseColor(sir ? "#FFFFFF" : (autoScan ? "#FACC15" : "#94A3B8"))); }
        if (sirenBtn != null) sirenBtn.setVisibility(sir ? View.VISIBLE : View.GONE);
        if (expInfo != null) expInfo.setText(ocrStatus + " | " + info + " | U:" + u);
        if (scanBtn != null) { scanBtn.setText(autoScan ? "AUTO: ON" : "AUTO: OFF"); scanBtn.setBackground(bg(autoScan ? "#15803D" : "#334155", autoScan ? "#22C55E" : "#475569", 1, 5)); }
        if (lockBtn != null) { lockBtn.setText(boxLocked ? "UNLOCK BOX" : "LOCK BOX"); lockBtn.setBackground(bg(boxLocked ? "#0369A1" : "#334155", boxLocked ? "#38BDF8" : "#475569", 1, 5)); }
    }

    private void setExpanded(boolean ex) {
        if (root == null || params == null || wm == null) return;
        pillView.setVisibility(ex ? View.GONE : View.VISIBLE);
        expandedView.setVisibility(ex ? View.VISIBLE : View.GONE);
        params.width = dp(ex ? 224 : 220); params.height = dp(ex ? 354 : 66);
        wm.updateViewLayout(root, params);
    }

    private void addDrag(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy; float tx, ty; boolean mv;
            @Override public boolean onTouch(View view, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { ix = params.x; iy = params.y; tx = e.getRawX(); ty = e.getRawY(); mv = false; return false; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    int dx = Math.round(e.getRawX() - tx), dy = Math.round(e.getRawY() - ty);
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                        mv = true; params.x = Math.max(0, ix + dx); params.y = Math.max(0, iy + dy);
                        if (root != null && wm != null) wm.updateViewLayout(root, params);
                    }
                    return mv;
                }
                if (e.getAction() == MotionEvent.ACTION_UP) { if (mv) return true; view.performClick(); return true; }
                return false;
            }
        });
    }

    private boolean isRed(int n) { for (int r : new int[]{1,3,5,7,9,12,14,16,18,19,21,23,25,27,30,32,34,36}) if (r == n) return true; return false; }
    private GradientDrawable bg(String f, String s, int sw, int r) { GradientDrawable g = new GradientDrawable(); g.setColor(Color.parseColor(f)); g.setStroke(dp(sw), Color.parseColor(s)); g.setCornerRadius(dp(r)); return g; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void removeOverlay() { if (root != null && wm != null) { try { wm.removeView(root); } catch (Exception ignored) {} root = null; } }

    @Override public void onDestroy() {
        isRunning = false; stopPoliceSiren(this); stopCapture(); removeScanBox(); removeOverlay();
        if (lastPreviewBmp != null && !lastPreviewBmp.isRecycled()) lastPreviewBmp.recycle();
        if (ocr != null) { try { ocr.close(); } catch (Exception ignored) {} }
        super.onDestroy();
    }
}
