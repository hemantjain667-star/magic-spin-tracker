package com.hemantjain.magicspintracker;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import com.google.mlkit.vision.text.Text;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OcrHelper {
    public static final int COL_NONE = 0, COL_RED = 1, COL_BLK = 2, COL_GRN = 3;

    public static class BarResult {
        public final List<Integer> nums;
        public final int firstCol;
        public BarResult(List<Integer> nums, int firstCol) { this.nums = nums; this.firstCol = firstCol; }
    }

    public static boolean isRed(int n) {
        for (int r : new int[]{1,3,5,7,9,12,14,16,18,19,21,23,25,27,30,32,34,36}) if (r == n) return true;
        return false;
    }

    public static String cTag(int c) {
        return c == COL_RED ? "RED" : (c == COL_BLK ? "BLK" : (c == COL_GRN ? "GRN" : "---"));
    }

    public static int badgeCol(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), r = 0, g = 0, k = 0, t = 0;
        int st = Math.max(1, Math.min(w, h) / 20);
        float[] hsv = new float[3];
        int x0 = (w * 15) / 100, x1 = w - x0, y0 = (h * 18) / 100, y1 = h - y0;
        if (x1 <= x0) { x0 = 0; x1 = w; }
        if (y1 <= y0) { y0 = 0; y1 = h; }
        for (int y = y0; y < y1; y += st) {
            for (int x = x0; x < x1; x += st) {
                int c = b.getPixel(x, y);
                int cr = Color.red(c), cg = Color.green(c), cb = Color.blue(c);
                if (Math.min(cr, Math.min(cg, cb)) > 175) continue;
                Color.RGBToHSV(cr, cg, cb, hsv);
                t++;
                boolean isGrn = (hsv[0] >= 75f && hsv[0] <= 178f && hsv[1] >= 0.25f && hsv[2] >= 0.14f)
                        || (cg > cr + 20 && cg > cb + 10 && cg > 55);
                if (isGrn) g++;
                else if ((hsv[0] <= 22f || hsv[0] >= 335f) && hsv[1] >= 0.30f && hsv[2] >= 0.18f) r++;
                else if ((hsv[2] <= 0.38f && hsv[1] <= 0.50f) || (hsv[2] <= 0.68f && hsv[1] <= 0.22f)) k++;
            }
        }
        if (t < 6) return COL_NONE;
        int rp = r * 100 / t, gp = g * 100 / t, kp = k * 100 / t;
        if (gp >= 30 && gp > rp) return COL_GRN;
        if (rp >= 24 && rp >= gp) return COL_RED;
        if (kp >= 28) return COL_BLK;
        return COL_NONE;
    }

    private static String cleanDigitChars(String s) {
        return s.replace('O', '0').replace('o', '0').replace('D', '0').replace('Q', '0')
                .replace('I', '1').replace('l', '1').replace('|', '1').replace('!', '1').replace('i', '1').replace(']', '1').replace('[', '1')
                .replace('Z', '2').replace('z', '2')
                .replace('S', '5').replace('s', '5')
                .replace('G', '6').replace('b', '6')
                .replace('B', '8');
    }

    // Converts the ENTIRE wide history bar ([18] 16 4 1 2 2) into ONE super-crisp Black-on-White bitmap!
    public static Bitmap cleanFullHistoryBar(Bitmap bar) {
        int sw = Math.max(1, bar.getWidth()), sh = Math.max(1, bar.getHeight());
        float sc = Math.max(2.0f, Math.min(3.5f, 110f / Math.max(1, sh)));
        int tw = Math.min(920, Math.round(sw * sc)), th = Math.min(160, Math.round(sh * sc));
        Bitmap up = Bitmap.createScaledBitmap(bar, tw, th, true);

        int[] px = new int[tw * th];
        up.getPixels(px, 0, tw, 0, 0, tw, th);
        up.recycle();

        // Slot #1 sits on the left (~1.5x height wide); rest is black history strip
        int slot1End = Math.min(tw, Math.round(th * 1.55f));

        // Adaptive background min(R,G,B) for Slot #1 (handles Red~35, Green~40, Black~25, AND Grey [4]~125!)
        long bgSum = 0; int bgCnt = 0;
        int ry = Math.max(4, (th * 14) / 100), rx = Math.max(4, (slot1End * 12) / 100);
        for (int y = ry; y < th - ry; y += 2) {
            for (int x = rx; x < Math.max(rx + 2, slot1End - rx); x += 2) {
                int c = px[y * tw + x];
                int mn = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
                if (mn < 175) { bgSum += mn; bgCnt++; }
            }
        }
        int s1Bg = bgCnt > 0 ? (int) (bgSum / bgCnt) : 40;
        int s1Thresh = Math.max(152, Math.min(210, s1Bg + 52));

        boolean[] ink = new boolean[tw * th];
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int c = px[y * tw + x];
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                if (x < slot1End) {
                    // Slot #1: pure white digit on Red/Green/Black/Grey filled box
                    ink[y * tw + x] = Math.min(r, Math.min(g, b)) >= s1Thresh;
                } else {
                    // Slots #2..#8: bright White/Red/Green digits on pure black strip
                    int maxCh = Math.max(r, Math.max(g, b));
                    ink[y * tw + x] = maxCh >= 78;
                }
            }
        }

        // Erase white outline box lines inside Slot #1 ONLY (top/bottom horizontal & left/right vertical borders)
        boolean[] eraseRowS1 = new boolean[th];
        for (int y = 0; y < th; y++) {
            int cnt = 0;
            for (int x = 0; x < slot1End; x++) if (ink[y * tw + x]) cnt++;
            if (cnt * 100 / Math.max(1, slot1End) >= 52) {
                for (int dy = -3; dy <= 3; dy++) { int ny = y + dy; if (ny >= 0 && ny < th) eraseRowS1[ny] = true; }
            }
        }
        boolean[] eraseColS1 = new boolean[slot1End];
        int edgeLim = Math.max(6, (slot1End * 22) / 100);
        for (int x = 0; x < slot1End; x++) {
            if (x > edgeLim && x < slot1End - edgeLim) continue;
            int cnt = 0;
            for (int y = 0; y < th; y++) if (ink[y * tw + x]) cnt++;
            if (cnt * 100 / th >= 48) {
                for (int dx = -3; dx <= 3; dx++) { int nx = x + dx; if (nx >= 0 && nx < slot1End) eraseColS1[nx] = true; }
            }
        }

        int topCut = Math.max(3, (th * 8) / 100);
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                boolean drop = (y < topCut || y >= th - topCut);
                if (x < slot1End && (eraseRowS1[y] || eraseColS1[x] || x < 4)) drop = true;
                px[y * tw + x] = (!drop && ink[y * tw + x]) ? Color.BLACK : Color.WHITE;
            }
        }

        Bitmap bw = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        bw.setPixels(px, 0, tw, 0, 0, tw, th);
        int pad = 28;
        Bitmap out = Bitmap.createBitmap(tw + pad * 2, th + pad * 2, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out); cv.drawColor(Color.WHITE); cv.drawBitmap(bw, pad, pad, null); bw.recycle();
        return out;
    }

    // Parses ALL numbers left-to-right AND verifies Slot #1's Roulette Color (Red/Black/Green)!
    public static BarResult parseFullHistoryBar(Text visionText, Bitmap rawBar) {
        List<Text.Element> elems = new ArrayList<>();
        if (visionText != null) {
            for (Text.TextBlock b : visionText.getTextBlocks()) {
                for (Text.Line l : b.getLines()) elems.addAll(l.getElements());
            }
        }
        Collections.sort(elems, (a, b) -> {
            int ax = a.getBoundingBox() != null ? a.getBoundingBox().left : 0;
            int bx = b.getBoundingBox() != null ? b.getBoundingBox().left : 0;
            return Integer.compare(ax, bx);
        });

        int slot1W = Math.min(rawBar.getWidth(), Math.max(10, Math.round(rawBar.getHeight() * 1.45f)));
        Bitmap s1Crop = Bitmap.createBitmap(rawBar, 0, 0, slot1W, rawBar.getHeight());
        int firstCol = badgeCol(s1Crop);
        s1Crop.recycle();

        List<Integer> nums = new ArrayList<>();
        for (Text.Element el : elems) {
            String raw = el.getText();
            if (raw == null) continue;
            String s = raw.trim();
            if (s.isEmpty() || s.length() > 3 || s.matches(".*[:.,$%xX₹].*")) continue;
            s = cleanDigitChars(s);
            if (!s.matches("^[0-9]{1,2}$") || (s.length() == 2 && s.startsWith("0"))) continue;
            int v = Integer.parseInt(s);
            if (v < 0 || v > 36) continue;

            // Strictly validate the very first number (Slot #1) against its Red/Black/Green badge color!
            if (nums.isEmpty() && firstCol != COL_NONE) {
                if (firstCol == COL_GRN && v != 0) continue;
                if (firstCol == COL_RED && (v == 0 || !isRed(v))) continue;
                if (firstCol == COL_BLK && v != 0 && isRed(v)) continue;
            }
            nums.add(v);
            if (nums.size() >= 6) break;
        }
        return new BarResult(nums, firstCol);
    }
}
