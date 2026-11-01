package a.b.util;

import java.util.Arrays;

/** Argon2id v1.3 (RFC 9106), single lane, no secret/associated data. */
public final class Argon2id {
    private static final int WORDS = 128;
    private static final int SYNC_POINTS = 4;
    private static final int TYPE_ID = 2;

    private final long[][] memory;
    private final int laneLength, segmentLength, blocks, passes;
    private final long[] r = new long[WORDS];
    private final long[] z = new long[WORDS];

    private Argon2id(int memoryKiB, int passes) {
        this.blocks = 4 * (memoryKiB / 4);
        this.laneLength = blocks;
        this.segmentLength = laneLength / SYNC_POINTS;
        this.passes = passes;
        this.memory = new long[blocks][];
        for (int i = 0; i < blocks; i++) memory[i] = new long[WORDS];
    }

    public static byte[] hash(byte[] password, byte[] salt, int passes, int memoryKiB, int outLen) {
        if (passes < 1) throw new IllegalArgumentException("passes");
        if (memoryKiB < 8) throw new IllegalArgumentException("memory");
        if (salt.length < 8) throw new IllegalArgumentException("salt");
        if (outLen < 4) throw new IllegalArgumentException("outLen");
        return new Argon2id(memoryKiB, passes).run(password, salt, outLen, memoryKiB);
    }

    private byte[] run(byte[] password, byte[] salt, int outLen, int memoryKiB) {
        byte[] h0 = initialHash(password, salt, outLen, memoryKiB);

        byte[] seed = new byte[h0.length + 8];
        System.arraycopy(h0, 0, seed, 0, h0.length);
        for (int i = 0; i < 2; i++) {
            putLe32(seed, h0.length, i);
            putLe32(seed, h0.length + 4, 0);
            bytesToBlock(hPrime(seed, 1024), memory[i]);
        }
        Arrays.fill(h0, (byte) 0);

        for (int pass = 0; pass < passes; pass++) {
            for (int slice = 0; slice < SYNC_POINTS; slice++) fillSegment(pass, slice);
        }

        byte[] last = new byte[1024];
        blockToBytes(memory[laneLength - 1], last);
        byte[] tag = hPrime(last, outLen);

        for (long[] b : memory) Arrays.fill(b, 0L);
        Arrays.fill(last, (byte) 0);
        return tag;
    }

    private byte[] initialHash(byte[] pwd, byte[] salt, int outLen, int memoryKiB) {
        byte[] buf = new byte[10 * 4 + pwd.length + salt.length];
        int o = 0;
        o = putLe32(buf, o, 1);
        o = putLe32(buf, o, outLen);
        o = putLe32(buf, o, memoryKiB);
        o = putLe32(buf, o, passes);
        o = putLe32(buf, o, 0x13);
        o = putLe32(buf, o, TYPE_ID);
        o = putLe32(buf, o, pwd.length);
        System.arraycopy(pwd, 0, buf, o, pwd.length);
        o += pwd.length;
        o = putLe32(buf, o, salt.length);
        System.arraycopy(salt, 0, buf, o, salt.length);
        o += salt.length;
        o = putLe32(buf, o, 0);
        o = putLe32(buf, o, 0);
        byte[] out = Blake2b.hash(Arrays.copyOf(buf, o), 64);
        Arrays.fill(buf, (byte) 0);
        return out;
    }

    private void fillSegment(int pass, int slice) {
        boolean dataIndependent = pass == 0 && slice < 2;
        long[] input = null, address = null, zero = null;
        if (dataIndependent) {
            input = new long[WORDS];
            address = new long[WORDS];
            zero = new long[WORDS];
            input[0] = pass;
            input[1] = 0;
            input[2] = slice;
            input[3] = blocks;
            input[4] = passes;
            input[5] = TYPE_ID;
        }

        int start = 0;
        if (pass == 0 && slice == 0) {
            start = 2;
            if (dataIndependent) nextAddresses(address, input, zero);
        }

        int cur = slice * segmentLength + start;
        for (int i = start; i < segmentLength; i++, cur++) {
            int prev = cur == 0 ? laneLength - 1 : cur - 1;
            long rand;
            if (dataIndependent) {
                if (i % WORDS == 0) nextAddresses(address, input, zero);
                rand = address[i % WORDS];
            } else {
                rand = memory[prev][0];
            }
            int ref = referenceIndex(pass, slice, i, rand & 0xFFFFFFFFL);
            fillBlock(memory[prev], memory[ref], memory[cur], pass != 0);
        }
    }

