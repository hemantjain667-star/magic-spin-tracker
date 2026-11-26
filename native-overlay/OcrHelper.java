package com.hemantjain.magicspintracker;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

public class OcrHelper {
    public static final int COL_NONE = 0, COL_RED = 1, COL_BLK = 2, COL_GRN = 3;

    public static boolean isRed(int n) {
        for (int r : new int[]{1,3,5,7,9,12,14,16,18,19,21,23,25,27,30,32,34,36}) {
            if (r == n) return true;
        }
        return false;
    }

    public static String cTag(int c) {
        return c == COL_RED ? "RED" : (c == COL_BLK ? "BLK" : (c == COL_GRN ? "GRN" : "---"));
    }

    public static int badgeCol(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), r = 0, g = 0, k = 0, t = 0;
        int st = Math.max(1, Math.min(w, h) / 24);
        float[] hsv = new float[3];
        int x0 = w / 10, x1 = w - x0, y0 = h / 10, y1 = h - y0;
        for (int y = y0; y < y1; y += st) {
            for (int x = x0; x < x1; x += st) {
                int c = b.getPixel(x, y);
                Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv);
                if (hsv[2] > 0.68f && hsv[1] < 0.30f) continue;
                t++;
                if ((hsv[0] <= 22f || hsv[0] >= 335f) && hsv[1] >= 0.35f && hsv[2] >= 0.20f) r++;
                else if (hsv[0] >= 75f && hsv[0] <= 178f && hsv[1] >= 0.22f && hsv[2] >= 0.14f) g++;
                else if (hsv[2] <= 0.35f && hsv[1] <= 0.50f) k++;
            }
        }
        if (t < 10) return COL_NONE;
        int rp = r * 100 / t, gp = g * 100 / t, kp = k * 100 / t;
        if (gp >= 18 && gp > rp) return COL_GRN;
        if (rp >= 26 && rp >= gp) return COL_RED;
        if (kp >= 34) return COL_BLK;
        return COL_NONE;
    }

    public static Integer parseNum(String raw, int col) {
        if (raw == null || col == COL_NONE) return null;
        String s = raw.replace("\n", " ").trim();
        if (s.isEmpty() || s.length() > 3 || s.matches(".*[:.,$%xX₹].*")) return null;
        if (col == COL_GRN) return 0;
        s = s.replace(" ", "").replace('O', '0').replace('o', '0')
                .replace('I', '1').replace('l', '1').replace('|', '1').replace('!', '1');
        if (!s.matches("^[0-9]{1,2}$") || (s.length() == 2 && s.startsWith("0"))) return null;
        int v = Integer.parseInt(s);
        if (v < 1 || v > 36) return null;
        if (col == COL_RED && !isRed(v)) return null;
        if (col == COL_BLK && isRed(v)) return null;
        return v;
    }

    public static Bitmap cleanWhiteDigits(Bitmap s, boolean[] hints) {
        int w = s.getWidth(), h = s.getHeight();
        boolean[] m = new boolean[w * h];
        float[] hsv = new float[3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = s.getPixel(x, y);
                Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv);
                if (hsv[2] >= 0.62f && hsv[1] <= 0.34f) m[y * w + x] = true;
            }
        }
        boolean[] cl = m.clone();
        int[] q = new int[w * h];
        int hd = 0, tl = 0;
        for (int x = 0; x < w; x++) {
            if (cl[x]) { cl[x] = false; q[tl++] = x; }
            int b = (h - 1) * w + x;
            if (cl[b]) { cl[b] = false; q[tl++] = b; }
        }
        for (int y = 1; y < h - 1; y++) {
            int l = y * w, r = l + w - 1;
            if (cl[l]) { cl[l] = false; q[tl++] = l; }
            if (cl[r]) { cl[r] = false; q[tl++] = r; }
        }
        while (hd < tl) {
            int idx = q[hd++], cx = idx % w, cy = idx / w;
            if (cx > 0 && cl[idx - 1]) { cl[idx - 1] = false; q[tl++] = idx - 1; }
            if (cx + 1 < w && cl[idx + 1]) { cl[idx + 1] = false; q[tl++] = idx + 1; }
            if (cy > 0 && cl[idx - w]) { cl[idx - w] = false; q[tl++] = idx - w; }
            if (cy + 1 < h && cl[idx + w]) { cl[idx + w] = false; q[tl++] = idx + w; }
        }
        int mx0 = w, mx1 = -1, my0 = h, my1 = -1, rem = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (cl[y * w + x]) {
                    rem++;
                    if (x < mx0) mx0 = x; if (x > mx1) mx1 = x;
                    if (y < my0) my0 = y; if (y > my1) my1 = y;
                }
            }
        }
        boolean[] use = rem >= 8 ? cl : m;
        if (rem >= 8) {
            hints[1] = true;
            int dw = mx1 - mx0 + 1, dh = my1 - my0 + 1;
            if (dh >= Math.max(8, h / 3) && dw >= 2 && dw <= Math.round(dh * 0.45f)) hints[0] = true;
        }
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) px[i] = use[i] ? Color.BLACK : Color.WHITE;
        Bitmap rb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        rb.setPixels(px, 0, w, 0, 0, w, h);
        float sc = Math.max(3f, Math.min(5f, 200f / Math.max(1, h)));
        int tw = Math.min(440, Math.round(w * sc)), th = Math.min(440, Math.round(h * sc));
        Bitmap up = Bitmap.createScaledBitmap(rb, tw, th, false);
        rb.recycle();
        Bitmap out = Bitmap.createBitmap(tw + 72, th + 72, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(Color.WHITE);
        cv.drawBitmap(up, 36, 36, null);
        up.recycle();
        return out;
    }

    public static Bitmap padCol(Bitmap s) {
        int sw = Math.max(1, s.getWidth()), sh = Math.max(1, s.getHeight());
        float sc = Math.max(2.5f, Math.min(4f, 180f / sh));
        int tw = Math.min(400, Math.round(sw * sc)), th = Math.min(400, Math.round(sh * sc));
        Bitmap up = Bitmap.createScaledBitmap(s, tw, th, true);
        Bitmap out = Bitmap.createBitmap(tw + 56, th + 56, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(s.getPixel(sw / 4, sh / 4));
        cv.drawBitmap(up, 28, 28, null);
        up.recycle();
        return out;
    }
}
