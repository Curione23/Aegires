package a.b.util;

import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end encryption for messages and attachments.
 *
 * Password -> Argon2id -> 256-bit key (kept in memory only).
 * Every message/file then gets its own key and nonce from HKDF-SHA256(master, random salt,
 * context), where the context binds the preset, channel and sender. Messages are padded,
 * never compressed, and authenticated together with their header and context, so a ciphertext
 * cannot be moved to another channel or replayed under another author.
 */
public final class Crypto {

    /** What a sender uses; receivers read it from the header, so presets can be mixed freely. */
    public enum Preset {
        AES128(0, "AES-128-GCM", 16, 12, 16),
        AES192(1, "AES-192-GCM", 24, 14, 32),
        AES256(2, "AES-256-GCM", 32, 16, 64);

        public final int index;
        public final String label;
        public final int keyBytes;
        public final int tagBytes;
        public final int padBlock;

        Preset(int index, String label, int keyBytes, int tagBytes, int padBlock) {
            this.index = index;
            this.label = label;
            this.keyBytes = keyBytes;
            this.tagBytes = tagBytes;
            this.padBlock = padBlock;
        }

        public int tagBits() { return tagBytes * 8; }

        public static Preset fromIndex(int i) {
            for (Preset p : values()) if (p.index == i) return p;
            return null;
        }
    }

    public static final int DISCORD_CONTENT_LIMIT = 2000;

    private static final String PREFIX = "\u00A7";
    private static final int VERSION_MESSAGE = 2;
    private static final int VERSION_FILE = 3;
    private static final int SALT_LEN = 16;
    private static final int NONCE_LEN = 12;
    private static final int MASTER_LEN = 32;
    private static final int FILE_CHUNK = 64 * 1024;
    private static final int FILE_PREFIX_LEN = 7;

    private static final int ARGON_PASSES = 3;
    private static final int ARGON_MEMORY_KIB = 64 * 1024;
    private static final byte[] USER_KEY_SALT = "dcenc2|user-key|v1".getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {}

    public static boolean looksEncrypted(String s) {
        return s != null && s.startsWith(PREFIX);
    }

    // ------------------------------------------------------------- key derivation

    /** Key for the session-wide password. Slow by design: call off the UI thread. */
    public static SecretKey deriveKey(String password) {
        return derive(password, USER_KEY_SALT);
    }

    /** Key bound to a context (for example one preset channel), with its own salt. */
    public static SecretKey deriveKey(String password, String context) {
        return derive(password, ("dcenc2|ctx|" + context).getBytes(StandardCharsets.UTF_8));
    }

    private static SecretKey derive(String password, byte[] salt) {
        if (password == null || password.isEmpty()) return null;
        byte[] pw = null;
        try {
            pw = Normalizer.normalize(password, Normalizer.Form.NFKC).getBytes(StandardCharsets.UTF_8);
            byte[] master = Argon2id.hash(pw, salt, ARGON_PASSES, ARGON_MEMORY_KIB, MASTER_LEN);
            SecretKey key = new SecretKeySpec(master, "HKDF");
            Arrays.fill(master, (byte) 0);
            return key;
        } catch (Throwable t) {
            return null;
        } finally {
            if (pw != null) Arrays.fill(pw, (byte) 0);
        }
    }

    // --------------------------------------------------------------------- sizes

    private static int maxRawBytesForChars(int chars) {
        int n = chars * 3 / 4 + 1;
        while ((4 * n + 2) / 3 > chars) n--;
        return n;
    }

    public static int maxPlaintextBytes(Preset p) {
        int raw = maxRawBytesForChars(DISCORD_CONTENT_LIMIT - PREFIX.length());
        int payloadCap = raw - 1 - SALT_LEN - p.tagBytes;
        return (payloadCap / p.padBlock) * p.padBlock - 1;
    }

    public static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    public static long fileOverhead(Preset p, long plainSize) {
        long chunks = Math.max(1, (plainSize + FILE_CHUNK - 1) / FILE_CHUNK);
        return 1 + SALT_LEN + chunks * p.tagBytes;
    }

    // ----------------------------------------------------------------- messages

