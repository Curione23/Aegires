package a.b.util;

import java.util.Arrays;

/** Unkeyed BLAKE2b (RFC 7693), needed by Argon2. */
final class Blake2b {
    private static final long[] IV = {
        0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
        0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
    };

    private static final byte[][] SIGMA = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
        {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
        {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
        {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
        {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
        {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
        {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
        {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
        {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0},
    };

    private Blake2b() {}

    static byte[] hash(byte[] in, int outLen) {
        if (outLen < 1 || outLen > 64) throw new IllegalArgumentException("outLen");
        long[] h = IV.clone();
        h[0] ^= 0x01010000L ^ outLen;
        long[] m = new long[16];
        long[] v = new long[16];
        byte[] block = new byte[128];
        long t = 0;
        int pos = 0;
        while (in.length - pos > 128) {
            System.arraycopy(in, pos, block, 0, 128);
            t += 128;
            compress(h, block, m, v, t, false);
            pos += 128;
        }
        int rem = in.length - pos;
        Arrays.fill(block, (byte) 0);
        System.arraycopy(in, pos, block, 0, rem);
        t += rem;
        compress(h, block, m, v, t, true);

        byte[] out = new byte[outLen];
        for (int i = 0; i < outLen; i++) out[i] = (byte) (h[i >> 3] >>> (8 * (i & 7)));
        return out;
    }

    private static void compress(long[] h, byte[] block, long[] m, long[] v, long t, boolean last) {
        for (int i = 0; i < 16; i++) {
            long w = 0;
            for (int b = 7; b >= 0; b--) w = (w << 8) | (block[i * 8 + b] & 0xFFL);
            m[i] = w;
        }
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        v[12] ^= t;
        if (last) v[14] = ~v[14];

        for (int r = 0; r < 12; r++) {
            byte[] s = SIGMA[r % 10];
            g(v, m, 0, 4, 8, 12, s[0], s[1]);
            g(v, m, 1, 5, 9, 13, s[2], s[3]);
            g(v, m, 2, 6, 10, 14, s[4], s[5]);
            g(v, m, 3, 7, 11, 15, s[6], s[7]);
            g(v, m, 0, 5, 10, 15, s[8], s[9]);
            g(v, m, 1, 6, 11, 12, s[10], s[11]);
            g(v, m, 2, 7, 8, 13, s[12], s[13]);
            g(v, m, 3, 4, 9, 14, s[14], s[15]);
        }
        for (int i = 0; i < 8; i++) h[i] ^= v[i] ^ v[i + 8];
    }

    private static void g(long[] v, long[] m, int a, int b, int c, int d, int x, int y) {
        v[a] = v[a] + v[b] + m[x];
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + m[y];
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }
}