    private void nextAddresses(long[] address, long[] input, long[] zero) {
        input[6]++;
        fillBlock(zero, input, address, false);
        fillBlock(zero, address, address, false);
    }

    private int referenceIndex(int pass, int slice, int index, long j1) {
        long area;
        if (pass == 0) {
            area = slice == 0 ? index - 1 : (long) slice * segmentLength + index - 1;
        } else {
            area = laneLength - segmentLength + index - 1;
        }
        long rel = (j1 * j1) >>> 32;
        rel = area - 1 - ((area * rel) >>> 32);
        long start = 0;
        if (pass != 0) start = slice == SYNC_POINTS - 1 ? 0 : (long) (slice + 1) * segmentLength;
        return (int) ((start + rel) % laneLength);
    }

    private void fillBlock(long[] x, long[] y, long[] out, boolean xorOut) {
        for (int i = 0; i < WORDS; i++) {
            r[i] = x[i] ^ y[i];
            z[i] = r[i];
        }
        for (int i = 0; i < 8; i++) {
            int b = 16 * i;
            p(z, b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7, b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15);
        }
        for (int i = 0; i < 8; i++) {
            int b = 2 * i;
            p(z, b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49, b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113);
        }
        if (xorOut) for (int i = 0; i < WORDS; i++) out[i] ^= z[i] ^ r[i];
        else for (int i = 0; i < WORDS; i++) out[i] = z[i] ^ r[i];
    }

    private static long mix(long x, long y) {
        return x + y + 2 * (x & 0xFFFFFFFFL) * (y & 0xFFFFFFFFL);
    }

    private static void g(long[] v, int a, int b, int c, int d) {
        v[a] = mix(v[a], v[b]);
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = mix(v[c], v[d]);
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = mix(v[a], v[b]);
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = mix(v[c], v[d]);
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    private static void p(long[] v, int i0, int i1, int i2, int i3, int i4, int i5, int i6, int i7,
                          int i8, int i9, int i10, int i11, int i12, int i13, int i14, int i15) {
        g(v, i0, i4, i8, i12);
        g(v, i1, i5, i9, i13);
        g(v, i2, i6, i10, i14);
        g(v, i3, i7, i11, i15);
        g(v, i0, i5, i10, i15);
        g(v, i1, i6, i11, i12);
        g(v, i2, i7, i8, i13);
        g(v, i3, i4, i9, i14);
    }

    private static byte[] hPrime(byte[] in, int outLen) {
        byte[] withLen = new byte[4 + in.length];
        putLe32(withLen, 0, outLen);
        System.arraycopy(in, 0, withLen, 4, in.length);
        if (outLen <= 64) return Blake2b.hash(withLen, outLen);

        byte[] out = new byte[outLen];
        int pos = 0;
        byte[] v = Blake2b.hash(withLen, 64);
        System.arraycopy(v, 0, out, 0, 32);
        pos = 32;
        int remaining = outLen - 32;
        while (remaining > 64) {
            v = Blake2b.hash(v, 64);
            System.arraycopy(v, 0, out, pos, 32);
            pos += 32;
            remaining -= 32;
        }
        v = Blake2b.hash(v, remaining);
        System.arraycopy(v, 0, out, pos, remaining);
        return out;
    }

    private static int putLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
        return off + 4;
    }

    private static void bytesToBlock(byte[] in, long[] out) {
        for (int i = 0; i < WORDS; i++) {
            long w = 0;
            for (int b = 7; b >= 0; b--) w = (w << 8) | (in[i * 8 + b] & 0xFFL);
            out[i] = w;
        }
    }

    private static void blockToBytes(long[] in, byte[] out) {
        for (int i = 0; i < WORDS; i++) {
            for (int b = 0; b < 8; b++) out[i * 8 + b] = (byte) (in[i] >>> (8 * b));
        }
    }
}
