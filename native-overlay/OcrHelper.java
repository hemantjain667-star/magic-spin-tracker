package com.hemantjain.magicspintracker;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import com.google.mlkit.vision.text.Text;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class OcrHelper {
    public static final int COL_NONE = 0, COL_RED = 1, COL_BLK = 2, COL_GRN = 3;

    public static class SpinTracker {
        private final int confirmCount;
        private Integer candNum = null;
        private int candCol = COL_NONE;
        private int candHits = 0;
        private Integer lastNum = null;
        private int lastCol = COL_NONE;
        private int lastSig = 0;

        public SpinTracker(int confirmCount) {
            this.confirmCount = Math.max(1, confirmCount);
        }

        public void reset() {
            candNum = null;
            candCol = COL_NONE;
            candHits = 0;
        }

        public Integer onReading(Integer num, int col, int stripSig) {
            if (num == null || num < 0 || num > 36 || col == COL_NONE) {
                reset();
                return null;
            }
            if (candNum != null && candNum == num && candCol == col) {
                candHits++;
            } else {
                candNum = num;
                candCol = col;
                candHits = 1;
            }
            if (candHits < confirmCount) return null;

            boolean shifted = (lastSig != 0 && stripSig != 0 && stripSig != lastSig);
            boolean diff = (lastNum == null || lastNum != num || lastCol != col);
            if (diff || shifted) {
                lastNum = num;
                lastCol = col;
                lastSig = stripSig;
                reset();
                return num;
            }
            return null;
        }
    }

    public static boolean isRed(int n) {
        for (int r : new int[]{1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36}) {
            if (r == n) return true;
        }
        return false;
    }

    public static String cTag(int c) {
        return c == COL_RED ? "RED" : (c == COL_BLK ? "BLK" : (c == COL_GRN ? "GRN" : "---"));
    }

    public static int computeStripSignature(Bitmap full) {
        if (full == null || full.getWidth() < 20 || full.getHeight() < 20) return 0;
        int fw = full.getWidth(), fh = full.getHeight();
        int y0 = Math.max(0, (fh * 11) / 100);
        int y1 = Math.min(fh - 1, (fh * 20) / 100);
        int x0 = Math.max(0, (fw * 2) / 100);
        int x1 = Math.min(fw - 1, (fw * 65) / 100);
        if (y1 <= y0 || x1 <= x0) return 0;

        long hash = 1469598103934665603L;
        int stepY = Math.max(1, (y1 - y0) / 6);
        int stepX = Math.max(1, (x1 - x0) / 36);
        for (int y = y0; y <= y1; y += stepY) {
            for (int x = x0; x <= x1; x += stepX) {
                int c = full.getPixel(x, y);
                int q = ((Color.red(c) >> 4) << 8) | ((Color.green(c) >> 4) << 4) | (Color.blue(c) >> 4);
                hash ^= q;
                hash *= 1099511628211L;
            }
        }
        return (int) (hash ^ (hash >>> 32));
    }

    public static Rect detectLatestNumberBox(Bitmap full) {
        if (full == null) return null;
        int fw = full.getWidth(), fh = full.getHeight();
        if (fw < 60 || fh < 80) return null;

        int bandTop = Math.max(0, (fh * 11) / 100);
        int bandBot = Math.min(fh, (fh * 20) / 100);
        int bandLeft = Math.max(0, (fw * 1) / 100);
        int bandRight = Math.min(fw, (fw * 35) / 100);
        int bw = bandRight - bandLeft, bh = bandBot - bandTop;
        if (bw < 20 || bh < 12) return null;

        boolean[] mask = new boolean[bw * bh];
        float[] hsv = new float[3];
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int px = full.getPixel(bandLeft + x, bandTop + y);
                int cr = Color.red(px), cg = Color.green(px), cb = Color.blue(px);
                int maxC = Math.max(cr, Math.max(cg, cb));
                int minC = Math.min(cr, Math.min(cg, cb));
                if (minC > 190 || maxC < 45) continue;

                Color.RGBToHSV(cr, cg, cb, hsv);
                boolean red = (hsv[0] <= 24f || hsv[0] >= 332f) && hsv[1] >= 0.28f && hsv[2] >= 0.22f;
                boolean green = (hsv[0] >= 70f && hsv[0] <= 185f && hsv[1] >= 0.20f && hsv[2] >= 0.16f)
                        || (cg > cr + 18 && cg > cb + 8 && cg > 55);
                boolean dark = (hsv[2] <= 0.36f && hsv[1] <= 0.45f && maxC >= 48);
                if (red || green || dark) mask[y * bw + x] = true;
            }
        }

        boolean[] vis = new boolean[bw * bh];
        List<Rect> boxes = new ArrayList<>();
        int[] dx = {-1, 1, 0, 0}, dy = {0, 0, -1, 1};
        int[] qx = new int[bw * bh], qy = new int[bw * bh];

        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int idx = y * bw + x;
                if (!mask[idx] || vis[idx]) continue;
                int head = 0, tail = 0;
                vis[idx] = true;
                qx[tail] = x; qy[tail] = y; tail++;
                int minX = x, maxX = x, minY = y, maxY = y;
                while (head < tail) {
                    int cx = qx[head], cy = qy[head]; head++;
                    if (cx < minX) minX = cx; if (cx > maxX) maxX = cx;
                    if (cy < minY) minY = cy; if (cy > maxY) maxY = cy;
                    for (int d = 0; d < 4; d++) {
                        int nx = cx + dx[d], ny = cy + dy[d];
                        if (nx >= 0 && nx < bw && ny >= 0 && ny < bh) {
                            int nIdx = ny * bw + nx;
                            if (mask[nIdx] && !vis[nIdx]) {
                                vis[nIdx] = true;
                                qx[tail] = nx; qy[tail] = ny; tail++;
                            }
                        }
                    }
                }
                int cw = maxX - minX + 1, ch = maxY - minY + 1;
                if (cw >= 16 && cw <= fw / 6 && ch >= 14 && ch <= bh) {
                    boxes.add(new Rect(
                            Math.max(0, bandLeft + minX - 2),
                            Math.max(0, bandTop + minY - 2),
                            Math.min(fw, bandLeft + maxX + 3),
                            Math.min(fh, bandTop + maxY + 3)
                    ));
                }
            }
        }
        if (boxes.isEmpty()) return null;
        Collections.sort(boxes, Comparator.comparingInt(r -> r.left));
        return boxes.get(0);
    }

    public static int badgeCol(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), r = 0, g = 0, k = 0, t = 0;
        int st = Math.max(1, Math.min(w, h) / 24);
        float[] hsv = new float[3];
        int x0 = (w * 18) / 100, x1 = w - x0, y0 = (h * 22) / 100, y1 = h - y0;
        if (x1 <= x0) { x0 = 0; x1 = w; }
        if (y1 <= y0) { y0 = 0; y1 = h; }
        for (int y = y0; y < y1; y += st) {
            for (int x = x0; x < x1; x += st) {
                int c = b.getPixel(x, y);
                int cr = Color.red(c), cg = Color.green(c), cb = Color.blue(c);
                if (Math.min(cr, Math.min(cg, cb)) > 165) continue;
                Color.RGBToHSV(cr, cg, cb, hsv);
                t++;
                boolean isGreenPx = (hsv[0] >= 70f && hsv[0] <= 185f && hsv[1] >= 0.18f && hsv[2] >= 0.12f)
                        || (cg > cr + 16 && cg > cb + 6 && cg > 45);
                if (isGreenPx) g++;
                else if ((hsv[0] <= 22f || hsv[0] >= 335f) && hsv[1] >= 0.30f && hsv[2] >= 0.18f) r++;
                else if ((hsv[2] <= 0.38f && hsv[1] <= 0.50f) || (hsv[2] <= 0.65f && hsv[1] <= 0.24f)) k++;
            }
        }
        if (t < 8) return COL_NONE;
        int rp = r * 100 / t, gp = g * 100 / t, kp = k * 100 / t;
        if (gp >= 10 && gp > rp) return COL_GRN;
        if (rp >= 24 && rp >= gp) return COL_RED;
        if (kp >= 28) return COL_BLK;
        return COL_NONE;
    }

    public static int[] autoFitBadge(Bitmap full, int cx, int cy, int cw, int ch) {
        int fw = full.getWidth(), fh = full.getHeight();
        int midX = Math.max(4, Math.min(fw - 5, cx + cw / 2));
        int midY = Math.max(4, Math.min(fh - 5, cy + ch / 2));
        int maxDy = Math.max(ch, 50), maxDx = Math.max(cw, 60);
        int spanX = Math.max(8, cw / 3);

        int topY = -1, botY = -1, leftX = -1, rightX = -1;
        for (int y = midY - Math.max(4, ch / 6); y >= Math.max(0, midY - maxDy); y--) {
            int wCnt = 0, tot = 0;
            for (int x = Math.max(0, midX - spanX); x <= Math.min(fw - 1, midX + spanX); x += 2) {
                int c = full.getPixel(x, y); tot++;
                if (Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c))) >= 150) wCnt++;
            }
            if (tot > 0 && wCnt * 100 / tot >= 45) { topY = y; break; }
        }
        for (int y = midY + Math.max(4, ch / 6); y <= Math.min(fh - 1, midY + maxDy); y++) {
            int wCnt = 0, tot = 0;
            for (int x = Math.max(0, midX - spanX); x <= Math.min(fw - 1, midX + spanX); x += 2) {
                int c = full.getPixel(x, y); tot++;
                if (Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c))) >= 150) wCnt++;
            }
            if (tot > 0 && wCnt * 100 / tot >= 45) { botY = y; break; }
        }
        if (topY < 0 || botY < 0 || (botY - topY) < 20) return null;

        for (int x = midX - Math.max(4, cw / 6); x >= Math.max(0, midX - maxDx); x--) {
            int wCnt = 0, tot = 0;
            for (int y = topY + 2; y <= botY - 2; y += 2) {
                int c = full.getPixel(x, y); tot++;
                if (Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c))) >= 150) wCnt++;
            }
            if (tot > 0 && wCnt * 100 / tot >= 45) { leftX = x; break; }
        }
        for (int x = midX + Math.max(4, cw / 6); x <= Math.min(fw - 1, midX + maxDx); x++) {
            int wCnt = 0, tot = 0;
            for (int y = topY + 2; y <= botY - 2; y += 2) {
                int c = full.getPixel(x, y); tot++;
                if (Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c))) >= 150) wCnt++;
            }
            if (tot > 0 && wCnt * 100 / tot >= 45) { rightX = x; break; }
        }
        if (leftX < 0) leftX = Math.max(0, cx);
        if (rightX < 0) return null;

        int nw = rightX - leftX + 1, nh = botY - topY + 1;
        if (nw < 30 || nw > fw / 3 || nh < 22 || nh > fh / 6) return null;
        return new int[]{Math.max(0, leftX - 2), Math.max(0, topY - 2), nw + 4, nh + 4};
    }

    public static Integer parseNum(String raw, int col) {
        if (raw == null || col == COL_NONE) return null;
        String s = raw.replace("\n", " ").trim();
        if (s.isEmpty() || s.length() > 3 || s.matches(".*[:.,$%xX₹].*")) return null;
        s = s.replace(" ", "");
        if (col == COL_GRN) return 0;
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

    private static String cleanDigitChars(String s) {
        return s.replace('O', '0').replace('o', '0').replace('D', '0').replace('Q', '0')
                .replace('I', '1').replace('l', '1').replace('|', '1').replace('!', '1').replace('i', '1').replace(']', '1').replace('[', '1')
                .replace('Z', '2').replace('z', '2')
                .replace('S', '5').replace('s', '5')
                .replace('G', '6').replace('b', '6')
                .replace('B', '8');
    }

    public static Bitmap cleanWhiteDigits(Bitmap s, boolean[] hints) {
        int sw = Math.max(1, s.getWidth()), sh = Math.max(1, s.getHeight());
        float sc = Math.max(2.5f, Math.min(5.0f, 160f / Math.max(1, sh)));
        int tw = Math.min(360, Math.round(sw * sc)), th = Math.min(260, Math.round(sh * sc));
        Bitmap up = Bitmap.createScaledBitmap(s, tw, th, true);

        int[] px = new int[tw * th];
        up.getPixels(px, 0, tw, 0, 0, tw, th);
        up.recycle();

        boolean[] ink = new boolean[tw * th];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            ink[i] = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c))) >= 118;
        }

        boolean[] eraseRow = new boolean[th];
        int topEdge = (th * 18) / 100, botEdge = (th * 82) / 100;
        for (int y = 0; y < th; y++) {
            if (y > topEdge && y < botEdge) continue;
            int cnt = 0;
            for (int x = 0; x < tw; x++) if (ink[y * tw + x]) cnt++;
            if (cnt * 100 / tw >= 78) {
                for (int dy = -2; dy <= 2; dy++) { int ny = y + dy; if (ny >= 0 && ny < th) eraseRow[ny] = true; }
            }
        }
        boolean[] eraseCol = new boolean[tw];
        int edgeLim = (tw * 18) / 100;
        for (int x = 0; x < tw; x++) {
            if (x > edgeLim && x < tw - edgeLim) continue;
            int cnt = 0;
            for (int y = 0; y < th; y++) if (ink[y * tw + x]) cnt++;
            if (cnt * 100 / th >= 78) {
                for (int dx = -2; dx <= 2; dx++) { int nx = x + dx; if (nx >= 0 && nx < tw) eraseCol[nx] = true; }
            }
        }

        int rx = Math.max(5, (tw * 8) / 100), ry = Math.max(5, (th * 10) / 100);
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

        if (hints != null && hints.length >= 2 && darkCnt >= 70) {
            hints[1] = true;
            int dw = maxX - minX + 1, dh = maxY - minY + 1;
            if (dh >= th / 4 && dw >= 4 && dw <= Math.round(dh * 0.52f)) hints[0] = true;
        }

        Bitmap bw = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        bw.setPixels(px, 0, tw, 0, 0, tw, th);
        int pad = 44;
        Bitmap out = Bitmap.createBitmap(tw + pad * 2, th + pad * 2, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out); cv.drawColor(Color.WHITE); cv.drawBitmap(bw, pad, pad, null); bw.recycle();
        return out;
    }

    public static Bitmap padCol(Bitmap s) {
        int sw = Math.max(1, s.getWidth()), sh = Math.max(1, s.getHeight());
        int mx = Math.max(2, (sw * 12) / 100), my = Math.max(2, (sh * 14) / 100);
        int iw = Math.max(4, sw - mx * 2), ih = Math.max(4, sh - my * 2);
        Bitmap inner = Bitmap.createBitmap(s, Math.min(mx, sw - 4), Math.min(my, sh - 4), iw, ih);
        float sc = Math.max(2.5f, Math.min(4.5f, 160f / Math.max(1, ih)));
        int tw = Math.min(360, Math.round(iw * sc)), th = Math.min(260, Math.round(ih * sc));
        Bitmap up = Bitmap.createScaledBitmap(inner, tw, th, true);
        int bg = inner.getPixel(iw / 4, ih / 4); inner.recycle();
        Bitmap out = Bitmap.createBitmap(tw + 64, th + 64, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out); cv.drawColor(bg); cv.drawBitmap(up, 32, 32, null); up.recycle();
        return out;
    }

    public static Bitmap cleanHistoryStrip(Bitmap strip) {
        int sw = Math.max(1, strip.getWidth()), sh = Math.max(1, strip.getHeight());
        float sc = Math.max(2.5f, Math.min(4.0f, 120f / Math.max(1, sh)));
        int tw = Math.min(760, Math.round(sw * sc)), th = Math.min(180, Math.round(sh * sc));
        Bitmap up = Bitmap.createScaledBitmap(strip, tw, th, true);
        int[] px = new int[tw * th];
        up.getPixels(px, 0, tw, 0, 0, tw, th);
        up.recycle();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int maxCh = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
            px[i] = (maxCh >= 78) ? Color.BLACK : Color.WHITE;
        }
        Bitmap bw = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        bw.setPixels(px, 0, tw, 0, 0, tw, th);
        int pad = 28;
        Bitmap out = Bitmap.createBitmap(tw + pad * 2, th + pad * 2, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out); cv.drawColor(Color.WHITE); cv.drawBitmap(bw, pad, pad, null); bw.recycle();
        return out;
    }

    public static List<Integer> parseHistoryLeftToRight(Text visionText) {
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

        List<String> tokens = new ArrayList<>();
        List<Rect> boxes = new ArrayList<>();
        for (Text.Element el : elems) {
            String raw = el.getText();
            if (raw == null) continue;
            String s = cleanDigitChars(raw.replace(" ", "").trim());
            if (s.matches("^[0-9]{1,2}$")) {
                tokens.add(s);
                boxes.add(el.getBoundingBox());
            }
        }

        List<Integer> nums = new ArrayList<>();
        int i = 0;
        while (i < tokens.size() && nums.size() < 4) {
            String cur = tokens.get(i);
            if (cur.length() == 1 && i + 1 < tokens.size() && tokens.get(i + 1).length() == 1) {
                Rect r1 = boxes.get(i), r2 = boxes.get(i + 1);
                boolean close = true;
                if (r1 != null && r2 != null) {
                    int gap = r2.left - r1.right;
                    int avgH = Math.max(1, (r1.height() + r2.height()) / 2);
                    close = (gap >= -6 && gap <= avgH);
                }
                if (close) {
                    String twoStr = cur + tokens.get(i + 1);
                    if (!twoStr.startsWith("0")) {
                        int twoVal = Integer.parseInt(twoStr);
                        if (twoVal >= 10 && twoVal <= 36) {
                            nums.add(twoVal);
                            i += 2;
                            continue;
                        }
                    }
                }
            }
            if (!(cur.length() == 2 && cur.startsWith("0"))) {
                int v = Integer.parseInt(cur);
                if (v >= 0 && v <= 36) nums.add(v);
            }
            i++;
        }
        return nums;
    }
}
