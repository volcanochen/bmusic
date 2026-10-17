package com.volcano.bmusic.util;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, dependency-free QR-code decoder.
 *
 * Pipeline: grayscale -> binarize -> locate 3 finder patterns -> rectify the
 * module grid via affine mapping -> read format info (BCH) -> read data bits
 * in zigzag with the correct mask -> deinterleave blocks -> Reed-Solomon
 * error-correct -> parse the bit stream (numeric / alphanumeric / byte modes).
 *
 * Pure Java (no android.* imports) on purpose: it is unit-tested on the JVM
 * against generated QR images before being wired into the camera scanner.
 *
 * Supports versions 1-10 and EC levels L/M/Q/H. Enough for the short bilibili
 * links this app scans.
 */
public final class QrDecoder {

    private QrDecoder() {
    }

    private static final boolean DEBUG = false;
    private static void dbg(String s) { if (DEBUG) System.err.println(s); }

    // ----------------------------------------------------------------- EC table
    // fields: {totalData, ecPerBlock, g1Blocks, g1Data, g2Blocks, g2Data}
    // indexed [version][ecIndex], ecIndex 0=M 1=L 2=H 3=Q (matches format bits)
    private static final int[][][] EC = new int[11][4][];
    static {
        EC[1][0] = new int[]{16, 10, 1, 16, 0, 0};
        EC[1][1] = new int[]{19, 7, 1, 19, 0, 0};
        EC[1][2] = new int[]{9, 17, 1, 9, 0, 0};
        EC[1][3] = new int[]{13, 13, 1, 13, 0, 0};
        EC[2][0] = new int[]{28, 16, 1, 28, 0, 0};
        EC[2][1] = new int[]{34, 10, 1, 34, 0, 0};
        EC[2][2] = new int[]{16, 28, 1, 16, 0, 0};
        EC[2][3] = new int[]{22, 22, 1, 22, 0, 0};
        EC[3][0] = new int[]{44, 26, 1, 44, 0, 0};
        EC[3][1] = new int[]{55, 15, 1, 55, 0, 0};
        EC[3][2] = new int[]{26, 22, 2, 13, 0, 0};
        EC[3][3] = new int[]{34, 18, 2, 17, 0, 0};
        EC[4][0] = new int[]{64, 18, 2, 32, 0, 0};
        EC[4][1] = new int[]{80, 20, 1, 80, 0, 0};
        EC[4][2] = new int[]{36, 16, 4, 9, 0, 0};
        EC[4][3] = new int[]{48, 26, 2, 24, 0, 0};
        EC[5][0] = new int[]{86, 24, 2, 43, 0, 0};
        EC[5][1] = new int[]{108, 26, 1, 108, 0, 0};
        EC[5][2] = new int[]{46, 22, 2, 11, 2, 12};
        EC[5][3] = new int[]{62, 18, 2, 15, 2, 16};
        EC[6][0] = new int[]{108, 16, 4, 27, 0, 0};
        EC[6][1] = new int[]{136, 18, 2, 68, 0, 0};
        EC[6][2] = new int[]{60, 28, 4, 15, 0, 0};
        EC[6][3] = new int[]{76, 24, 4, 19, 0, 0};
        EC[7][0] = new int[]{124, 18, 4, 31, 0, 0};
        EC[7][1] = new int[]{156, 20, 2, 78, 0, 0};
        EC[7][2] = new int[]{66, 26, 4, 13, 1, 14};
        EC[7][3] = new int[]{88, 18, 2, 14, 4, 15};
        EC[8][0] = new int[]{154, 22, 2, 38, 2, 39};
        EC[8][1] = new int[]{194, 24, 2, 97, 0, 0};
        EC[8][2] = new int[]{86, 26, 4, 14, 2, 15};
        EC[8][3] = new int[]{110, 22, 4, 18, 2, 19};
        EC[9][0] = new int[]{182, 22, 3, 36, 2, 37};
        EC[9][1] = new int[]{232, 30, 2, 116, 0, 0};
        EC[9][2] = new int[]{100, 24, 4, 12, 4, 13};
        EC[9][3] = new int[]{132, 20, 4, 16, 4, 17};
        EC[10][0] = new int[]{216, 26, 4, 43, 1, 44};
        EC[10][1] = new int[]{274, 18, 2, 68, 2, 69};
        EC[10][2] = new int[]{122, 28, 6, 15, 2, 16};
        EC[10][3] = new int[]{154, 24, 6, 19, 2, 20};
    }

