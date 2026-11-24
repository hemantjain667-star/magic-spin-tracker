
package com.hemantjain.magicspintracker;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
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

public class FloatingOverlayService extends Service {

    public static final String ACTION_SHOW = "com.hemantjain.magicspintracker.ACTION_SHOW_OVERLAY";
    public static final String ACTION_HIDE = "com.hemantjain.magicspintracker.ACTION_HIDE_OVERLAY";
    public static final String ACTION_NUMBER_SUBMITTED = "com.hemantjain.magicspintracker.NUMBER_SUBMITTED";
    public static final String ACTION_UPDATE_INFO = "com.hemantjain.magicspintracker.ACTION_UPDATE_INFO";

    public static final String EXTRA_NUMBER = "extra_number";
    public static final String EXTRA_PATTERN = "extra_pattern";
    public static final String EXTRA_MULTIPLIER = "extra_multiplier";
    public static final String EXTRA_IS_PLAYING = "extra_is_playing";

    private static final String PREFS_NAME = "floating_overlay_state";

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private FrameLayout rootContainer;

    private LinearLayout pillView;
    private LinearLayout expandedView;
    private TextView selectedNumberText;
    private TextView pillInfoText;
    private TextView expandedInfoText;

    private Integer selectedNumber = null;

    private int currentPattern = 1;
    private int currentMultiplier = 1;
    private boolean currentIsPlaying = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
            removeOverlay();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_UPDATE_INFO.equals(intent.getAction())) {
            currentPattern = Math.max(0, Math.min(9,
                    intent.getIntExtra(EXTRA_PATTERN, 1)));
            currentMultiplier = Math.max(1,
                    intent.getIntExtra(EXTRA_MULTIPLIER, 1));
            currentIsPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false);

            saveOverlayState();
            updateInfoLabels();

            if (rootContainer == null) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Display over other apps permission chahiye", Toast.LENGTH_SHORT).show();
            stopSelf();
            return START_NOT_STICKY;
        }

        loadOverlayState();

        if (rootContainer == null) {
            createOverlay();
        }

        return START_STICKY;
    }

    private void saveOverlayState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt("pattern", currentPattern)
                .putInt("multiplier", currentMultiplier)
                .putBoolean("isPlaying", currentIsPlaying)
                .apply();
    }

    private void loadOverlayState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        currentPattern = prefs.getInt("pattern", 1);
        currentMultiplier = prefs.getInt("multiplier", 1);
        currentIsPlaying = prefs.getBoolean("isPlaying", false);
    }

    @SuppressWarnings("deprecation")
    private int getOverlayWindowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;

        int layoutFlag = getOverlayWindowType();
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        params = new WindowManager.LayoutParams(
                dpToPx(178),
                dpToPx(56),
                layoutFlag,
                flags,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dpToPx(16);
        params.y = dpToPx(110);

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
        box.setLayoutParams(new FrameLayout.LayoutParams(
                dpToPx(178), dpToPx(56)));
        box.setBackground(makeRoundedBg("#0F172A", "#38BDF8", 2, 12));
        box.setPadding(dpToPx(5), dpToPx(3), dpToPx(5), dpToPx(3));

        TextView heading = new TextView(this);
        heading.setText("SPIN PIN");
        heading.setTextColor(Color.parseColor("#38BDF8"));
        heading.setTextSize(9f);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setGravity(Gravity.CENTER);

        pillInfoText = new TextView(this);
        pillInfoText.setTextColor(Color.parseColor("#F8FAFC"));
        pillInfoText.setTextSize(12f);
        pillInfoText.setTypeface(Typeface.DEFAULT_BOLD);
        pillInfoText.setGravity(Gravity.CENTER);
        pillInfoText.setMaxLines(1);

        box.addView(heading);
        box.addView(pillInfoText);

        box.setOnClickListener(v -> toggleExpand(true));
        updateInfoLabels();
        return box;
    }

    private LinearLayout buildExpandedKeypadView() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutParams(new FrameLayout.LayoutParams(
                dpToPx(220), dpToPx(278)));
        panel.setBackground(makeRoundedBg("#0F172A", "#38BDF8", 2, 12));
        panel.setPadding(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout.LayoutParams topParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(30));
        topParams.bottomMargin = dpToPx(2);
        topBar.setLayoutParams(topParams);

        Button collapseBtn = new Button(this);
        collapseBtn.setText("—");
        collapseBtn.setTextColor(Color.WHITE);
        collapseBtn.setTextSize(11f);
        collapseBtn.setBackground(makeRoundedBg("#334155", "#475569", 1, 6));
        collapseBtn.setPadding(0, 0, 0, 0);
        collapseBtn.setLayoutParams(new LinearLayout.LayoutParams(
                dpToPx(28), dpToPx(28)));
        collapseBtn.setOnClickListener(v -> toggleExpand(false));

        selectedNumberText = new TextView(this);
        selectedNumberText.setText("NO: --");
        selectedNumberText.setTextColor(Color.parseColor("#FACC15"));
        selectedNumberText.setTextSize(11.5f);
        selectedNumberText.setTypeface(Typeface.DEFAULT_BOLD);
        selectedNumberText.setGravity(Gravity.CENTER);
        selectedNumberText.setLayoutParams(new LinearLayout.LayoutParams(
                0, dpToPx(28), 1f));

        Button goBtn = new Button(this);
        goBtn.setText("GO");
        goBtn.setTextColor(Color.parseColor("#052E16"));
        goBtn.setTextSize(11f);
        goBtn.setTypeface(Typeface.DEFAULT_BOLD);
        goBtn.setBackground(makeRoundedBg("#22C55E", "#16A34A", 1, 6));
        goBtn.setPadding(0, 0, 0, 0);
        goBtn.setLayoutParams(new LinearLayout.LayoutParams(
                dpToPx(44), dpToPx(28)));
        goBtn.setOnClickListener(v -> submitSelectedNumber());

        topBar.addView(collapseBtn);
        topBar.addView(selectedNumberText);
        topBar.addView(goBtn);
        panel.addView(topBar);

        expandedInfoText = new TextView(this);
        expandedInfoText.setTextColor(Color.parseColor("#F8FAFC"));
        expandedInfoText.setTextSize(12f);
        expandedInfoText.setTypeface(Typeface.DEFAULT_BOLD);
        expandedInfoText.setGravity(Gravity.CENTER);
        expandedInfoText.setBackground(makeRoundedBg("#1E293B", "#334155", 1, 6));

        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(27));
        infoParams.bottomMargin = dpToPx(3);
        expandedInfoText.setLayoutParams(infoParams);
        panel.addView(expandedInfoText);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(7);
        grid.setRowCount(6);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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

            String bgHex = (num == 0) ? "#15803D"
                    : (isRed(num) ? "#B91C1C" : "#1E293B");
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
        clrBtn.setFocusable(false);
        clrBtn.setFocusableInTouchMode(false);
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
        String info = "PATTERN: " + currentPattern
                + "     BET: " + currentMultiplier + "X";

        if (pillInfoText != null) {
            pillInfoText.setText(info);
        }

        if (expandedInfoText != null) {
            expandedInfoText.setText(info);
        }
    }

    private void submitSelectedNumber() {
        if (selectedNumber == null) {
            Toast.makeText(this, "0 se 36 ke beech number chunein",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Intent broadcast = new Intent(ACTION_NUMBER_SUBMITTED);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(EXTRA_NUMBER, selectedNumber);
        sendBroadcast(broadcast);

        Toast.makeText(this, "Spin Added: " + selectedNumber,
                Toast.LENGTH_SHORT).show();

        selectedNumber = null;
        selectedNumberText.setText("NO: --");
    }

    private void toggleExpand(boolean expand) {
        if (rootContainer == null || params == null || windowManager == null) return;

        if (expand) {
            pillView.setVisibility(View.GONE);
            expandedView.setVisibility(View.VISIBLE);
            params.width = dpToPx(220);
            params.height = dpToPx(278);
        } else {
            expandedView.setVisibility(View.GONE);
            pillView.setVisibility(View.VISIBLE);
            params.width = dpToPx(178);
            params.height = dpToPx(56);
        }

        windowManager.updateViewLayout(rootContainer, params);
    }

    private void attachDragListener(View target) {
        target.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
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
                        if (moved) {
                            return true;
                        }
                        v.performClick();
                        return true;
                }
                return false;
            }
        });
    }

    private boolean isRed(int n) {
        int[] reds = {1,3,5,7,9,12,14,16,18,19,21,23,25,27,30,32,34,36};
        for (int r : reds) {
            if (r == n) return true;
        }
        return false;
    }

    private GradientDrawable makeRoundedBg(
            String fillHex, String strokeHex, int strokeDp, int radiusDp) {
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
            try {
                windowManager.removeView(rootContainer);
            } catch (Exception ignored) {
            }
            rootContainer = null;
            pillView = null;
            expandedView = null;
            pillInfoText = null;
            expandedInfoText = null;
            selectedNumberText = null;
        }
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }
}