    public static String encrypt(String plaintext, SecretKey master, Preset preset, String channelId, String senderId) {
        if (plaintext == null || master == null || preset == null || channelId == null || senderId == null) return null;
        byte[] data = null, payload = null, prk = null, okm = null, aad = null;
        try {
            data = plaintext.getBytes(StandardCharsets.UTF_8);
            if (data.length > maxPlaintextBytes(preset)) return null;

            int padded = ((data.length + 1 + preset.padBlock - 1) / preset.padBlock) * preset.padBlock;
            payload = new byte[padded];
            System.arraycopy(data, 0, payload, 0, data.length);
            payload[data.length] = (byte) 0x80;

            byte header = (byte) ((VERSION_MESSAGE << 4) | preset.index);
            byte[] salt = new byte[SALT_LEN];
            RANDOM.nextBytes(salt);

            byte[] info = info("msg", preset, channelId, senderId);
            prk = hkdfExtract(salt, master.getEncoded());
            okm = hkdfExpand(prk, info, preset.keyBytes + NONCE_LEN);
            aad = aad(header, salt, info);

            byte[] ct = gcm(true, Arrays.copyOfRange(okm, 0, preset.keyBytes),
                Arrays.copyOfRange(okm, preset.keyBytes, okm.length), preset.tagBits(), aad, payload, 0, payload.length);

            byte[] wire = new byte[1 + SALT_LEN + ct.length];
            wire[0] = header;
            System.arraycopy(salt, 0, wire, 1, SALT_LEN);
            System.arraycopy(ct, 0, wire, 1 + SALT_LEN, ct.length);
            return PREFIX + Base64.encodeToString(wire, Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            return null;
        } finally {
            wipe(data, payload, prk, okm, aad);
        }
    }

    public static String decrypt(String wire, SecretKey master, String channelId, String senderId) {
        if (!looksEncrypted(wire) || master == null || channelId == null || senderId == null) return null;
        byte[] prk = null, okm = null, payload = null;
        try {
            byte[] raw = Base64.decode(wire.substring(PREFIX.length()), Base64.NO_WRAP);
            if (raw.length < 1 + SALT_LEN + 1) return null;
            int header = raw[0] & 0xFF;
            if ((header >> 4) != VERSION_MESSAGE) return null;
            Preset preset = Preset.fromIndex(header & 0x0F);
            if (preset == null) return null;

            int ctLen = raw.length - 1 - SALT_LEN;
            if (ctLen < preset.tagBytes + preset.padBlock || (ctLen - preset.tagBytes) % preset.padBlock != 0) return null;

            byte[] salt = Arrays.copyOfRange(raw, 1, 1 + SALT_LEN);
            byte[] info = info("msg", preset, channelId, senderId);
            prk = hkdfExtract(salt, master.getEncoded());
            okm = hkdfExpand(prk, info, preset.keyBytes + NONCE_LEN);
            byte[] aad = aad((byte) header, salt, info);

            payload = gcm(false, Arrays.copyOfRange(okm, 0, preset.keyBytes),
                Arrays.copyOfRange(okm, preset.keyBytes, okm.length), preset.tagBits(), aad, raw, 1 + SALT_LEN, ctLen);

            int end = payload.length;
            while (end > 0 && payload[end - 1] == 0) end--;
            if (end == 0 || payload[end - 1] != (byte) 0x80) return null;
            return new String(payload, 0, end - 1, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } finally {
            wipe(prk, okm, payload);
        }
    }

    // -------------------------------------------------------------------- files

    /**
     * Streams plaintext in as 64 KiB chunks, each authenticated on its own. Chunks carry a counter
     * and a final-chunk flag in their nonce, so reordering, dropping or truncating is detected.
     */
    public static boolean encryptStream(InputStream in, OutputStream out, SecretKey master, Preset preset,
                                        String channelId, String senderId) {
        if (in == null || out == null || master == null || preset == null || channelId == null || senderId == null) return false;
        byte[] prk = null, okm = null;
        try {
            byte header = (byte) ((VERSION_FILE << 4) | preset.index);
            byte[] salt = new byte[SALT_LEN];
            RANDOM.nextBytes(salt);
            byte[] info = info("file", preset, channelId, senderId);
            prk = hkdfExtract(salt, master.getEncoded());
            okm = hkdfExpand(prk, info, preset.keyBytes + FILE_PREFIX_LEN);
            byte[] key = Arrays.copyOfRange(okm, 0, preset.keyBytes);
            byte[] noncePrefix = Arrays.copyOfRange(okm, preset.keyBytes, okm.length);
            byte[] aad = aad(header, salt, info);

            out.write(header);
            out.write(salt);

            byte[] cur = new byte[FILE_CHUNK], next = new byte[FILE_CHUNK];
            int curLen = readFully(in, cur);
            long counter = 0;
            while (true) {
                int nextLen = curLen == FILE_CHUNK ? readFully(in, next) : 0;
                boolean last = nextLen == 0;
                out.write(gcm(true, key, chunkNonce(noncePrefix, counter, last), preset.tagBits(), aad, cur, 0, curLen));
                if (last) break;
                byte[] t = cur; cur = next; next = t;
                curLen = nextLen;
                if (++counter >= 0xFFFFFFFFL) return false;
            }
            out.flush();
            Arrays.fill(key, (byte) 0);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            wipe(prk, okm);
        }
    }

    /** Output is written chunk by chunk, but only after that chunk has been authenticated. */
    public static boolean decryptStream(InputStream in, OutputStream out, SecretKey master, String channelId, String senderId) {
        if (in == null || out == null || master == null || channelId == null || senderId == null) return false;
        byte[] prk = null, okm = null;
        try {
            int header = in.read();
            if (header < 0 || (header >> 4) != VERSION_FILE) return false;
            Preset preset = Preset.fromIndex(header & 0x0F);
            if (preset == null) return false;
            byte[] salt = new byte[SALT_LEN];
            if (readFully(in, salt) != SALT_LEN) return false;

            byte[] info = info("file", preset, channelId, senderId);
            prk = hkdfExtract(salt, master.getEncoded());
            okm = hkdfExpand(prk, info, preset.keyBytes + FILE_PREFIX_LEN);
            byte[] key = Arrays.copyOfRange(okm, 0, preset.keyBytes);
            byte[] noncePrefix = Arrays.copyOfRange(okm, preset.keyBytes, okm.length);
            byte[] aad = aad((byte) header, salt, info);

            int chunkCt = FILE_CHUNK + preset.tagBytes;
            byte[] cur = new byte[chunkCt], next = new byte[chunkCt];
            int curLen = readFully(in, cur);
            if (curLen < preset.tagBytes) return false;
            long counter = 0;
            while (true) {
                int nextLen = curLen == chunkCt ? readFully(in, next) : 0;
                boolean last = nextLen == 0;
                out.write(gcm(false, key, chunkNonce(noncePrefix, counter, last), preset.tagBits(), aad, cur, 0, curLen));
                if (last) break;
                byte[] t = cur; cur = next; next = t;
                curLen = nextLen;
                if (++counter >= 0xFFFFFFFFL) return false;
            }
            out.flush();
            Arrays.fill(key, (byte) 0);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            wipe(prk, okm);
        }
    }

    public static byte[] encryptBytes(byte[] plaintext, SecretKey master, Preset preset, String channelId, String senderId) {
        if (plaintext == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(plaintext.length + 64);
        return encryptStream(new ByteArrayInputStream(plaintext), out, master, preset, channelId, senderId) ? out.toByteArray() : null;
    }

    public static byte[] decryptBytes(byte[] wire, SecretKey master, String channelId, String senderId) {
        if (wire == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(wire.length);
        return decryptStream(new ByteArrayInputStream(wire), out, master, channelId, senderId) ? out.toByteArray() : null;
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] chunkNonce(byte[] prefix, long counter, boolean last) {
        byte[] n = new byte[NONCE_LEN];
        System.arraycopy(prefix, 0, n, 0, FILE_PREFIX_LEN);
        n[7] = (byte) (counter >>> 24);
        n[8] = (byte) (counter >>> 16);
        n[9] = (byte) (counter >>> 8);
        n[10] = (byte) counter;
        n[11] = (byte) (last ? 1 : 0);
        return n;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    private static byte[] gcm(boolean encrypt, byte[] key, byte[] nonce, int tagBits, byte[] aad,
                              byte[] data, int off, int len) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(tagBits, nonce));
        c.updateAAD(aad);
        return c.doFinal(data, off, len);
    }

    private static byte[] info(String kind, Preset preset, String channelId, String senderId) {
        byte[] label = ("dcenc2/" + kind).getBytes(StandardCharsets.UTF_8);
        byte[] ch = channelId.getBytes(StandardCharsets.UTF_8);
        byte[] se = senderId.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(label, 0, label.length);
        o.write(preset.index);
        o.write(ch.length >>> 8);
        o.write(ch.length);
        o.write(ch, 0, ch.length);
        o.write(se.length >>> 8);
        o.write(se.length);
        o.write(se, 0, se.length);
        return o.toByteArray();
    }

    private static byte[] aad(byte header, byte[] salt, byte[] info) {
        byte[] a = new byte[1 + salt.length + info.length];
        a[0] = header;
        System.arraycopy(salt, 0, a, 1, salt.length);
        System.arraycopy(info, 0, a, 1 + salt.length, info.length);
        return a;
    }

    private static byte[] hmac(byte[] key, byte[]... parts) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] p : parts) mac.update(p);
        return mac.doFinal();
    }

    private static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws GeneralSecurityException {
        try {
            return hmac(salt, ikm);
        } finally {
            Arrays.fill(ikm, (byte) 0);
        }
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < length; i++) {
            t = hmac(prk, t, info, new byte[]{(byte) i});
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        Arrays.fill(t, (byte) 0);
        return out;
    }

    private static void wipe(byte[]... arrays) {
        for (byte[] a : arrays) if (a != null) Arrays.fill(a, (byte) 0);
    }
}
