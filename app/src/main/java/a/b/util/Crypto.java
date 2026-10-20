package a.b.util;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class Crypto {
    private static final String PREFIX = "\u00A7";
    private static final byte[] SALT = "pwc".getBytes(StandardCharsets.UTF_8);
    private static final int ITERATIONS = 15000;
    private static final int KEY_LENGTH = 256;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final int COMPRESS_THRESHOLD = 100;
    private static final byte FLAG_RAW = 0;
    private static final byte FLAG_GZIP = 1;
    public static final int MAX_PLAINTEXT_LEN = 1300;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {}

    public static boolean looksEncrypted(String s) {
        return s != null && s.startsWith(PREFIX);
    }

    public static SecretKey deriveKey(String password) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), SALT, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            return null;
        }
    }

    public static String encrypt(String plaintext, SecretKey key) {
        if (plaintext == null || key == null) return null;
        try {
            byte[] rawBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] compressed = rawBytes.length >= COMPRESS_THRESHOLD ? gzip(rawBytes) : null;
            boolean useGzip = compressed != null && compressed.length < rawBytes.length;
            byte[] data = useGzip ? compressed : rawBytes;
            
            byte[] payload = new byte[data.length + 1];
            payload[0] = useGzip ? FLAG_GZIP : FLAG_RAW;
            System.arraycopy(data, 0, payload, 1, data.length);
            
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(payload);
            
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    public static String decrypt(String wire, SecretKey key) {
        if (!looksEncrypted(wire) || key == null) return null;
        try {
            String b64 = wire.substring(PREFIX.length());
            byte[] combined = Base64.decode(b64, Base64.NO_WRAP);
            if (combined.length < IV_LENGTH + (GCM_TAG_LENGTH / 8)) return null;
            
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] payload = cipher.doFinal(ciphertext);
            if (payload.length == 0) return null;
            
            byte flag = payload[0];
            byte[] data = Arrays.copyOfRange(payload, 1, payload.length);
            if (flag == FLAG_GZIP) {
                byte[] decompressed = gunzip(data);
                return decompressed == null ? null : new String(decompressed, StandardCharsets.UTF_8);
            } else {
                return new String(data, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] gzip(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
            byte[] buf = new byte[1024];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                if (n > 0) out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] gunzip(byte[] data) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 2);
            byte[] buf = new byte[2048];
            while (!inflater.finished() && !inflater.needsInput()) {
                int n = inflater.inflate(buf);
                if (n > 0) {
                    out.write(buf, 0, n);
                } else if (n == 0 && (inflater.needsDictionary() || inflater.finished() || inflater.needsInput())) {
                    break;
                }
            }
            return out.size() > 0 ? out.toByteArray() : null;
        } catch (Exception e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    public static byte[] encryptBytes(byte[] plaintext, SecretKey key) {
        if (plaintext == null || key == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] decryptBytes(byte[] combined, SecretKey key) {
        if (combined == null || key == null || combined.length < IV_LENGTH + (GCM_TAG_LENGTH / 8)) return null;
        try {
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
            byte[] ct = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(ct);
        } catch (Exception e) {
            return null;
        }
    }
}