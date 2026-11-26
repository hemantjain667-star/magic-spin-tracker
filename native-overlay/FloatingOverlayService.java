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
import android.speech.tts.TextToSpeech;
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
    public static final String EXTRA_CAPTURING = "extra_capturing", EXTRA_SCANNING = "extra_scanning", EXTRA_SIREN_PLAYING = "extra_siren_playing";
    public static final String PREFS_NAME = "floating_overlay_state", KEY_PENDING_SPINS = "pending_spins_json", KEY_SPINS_JSON = "synced_spins_json", KEY_PATTERNS_JSON = "synced_patterns_json";

    private static final String CH_ID = "magic_spin_overlay_channel";
    private static final int NOTIF_ID = 4109, COL_NONE = 0, COL_RED = 1, COL_BLK = 2, COL_GRN = 3;
    private static final long OCR_MS = 500L, LOCK_MS = 22000L;
    private static final Object Q_LOCK = new Object(), S_LOCK = new Object();
    private static volatile boolean sirenOn = false;
    private static Thread sirenTh = null;
    private static AudioTrack sirenTr = null;

    public static void appendPendingSpinSync(Context ctx, int num) {
        if (ctx == null || num < 0 || num > 36) return;
        synchronized (Q_LOCK) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            try { JSONArray a = new JSONArray(p.getString(KEY_PENDING_SPINS, "[]")); a.put(num); p.edit().putString(KEY_PENDING_SPINS, a.toString()).commit(); } catch (Exception ignored) {}
        }
    }

    public static List<Integer> drainPendingSpinsSync(Context ctx) {
        List<Integer> out = new ArrayList<>();
        if (ctx == null) return out;
        synchronized (Q_LOCK) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String raw = p.getString(KEY_PENDING_SPINS, "[]");
            p.edit().putString(KEY_PENDING_SPINS, "[]").commit();
            try { JSONArray a = new JSONArray(raw); for (int i = 0; i < a.length(); i++) { int n = a.optInt(i, -1); if (n >= 0 && n <= 36) out.add(n); } } catch (Exception ignored) {}
        }
        return out;
    }

    public static boolean isSirenPlaying() { return sirenOn; }

    public static void startPoliceSiren(Context ctx) {
        synchronized (S_LOCK) {
            if (sirenOn) return;
            sirenOn = true;
            sirenTh = new Thread(() -> {
                final int sr = 22050, n = 1024; short[] buf = new short[n];
                int mb = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioTrack tr = null;
                try {
                    tr = new AudioTrack(
                            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                            new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                            Math.max(mb, n * 4), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
                    synchronized (S_LOCK) { sirenTr = tr; }
                    tr.play();
                    double ph = 0, lfo = 0, ls = 2 * Math.PI * 0.85 / sr;
                    while (sirenOn) {
                        for (int i = 0; i < n; i++) {
                            double f = 1015.0 + 365.0 * Math.sin(lfo);
                            lfo = (lfo + ls) % (2 * Math.PI); ph = (ph + 2 * Math.PI * f / sr) % (2 * Math.PI);
                            buf[i] = (short) ((0.75 * Math.sin(ph) + 0.25 * Math.sin(3 * ph)) * 24000.0);
                        }
                        if (!sirenOn) break;
                        tr.write(buf, 0, n);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (tr != null) { try { tr.stop(); tr.release(); } catch (Exception ignored) {} }
                    synchronized (S_LOCK) { if (sirenTr == tr) sirenTr = null; sirenOn = false; }
                }
            }, "Magic8xSiren");
            sirenTh.start();
        }
        sendSirenBcast(ctx, true);
    }

    public static void stopPoliceSiren(Context ctx) {
        synchronized (S_LOCK) {
            sirenOn = false;
            if (sirenTr != null) { try { sirenTr.pause(); sirenTr.flush(); sirenTr.stop(); } catch (Exception ignored) {} }
            if (sirenTh != null) { sirenTh.interrupt(); sirenTh = null; }
        }
        sendSirenBcast(ctx, false);
    }

    private static void sendSirenBcast(Context c, boolean on) {
        if (c == null) return;
        try { c.sendBroadcast(new Intent(ACTION_SIREN_STATUS).setPackage(c.getPackageName()).putExtra(EXTRA_SIREN_PLAYING, on)); } catch (Exception ignored) {}
    }

    private WindowManager wm;
    private WindowManager.LayoutParams params, boxParams;
    private FrameLayout root, scanBox;
    private View resizeHandle;
    private LinearLayout pillView, expandedView;
    private TextView pillHead, pillInfo, pillOcr, expInfo, selText;
    private final TextView[] l5Views = new TextView[5];
    private final List<Integer> l5Nums = new ArrayList<>();
    private Button scanBtn, lockBtn, sirenBtn;
    private ImageView previewImg;
    private Bitmap lastPrevBmp = null;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    private volatile int boxSX = 0, boxSY = 0, boxSW = 0, boxSH = 0;
    private boolean boxVis = false, boxLock = false, capOn = false, autoScan = false;
    private MediaProjection proj;
    private VirtualDisplay vDisp;
    private ImageReader reader;
    private int capW = 0, capH = 0;
    private TextRecognizer ocr;
    private HandlerThread capTh;
    private Handler capHdl;
    private final Handler uiHdl = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile long ocrMs = 0L;

    private Integer cand = null, lastOcr = null, selNum = null;
    private int stCnt = 0, blCnt = 0, lastS2Col = COL_NONE, curPat = 1, curMult = 1, curUnit = 0;
    private boolean longBlank = false, curPlay = false;
    private long lastSubMs = 0L;
    private String ocrTxt = "MANUAL";

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
        super.onCreate(); isRunning = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH_ID, "Magic Spin Overlay", NotificationManager.IMPORTANCE_LOW));
        }
        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS && tts != null) {
                int r = tts.setLanguage(new Locale("en", "IN"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
                tts.setSpeechRate(1.05f);
                ttsReady = true;
            }
        });
    }

    private void speakNumberOnly(int num) {
        if (tts != null && ttsReady) {
            try { tts.speak(String.valueOf(num), TextToSpeech.QUEUE_FLUSH, null, "n_" + num); } catch (Exception ignored) {}
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int fl, int sid) {
        String a = i != null ? i.getAction() : ACTION_SHOW;
        if (ACTION_HIDE.equals(a)) { stopPoliceSiren(this); stopCap(); rmBox(); rmOvl(); stopForeground(true); stopSelf(); return START_NOT_STICKY; }
        if (ACTION_REFRESH_UI.equals(a)) { loadLast5(); updateUI(); return START_STICKY; }
        if (ACTION_STOP_CAPTURE.equals(a)) { stopCap(); rmBox(); updateUI(); return START_STICKY; }
        if (ACTION_TOGGLE_TARGET_BOX.equals(a)) { if (boxVis) rmBox(); else mkBox(); return START_STICKY; }
        if (ACTION_UPDATE_INFO.equals(a) && i != null) {
            curPat = Math.max(0, Math.min(9, i.getIntExtra(EXTRA_PATTERN, curPat)));
            curMult = Math.max(1, i.getIntExtra(EXTRA_MULTIPLIER, curMult));
            curPlay = i.getBooleanExtra(EXTRA_IS_PLAYING, curPlay);
            curUnit = i.getIntExtra(EXTRA_TOTAL_UNIT, curUnit);
            loadLast5(); updateUI(); return START_STICKY;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        curPat = p.getInt("pattern", 1); curMult = p.getInt("multiplier", 1); curPlay = p.getBoolean("isPlaying", false); curUnit = p.getInt("totalUnit", 0);
        loadLast5();
        if (ACTION_START_CAPTURE.equals(a) && i != null) {
            startFg(true); if (root == null) mkOvl(); if (!boxVis) mkBox();
            Intent rd = i.getParcelableExtra(EXTRA_RESULT_DATA);
            if (rd != null) startCap(i.getIntExtra(EXTRA_RESULT_CODE, -1), rd);
            return START_STICKY;
        }
        startFg(false); if (root == null) mkOvl(); return START_STICKY;
    }

    private void startFg(boolean mp) {
        Notification n = new NotificationCompat.Builder(this, CH_ID).setContentTitle("Magic Spin Tracker")
                .setContentText(mp ? "Auto Capture + OCR Active" : "Floating Overlay Active").setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(NOTIF_ID, n, mp ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mp) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTIF_ID, n);
    }

    private List<Integer> allSpins() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        List<Integer> list = new ArrayList<>();
        synchronized (Q_LOCK) {
            try {
                JSONArray a = new JSONArray(p.getString(KEY_SPINS_JSON, "[]"));
                for (int k = 0; k < a.length(); k++) { int v = a.optInt(k, -1); if (v >= 0 && v <= 36) list.add(v); }
                JSONArray b = new JSONArray(p.getString(KEY_PENDING_SPINS, "[]"));
                for (int k = 0; k < b.length(); k++) { int v = b.optInt(k, -1); if (v >= 0 && v <= 36) list.add(v); }
            } catch (Exception ignored) {}
        }
        return list;
    }

    private void loadLast5() {
        List<Integer> all = allSpins(); l5Nums.clear();
        for (int i = all.size() - 1; i >= 0 && l5Nums.size() < 5; i--) l5Nums.add(all.get(i));
    }

    private DisplayMetrics realDm() { DisplayMetrics d = new DisplayMetrics(); if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE); wm.getDefaultDisplay().getRealMetrics(d); return d; }
    private void syncBoxXY() { if (scanBox == null) return; int[] l = new int[2]; scanBox.getLocationOnScreen(l); boxSX = l[0]; boxSY = l[1]; boxSW = scanBox.getWidth(); boxSH = scanBox.getHeight(); }

    private void setDisp(int w, int h, int d) {
        if (vDisp != null) { try { vDisp.release(); } catch (Exception ignored) {} vDisp = null; }
        if (reader != null) { try { reader.close(); } catch (Exception ignored) {} reader = null; }
        if (proj == null || w <= 0 || h <= 0) return;
        capW = w; capH = h; reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        vDisp = proj.createVirtualDisplay("MagicCap", w, h, d, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, capHdl);
    }

    private void startCap(int rc, Intent data) {
        stopCap();
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) return;
        proj = mpm.getMediaProjection(rc, data); if (proj == null) return;
        capTh = new HandlerThread("MagicOcr"); capTh.start(); capHdl = new Handler(capTh.getLooper());
        proj.registerCallback(new MediaProjection.Callback() { @Override public void onStop() { uiHdl.post(() -> { stopCap(); updateUI(); }); } }, capHdl);
        DisplayMetrics dm = realDm(); setDisp(dm.widthPixels, dm.heightPixels, dm.densityDpi);
        capOn = true; autoScan = true; ocrTxt = "READY"; cand = null; stCnt = 0; blCnt = 0; longBlank = false; lastS2Col = COL_NONE; busy.set(false);
        bcastCap(); updateUI(); tick();
        Toast.makeText(this, "Box number par rakh kar LOCK BOX dabayein!", Toast.LENGTH_LONG).show();
    }

    private void stopCap() {
        capOn = false; autoScan = false; ocrTxt = "MANUAL"; busy.set(false);
        if (capHdl != null) capHdl.removeCallbacksAndMessages(null);
        if (vDisp != null) { try { vDisp.release(); } catch (Exception ignored) {} vDisp = null; }
        if (reader != null) { try { reader.close(); } catch (Exception ignored) {} reader = null; }
        if (proj != null) { try { proj.stop(); } catch (Exception ignored) {} proj = null; }
        if (capTh != null) { try { capTh.quitSafely(); } catch (Exception ignored) {} capTh = null; capHdl = null; }
        capW = 0; capH = 0; bcastCap();
    }

    private void bcastCap() { sendBroadcast(new Intent(ACTION_CAPTURE_STATUS).setPackage(getPackageName()).putExtra(EXTRA_CAPTURING, capOn).putExtra(EXTRA_SCANNING, autoScan)); }

    private void tick() {
        if (!capOn || capHdl == null) return;
        capHdl.postDelayed(() -> {
            if (!capOn) return;
            if (busy.get() && System.currentTimeMillis() - ocrMs > 4000L) busy.set(false);
            if (autoScan && boxVis && !busy.get()) { uiHdl.post(this::syncBoxXY); scanFrame(); }
            tick();
        }, OCR_MS);
    }

    private void scanFrame() {
        if (proj == null || !boxVis || boxSW <= 0 || boxSH <= 0) return;
        long el = System.currentTimeMillis() - lastSubMs;
        if (lastOcr != null && el < LOCK_MS) {
            final long rs = (LOCK_MS - el + 999L) / 1000L; cand = null; stCnt = 0;
            uiHdl.post(() -> { ocrTxt = "WAIT " + rs + "s (" + lastOcr + ")"; updateUI(); }); return;
        }
        DisplayMetrics dm = realDm();
        if (dm.widthPixels != capW || dm.heightPixels != capH) { setDisp(dm.widthPixels, dm.heightPixels, dm.densityDpi); return; }
        if (reader == null) return;
        Image img = null;
        try {
            img = reader.acquireLatestImage(); if (img == null) return;
            int iw = img.getWidth(), ih = img.getHeight();
            Image.Plane pl = img.getPlanes()[0]; int ps = pl.getPixelStride(), pad = pl.getRowStride() - ps * iw;
            Bitmap full = Bitmap.createBitmap(iw + pad / ps, ih, Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(pl.getBuffer()); img.close(); img = null;

            float sx = (float) iw / Math.max(1, dm.widthPixels), sy = (float) ih / Math.max(1, dm.heightPixels);
            int ins = dp(4), cx = Math.max(0, Math.min(iw - 4, Math.round((boxSX + ins) * sx))), cy = Math.max(0, Math.min(ih - 4, Math.round((boxSY + ins) * sy)));
            int cw = Math.max(4, Math.min(iw - cx, Math.round((boxSW - ins * 2) * sx))), ch = Math.max(4, Math.min(ih - cy, Math.round((boxSH - ins * 2) * sy)));

            int s2x = cx + cw + Math.max(4, cw / 5), s2c = COL_NONE;
            if (s2x + cw < iw) { Bitmap b2 = Bitmap.createBitmap(full, s2x, cy, cw, ch); s2c = badgeCol(b2); b2.recycle(); }
            final int fs2 = s2c;
            Bitmap crop = Bitmap.createBitmap(full, cx, cy, cw, ch); full.recycle();

            final Bitmap mp = Bitmap.createScaledBitmap(crop, 72, 52, true);
            uiHdl.post(() -> { if (previewImg != null) previewImg.setImageBitmap(mp); if (lastPrevBmp != null && !lastPrevBmp.isRecycled()) lastPrevBmp.recycle(); lastPrevBmp = mp; });

            final int col = badgeCol(crop);
            if (col == COL_NONE) { crop.recycle(); uiHdl.post(() -> onOcr(null, COL_NONE, fs2)); return; }

            final boolean[] hints = new boolean[]{false, false};
            final Bitmap bw = cleanWhiteDigits(crop, hints);
            if (col == COL_GRN && hints[1]) { bw.recycle(); crop.recycle(); uiHdl.post(() -> onOcr(0, COL_GRN, fs2)); return; }
            final Bitmap rp = padCol(crop); crop.recycle();

            busy.set(true); ocrMs = System.currentTimeMillis();
            ocr.process(InputImage.fromBitmap(bw, 0)).addOnSuccessListener(v1 -> {
                Integer d1 = parseNum(v1.getText(), col);
                if (d1 != null) { bw.recycle(); rp.recycle(); uiHdl.post(() -> { onOcr(d1, col, fs2); busy.set(false); }); }
                else {
                    bw.recycle();
                    ocr.process(InputImage.fromBitmap(rp, 0)).addOnSuccessListener(v2 -> {
                        Integer d2 = parseNum(v2.getText(), col);
                        if (d2 == null && col == COL_RED && hints[0]) d2 = 1;
                        final Integer fd = d2; uiHdl.post(() -> { onOcr(fd, col, fs2); busy.set(false); });
                    }).addOnFailureListener(e -> busy.set(false)).addOnCompleteListener(t -> rp.recycle());
                }
            }).addOnFailureListener(e -> { bw.recycle(); rp.recycle(); busy.set(false); });
        } catch (Exception e) { if (img != null) { try { img.close(); } catch (Exception ignored) {} } busy.set(false); }
    }

    private Bitmap cleanWhiteDigits(Bitmap s, boolean[] hints) {
        int w = s.getWidth(), h = s.getHeight(); boolean[] m = new boolean[w * h]; float[] hsv = new float[3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = s.getPixel(x, y); Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv);
            if (hsv[2] >= 0.62f && hsv[1] <= 0.34f) m[y * w + x] = true;
        }
        boolean[] cl = m.clone(); int[] q = new int[w * h]; int hd = 0, tl = 0;
        for (int x = 0; x < w; x++) { if (cl[x]) { cl[x] = false; q[tl++] = x; } int b = (h - 1) * w + x; if (cl[b]) { cl[b] = false; q[tl++] = b; } }
        for (int y = 1; y < h - 1; y++) { int l = y * w, r = l + w - 1; if (cl[l]) { cl[l] = false; q[tl++] = l; } if (cl[r]) { cl[r] = false; q[tl++] = r; } }
        while (hd < tl) {
            int idx = q[hd++], cx = idx % w, cy = idx / w;
            if (cx > 0 && cl[idx - 1]) { cl[idx - 1] = false; q[tl++] = idx - 1; }
            if (cx + 1 < w && cl[idx + 1]) { cl[idx + 1] = false; q[tl++] = idx + 1; }
            if (cy > 0 && cl[idx - w]) { cl[idx - w] = false; q[tl++] = idx - w; }
            if (cy + 1 < h && cl[idx + w]) { cl[idx + w] = false; q[tl++] = idx + w; }
        }
        int mx0 = w, mx1 = -1, my0 = h, my1 = -1, rem = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (cl[y * w + x]) { rem++; if (x < mx0) mx0 = x; if (x > mx1) mx1 = x; if (y < my0) my0 = y; if (y > my1) my1 = y; }
        boolean[] use = rem >= 8 ? cl : m;
        if (rem >= 8) { hints[1] = true; int dw = mx1 - mx0 + 1, dh = my1 - my0 + 1; if (dh >= Math.max(8, h / 3) && dw >= 2 && dw <= Math.round(dh * 0.45f)) hints[0] = true; }
        int[] px = new int[w * h]; for (int i = 0; i < px.length; i++) px[i] = use[i] ? Color.BLACK : Color.WHITE;
        Bitmap rb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); rb.setPixels(px, 0, w, 0, 0, w, h);
        float sc = Math.max(3f, Math.min(5f, 200f / Math.max(1, h))); int tw = Math.min(440, Math.round(w * sc)), th = Math.min(440, Math.round(h * sc));
        Bitmap up = Bitmap.createScaledBitmap(rb, tw, th, false); rb.recycle();
        Bitmap out = Bitmap.createBitmap(tw + 72, th + 72, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out); cv.drawColor(Color.WHITE); cv.drawBitmap(up, 36, 36, null); up.recycle(); return out;
    }

    private Bitmap padCol(Bitmap s) {
        int sw = Math.max(1, s.getWidth()), sh = Math.max(1, s.getHeight());
        float sc = Math.max(2.5f, Math.min(4f, 180f / sh)); int tw = Math.min(400, Math.round(sw * sc)), th = Math.min(400, Math.round(sh * sc));
        Bitmap up = Bitmap.createScaledBitmap(s, tw, th, true);
        Bitmap out = Bitmap.createBitmap(tw + 56, th + 56, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out); cv.drawColor(s.getPixel(sw / 4, sh / 4)); cv.drawBitmap(up, 28, 28, null); up.recycle(); return out;
    }

    private int badgeCol(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), r = 0, g = 0, k = 0, t = 0, st = Math.max(1, Math.min(w, h) / 24);
        float[] hsv = new float[3]; int x0 = w / 10, x1 = w - x0, y0 = h / 10, y1 = h - y0;
        for (int y = y0; y < y1; y += st) for (int x = x0; x < x1; x += st) {
            int c = b.getPixel(x, y); Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv);
            if (hsv[2] > 0.68f && hsv[1] < 0.30f) continue; t++;
            if ((hsv[0] <= 22f || hsv[0] >= 335f) && hsv[1] >= 0.35f && hsv[2] >= 0.20f) r++;
            else if (hsv[0] >= 75f && hsv[0] <= 178f && hsv[1] >= 0.22f && hsv[2] >= 0.14f) g++;
            else if (hsv[2] <= 0.35f && hsv[1] <= 0.50f) k++;
        }
        if (t < 10) return COL_NONE;
        int rp = r * 100 / t, gp = g * 100 / t, kp = k * 100 / t;
        if (gp >= 18 && gp > rp) return COL_GRN;
        if (rp >= 26 && rp >= gp) return COL_RED;
        if (kp >= 34) return COL_BLK;
        return COL_NONE;
    }

    private Integer parseNum(String raw, int col) {
        if (raw == null || col == COL_NONE) return null;
        String s = raw.replace("\n", " ").trim();
        if (s.isEmpty() || s.length() > 3 || s.matches(".*[:.,$%xX₹].*")) return null;
        if (col == COL_GRN) return 0;
        s = s.replace(" ", "").replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').replace('|', '1').replace('!', '1');
        if (!s.matches("^[0-9]{1,2}$") || (s.length() == 2 && s.startsWith("0"))) return null;
        int v = Integer.parseInt(s);
        if (v < 1 || v > 36) return null;
        if (col == COL_RED && !isRed(v)) return null;
        if (col == COL_BLK && isRed(v)) return null;
        return v;
    }

    private String cTag(int c) { return c == COL_RED ? "RED" : (c == COL_BLK ? "BLK" : (c == COL_GRN ? "GRN" : "---")); }

    private void onOcr(Integer det, int col, int s2c) {
        if (!autoScan) return;
        if (lastOcr != null && System.currentTimeMillis() - lastSubMs < LOCK_MS) return;
        if (det == null) { cand = null; stCnt = 0; if (++blCnt >= 10) longBlank = true; ocrTxt = cTag(col) + ": --"; updateUI(); return; }
        blCnt = 0;
        boolean s2Shift = lastS2Col != COL_NONE && s2c != COL_NONE && s2c != lastS2Col;
        if (lastOcr != null && lastOcr.equals(det) && !longBlank && !s2Shift) { cand = null; stCnt = 0; ocrTxt = cTag(col) + " HOLD:" + det; updateUI(); return; }
        if (cand != null && cand.equals(det)) stCnt++; else { cand = det; stCnt = 1; }
        if (stCnt >= 2) { cand = null; stCnt = 0; lastS2Col = s2c; ocrTxt = cTag(col) + " ADD:" + det; submitSpin(det, "ocr"); return; }
        ocrTxt = cTag(col) + " " + det + " (" + stCnt + "/2)"; updateUI();
    }

    private void submitSpin(int num, String src) {
        if (num < 0 || num > 36) return;
        lastOcr = num; lastSubMs = System.currentTimeMillis(); longBlank = false; blCnt = 0; cand = null; stCnt = 0;
        appendPendingSpinSync(this, num);
        int pm = curMult; recalc();
        if (pm != 8 && curMult == 8) startPoliceSiren(this);
        else speakNumberOnly(num); // Speak ONLY the scanned number!
        loadLast5(); updateUI();
        sendBroadcast(new Intent(ACTION_NUMBER_SUBMITTED).setPackage(getPackageName()).putExtra(EXTRA_NUMBER, num).putExtra(EXTRA_SOURCE, src));
        Toast.makeText(this, ("ocr".equals(src) ? "Auto: " : "Spin: ") + num + " -> " + curPat + "P | " + curMult + "X", Toast.LENGTH_SHORT).show();
    }

    private void recalc() {
        List<Integer> all = allSpins();
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Map<Integer, Set<Integer>> pm = new HashMap<>();
        for (int d = 0; d <= 9; d++) { Set<Integer> s = new HashSet<>(); for (int n : DEF_PAT[d]) s.add(n); pm.put(d, s); }
        try {
            JSONObject o = new JSONObject(p.getString(KEY_PATTERNS_JSON, "{}"));
            for (int d = 0; d <= 9; d++) {
                JSONArray arr = o.optJSONArray(String.valueOf(d));
                if (arr != null) { Set<Integer> cs = new HashSet<>(); for (int i = 0; i < arr.length(); i++) { int n = arr.optInt(i, -1); if (n >= 0 && n <= 36) cs.add(n); } pm.put(d, cs); }
            }
        } catch (Exception ignored) {}
        int mult = 1, rem = 0, unit = 0, base = all.isEmpty() ? 1 : all.get(0);
        for (int i = 0; i < all.size(); i++) {
            int sp = all.get(i), pat = (base + i) % 10; Set<Integer> inP = pm.get(pat);
            int cut = 37 - (inP != null ? inP.size() : 0); boolean hit = inP == null || !inP.contains(sp);
            unit += (hit ? (36 - cut) : -cut) * mult;
            if (hit) { if (mult == 1) { mult = 2; rem = 2; } else { mult = 1; rem = 0; } }
            else if (mult > 1 && --rem == 0) { mult *= 2; rem = 2; }
        }
        curPat = (all.isEmpty() ? 1 : base + all.size()) % 10; curMult = mult; curPlay = mult > 1; curUnit = unit;
    }

    @SuppressWarnings("deprecation")
    private int winType() { return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE; }

    private void mkBox() {
        if (scanBox != null) return;
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int fl = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | (boxLock ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        boxParams = new WindowManager.LayoutParams(dp(44), dp(40), winType(), fl, PixelFormat.TRANSLUCENT);
        boxParams.gravity = Gravity.TOP | Gravity.START; boxParams.x = dp(10); boxParams.y = dp(150);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) boxParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        scanBox = new FrameLayout(this); scanBox.setBackground(bg("#00000000", boxLock ? "#38BDF8" : "#22C55E", 2, 4));
        resizeHandle = new View(this); resizeHandle.setBackground(bg("#22C55E", "#FFFFFF", 1, 3)); resizeHandle.setVisibility(boxLock ? View.GONE : View.VISIBLE);
        scanBox.addView(resizeHandle, new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.BOTTOM | Gravity.END));
        scanBox.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float tx, ty;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (boxLock) return false;
                if (e.getAction() == MotionEvent.ACTION_DOWN) { sx = boxParams.x; sy = boxParams.y; tx = e.getRawX(); ty = e.getRawY(); return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) { boxParams.x = Math.max(0, sx + Math.round(e.getRawX() - tx)); boxParams.y = Math.max(0, sy + Math.round(e.getRawY() - ty)); wm.updateViewLayout(scanBox, boxParams); syncBoxXY(); return true; }
                return false;
            }
        });
        resizeHandle.setOnTouchListener(new View.OnTouchListener() {
            int sw, sh; float tx, ty;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (boxLock) return false;
                if (e.getAction() == MotionEvent.ACTION_DOWN) { sw = boxParams.width; sh = boxParams.height; tx = e.getRawX(); ty = e.getRawY(); return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) { boxParams.width = Math.max(dp(30), sw + Math.round(e.getRawX() - tx)); boxParams.height = Math.max(dp(28), sh + Math.round(e.getRawY() - ty)); wm.updateViewLayout(scanBox, boxParams); syncBoxXY(); return true; }
                return false;
            }
        });
        wm.addView(scanBox, boxParams); boxVis = true; scanBox.post(this::syncBoxXY);
    }

    private void toggleLock() {
        if (scanBox == null || boxParams == null || wm == null) return;
        boxLock = !boxLock;
        if (boxLock) boxParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; else boxParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        scanBox.setBackground(bg("#00000000", boxLock ? "#38BDF8" : "#22C55E", 2, 4));
        if (resizeHandle != null) resizeHandle.setVisibility(boxLock ? View.GONE : View.VISIBLE);
        wm.updateViewLayout(scanBox, boxParams); syncBoxXY(); updateUI();
    }

    private void rmBox() { if (scanBox != null && wm != null) { try { wm.removeView(scanBox); } catch (Exception ignored) {} scanBox = null; boxParams = null; } boxVis = false; }

    private void mkOvl() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE); if (wm == null) return;
        params = new WindowManager.LayoutParams(dp(152), dp(112), winType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = dp(14); params.y = dp(90);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        root = new FrameLayout(this); pillView = mkSquarePill(); expandedView = mkExpanded(); expandedView.setVisibility(View.GONE);
        root.addView(pillView); root.addView(expandedView); addDrag(pillView); addDrag(expandedView); wm.addView(root, params);
    }

    private LinearLayout mkSquarePill() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL); b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setLayoutParams(new FrameLayout.LayoutParams(dp(152), dp(112))); b.setPadding(dp(6), dp(5), dp(6), dp(5));

        pillHead = mkTxt(9f);
        pillInfo = mkTxt(14f); pillInfo.setTextColor(Color.parseColor("#F8FAFC"));

        LinearLayout mid = new LinearLayout(this); mid.setOrientation(LinearLayout.HORIZONTAL); mid.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, dp(24)); mlp.topMargin = dp(2); mid.setLayoutParams(mlp);
        pillOcr = mkTxt(8.5f); pillOcr.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        Button rb = mkBtn("R", "#0284C7", "#38BDF8", dp(24), dp(22)); rb.setTextSize(9f);
        rb.setOnClickListener(v -> { if (lastOcr != null) submitSpin(lastOcr, "manual"); else Toast.makeText(this, "Koi last number nahi", Toast.LENGTH_SHORT).show(); });
        mid.addView(pillOcr); mid.addView(rb);

        View div = new View(this); div.setBackgroundColor(Color.parseColor("#334155"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(1)); dlp.topMargin = dp(3); dlp.bottomMargin = dp(4); div.setLayoutParams(dlp);

        LinearLayout l5Row = new LinearLayout(this); l5Row.setOrientation(LinearLayout.HORIZONTAL); l5Row.setGravity(Gravity.CENTER);
        l5Row.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(24)));
        for (int i = 0; i < 5; i++) {
            TextView tv = mkTxt(9.5f); tv.setText("-"); tv.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(22), 1f);
            if (i > 0) lp.leftMargin = dp(2);
            tv.setLayoutParams(lp); tv.setBackground(bg("#1E293B", "#334155", 1, 4));
            l5Views[i] = tv; l5Row.addView(tv);
        }

        b.addView(pillHead); b.addView(pillInfo); b.addView(mid); b.addView(div); b.addView(l5Row);
        b.setOnClickListener(v -> {
            if (isSirenPlaying()) { stopPoliceSiren(this); updateUI(); Toast.makeText(this, "8X Siren OFF", Toast.LENGTH_SHORT).show(); }
            else setExp(true);
        });
        updateUI(); return b;
    }

    private LinearLayout mkExpanded() {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL);
        p.setLayoutParams(new FrameLayout.LayoutParams(dp(224), dp(354))); p.setBackground(bg("#0F172A", "#38BDF8", 2, 12)); p.setPadding(dp(5), dp(5), dp(5), dp(5));
        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(32)); tlp.bottomMargin = dp(3); top.setLayoutParams(tlp);
        Button col = mkBtn("-", "#334155", "#475569", dp(26), dp(28)); col.setOnClickListener(v -> setExp(false));
        previewImg = new ImageView(this); previewImg.setScaleType(ImageView.ScaleType.FIT_CENTER); previewImg.setBackground(bg("#000000", "#22C55E", 1, 4));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(38), dp(28)); plp.leftMargin = dp(3); previewImg.setLayoutParams(plp);
        selText = mkTxt(11f); selText.setText("NO: --"); selText.setTextColor(Color.parseColor("#FACC15")); selText.setLayoutParams(new LinearLayout.LayoutParams(0, dp(28), 1f));
        Button go = mkBtn("GO", "#22C55E", "#16A34A", dp(40), dp(28)); go.setTextColor(Color.parseColor("#052E16"));
        go.setOnClickListener(v -> { if (selNum == null) return; submitSpin(selNum, "manual"); selNum = null; selText.setText("NO: --"); });
        top.addView(col); top.addView(previewImg); top.addView(selText); top.addView(go); p.addView(top);

        sirenBtn = mkBtn("STOP 8X SIREN (OFF)", "#DC2626", "#FCA5A5", -1, dp(26));
        ((LinearLayout.LayoutParams) sirenBtn.getLayoutParams()).bottomMargin = dp(3); sirenBtn.setVisibility(View.GONE);
        sirenBtn.setOnClickListener(v -> { stopPoliceSiren(this); updateUI(); }); p.addView(sirenBtn);

        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(26)); rlp.bottomMargin = dp(3); row.setLayoutParams(rlp);
        scanBtn = mkBtn("AUTO: OFF", "#334155", "#475569", 0, dp(26));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(26), 1f); slp.rightMargin = dp(3); scanBtn.setLayoutParams(slp);
        scanBtn.setOnClickListener(v -> { if (!capOn) { Toast.makeText(this, "App se START AUTO CAPTURE dabayein", Toast.LENGTH_SHORT).show(); return; } autoScan = !autoScan; ocrTxt = autoScan ? "READY" : "PAUSED"; bcastCap(); updateUI(); });
        lockBtn = mkBtn("LOCK BOX", "#334155", "#475569", 0, dp(26)); lockBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(26), 1f));
        lockBtn.setOnClickListener(v -> { if (!boxVis) mkBox(); else toggleLock(); });
        row.addView(scanBtn); row.addView(lockBtn); p.addView(row);

        expInfo = mkTxt(10.5f); expInfo.setTextColor(Color.parseColor("#F8FAFC")); expInfo.setBackground(bg("#1E293B", "#334155", 1, 6));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, dp(26)); ilp.bottomMargin = dp(3); expInfo.setLayoutParams(ilp); p.addView(expInfo);

        GridLayout g = new GridLayout(this); g.setColumnCount(7); g.setRowCount(6); g.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        int cw = dp(28), ch = dp(30), m = dp(1);
        for (int n = 0; n <= 36; n++) {
            final int num = n; Button bt = mkBtn(String.valueOf(n), n == 0 ? "#15803D" : (isRed(n) ? "#B91C1C" : "#1E293B"), "#334155", cw, ch);
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams(); glp.width = cw; glp.height = ch; glp.setMargins(m, m, m, m); bt.setLayoutParams(glp);
            bt.setOnClickListener(v -> { selNum = num; selText.setText("NO: " + num); }); g.addView(bt);
        }
        Button rep = mkBtn("R", "#0284C7", "#38BDF8", cw, ch);
        GridLayout.LayoutParams rglp = new GridLayout.LayoutParams(); rglp.width = cw; rglp.height = ch; rglp.setMargins(m, m, m, m); rep.setLayoutParams(rglp);
        rep.setOnClickListener(v -> { if (lastOcr != null) submitSpin(lastOcr, "manual"); }); g.addView(rep);
        p.addView(g); updateUI(); return p;
    }

    private TextView mkTxt(float sp) { TextView t = new TextView(this); t.setTextSize(sp); t.setTypeface(Typeface.DEFAULT_BOLD); t.setGravity(Gravity.CENTER); t.setMaxLines(1); return t; }
    private Button mkBtn(String txt, String f, String s, int w, int h) {
        Button b = new Button(this); b.setText(txt); b.setTextColor(Color.WHITE); b.setTextSize(9.5f); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(0, 0, 0, 0); b.setFocusable(false); b.setBackground(bg(f, s, 1, 5)); b.setLayoutParams(new LinearLayout.LayoutParams(w, h)); return b;
    }

    private void updateUI() {
        boolean sir = isSirenPlaying();
        String mode = curMult == 8 ? "8X ALERT" : (curPlay ? "PLAY NOW" : "BASE");
        String info = curPat + "P • BET " + curMult + "X", u = curUnit > 0 ? "+" + curUnit : String.valueOf(curUnit);
        if (pillView != null) pillView.setBackground(sir ? bg("#991B1B", "#FCA5A5", 2, 12) : (curMult == 8 ? bg("#450A0A", "#EF4444", 2, 12) : bg(curPlay ? "#0B2922" : "#0F172A", curPlay ? "#22C55E" : "#38BDF8", 2, 12)));
        if (pillHead != null) { pillHead.setText(sir ? "8X SIREN-TAP OFF" : (mode + " | U:" + u)); pillHead.setTextColor(Color.parseColor(sir ? "#FEF08A" : (curMult == 8 ? "#FCA5A5" : (curPlay ? "#4ADE80" : "#38BDF8")))); }
        if (pillInfo != null) pillInfo.setText(info);
        if (pillOcr != null) { pillOcr.setText(sir ? "TAP TO OFF" : ocrTxt); pillOcr.setTextColor(Color.parseColor(sir ? "#FFFFFF" : (autoScan ? "#FACC15" : "#94A3B8"))); }
        for (int i = 0; i < 5; i++) {
            if (l5Views[i] == null) continue;
            if (i < l5Nums.size()) {
                int n = l5Nums.get(i);
                l5Views[i].setText(String.valueOf(n));
                l5Views[i].setBackground(bg(n == 0 ? "#15803D" : (isRed(n) ? "#B91C1C" : "#111827"), i == 0 ? "#FACC15" : "#475569", 1, 4));
            } else { l5Views[i].setText("-"); l5Views[i].setBackground(bg("#1E293B", "#334155", 1, 4)); }
        }
        if (sirenBtn != null) sirenBtn.setVisibility(sir ? View.VISIBLE : View.GONE);
        if (expInfo != null) expInfo.setText(ocrTxt + " | " + info + " | U:" + u);
        if (scanBtn != null) { scanBtn.setText(autoScan ? "AUTO: ON" : "AUTO: OFF"); scanBtn.setBackground(bg(autoScan ? "#15803D" : "#334155", autoScan ? "#22C55E" : "#475569", 1, 5)); }
        if (lockBtn != null) { lockBtn.setText(boxLock ? "UNLOCK BOX" : "LOCK BOX"); lockBtn.setBackground(bg(boxLock ? "#0369A1" : "#334155", boxLock ? "#38BDF8" : "#475569", 1, 5)); }
    }

    private void setExp(boolean ex) {
        if (root == null || params == null || wm == null) return;
        pillView.setVisibility(ex ? View.GONE : View.VISIBLE); expandedView.setVisibility(ex ? View.VISIBLE : View.GONE);
        params.width = dp(ex ? 224 : 152); params.height = dp(ex ? 354 : 112); wm.updateViewLayout(root, params);
    }

    private void addDrag(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy; float tx, ty; boolean mv;
            @Override public boolean onTouch(View view, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { ix = params.x; iy = params.y; tx = e.getRawX(); ty = e.getRawY(); mv = false; return false; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    int dx = Math.round(e.getRawX() - tx), dy = Math.round(e.getRawY() - ty);
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) { mv = true; params.x = Math.max(0, ix + dx); params.y = Math.max(0, iy + dy); if (root != null && wm != null) wm.updateViewLayout(root, params); }
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
    private void rmOvl() { if (root != null && wm != null) { try { wm.removeView(root); } catch (Exception ignored) {} root = null; } }

    @Override public void onDestroy() {
        isRunning = false; stopPoliceSiren(this); stopCap(); rmBox(); rmOvl();
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {} tts = null; }
        if (lastPrevBmp != null && !lastPrevBmp.isRecycled()) lastPrevBmp.recycle();
        if (ocr != null) { try { ocr.close(); } catch (Exception ignored) {} }
        super.onDestroy();
    }
}
