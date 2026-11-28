package com.hemantjain.magicspintracker;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import com.google.mlkit.vision.text.Text;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OcrHelper {
    public static final int COL_NONE = 0, COL_RED = 1, COL_BLK = 2, COL_GRN = 3;

    public static boolean isRed(int n) {
        for (int r : new int[]{1,3,5,7,9,12,14,16,18,19,21,23,25,27,30,32,34,36}) if (r == n) return true;
        return false;
    }

    public static String cTag(int c) {
        return c == COL_RED ? "RED" : (c == COL_BLK ? "BLK" : (c == COL_GRN ? "GRN" : "---"));
    }

    // Low-brightness & Extra-Dim compatible Red / Black / Green badge detector
    public static int badgeCol(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), r = 0, g = 0, k = 0, t = 0;
        int st = Math.max(1, Math.min(w, h) / 20);
        float[] hsv = new float[3];
        int x0 = (w * 16) / 100, x1 = w - x0, y0 = (h * 20) / 100, y1 = h - y0;
        if (x1 <= x0) { x0 = 0; x1 = w; }
        if (y1 <= y0) { y0 = 0; y1 = h; }

        int maxBright = 0;
        for (int y = y0; y < y1; y += st) {
            for (int x = x0; x < x1; x += st) {
                int c = b.getPixel(x, y);
                int mn = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
                if (mn > maxBright) maxBright = mn;
            }
        }
        int digitSkip = Math.max(90, (maxBright * 78) / 100);

        for (int y = y0; y < y1; y += st) {
            for (int x = x0; x < x1; x += st) {
                int c = b.getPixel(x, y);
                int cr = Color.red(c), cg = Color.green(c), cb = Color.blue(c);
                if (Math.min(cr, Math.min(cg, cb)) > digitSkip) continue;
                Color.RGBToHSV(cr, cg, cb, hsv);
                t++;
                boolean isGrn = (hsv[0] >= 75f && hsv[0] <= 178f && hsv[1] >= 0.22f && hsv[2] >= 0.08f)
                        || (cg > cr + 14 && cg > cb + 8 && cg > 35);
                if (isGrn) g++;
                else if ((hsv[0] <= 24f || hsv[0] >= 332f) && hsv[1] >= 0.28f && hsv[2] >= 0.09f) r++;
                else if ((hsv[2] <= 0.40f && hsv[1] <= 0.50f) || (hsv[2] <= 0.68f && hsv[1] <= 0.24f)) k++;
            }
        }
        if (t < 6) return COL_NONE;
        int rp = r * 100 / t, gp = g * 100 / t, kp = k * 100 / t;
        if (gp >= 30 && gp > rp) return COL_GRN;
        if (rp >= 22 && rp >= gp) return COL_RED;
        if (kp >= 26) return COL_BLK;
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

    // LEFT BOX: High-Zoom (220px) + Full Adaptive Low-Brightness Contrast Extractor!
    public static Bitmap cleanLeftSlot(Bitmap s, boolean[] hints) {
        int sw = Math.max(1, s.getWidth()), sh = Math.max(1, s.getHeight());
        float sc = Math.max(3.0f, Math.min(5.5f, 220f / Math.max(1, sh)));
        int tw = Math.min(380, Math.round(sw * sc)), th = Math.min(260, Math.round(sh * sc));
        Bitmap up = Bitmap.createScaledBitmap(s, tw, th, true);

        int[] px = new int[tw * th];
        up.getPixels(px, 0, tw, 0, 0, tw, th);
        up.recycle();

        // Measure BOTH background floor (bgMin) AND brightest digit peak (fgMax) inside inner core!
        int rx = Math.max(6, (tw * 12) / 100), ry = Math.max(6, (th * 14) / 100);
        int fgMax = 0, bgLowSum = 0, bgLowCnt = 0;
        for (int y = ry; y < th - ry; y += 2) {
            for (int x = rx; x < tw - rx; x += 2) {
                int c = px[y * tw + x];
                int mn = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
                if (mn > fgMax) fgMax = mn;
            }
        }
        int bgCut = Math.max(40, (fgMax * 72) / 100);
        for (int y = ry; y < th - ry; y += 2) {
            for (int x = rx; x < tw - rx; x += 2) {
                int c = px[y * tw + x];
                int mn = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
                if (mn < bgCut) { bgLowSum += mn; bgLowCnt++; }
            }
        }
        int bgMin = bgLowCnt > 0 ? (bgLowSum / bgLowCnt) : 25;
        int contrast = fgMax - bgMin;
        // Works at 100% brightness AND at 25% Extra-Dim / Night Mode!
        int thresh = (contrast >= 32) ? (bgMin + (contrast * 52) / 100) : Math.max(85, fgMax - 18);

        boolean[] ink = new boolean[tw * th];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            ink[i] = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c))) >= thresh;
        }

        boolean[] eraseRow = new boolean[th];
        for (int y = 0; y < th; y++) {
            int cnt = 0;
            for (int x = 0; x < tw; x++) if (ink[y * tw + x]) cnt++;
            if (cnt * 100 / tw >= 52) {
                for (int dy = -4; dy <= 4; dy++) { int ny = y + dy; if (ny >= 0 && ny < th) eraseRow[ny] = true; }
            }
        }
        boolean[] eraseCol = new boolean[tw];
        int edgeLim = (tw * 24) / 100;
        for (int x = 0; x < tw; x++) {
            if (x > edgeLim && x < tw - edgeLim) continue;
            int cnt = 0;
            for (int y = 0; y < th; y++) if (ink[y * tw + x]) cnt++;
            if (cnt * 100 / th >= 48) {
                for (int dx = -4; dx <= 4; dx++) { int nx = x + dx; if (nx >= 0 && nx < tw) eraseCol[nx] = true; }
            }
        }

        int minX = tw, maxX = -1, minY = th, maxY = -1, darkCnt = 0;
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                boolean rim = (x < rx || x >= tw - rx || y < ry || y >= th - ry);
                if (ink[y * tw + x] && !rim && !eraseRow[y] && !eraseCol[x]) {
                    px[y * tw + x] = Color.BLACK; darkCnt++;
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                } else {
                    px[y * tw + x] = Color.WHITE;
                }
            }
        }

        if (darkCnt >= 110) {
            hints[1] = true;
            int dw = maxX - minX + 1, dh = maxY - minY + 1;
            if (dh >= th / 4 && dw >= 6 && dw <= Math.round(dh * 0.50f)) hints[0] = true;
        }

        Bitmap bw = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        bw.setPixels(px, 0, tw, 0, 0, tw, th);
        int pad = 46;
        Bitmap out = Bitmap.createBitmap(tw + pad * 2, th + pad * 2, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out); cv.drawColor(Color.WHITE); cv.drawBitmap(bw, pad, pad, null); bw.recycle();
        return out;
    }

    // Pass-2 High-Zoom Color/Brightness-Boosted Left Slot (fallback if Pass 1 misses!)
    public static Bitmap zoomBoostLeftSlot(Bitmap s) {
        int sw = Math.max(1, s.getWidth()), sh = Math.max(1, s.getHeight());
        int mx = Math.max(2, (sw * 12) / 100), my = Math.max(2, (sh * 14) / 100);
        int iw = Math.max(4, sw - mx * 2), ih = Math.max(4, sh - my * 2);
        Bitmap inner = Bitmap.createBitmap(s, Math.min(mx, sw - 4), Math.min(my, sh - 4), iw, ih);
        float sc = Math.max(3.0f, Math.min(5.0f, 200f / Math.max(1, ih)));
        int tw = Math.min(360, Math.round(iw * sc)), th = Math.min(240, Math.round(ih * sc));
        Bitmap up = Bitmap.createScaledBitmap(inner, tw, th, true);
        int bg = inner.getPixel(iw / 4, ih / 4); inner.recycle();

        // Auto-brighten dim crops so ML Kit reads clearly even at low screen brightness!
        int[] px = new int[tw * th];
        up.getPixels(px, 0, tw, 0, 0, tw, th);
        int maxV = 1;
        for (int i = 0; i < px.length; i += 4) {
            int c = px[i];
            int m = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
            if (m > maxV) maxV = m;
        }
        if (maxV < 190) {
            float gain = 235f / maxV;
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                int nr = Math.min(255, Math.round(Color.red(c) * gain));
                int ng = Math.min(255, Math.round(Color.green(c) * gain));
                int nb = Math.min(255, Math.round(Color.blue(c) * gain));
                px[i] = Color.rgb(nr, ng, nb);
            }
            up.setPixels(px, 0, tw, 0, 0, tw, th);
            bg = px[0];
        }
        Bitmap out = Bitmap.createBitmap(tw + 64, th + 64, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out); cv.drawColor(bg); cv.drawBitmap(up, 32, 32, null); up.recycle();
        return out;
    }

    public static Integer parseLeftSlot(String raw, int col) {
        if (raw == null || col == COL_NONE) return null;
        String s = raw.replace("\n", " ").trim();
        if (s.isEmpty() || s.length() > 3 || s.matches(".*[:.,$%xX₹].*")) return null;
        s = s.replace(" ", "");
        if (col == COL_GRN) {
            return (s.equals("0") || s.equalsIgnoreCase("O") || s.equalsIgnoreCase("D") || s.equalsIgnoreCase("Q") || s.equals("()")) ? 0 : null;
        }
        if (col == COL_BLK && (s.equals("0") || s.equalsIgnoreCase("O"))) return 0;
        s = cleanDigitChars(s);
        if (!s.matches("^[0-9]{1,2}$") || (s.length() == 2 && s.startsWith("0"))) return null;
        int v = Integer.parseInt(s);
        if (v == 0) return (col == COL_RED) ? null : 0;
        if (v < 1 || v > 36) return null;
        if (col == COL_RED && !isRed(v)) return null;
        if (col == COL_BLK && isRed(v)) return null;
        return v;
    }

    // RIGHT VERIFY ZONE: Adaptive Low-Brightness Contrast (works even when screen is very dim!)
    public static Bitmap cleanRightHistory(Bitmap strip) {
        int sw = Math.max(1, strip.getWidth()), sh = Math.max(1, strip.getHeight());
        float sc = Math.max(2.2f, Math.min(3.8f, 120f / Math.max(1, sh)));
        int tw = Math.min(760, Math.round(sw * sc)), th = Math.min(180, Math.round(sh * sc));
        Bitmap up = Bitmap.createScaledBitmap(strip, tw, th, true);
        int[] px = new int[tw * th];
        up.getPixels(px, 0, tw, 0, 0, tw, th);
        up.recycle();

        int topCut = Math.max(2, (th * 8) / 100);
        int peak = 0;
        for (int y = topCut; y < th - topCut; y += 2) {
            for (int x = 0; x < tw; x += 2) {
                int c = px[y * tw + x];
                int m = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
                if (m > peak) peak = m;
            }
        }
        int rThresh = Math.max(36, Math.min(92, (peak * 36) / 100));

        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int c = px[y * tw + x];
                int maxCh = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
                px[y * tw + x] = (y >= topCut && y < th - topCut && maxCh >= rThresh) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bw = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        bw.setPixels(px, 0, tw, 0, 0, tw, th);
        int pad = 28;
        Bitmap out = Bitmap.createBitmap(tw + pad * 2, th + pad * 2, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out); cv.drawColor(Color.WHITE); cv.drawBitmap(bw, pad, pad, null); bw.recycle();
        return out;
    }

    public static List<Integer> parseRightHistory(Text visionText) {
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
        List<Integer> nums = new ArrayList<>();
        for (Text.Element el : elems) {
            String raw = el.getText();
            if (raw == null) continue;
            String s = cleanDigitChars(raw.trim());
            if (s.matches("^[0-9]{1,2}$") && !(s.length() == 2 && s.startsWith("0"))) {
                int v = Integer.parseInt(s);
                if (v >= 0 && v <= 36) nums.add(v);
            }
            if (nums.size() >= 6) break;
        }
        return nums;
    }
}