    private static final int[][] ALIGN = new int[11][];
    static {
        ALIGN[2] = new int[]{6, 18};
        ALIGN[3] = new int[]{6, 22};
        ALIGN[4] = new int[]{6, 26};
        ALIGN[5] = new int[]{6, 30};
        ALIGN[6] = new int[]{6, 34};
        ALIGN[7] = new int[]{6, 22, 38};
        ALIGN[8] = new int[]{6, 24, 42};
        ALIGN[9] = new int[]{6, 26, 46};
        ALIGN[10] = new int[]{6, 28, 50};
    }

    // --------------------------------------------------------------- GF(256)
    private static final int[] EXP = new int[256];
    private static final int[] LOG = new int[256];
    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        EXP[255] = EXP[0];
    }
    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[(LOG[a] + LOG[b]) % 255];
    }
    private static int gfPow(int a, int n) {
        if (n == 0) return 1;
        int r = 1;
        int base = a;
        int e = n;
        while (e > 0) {
            if ((e & 1) != 0) r = gfMul(r, base);
            base = gfMul(base, base);
            e >>= 1;
        }
        return r;
    }
    private static int gfInv(int a) {
        return EXP[255 - LOG[a]];
    }
    private static int gfPolyEval(int[] p, int x) {
        int y = p[0];
        for (int i = 1; i < p.length; i++) y = gfMul(y, x) ^ p[i];
        return y;
    }

    private static final class Pt {
        int x, y, size;
        Pt(int x, int y, int s) { this.x = x; this.y = y; this.size = s; }
    }

    /** Decode a grayscale image (0=black .. 255=white) to text, or null. */
    public static String decode(int[] gray, int w, int h) {
        String r = decodeOnce(gray, w, h, false);
        if (r == null && gray != null && w > 0 && h > 0) {
            // 拍照/截图光照不均时全局阈值会失真，换自适应局部二值化再试一次
            r = decodeOnce(gray, w, h, true);
        }
        return r;
    }

    private static String decodeOnce(int[] gray, int w, int h, boolean adaptive) {
        try {
            if (gray == null || w <= 0 || h <= 0) return null;
            boolean[][] bin = adaptive
                    ? binarizeAdaptive(gray, w, h) : binarize(gray, w, h);
            if (DEBUG && w > 200) {
                int x0 = 30, y0 = 30, sz = 120;
                StringBuilder sb = new StringBuilder("DBG bin crop (TL region):\n");
                for (int y = y0; y < y0 + sz; y += 3) {
                    for (int x = x0; x < x0 + sz; x += 3)
                        sb.append(bin[y][x] ? "#" : ".");
                    sb.append("\n");
                }
                dbg(sb.toString());
            }
            Pt[] f = findFinders(bin, w, h);
            if (f == null) { dbg("DBG no finders"); return null; }
            int n = (int) Math.round((f[1].x - f[0].x) / f[0].size) + 7;
            dbg("DBG finders TL=(" + f[0].x + "," + f[0].y + ") TR=(" + f[1].x + "," + f[1].y + ") BL=(" + f[2].x + "," + f[2].y + ") size=" + f[0].size + " n=" + n);
            if (n < 21 || n > 57) return null;
            int version = (n - 17) / 4;
            if (version < 1 || version > 10) return null;

            boolean[][] mat = sample(bin, w, h, f, n);
            dbg("DBG corners dTL=" + mat[3][3] + " dTR=" + mat[3][n - 4] + " dBL=" + mat[n - 4][3]);
            // finder centers should be dark; if not, image is inverted
            if (!mat[3][3] || !mat[3][n - 4] || !mat[n - 4][3]) {
                mat = invert(mat);
                dbg("DBG inverted");
            }

            int[] fmt = readFormat(mat, n);
            if (fmt == null) { dbg("DBG fmt null"); return null; }
            int ecIndex = fmt[0];
            int mask = fmt[1];
            dbg("DBG fmt ec=" + ecIndex + " mask=" + mask);
            int[] t = EC[version][ecIndex];

            boolean[][] func = buildFunction(n, version);
            int ft = 0; for (boolean[] row : func) for (boolean b : row) if (b) ft++;
            dbg("DBG funcTrues=" + ft + " (n=" + n + ")");
            if (DEBUG) dbg(ascii(mat, "M") + "\n" + ascii(func, "F"));
            int[] bits = readData(mat, func, n, mask);
            if (bits == null) return null;

            int[] code = packCodewords(bits);
            dbg("DBG codewords=" + code.length + " first=" + (code.length > 0 ? code[0] : -1));
            int[] data = deinterleaveAndDecode(code, t);
            if (data == null) {
                dbg("DBG rs null; brute-forcing 32 formats");
                for (int idx = 0; idx < 32; idx++) {
                    int ecI = idx >> 3;
                    int msk = idx & 7;
                    int[] tt = EC[version][ecI];
                    boolean[][] f2 = buildFunction(n, version);
                    int[] b2 = readData(mat, f2, n, msk);
                    int[] c2 = packCodewords(b2);
                    int[] d2 = deinterleaveAndDecode(c2, tt);
                    if (d2 != null) {
                        String s = parseData(d2, version);
                        if (s != null) dbg("DBG hit idx=" + idx + " (ec=" + ecI + " mask=" + msk + ") -> \"" + s + "\"");
                    }
                }
                return null;
            }
            if (DEBUG) {
                StringBuilder hx = new StringBuilder("DBG data hex:");
                for (int b : data) hx.append(' ').append(Integer.toHexString(b | 0x100).substring(1));
                dbg(hx.toString());
                dbg("DBG data bytes=" + data.length);
            }

            return parseData(data, version);
        } catch (Throwable t) {
            return null;
        }
    }

    // ----------------------------------------------------------- binarize
    private static boolean[][] binarize(int[] gray, int w, int h) {
        int min = 255, max = 0;
        for (int v : gray) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        int thr = (min + max) / 2;
        boolean[][] b = new boolean[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                b[y][x] = gray[y * w + x] < thr;
        return b;
    }

    /** 自适应局部二值化：积分图 + 窗口均值 - 15% 余量，抗光照不均。 */
    private static boolean[][] binarizeAdaptive(int[] gray, int w, int h) {
        long[] sum = new long[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            long rowSum = 0;
            for (int x = 0; x < w; x++) {
                rowSum += gray[y * w + x];
                sum[(y + 1) * (w + 1) + (x + 1)] = sum[y * (w + 1) + (x + 1)] + rowSum;
            }
        }
        int win = Math.max(15, Math.min(w, h) / 8) | 1;
        int half = win / 2;
        boolean[][] b = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - half), y1 = Math.min(h - 1, y + half);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - half), x1 = Math.min(w - 1, x + half);
                long area = (long) (x1 - x0 + 1) * (y1 - y0 + 1);
                long s = sum[(y1 + 1) * (w + 1) + (x1 + 1)]
                        - sum[y0 * (w + 1) + (x1 + 1)]
                        - sum[(y1 + 1) * (w + 1) + x0]
                        + sum[y0 * (w + 1) + x0];
                int mean = (int) (s / area);
                b[y][x] = gray[y * w + x] < mean - mean / 7;
            }
        }
        return b;
    }

    private static boolean[][] invert(boolean[][] m) {
        int n = m.length;
        boolean[][] o = new boolean[n][n];
        for (int r = 0; r < n; r++)
            for (int c = 0; c < n; c++)
                o[r][c] = !m[r][c];
        return o;
    }

    // ----------------------------------------------------- finder detect
    private static Pt[] findFinders(boolean[][] bin, int w, int h) {
        List<Pt> cands = new ArrayList<Pt>();
        for (int y = 0; y < h; y++) {
            List<int[]> runs = runsOf(bin, y, w);
            int xoff = 0;
            for (int i = 0; i + 4 < runs.size(); i++) {
                int[] r0 = runs.get(i), r1 = runs.get(i + 1), r2 = runs.get(i + 2),
                        r3 = runs.get(i + 3), r4 = runs.get(i + 4);
                if (r0[0] != 1 || r1[0] != 0 || r2[0] != 1 || r3[0] != 0 || r4[0] != 1) {
                    xoff += r0[1];
                    continue;
                }
                int a = r0[1], b = r1[1], c = r2[1], d = r3[1], e = r4[1];
                int m = (a + b + d + e) / 4;
                if (m == 0) { xoff += r0[1]; continue; }
                boolean ok = Math.abs(a - m) <= m * 0.45 && Math.abs(b - m) <= m * 0.45
                        && Math.abs(d - m) <= m * 0.45 && Math.abs(e - m) <= m * 0.45
                        && c >= m * 2 && c <= m * 4;
                if (ok) {
                    int total = a + b + c + d + e;
                    int cx = xoff + a + b + c / 2;
                    cands.add(new Pt(cx, y, total / 7));
                }
                xoff += r0[1];
            }
        }
        if (cands.size() < 3) return null;

        if (DEBUG) {
            System.err.print("DBG cands(" + cands.size() + "): ");
            for (Pt c : cands) System.err.print("(" + c.x + "," + c.y + " s" + c.size + ") ");
            System.err.println();
        }

        List<Pt> clusters = new ArrayList<Pt>();
        List<int[]> sums = new ArrayList<int[]>(); // {sumX, sumY, count, sumSize}
        for (Pt c : cands) {
            boolean merged = false;
            for (int ci = 0; ci < clusters.size(); ci++) {
                Pt f = clusters.get(ci);
                int[] s = sums.get(ci);
                int sz = s[3] / s[2];
                if (Math.abs(f.x - c.x) < sz * 2 && Math.abs(f.y - c.y) < sz * 4) {
                    s[0] += c.x; s[1] += c.y; s[2]++; s[3] += c.size;
                    f.x = s[0] / s[2]; f.y = s[1] / s[2]; f.size = s[3] / s[2];
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                clusters.add(new Pt(c.x, c.y, c.size));
                sums.add(new int[]{c.x, c.y, 1, c.size});
            }
        }
        if (clusters.size() < 3) return null;

        if (DEBUG) {
            System.err.print("DBG clusters(" + clusters.size() + "): ");
            for (Pt c : clusters) System.err.print("(" + c.x + "," + c.y + " s" + c.size + ") ");
            System.err.println();
        }

        Pt bestA = null, bestB = null, bestC = null;
        double bestScore = -99;
        double diag = Math.hypot(w, h);
        for (int i = 0; i < clusters.size(); i++) {
            for (int j = 0; j < clusters.size(); j++) {
                if (j == i) continue;
                for (int k = 0; k < clusters.size(); k++) {
                    if (k == i || k == j) continue;
                    Pt A = clusters.get(i), B = clusters.get(j), C = clusters.get(k);
                    double abx = B.x - A.x, aby = B.y - A.y;
                    double acx = C.x - A.x, acy = C.y - A.y;
                    double dot = abx * acx + aby * acy;
                    double lenAB = Math.hypot(abx, aby);
                    double lenAC = Math.hypot(acx, acy);
                    if (lenAB < 1 || lenAC < 1) continue;
                    double cosang = Math.abs(dot / (lenAB * lenAC));
                    // 评分：直角( cos≈0 ) + 两条边等长( 真定位点必然如此 )
                    // + 优先大尺寸（排除数据区偶然形成的假直角）
                    double ratio = Math.min(lenAB, lenAC) / Math.max(lenAB, lenAC);
                    double score = ratio * 2 - cosang * 2
                            + Math.min(lenAB, lenAC) / diag;
                    if (score > bestScore) {
                        bestScore = score;
                        bestA = A; bestB = B; bestC = C;
                    }
                }
            }
        }
        if (bestA == null) return null;
        // bestA is the corner (TL). Among bestB/bestC, the one with the larger
        // x is TR, the other (larger y) is BL.
        Pt tr = (bestB.x > bestC.x) ? bestB : bestC;
        Pt bl = (bestB.x > bestC.x) ? bestC : bestB;
        return new Pt[]{bestA, tr, bl};
    }

    private static List<int[]> runsOf(boolean[][] bin, int y, int w) {
        List<int[]> runs = new ArrayList<int[]>();
        int c = bin[y][0] ? 1 : 0;
        int n = 1;
        for (int x = 1; x < w; x++) {
            int cc = bin[y][x] ? 1 : 0;
            if (cc == c) n++;
            else {
                runs.add(new int[]{c, n});
                c = cc;
                n = 1;
            }
        }
        runs.add(new int[]{c, n});
        return runs;
    }

    // --------------------------------------------------------- sample grid
    private static boolean[][] sample(boolean[][] bin, int w, int h, Pt[] f, int n) {
        double size = f[0].size;
        double vx_x = (f[1].x - f[0].x) / (n - 7);
        double vx_y = (f[1].y - f[0].y) / (n - 7);
        double vy_x = (f[2].x - f[0].x) / (n - 7);
        double vy_y = (f[2].y - f[0].y) / (n - 7);
        double ox = f[0].x - vx_x * 3 - vy_x * 3;
        double oy = f[0].y - vx_y * 3 - vy_y * 3;

        boolean[][] mat = new boolean[n][n];
        int rr = Math.max(1, (int) (size * 0.6));
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                double px = ox + vx_x * c + vy_x * r;
                double py = oy + vx_y * c + vy_y * r;
                int ix = (int) Math.round(px);
                int iy = (int) Math.round(py);
                int dark = 0, tot = 0;
                for (int dy = -rr; dy <= rr; dy++) {
                    for (int dx = -rr; dx <= rr; dx++) {
                        int xx = ix + dx, yy = iy + dy;
                        if (xx >= 0 && xx < w && yy >= 0 && yy < h) {
                            tot++;
                            if (bin[yy][xx]) dark++;
                        }
                    }
                }
                mat[r][c] = tot > 0 && dark * 2 >= tot;
            }
        }
        return mat;
    }

    // ----------------------------------------------------- format (BCH)
    private static int[] readFormat(boolean[][] mat, int n) {
        int[][] pos = {
                {8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}, {8, 7}, {8, 8},
                {7, 8}, {5, 8}, {4, 8}, {3, 8}, {2, 8}, {1, 8}, {0, 8}
        };
        int read = 0;
        for (int[] p : pos) read = (read << 1) | (mat[p[0]][p[1]] ? 1 : 0);
        int bestDist = 99, bestIdx = -1;
        for (int idx = 0; idx < 32; idx++) {
            int v = validFormat(idx);
            int dist = Integer.bitCount(read ^ v);
            if (dist < bestDist) { bestDist = dist; bestIdx = idx; }
        }
        if (bestIdx < 0 || bestDist > 4) return null;
        return new int[]{bestIdx >> 3, bestIdx & 7}; // {ecBits, mask}
    }

    private static int[] FORMATS;
    private static int validFormat(int idx) {
        if (FORMATS == null) {
            FORMATS = new int[32];
            for (int idx2 = 0; idx2 < 32; idx2++) {
                int data = idx2 & 0x1F;
                int bch = data << 10;
                int gen = 0x537;
                for (int i = 14; i >= 10; i--) {
                    if ((bch & (1 << i)) != 0) bch ^= gen << (i - 10);
                }
                FORMATS[idx2] = ((data << 10) | (bch & 0x3FF)) ^ 0x5412;
            }
        }
        return FORMATS[idx];
    }

    // ------------------------------------------------- function modules
    private static boolean[][] buildFunction(int n, int version) {
        boolean[][] f = new boolean[n][n];
        for (int r = 0; r < n; r++)
            for (int c = 0; c < n; c++) {
                if (r <= 8 && c <= 8) f[r][c] = true;
                if (r <= 8 && c >= n - 8) f[r][c] = true;
                if (r >= n - 8 && c <= 8) f[r][c] = true;
                if (r == 6 || c == 6) f[r][c] = true;
                if (r == 8 && c <= 8) f[r][c] = true;
                if (c == 8 && r <= 8) f[r][c] = true;
                if (r == 8 && c >= n - 8) f[r][c] = true;
                if (c == 8 && r >= n - 8) f[r][c] = true;
            }
        if (4 * version + 9 < n) f[4 * version + 9][8] = true;
        if (version >= 2 && version <= 10) {
            int[] co = ALIGN[version];
            for (int i = 0; i < co.length; i++)
                for (int j = 0; j < co.length; j++) {
                    int ar = co[i], ac = co[j];
                    if ((ar == 6 && ac == 6) || (ar == 6 && ac == n - 7)
                            || (ar == n - 7 && ac == 6)) continue;
                    for (int dr = -2; dr <= 2; dr++)
                        for (int dc = -2; dc <= 2; dc++)
                            if (ar + dr >= 0 && ar + dr < n && ac + dc >= 0 && ac + dc < n)
                                f[ar + dr][ac + dc] = true;
                }
        }
        if (version >= 7) {
            for (int r = 0; r < 6; r++)
                for (int c = 0; c < 3; c++) {
                    f[r][n - 11 + c] = true;
                    f[n - 11 + c][r] = true;
                }
        }
        return f;
    }

    // --------------------------------------------------------- read data
    private static int[] readData(boolean[][] mat, boolean[][] func, int n, int mask) {
        List<Integer> bits = new ArrayList<Integer>();
        // 之字形按 QR 规范：每次读"同一行的两个模块"（右列先、左列后），
        // 整条 2 列竖带同方向推进，方向逐条带上下交替；竖直 timing 列(6)
        // 全是功能位，由 func 跳过即可。
        int col = n - 1;
        boolean up = true;
        while (col > 0) {
            if (col == 6) { col--; continue; } // timing 列单独跳过，保持 (5,4) 配对
            for (int i = 0; i < n; i++) {
                int row = up ? (n - 1 - i) : i;
                for (int k = 0; k < 2; k++) {
                    int cc = col - k;
                    if (cc < 0) continue;
                    if (func[row][cc]) continue;
                    int bit = mat[row][cc] ? 1 : 0;
                    if (maskBit(row, cc, mask)) bit ^= 1;
                    bits.add(bit);
                }
            }
            up = !up;
            col -= 2;
        }
        int[] out = new int[bits.size()];
        for (int i = 0; i < out.length; i++) out[i] = bits.get(i);
        return out;
    }

    private static boolean maskBit(int r, int c, int mask) {
        switch (mask) {
            case 0: return (r + c) % 2 == 0;
            case 1: return r % 2 == 0;
            case 2: return c % 3 == 0;
            case 3: return (r + c) % 3 == 0;
            case 4: return (r / 2 + c / 3) % 2 == 0;
            case 5: return (r * c) % 2 + (r * c) % 3 == 0;
            case 6: return ((r * c) % 2 + (r * c) % 3) % 2 == 0;
            case 7: return ((r + c) % 2 + (r * c) % 3) % 2 == 0;
            default: return false;
        }
    }

    private static int[] packCodewords(int[] bits) {
        int ncode = bits.length / 8;
        int[] code = new int[ncode];
        for (int i = 0; i < ncode; i++) {
            int v = 0;
            for (int j = 0; j < 8; j++) v = (v << 1) | bits[i * 8 + j];
            code[i] = v;
        }
        return code;
    }

    // ------------------------------------------- deinterleave + RS decode
    private static int[] deinterleaveAndDecode(int[] code, int[] t) {
        int totalData = t[0], ecPerBlock = t[1];
        int g1Blocks = t[2], g1Data = t[3], g2Blocks = t[4], g2Data = t[5];
        int totalBlocks = g1Blocks + g2Blocks;
        int[] blockData = new int[totalBlocks];
        for (int i = 0; i < g1Blocks; i++) blockData[i] = g1Data;
        for (int i = g1Blocks; i < totalBlocks; i++) blockData[i] = g2Data;

        List<int[]> blocks = new ArrayList<int[]>();
        for (int i = 0; i < totalBlocks; i++) blocks.add(new int[blockData[i] + ecPerBlock]);

        int di = 0;
        int maxData = g1Data > g2Data ? g1Data : g2Data;
        for (int d = 0; d < maxData; d++) {
            for (int b = 0; b < totalBlocks; b++) {
                if (d < blockData[b]) blocks.get(b)[d] = code[di++];
            }
        }
        int ei = totalData;
        for (int e = 0; e < ecPerBlock; e++) {
            for (int b = 0; b < totalBlocks; b++) {
                blocks.get(b)[blockData[b] + e] = code[ei++];
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int b = 0; b < totalBlocks; b++) {
            int[] corrected = rsDecode(blocks.get(b), ecPerBlock);
            if (corrected == null) return null;
            for (int i = 0; i < blockData[b]; i++) baos.write(corrected[i]);
        }
        int[] data = new int[baos.size()];
        byte[] arr = baos.toByteArray();
        for (int i = 0; i < data.length; i++) data[i] = arr[i] & 0xFF;
        return data;
    }

    /** Reed-Solomon decode. code[0] is the highest-degree coefficient.
     *  约定：S_j = C(α^j)（大端 Horner），错误位置 p 的定位子 X_p=α^(n-1-p)。
     *  Λ/Ω 一律按小端数组（下标=x 的幂）处理，避免大端/小端混用。
     *  Returns corrected codeword array, or null on unrecoverable errors. */
    private static int[] rsDecode(int[] code, int nsym) {
        int n = code.length;
        int[] syn = new int[nsym];
        boolean zero = true;
        for (int i = 0; i < nsym; i++) {
            syn[i] = gfPolyEval(code, gfPow(2, i));
            if (syn[i] != 0) zero = false;
        }
        if (zero) return code;

        if (DEBUG) {
            int nz = 0; for (int s : syn) if (s != 0) nz++;
            dbg("DBG rs syn=" + java.util.Arrays.toString(syn) + " nz=" + nz);
        }

        // Berlekamp-Massey：Λ(x)=Σ C[i]·x^i（小端），C[0]=1
        int[] C = {1};
        int[] B = {1};
        int L = 0, m = 1, b = 1;
        for (int nn = 0; nn < nsym; nn++) {
            int disc = syn[nn];
            for (int i = 1; i <= L && i < C.length; i++) disc ^= gfMul(C[i], syn[nn - i]);
            if (disc == 0) {
                m++;
                continue;
            }
            int coef = gfMul(disc, gfInv(b));
            int[] C2 = new int[Math.max(C.length, B.length + m)];
            System.arraycopy(C, 0, C2, 0, C.length);
            for (int i = 0; i < B.length; i++) C2[i + m] ^= gfMul(coef, B[i]);
            if (2 * L <= nn) {
                B = C;          // 前一个 C 作为 B
                C = C2;
                L = nn + 1 - L;
                b = disc;
                m = 1;
            } else {
                C = C2;
                m++;
            }
        }
        if (L == 0) return null;

        // Chien：Λ(α^{-j})==0 → α^{-j}=X^{-1}=α^{-(n-1-p)} → p = n-1-j
        List<Integer> errPos = new ArrayList<Integer>();
        for (int j = 0; j < n; j++) {
            int x = gfPow(2, (255 - j) % 255); // α^{-j}
            int v = 0;
            for (int i = 0; i < C.length && i <= L; i++) v ^= gfMul(C[i], gfPow(x, i));
            if (v == 0) errPos.add(j);
        }
        if (errPos.size() != L) return null;

        // Ω(x) = Λ(x)·S(x) mod x^nsym（小端卷积），Forney：e = X·Ω(X⁻¹)/Λ'(X⁻¹)
        int[] omega = polyMul(syn, C);
        int olen = Math.min(omega.length, nsym);

        int[] out = code.clone();
        for (int jj : errPos) {
            int Xinv = gfPow(2, (255 - jj) % 255);       // X⁻¹
            int num = 0;                                  // Ω(X⁻¹)
            for (int i = 0; i < olen; i++) num ^= gfMul(omega[i], gfPow(Xinv, i));
            int den = 0;                                  // Λ'(X⁻¹)（奇次项）
            for (int k = 1; k < C.length && k <= L; k += 2) {
                den ^= gfMul(C[k], gfPow(Xinv, k - 1));
            }
            if (den == 0) return null;
            int e = gfMul(gfPow(2, jj % 255), gfMul(num, gfInv(den))); // ×X
            int p = (n - 1) - jj;
            if (p < 0 || p >= n) return null;
            out[p] ^= e;
        }
        return out;
    }

    private static int[] polyMul(int[] a, int[] b) {
        int[] r = new int[a.length + b.length - 1];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < b.length; j++)
                r[i + j] ^= gfMul(a[i], b[j]);
        return r;
    }

    // ----------------------------------------------------------- parse
    private static String parseData(int[] data, int version) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int pos = 0;
            int n = data.length * 8;
            boolean small = version <= 9;
            while (pos + 4 <= n) {
                int mode = readBits(data, pos, 4); pos += 4;
                if (mode == 0) break;
                if (mode == 4) { // byte
                    int cntLen = small ? 8 : 16;
                    if (pos + cntLen > n) break;
                    int cnt = readBits(data, pos, cntLen); pos += cntLen;
                    for (int i = 0; i < cnt; i++) {
                        if (pos + 8 > n) break;
                        baos.write(readBits(data, pos, 8)); pos += 8;
                    }
                } else if (mode == 2) { // alphanumeric
                    int cntLen = small ? 9 : 11;
                    if (pos + cntLen > n) break;
                    int cnt = readBits(data, pos, cntLen); pos += cntLen;
                    String AL = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
                    int i = 0;
                    while (i + 1 < cnt) {
                        if (pos + 11 > n) break;
                        int v = readBits(data, pos, 11); pos += 11;
                        baos.write(AL.charAt(v / 45));
                        baos.write(AL.charAt(v % 45));
                        i += 2;
                    }
                    if (i < cnt) {
                        if (pos + 6 > n) break;
                        int v = readBits(data, pos, 6); pos += 6;
                        baos.write(AL.charAt(v));
                        i++;
                    }
                } else if (mode == 1) { // numeric
                    int cntLen = small ? 10 : 12;
                    if (pos + cntLen > n) break;
                    int cnt = readBits(data, pos, cntLen); pos += cntLen;
                    int i = 0;
                    while (i + 2 < cnt) {
                        if (pos + 10 > n) break;
                        int v = readBits(data, pos, 10); pos += 10;
                        String s = String.format("%03d", v);
                        baos.write(s.charAt(0)); baos.write(s.charAt(1)); baos.write(s.charAt(2));
                        i += 3;
                    }
                    int rem = cnt - i;
                    if (rem == 1) {
                        if (pos + 4 > n) break;
                        baos.write('0' + readBits(data, pos, 4)); pos += 4;
                    } else if (rem == 2) {
                        if (pos + 7 > n) break;
                        int v = readBits(data, pos, 7); pos += 7;
                        String s = String.format("%02d", v);
                        baos.write(s.charAt(0)); baos.write(s.charAt(1));
                    }
                } else {
                    break;
                }
            }
            return new String(baos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static String ascii(boolean[][] m, String tag) {
        StringBuilder sb = new StringBuilder();
        sb.append(tag + " " + m.length + "x" + m.length + "\n");
        for (int r = 0; r < m.length; r++) {
            for (int c = 0; c < m.length; c++)
                sb.append(m[r][c] ? "#" : ".");
            sb.append("\n");
        }
        return sb.toString();
    }

    private static int readBits(int[] data, int pos, int len) {
        int v = 0;
        for (int i = 0; i < len; i++) {
            int bit = (data[(pos + i) / 8] >> (7 - ((pos + i) % 8))) & 1;
            v = (v << 1) | bit;
        }
        return v;
    }

    /** Isolated Reed-Solomon round-trip test (debug only). */
    public static String rsSelfTest() {
        int nsym = 7;
        // g(x)=Π(x+α^i)：小端构造后反转为大端数组（g[0]=最高次）
        int[] g = {1};
        for (int i = 0; i < nsym; i++) {
            int[] ng = new int[g.length + 1];
            for (int j = 0; j < g.length; j++) {
                ng[j + 1] ^= g[j];
                ng[j] ^= gfMul(g[j], gfPow(2, i));
            }
            g = ng;
        }
        for (int i = 0; i < g.length / 2; i++) {
            int t = g[i]; g[i] = g[g.length - 1 - i]; g[g.length - 1 - i] = t;
        }
        int[] msg = new int[19];
        for (int i = 0; i < 19; i++) msg[i] = (i * 37 + 11) & 0xFF;
        int[] code = new int[26];
        for (int i = 0; i < 19; i++) code[i] = msg[i];
        int[] rem = code.clone();
        for (int i = 0; i < 19; i++) {
            if (rem[i] != 0) {
                int coef = rem[i];
                for (int j = 0; j < g.length; j++) rem[i + j] ^= gfMul(g[j], coef);
            }
        }
        for (int i = 0; i < nsym; i++) code[19 + i] = rem[19 + i];
        int[] syn = new int[nsym];
        for (int i = 0; i < nsym; i++) syn[i] = gfPolyEval(code, gfPow(2, i));
        boolean synZero = true;
        for (int s : syn) if (s != 0) synZero = false;
        // 注入 3 个错误（≤ t=(nsym-L)/2=3 应可纠正）
        int[] dirty = code.clone();
        dirty[2] ^= 0x5A;
        dirty[10] ^= 0xA5;
        dirty[25] ^= 0x77;
        int[] dec = rsDecode(dirty, nsym);
        boolean fixed = java.util.Arrays.equals(dec, code);
        return "rsSelfTest synZero=" + synZero + " fixed=" + fixed
                + (fixed ? "" : " dec=" + (dec == null ? "null" : "mismatch"));
    }
}
