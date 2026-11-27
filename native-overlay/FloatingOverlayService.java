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
    public static final String ACTION_UNDO_REQUESTED = "com.hemantjain.magicspintracker.UNDO_REQUESTED";
    public static final String ACTION_CAPTURE_STATUS = "com.hemantjain.magicspintracker.CAPTURE_STATUS";
    public static final String ACTION_SIREN_STATUS = "com.hemantjain.magicspintracker.SIREN_STATUS";

    public static final String EXTRA_NUMBER = "extra_number", EXTRA_SOURCE = "extra_source";
    public static final String EXTRA_PATTERN = "extra_pattern", EXTRA_MULTIPLIER = "extra_multiplier";
    public static final String EXTRA_IS_PLAYING = "extra_is_playing", EXTRA_TOTAL_UNIT = "extra_total_unit";
    public static final String EXTRA_RESULT_CODE = "extra_result_code", EXTRA_RESULT_DATA = "extra_result_data";
    public static final String EXTRA_CAPTURING = "extra_capturing", EXTRA_SCANNING = "extra_scanning";
    public static final String EXTRA_SIREN_PLAYING = "extra_siren_playing", EXTRA_SIREN_TYPE = "extra_siren_type";

    public static final String PREFS_NAME = "floating_overlay_state";
    public static final String KEY_PENDING_SPINS = "pending_spins_json", KEY_PENDING_UNDO = "pending_undo_count";
    public static final String KEY_SPINS_JSON = "synced_spins_json", KEY_PATTERNS_JSON = "synced_patterns_json";
    public static final String KEY_TARGET_PROFIT = "target_profit_unit", KEY_STOP_LOSS = "stop_loss_unit";

    private static final String CH_ID = "magic_spin_overlay_channel";
    private static final int NOTIF_ID = 4109;
    private static final long OCR_MS = 500L, LOCK_MS = 22000L;
    private static final Object Q_LOCK = new Object();

    public static class PendingActions {
        public final List<Integer> spins;
        public final int undoCount;
        public PendingActions(List<Integer> s, int u) { this.spins = s; this.undoCount = u; }
    }

    public static void appendPendingSpinSync(Context ctx, int num) {
        if (ctx == null || num < 0 || num > 36) return;
        synchronized (Q_LOCK) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            try { JSONArray a = new JSONArray(p.getString(KEY_PENDING_SPINS, "[]")); a.put(num); p.edit().putString(KEY_PENDING_SPINS, a.toString()).commit(); } catch (Exception ignored) {}
        }
    }

    public static boolean undoLastSpinSync(Context ctx) {
        if (ctx == null) return false;
        synchronized (Q_LOCK) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            try {
                JSONArray pend = new JSONArray(p.getString(KEY_PENDING_SPINS, "[]"));
                if (pend.length() > 0) {
                    JSONArray next = new JSONArray();
                    for (int i = 0; i < pend.length() - 1; i++) next.put(pend.get(i));
                    p.edit().putString(KEY_PENDING_SPINS, next.toString()).commit();
                    return true;
                }
                JSONArray base = new JSONArray(p.getString(KEY_SPINS_JSON, "[]"));
                if (base.length() > 0) {
                    JSONArray nextB = new JSONArray();
                    for (int i = 0; i < base.length() - 1; i++) nextB.put(base.get(i));
                    p.edit().putString(KEY_SPINS_JSON, nextB.toString()).putInt(KEY_PENDING_UNDO, p.getInt(KEY_PENDING_UNDO, 0) + 1).commit();
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    public static List<Integer> drainPendingSpinsSync(Context ctx) { return drainPendingActionsSync(ctx).spins; }

    public static PendingActions drainPendingActionsSync(Context ctx) {
        List<Integer> out = new ArrayList<>(); int uc = 0;
        if (ctx == null) return new PendingActions(out, 0);
        synchronized (Q_LOCK) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String raw = p.getString(KEY_PENDING_SPINS, "[]");
            uc = p.getInt(KEY_PENDING_UNDO, 0);
            p.edit().putString(KEY_PENDING_SPINS, "[]").putInt(KEY_PENDING_UNDO, 0).commit();
            try { JSONArray a = new JSONArray(raw); for (int i = 0; i < a.length(); i++) { int n = a.optInt(i, -1); if (n >= 0 && n <= 36) out.add(n); } } catch (Exception ignored) {}
        }
        return new PendingActions(out, uc);
    }

    private WindowManager wm;
    private WindowManager.LayoutParams params, boxParams;
    private FrameLayout root, scanBox;
    private View resizeHandle, numPadView, grid36View;
    private LinearLayout pillView, expandedView;
    private TextView pillHead, pillInfo, pillOcr, expInfo, selText, msg32xTxt;
    private final TextView[] l5Views = new TextView[5];
    private final List<Integer> l5Nums = new ArrayList<>();
    private Button scanBtn, lockBtn, voiceBtn, modeBtn, sirenBtn;
    private ImageView previewImg;
    private Bitmap lastPrevBmp = null;
    private TextToSpeech tts;
    private boolean ttsReady = false, voiceOn = true, tpFired = false, slFired = false, showGrid36 = false, isExpanded = false;
    private String typedSpin = "";
    private int blinkTone = 0, blinkStepsLeft = 0;

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
    private int stCnt = 0, blCnt = 0, lastS2Col = OcrHelper.COL_NONE, curPat = 1, curMult = 1, curStepsRem = 0, curUnit = 0;
    private boolean longBlank = false, curPlay = false, lastSpinWasHit = false;
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
                tts.setSpeechRate(1.05f); ttsReady = true;
            }
        });
    }

    private void speakNum(int num) {
        if (voiceOn && tts != null && ttsReady) { try { tts.speak(String.valueOf(num), TextToSpeech.QUEUE_FLUSH, null, "n_" + num); } catch (Exception ignored) {} }
    }

    private void speakAlert(String msg) {
        if (tts != null && ttsReady) { try { tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "alrt"); } catch (Exception ignored) {} }
    }

    private void startResultBlink(boolean isHit) { blinkTone = isHit ? 1 : 2; blinkStepsLeft = 6; runBlinkStep(); }
    private void runBlinkStep() { updateUI(); if (blinkStepsLeft <= 0) { blinkTone = 0; updateUI(); return; } blinkStepsLeft--; uiHdl.postDelayed(this::runBlinkStep, 200L); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int fl, int sid) {
        String a = i != null ? i.getAction() : ACTION_SHOW;
        if (ACTION_HIDE.equals(a)) { SirenPlayer.stop(this); stopCap(); rmBox(); rmOvl(); stopForeground(true); stopSelf(); return START_NOT_STICKY; }
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
        voiceOn = p.getBoolean("voiceOn", true); recalc(); loadLast5();
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

    private void loadLast5() { List<Integer> all = allSpins(); l5Nums.clear(); for (int i = all.size() - 1; i >= 0 && l5Nums.size() < 5; i--) l5Nums.add(all.get(i)); }
    private DisplayMetrics realDm() { DisplayMetrics d = new DisplayMetrics(); if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE); wm.getDefaultDisplay().getRealMetrics(d); return d; }
    private void syncBoxXY() { if (scanBox == null) return; int[] l = new int[2]; scanBox.getLocationOnScreen(l); boxSX = l[0]; boxSY = l[1]; boxSW = scanBox.getWidth(); boxSH = scanBox.getHeight(); }

    private void savePositions() {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        if (boxParams != null) ed.putInt("box_x", boxParams.x).putInt("box_y", boxParams.y).putInt("box_w", boxParams.width).putInt("box_h", boxParams.height);
        if (params != null) ed.putInt("pill_x", params.x).putInt("pill_y", params.y);
        ed.apply();
    }

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
        capOn = true; autoScan = true; ocrTxt = "READY"; cand = null; stCnt = 0; blCnt = 0; longBlank = false; lastS2Col = OcrHelper.COL_NONE; busy.set(false);
        bcastCap(); updateUI(); tick();
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

            int s2x = cx + cw + Math.max(4, cw / 5), s2c = OcrHelper.COL_NONE;
            if (s2x + cw < iw) { Bitmap b2 = Bitmap.createBitmap(full, s2x, cy, cw, ch); s2c = OcrHelper.badgeCol(b2); b2.recycle(); }
            final int fs2 = s2c;
            Bitmap crop = Bitmap.createBitmap(full, cx, cy, cw, ch); full.recycle();

            final Bitmap mp = Bitmap.createScaledBitmap(crop, 72, 52, true);
            uiHdl.post(() -> { if (previewImg != null) previewImg.setImageBitmap(mp); if (lastPrevBmp != null && !lastPrevBmp.isRecycled()) lastPrevBmp.recycle(); lastPrevBmp = mp; });

            final int col = OcrHelper.badgeCol(crop);
            if (col == OcrHelper.COL_NONE) { crop.recycle(); uiHdl.post(() -> onOcr(null, OcrHelper.COL_NONE, fs2)); return; }

            final boolean[] hints = new boolean[]{false, false};
            final Bitmap bw = OcrHelper.cleanWhiteDigits(crop, hints);
            if (col == OcrHelper.COL_GRN && hints[1]) { bw.recycle(); crop.recycle(); uiHdl.post(() -> onOcr(0, OcrHelper.COL_GRN, fs2)); return; }
            final Bitmap rp = OcrHelper.padCol(crop); crop.recycle();

            busy.set(true); ocrMs = System.currentTimeMillis();
            ocr.process(InputImage.fromBitmap(bw, 0)).addOnSuccessListener(v1 -> {
                Integer d1 = OcrHelper.parseNum(v1.getText(), col);
                if (d1 != null) { bw.recycle(); rp.recycle(); uiHdl.post(() -> { onOcr(d1, col, fs2); busy.set(false); }); }
                else {
                    bw.recycle();
                    ocr.process(InputImage.fromBitmap(rp, 0)).addOnSuccessListener(v2 -> {
                        Integer d2 = OcrHelper.parseNum(v2.getText(), col);
                        if (d2 == null && col == OcrHelper.COL_RED && hints[0]) d2 = 1;
                        final Integer fd = d2; uiHdl.post(() -> { onOcr(fd, col, fs2); busy.set(false); });
                    }).addOnFailureListener(e -> busy.set(false)).addOnCompleteListener(t -> rp.recycle());
                }
            }).addOnFailureListener(e -> { bw.recycle(); rp.recycle(); busy.set(false); });
        } catch (Exception e) { if (img != null) { try { img.close(); } catch (Exception ignored) {} } busy.set(false); }
    }

    private void onOcr(Integer det, int col, int s2c) {
        if (!autoScan) return;
        if (lastOcr != null && System.currentTimeMillis() - lastSubMs < LOCK_MS) return;
        if (det == null) { cand = null; stCnt = 0; if (++blCnt >= 10) longBlank = true; ocrTxt = OcrHelper.cTag(col) + ": --"; updateUI(); return; }
        blCnt = 0;
        boolean s2Shift = lastS2Col != OcrHelper.COL_NONE && s2c != OcrHelper.COL_NONE && s2c != lastS2Col;
        if (lastOcr != null && lastOcr.equals(det) && !longBlank && !s2Shift) { cand = null; stCnt = 0; ocrTxt = OcrHelper.cTag(col) + " HOLD:" + det; updateUI(); return; }
        if (cand != null && cand.equals(det)) stCnt++; else { cand = det; stCnt = 1; }
        if (stCnt >= 2) { cand = null; stCnt = 0; lastS2Col = s2c; ocrTxt = OcrHelper.cTag(col) + " ADD:" + det; submitSpin(det, "ocr"); return; }
        ocrTxt = OcrHelper.cTag(col) + " " + det + " (" + stCnt + "/2)"; updateUI();
    }

    private void submitSpin(int num, String src) {
        if (num < 0 || num > 36) return;
        lastOcr = num; lastSubMs = System.currentTimeMillis(); longBlank = false; blCnt = 0; cand = null; stCnt = 0;
        int prevM = curMult, prevRem = curStepsRem;
        appendPendingSpinSync(this, num);
        recalc(); loadLast5();
        startResultBlink(lastSpinWasHit);

        boolean enter32x2nd = (curMult == 32 && curStepsRem == 1 && !(prevM == 32 && prevRem == 1));
        boolean enter8x = (prevM != 8 && curMult == 8);
        boolean sirenTriggered = false;
        if (enter32x2nd) {
            sirenTriggered = true;
            SirenPlayer.start32x(this);
            Toast.makeText(this, "Aaj aapka din achha nahi hai, kal se fir koshish karein", Toast.LENGTH_LONG).show();
        } else if (enter8x) {
            sirenTriggered = true;
            SirenPlayer.start8x(this);
        }

        // Loud Stop-Loss & Target Profit Alarm + Voice Announcement
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int tp = p.getInt(KEY_TARGET_PROFIT, 0), sl = p.getInt(KEY_STOP_LOSS, 0);
        boolean goalTriggered = false;
        if (tp > 0 && curUnit >= tp && !tpFired) {
            tpFired = true; goalTriggered = true; ocrTxt = "TARGET HIT!";
            SirenPlayer.playGoalChime(true);
            uiHdl.postDelayed(() -> speakAlert("Target Profit Complete"), 1500L);
            Toast.makeText(this, "TARGET PROFIT (+" + tp + "U) COMPLETE!", Toast.LENGTH_LONG).show();
        } else if (sl > 0 && curUnit <= -sl && !slFired) {
            slFired = true; goalTriggered = true; ocrTxt = "STOP LOSS HIT!";
            SirenPlayer.playGoalChime(false);
            uiHdl.postDelayed(() -> speakAlert("Stop Loss Hit"), 1900L);
            Toast.makeText(this, "STOP-LOSS (-" + sl + "U) HIT! RUK JAIYE!", Toast.LENGTH_LONG).show();
        } else if ((tp == 0 || curUnit < tp) && (sl == 0 || curUnit > -sl)) {
            tpFired = false; slFired = false;
        }

        if (!sirenTriggered && !goalTriggered) speakNum(num);

        updateUI();
        sendBroadcast(new Intent(ACTION_NUMBER_SUBMITTED).setPackage(getPackageName()).putExtra(EXTRA_NUMBER, num).putExtra(EXTRA_SOURCE, src));
    }

    private void performUndo() {
        if (!undoLastSpinSync(this)) { Toast.makeText(this, "Undo ke liye koi spin nahi", Toast.LENGTH_SHORT).show(); return; }
        recalc(); loadLast5(); lastOcr = l5Nums.isEmpty() ? null : l5Nums.get(0); lastSubMs = 0L; tpFired = false; slFired = false;
        updateUI(); sendBroadcast(new Intent(ACTION_UNDO_REQUESTED).setPackage(getPackageName()));
        Toast.makeText(this, "UNDO -> " + curPat + "P | " + curMult + "X", Toast.LENGTH_SHORT).show();
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
        int mult = 1, rem = 0, unit = 0, base = all.isEmpty() ? 1 : all.get(0); boolean lastHit = false;
        for (int i = 0; i < all.size(); i++) {
            int sp = all.get(i), pat = (base + i) % 10; Set<Integer> inP = pm.get(pat);
            int cut = 37 - (inP != null ? inP.size() : 0); boolean hit = inP == null || !inP.contains(sp);
            lastHit = hit; unit += (hit ? (36 - cut) : -cut) * mult;
            if (hit) { if (mult == 1) { mult = 2; rem = 2; } else { mult = 1; rem = 0; } }
            else if (mult > 1 && --rem == 0) { mult *= 2; rem = 2; }
        }
        curPat = (all.isEmpty() ? 1 : base + all.size()) % 10; curMult = mult; curStepsRem = rem; curPlay = mult > 1; curUnit = unit; lastSpinWasHit = lastHit;
    }

    @SuppressWarnings("deprecation")
    private int winType() { return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE; }

    private void mkBox() {
        if (scanBox != null) return;
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int fl = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | (boxLock ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        boxParams = new WindowManager.LayoutParams(p.getInt("box_w", dp(44)), p.getInt("box_h", dp(40)), winType(), fl, PixelFormat.TRANSLUCENT);
        boxParams.gravity = Gravity.TOP | Gravity.START; boxParams.x = p.getInt("box_x", dp(10)); boxParams.y = p.getInt("box_y", dp(150));
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
                if (e.getAction() == MotionEvent.ACTION_UP) { savePositions(); return true; }
                return false;
            }
        });
        resizeHandle.setOnTouchListener(new View.OnTouchListener() {
            int sw, sh; float tx, ty;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (boxLock) return false;
                if (e.getAction() == MotionEvent.ACTION_DOWN) { sw = boxParams.width; sh = boxParams.height; tx = e.getRawX(); ty = e.getRawY(); return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) { boxParams.width = Math.max(dp(30), sw + Math.round(e.getRawX() - tx)); boxParams.height = Math.max(dp(28), sh + Math.round(e.getRawY() - ty)); wm.updateViewLayout(scanBox, boxParams); syncBoxXY(); return true; }
                if (e.getAction() == MotionEvent.ACTION_UP) { savePositions(); return true; }
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
        wm.updateViewLayout(scanBox, boxParams); syncBoxXY(); savePositions(); updateUI();
    }

    private void rmBox() { if (scanBox != null && wm != null) { try { wm.removeView(scanBox); } catch (Exception ignored) {} scanBox = null; boxParams = null; } boxVis = false; }

    private void mkOvl() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE); if (wm == null) return;
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        params = new WindowManager.LayoutParams(dp(152), dp(108), winType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = p.getInt("pill_x", dp(14)); params.y = p.getInt("pill_y", dp(90));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        root = new FrameLayout(this); pillView = mkSquarePill(); expandedView = mkExpanded(); expandedView.setVisibility(View.GONE);
        root.addView(pillView); root.addView(expandedView); addDrag(pillView); addDrag(expandedView); wm.addView(root, params);
    }

    private LinearLayout mkSquarePill() {
        LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setLayoutParams(new FrameLayout.LayoutParams(dp(152), dp(108))); b.setPadding(dp(5), dp(4), dp(5), dp(4));
        pillHead = mkTxt(9f); pillInfo = mkTxt(13.5f); pillInfo.setTextColor(Color.parseColor("#F8FAFC"));

        LinearLayout mid = new LinearLayout(this); mid.setOrientation(LinearLayout.HORIZONTAL); mid.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, dp(22)); mlp.topMargin = dp(2); mid.setLayoutParams(mlp);
        pillOcr = mkTxt(8.5f); pillOcr.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        Button rb = mkBtn("R", "#0284C7", "#38BDF8", dp(24), dp(20)); rb.setTextSize(9f);
        rb.setOnClickListener(v -> { if (lastOcr != null) submitSpin(lastOcr, "manual"); else Toast.makeText(this, "Koi last number nahi", Toast.LENGTH_SHORT).show(); });
        mid.addView(pillOcr); mid.addView(rb);

        View div = new View(this); div.setBackgroundColor(Color.parseColor("#334155"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(1)); dlp.topMargin = dp(3); dlp.bottomMargin = dp(3); div.setLayoutParams(dlp);

        LinearLayout l5Row = new LinearLayout(this); l5Row.setOrientation(LinearLayout.HORIZONTAL); l5Row.setGravity(Gravity.CENTER);
        l5Row.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(22)));
        for (int i = 0; i < 5; i++) {
            TextView tv = mkTxt(9f); tv.setText("-"); tv.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(20), 1f); if (i > 0) lp.leftMargin = dp(2);
            tv.setLayoutParams(lp); tv.setBackground(bg("#1E293B", "#334155", 1, 4));
            l5Views[i] = tv; l5Row.addView(tv);
        }
        b.addView(pillHead); b.addView(pillInfo); b.addView(mid); b.addView(div); b.addView(l5Row);
        b.setOnClickListener(v -> {
            if (SirenPlayer.isPlaying()) { SirenPlayer.stop(this); updateUI(); Toast.makeText(this, "Siren OFF", Toast.LENGTH_SHORT).show(); }
            else setExp(true);
        });
        updateUI(); return b;
    }

    // Compact Opened Pin Widget (192x238 in 123 mode, 212x288 in 0-36 mode!)
    private LinearLayout mkExpanded() {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL);
        p.setLayoutParams(new FrameLayout.LayoutParams(-1, -1)); p.setBackground(bg("#0F172A", "#38BDF8", 2, 10)); p.setPadding(dp(4), dp(4), dp(4), dp(4));

        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(26)); tlp.bottomMargin = dp(2); top.setLayoutParams(tlp);
        Button col = mkBtn("-", "#334155", "#475569", dp(22), dp(24)); col.setOnClickListener(v -> setExp(false));
        previewImg = new ImageView(this); previewImg.setScaleType(ImageView.ScaleType.FIT_CENTER); previewImg.setBackground(bg("#000000", "#22C55E", 1, 4));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(28), dp(24)); plp.leftMargin = dp(2); previewImg.setLayoutParams(plp);
        selText = mkTxt(10f); selText.setText("--"); selText.setTextColor(Color.parseColor("#FACC15")); selText.setBackground(bg("#080D17", "#38BDF8", 1, 4));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(0, dp(24), 1f); stlp.leftMargin = dp(2); stlp.rightMargin = dp(2); selText.setLayoutParams(stlp);
        Button undo = mkBtn("U", "#B45309", "#F59E0B", dp(26), dp(24)); undo.setTextSize(8.5f);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(dp(26), dp(24)); ulp.rightMargin = dp(2); undo.setLayoutParams(ulp);
        undo.setOnClickListener(v -> performUndo());
        Button go = mkBtn("GO", "#22C55E", "#16A34A", dp(30), dp(24)); go.setTextColor(Color.parseColor("#052E16")); go.setTextSize(9f);
        go.setOnClickListener(v -> {
            Integer val = selNum;
            if (!typedSpin.isEmpty()) { try { val = Integer.parseInt(typedSpin); } catch (Exception ignored) { val = null; } }
            if (val == null || val < 0 || val > 36) { Toast.makeText(this, "0-36 dalein", Toast.LENGTH_SHORT).show(); typedSpin = ""; selNum = null; selText.setText("--"); return; }
            submitSpin(val, "manual"); typedSpin = ""; selNum = null; selText.setText("--");
        });
        top.addView(col); top.addView(previewImg); top.addView(selText); top.addView(undo); top.addView(go); p.addView(top);

        msg32xTxt = new TextView(this); msg32xTxt.setText("Aaj aapka din achha nahi, kal fir koshish karein");
        msg32xTxt.setTextColor(Color.parseColor("#FEF08A")); msg32xTxt.setTextSize(8.5f); msg32xTxt.setTypeface(Typeface.DEFAULT_BOLD);
        msg32xTxt.setGravity(Gravity.CENTER); msg32xTxt.setBackground(bg("#7F1D1D", "#FACC15", 1, 5)); msg32xTxt.setPadding(dp(3), dp(2), dp(3), dp(2));
        LinearLayout.LayoutParams m32 = new LinearLayout.LayoutParams(-1, -2); m32.bottomMargin = dp(2); msg32xTxt.setLayoutParams(m32);
        msg32xTxt.setVisibility(View.GONE); p.addView(msg32xTxt);

        sirenBtn = mkBtn("STOP SIREN", "#DC2626", "#FCA5A5", -1, dp(22)); sirenBtn.setTextSize(8.5f);
        ((LinearLayout.LayoutParams) sirenBtn.getLayoutParams()).bottomMargin = dp(2); sirenBtn.setVisibility(View.GONE);
        sirenBtn.setOnClickListener(v -> { SirenPlayer.stop(this); updateUI(); }); p.addView(sirenBtn);

        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(22)); rlp.bottomMargin = dp(2); row.setLayoutParams(rlp);
        scanBtn = mkBtn("AUTO", "#334155", "#475569", 0, dp(22)); scanBtn.setTextSize(7.5f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(22), 1f); slp.rightMargin = dp(2); scanBtn.setLayoutParams(slp);
        scanBtn.setOnClickListener(v -> { if (!capOn) { Toast.makeText(this, "App se START AUTO CAPTURE dabayein", Toast.LENGTH_SHORT).show(); return; } autoScan = !autoScan; ocrTxt = autoScan ? "READY" : "PAUSED"; bcastCap(); updateUI(); });
        lockBtn = mkBtn("LOCK", "#334155", "#475569", 0, dp(22)); lockBtn.setTextSize(7.5f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, dp(22), 1f); llp.rightMargin = dp(2); lockBtn.setLayoutParams(llp);
        lockBtn.setOnClickListener(v -> { if (!boxVis) mkBox(); else toggleLock(); });
        voiceBtn = mkBtn("VOX", "#15803D", "#22C55E", 0, dp(22)); voiceBtn.setTextSize(7.5f);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, dp(22), 1f); vlp.rightMargin = dp(2); voiceBtn.setLayoutParams(vlp);
        voiceBtn.setOnClickListener(v -> { voiceOn = !voiceOn; getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("voiceOn", voiceOn).apply(); if (!voiceOn && tts != null) { try { tts.stop(); } catch (Exception ignored) {} } updateUI(); });
        modeBtn = mkBtn("0-36", "#4F46E5", "#818CF8", 0, dp(22)); modeBtn.setTextSize(7.5f); modeBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(22), 1f));
        modeBtn.setOnClickListener(v -> {
            showGrid36 = !showGrid36;
            numPadView.setVisibility(showGrid36 ? View.GONE : View.VISIBLE);
            grid36View.setVisibility(showGrid36 ? View.VISIBLE : View.GONE);
            modeBtn.setText(showGrid36 ? "123" : "0-36");
            applyWinSize();
        });
        row.addView(scanBtn); row.addView(lockBtn); row.addView(voiceBtn); row.addView(modeBtn); p.addView(row);

        expInfo = mkTxt(9f); expInfo.setTextColor(Color.parseColor("#F8FAFC")); expInfo.setBackground(bg("#1E293B", "#334155", 1, 5));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, dp(20)); ilp.bottomMargin = dp(2); expInfo.setLayoutParams(ilp); p.addView(expInfo);

        FrameLayout area = new FrameLayout(this); area.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        numPadView = mkNumPad(); grid36View = mkGrid36(); grid36View.setVisibility(View.GONE);
        area.addView(numPadView); area.addView(grid36View); p.addView(area);
        updateUI(); return p;
    }

    private View mkNumPad() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        String[][] rows = {{"1","2","3"},{"4","5","6"},{"7","8","9"},{"C","0","<"}};
        for (String[] r : rows) {
            LinearLayout lr = new LinearLayout(this); lr.setOrientation(LinearLayout.HORIZONTAL); lr.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
            for (String k : r) {
                Button bt = mkBtn(k, "C".equals(k) ? "#7F1D1D" : ("<".equals(k) ? "#475569" : "#1E293B"), "#38BDF8", 0, -1); bt.setTextSize(13f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f); lp.setMargins(dp(1), dp(1), dp(1), dp(1)); bt.setLayoutParams(lp);
                bt.setOnClickListener(v -> {
                    if ("C".equals(k)) { typedSpin = ""; selNum = null; }
                    else if ("<".equals(k)) { if (!typedSpin.isEmpty()) typedSpin = typedSpin.substring(0, typedSpin.length() - 1); selNum = null; }
                    else if (typedSpin.length() < 2) { typedSpin += k; selNum = null; }
                    selText.setText(typedSpin.isEmpty() ? "--" : typedSpin);
                });
                lr.addView(bt);
            }
            box.addView(lr);
        }
        return box;
    }

    private View mkGrid36() {
        GridLayout g = new GridLayout(this); g.setColumnCount(7); g.setRowCount(6); g.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        int cw = dp(27), ch = dp(26), m = dp(1);
        for (int n = 0; n <= 36; n++) {
            final int num = n; Button bt = mkBtn(String.valueOf(n), n == 0 ? "#15803D" : (OcrHelper.isRed(n) ? "#B91C1C" : "#1E293B"), "#334155", cw, ch); bt.setTextSize(8.5f);
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams(); glp.width = cw; glp.height = ch; glp.setMargins(m, m, m, m); bt.setLayoutParams(glp);
            bt.setOnClickListener(v -> { selNum = num; typedSpin = String.valueOf(num); selText.setText(String.valueOf(num)); }); g.addView(bt);
        }
        Button rep = mkBtn("R", "#0284C7", "#38BDF8", cw, ch); rep.setTextSize(8.5f);
        GridLayout.LayoutParams rglp = new GridLayout.LayoutParams(); rglp.width = cw; rglp.height = ch; rglp.setMargins(m, m, m, m); rep.setLayoutParams(rglp);
        rep.setOnClickListener(v -> { if (lastOcr != null) submitSpin(lastOcr, "manual"); }); g.addView(rep);
        return g;
    }

    private TextView mkTxt(float sp) { TextView t = new TextView(this); t.setTextSize(sp); t.setTypeface(Typeface.DEFAULT_BOLD); t.setGravity(Gravity.CENTER); t.setMaxLines(1); return t; }
    private Button mkBtn(String txt, String f, String s, int w, int h) {
        Button b = new Button(this); b.setText(txt); b.setTextColor(Color.WHITE); b.setTextSize(9f); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(0, 0, 0, 0); b.setFocusable(false); b.setBackground(bg(f, s, 1, 4)); b.setLayoutParams(new LinearLayout.LayoutParams(w, h)); return b;
    }

    private void updateUI() {
        boolean sir = SirenPlayer.isPlaying(), is32 = sir && SirenPlayer.TYPE_32X.equals(SirenPlayer.getActiveType());
        boolean flashOn = (blinkStepsLeft > 0 && (blinkStepsLeft % 2 == 1));
        String mode = is32 ? "32X ALERT" : (curMult == 8 ? "8X ALERT" : (curPlay ? "PLAY NOW" : "BASE"));
        String info = curPat + "P • BET " + curMult + "X", u = curUnit > 0 ? "+" + curUnit : String.valueOf(curUnit);

        if (pillView != null) {
            if (flashOn && blinkTone == 1) pillView.setBackground(bg("#059669", "#86EFAC", 3, 10));
            else if (flashOn && blinkTone == 2) pillView.setBackground(bg("#DC2626", "#FCA5A5", 3, 10));
            else if (is32) pillView.setBackground(bg("#7F1D1D", "#FACC15", 3, 10));
            else if (sir) pillView.setBackground(bg("#991B1B", "#FCA5A5", 2, 10));
            else pillView.setBackground(curMult == 8 ? bg("#450A0A", "#EF4444", 2, 10) : bg(curPlay ? "#0B2922" : "#0F172A", curPlay ? "#22C55E" : "#38BDF8", 2, 10));
        }
        if (pillHead != null) { pillHead.setText(is32 ? "32X (2ND)-TAP OFF" : (sir ? "8X SIREN-TAP OFF" : (mode + " | U:" + u))); pillHead.setTextColor(Color.parseColor(sir ? "#FEF08A" : (curMult == 8 ? "#FCA5A5" : (curPlay ? "#4ADE80" : "#38BDF8")))); }
        if (pillInfo != null) pillInfo.setText(info);
        if (pillOcr != null) { pillOcr.setText(is32 ? "Kal se fir koshish!" : (sir ? "TAP TO OFF" : ocrTxt)); pillOcr.setTextColor(Color.parseColor(sir ? "#FFFFFF" : (autoScan ? "#FACC15" : "#94A3B8"))); }
        for (int i = 0; i < 5; i++) {
            if (l5Views[i] == null) continue;
            if (i < l5Nums.size()) { int n = l5Nums.get(i); l5Views[i].setText(String.valueOf(n)); l5Views[i].setBackground(bg(n == 0 ? "#15803D" : (OcrHelper.isRed(n) ? "#B91C1C" : "#111827"), i == 0 ? "#FACC15" : "#475569", 1, 4)); }
            else { l5Views[i].setText("-"); l5Views[i].setBackground(bg("#1E293B", "#334155", 1, 4)); }
        }
        if (msg32xTxt != null) msg32xTxt.setVisibility(is32 ? View.VISIBLE : View.GONE);
        if (sirenBtn != null) { sirenBtn.setVisibility(sir ? View.VISIBLE : View.GONE); sirenBtn.setText(is32 ? "STOP 32X SIREN" : "STOP 8X SIREN"); }
        if (expInfo != null) expInfo.setText(ocrTxt + " | " + info + " | U:" + u);
        if (scanBtn != null) { scanBtn.setText(autoScan ? "AUTO" : "OFF"); scanBtn.setBackground(bg(autoScan ? "#15803D" : "#334155", autoScan ? "#22C55E" : "#475569", 1, 4)); }
        if (lockBtn != null) { lockBtn.setText(boxLock ? "UNLK" : "LOCK"); lockBtn.setBackground(bg(boxLock ? "#0369A1" : "#334155", boxLock ? "#38BDF8" : "#475569", 1, 4)); }
        if (voiceBtn != null) { voiceBtn.setText(voiceOn ? "VOX" : "MUTE"); voiceBtn.setBackground(bg(voiceOn ? "#15803D" : "#334155", voiceOn ? "#22C55E" : "#475569", 1, 4)); }
    }

    private void setExp(boolean ex) { isExpanded = ex; applyWinSize(); }

    private void applyWinSize() {
        if (root == null || params == null || wm == null) return;
        pillView.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
        expandedView.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        if (!isExpanded) { params.width = dp(152); params.height = dp(108); }
        else if (showGrid36) { params.width = dp(212); params.height = dp(288); }
        else { params.width = dp(192); params.height = dp(238); }
        wm.updateViewLayout(root, params);
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
                if (e.getAction() == MotionEvent.ACTION_UP) { if (mv) { savePositions(); return true; } view.performClick(); return true; }
                return false;
            }
        });
    }

    private GradientDrawable bg(String f, String s, int sw, int r) { GradientDrawable g = new GradientDrawable(); g.setColor(Color.parseColor(f)); g.setStroke(dp(sw), Color.parseColor(s)); g.setCornerRadius(dp(r)); return g; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void rmOvl() { if (root != null && wm != null) { try { wm.removeView(root); } catch (Exception ignored) {} root = null; } }

    @Override public void onDestroy() {
        isRunning = false; SirenPlayer.stop(this); stopCap(); rmBox(); rmOvl();
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {} tts = null; }
        if (lastPrevBmp != null && !lastPrevBmp.isRecycled()) lastPrevBmp.recycle();
        if (ocr != null) { try { ocr.close(); } catch (Exception ignored) {} }
        super.onDestroy();
    }
}
