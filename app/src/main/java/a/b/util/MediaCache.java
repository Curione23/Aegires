package a.b.util;

import android.graphics.Bitmap;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.SecretKey;

/* One cache for every picture and video the app shows. */
public final class MediaCache {

    static final long DISK_MAX = 256L << 20;
    static final long PLAIN_AUTO = 8L << 20, PLAIN_MAX = 24L << 20;
    static final long ENC_IMAGE_AUTO = 6L << 20, ENC_IMAGE_MAX = 48L << 20;
    static final long ENC_VIDEO_AUTO = 4L << 20, ENC_VIDEO_MAX = 256L << 20;
    static final long BIG_THRESHOLD = 512L << 10;
    static final long PART_MAX_AGE_MS = 12L * 3600 * 1000;
    static final int MAX_ATTEMPTS = 4;
    private static final int BUF = 32 * 1024;

    public interface Transport {
        Connection open(String url, long rangeStart) throws IOException;

        interface Connection {
            int code();

            long contentLength();

            String header(String name);

            InputStream body() throws IOException;

            void cancel();

            void close();
        }
    }

    public interface Decoder {
        Bitmap decode(File file, int targetW, int targetH, int[] outDims);

        Bitmap videoFrame(File file, int targetW, int[] outDims);
    }

    public interface Handle {
        void cancel();
    }

    public static final Handle NOOP = () -> {};

    public static final class Failure {
        public enum Kind {
            NETWORK,
            HTTP,
            TOO_LARGE,
            DECRYPT,
            DECODE
        }

        public final Kind kind;
        public final int httpCode;
        public final long bytes;
        public final String message;

        Failure(Kind kind, int httpCode, long bytes, String message) {
            this.kind = kind;
            this.httpCode = httpCode;
            this.bytes = bytes;
            this.message = message;
        }

        public boolean retryable() {
            return kind == Kind.NETWORK
                    || (kind == Kind.HTTP
                            && (httpCode == 408 || httpCode == 429 || httpCode >= 500));
        }

        @Override
        public String toString() {
            return kind + (httpCode != 0 ? " " + httpCode : "") + ": " + message;
        }
    }

    public interface BaseCallback {
        void onProgress(long done, long total);

        void onNeedsConfirm(long totalBytes);

        void onFailure(Failure failure);
    }

    public interface Callback extends BaseCallback {
        void onBitmap(Bitmap bitmap, int sourceWidth, int sourceHeight);
    }

    public interface TempCallback extends BaseCallback {
        void onFile(File plaintext);
    }

    public abstract static class SimpleCallback implements Callback {
        @Override
        public void onProgress(long done, long total) {}

        @Override
        public void onNeedsConfirm(long totalBytes) {}

        @Override
        public void onFailure(Failure failure) {}
    }

    public static final class Spec {
        public final String url;
        public String fallbackUrl;
        public int targetW, targetH;
        public long sizeHint = -1;
        public long autoBytes = PLAIN_AUTO;
        public long maxBytes = PLAIN_MAX;
        public boolean force;
        public boolean encrypted;
        public SecretKey key;
        public String channelId, senderId;
        private String diskKey;

        public Spec(String url) {
            this.url = url;
        }

        public static Spec plain(String url, int w, int h) {
            Spec s = new Spec(url);
            s.targetW = w;
            s.targetH = h;
            return s;
        }

        public static Spec encryptedImage(
                String url,
                SecretKey key,
                String channelId,
                String senderId,
                long size,
                int w,
                int h) {
            Spec s = new Spec(url);
            s.encrypted = true;
            s.key = key;
            s.channelId = channelId;
            s.senderId = senderId;
            s.sizeHint = size;
            s.targetW = w;
            s.targetH = h;
            s.autoBytes = ENC_IMAGE_AUTO;
            s.maxBytes = ENC_IMAGE_MAX;
            return s;
        }

        public static Spec encryptedVideo(
                String url, SecretKey key, String channelId, String senderId, long size, int w) {
            Spec s = encryptedImage(url, key, channelId, senderId, size, w, 0);
            s.autoBytes = ENC_VIDEO_AUTO;
            s.maxBytes = ENC_VIDEO_MAX;
            return s;
        }

        String diskKey() {
            if (diskKey == null) diskKey = sha256Hex(normalize(url));
            return diskKey;
        }

        String cryptoFailKey() {
            return diskKey()
                    + "|c|"
                    + (key == null ? 0 : key.hashCode())
                    + "|"
                    + channelId
                    + "|"
                    + senderId;
        }
    }

    private static final int BITMAP = 0, THUMB = 1, TEMP = 2;
    private final File rawDir, partDir, plainDir;
    private final Transport transport;
    private final Decoder decoder;
    private final Executor main;
    private final ByteLru<Entry> plainMem, secureMem;
    private final ExecutorService smallNet = pool(4, "media-net"),
            bigNet = pool(2, "media-big"),
            decodePool = pool(2, "media-decode"),
            ioPool = pool(1, "media-io");
    private final Object lock = new Object();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, FailEntry> failures = new ConcurrentHashMap<>();
    private final AtomicLong diskBytes = new AtomicLong();
    private final AtomicBoolean trimming = new AtomicBoolean();
    private final long diskMax;

    private static final class Entry {
        final Bitmap bitmap;
        final int srcW, srcH;

        Entry(Bitmap b, int w, int h) {
            bitmap = b;
            srcW = w;
            srcH = h;
        }
    }

    private static final class FailEntry {
        final Failure failure;
        final long until;

        FailEntry(Failure f, long until) {
            failure = f;
            this.until = until;
        }
    }

    public MediaCache(
            File root,
            Transport transport,
            Decoder decoder,
            Executor mainExecutor,
            long memoryBytes,
            long diskMax) {
        this.rawDir = new File(root, "raw");
        this.partDir = new File(root, "part");
        this.plainDir = new File(root, "plain");
        this.transport = transport;
        this.decoder = decoder;
        this.main = mainExecutor;
        this.diskMax = diskMax;
        Sizer<Entry> sizer = e -> e.bitmap.getByteCount();
        this.plainMem = new ByteLru<>(memoryBytes, sizer);
        this.secureMem = new ByteLru<>(Math.max(2L << 20, memoryBytes / 2), sizer);
        deleteChildren(plainDir);
        ioPool.execute(this::startupScan);
    }

    private static ExecutorService pool(int n, final String name) {
        return Executors.newFixedThreadPool(
                n,
                r -> {
                    Thread t = new Thread(r, name);
                    t.setDaemon(true);
                    return t;
                });
    }

    public long diskBytes() {
        return diskBytes.get();
    }

    public long memoryBytes() {
        return plainMem.size() + secureMem.size();
    }

    public long memoryMax() {
        return plainMem.max() + secureMem.max();
    }

    public Handle loadBitmap(Spec spec, Callback cb) {
        return submit(new Request(BITMAP, spec, cb, null));
    }

    public Handle loadVideoThumb(Spec spec, Callback cb) {
        return submit(new Request(THUMB, spec, cb, null));
    }

    public Handle decryptToTemp(Spec spec, TempCallback cb) {
        return submit(new Request(TEMP, spec, null, cb));
    }

    public Bitmap peek(Spec spec) {
        Entry e = (spec.encrypted ? secureMem : plainMem).get(memKey(spec, BITMAP));
        return e != null ? e.bitmap : null;
    }

    public void clearFailures() {
        failures.clear();
    }

    public void clearSensitive() {
        secureMem.clear();
        ioPool.execute(() -> deleteChildren(plainDir));
    }

    public static void deleteTemp(File f) {
        if (f != null) f.delete();
    }

    private final class Request implements Handle {
        final int kind;
        final Spec spec;
        final Callback cb;
        final TempCallback tcb;
        volatile boolean cancelled;
        Task task;
        File raw;
        boolean fresh, resubmitted, redownloaded;

        Request(int kind, Spec spec, Callback cb, TempCallback tcb) {
            this.kind = kind;
            this.spec = spec;
            this.cb = cb;
            this.tcb = tcb;
        }

        BaseCallback base() {
            return cb != null ? cb : tcb;
        }

        @Override
        public void cancel() {
            cancelled = true;
            Task t;
            synchronized (lock) {
                t = task;
                if (t == null) return;
                t.reqs.remove(this);
                if (t.reqs.isEmpty()) {
                    t.cancelled = true;
                    Transport.Connection c = t.conn;
                    if (c != null) c.cancel();
                }
            }
        }

        void postProgress(final long done, final long total) {
            main.execute(
                    () -> {
                        if (!cancelled) base().onProgress(done, total);
                    });
        }

        void postConfirm(final long bytes) {
            main.execute(
                    () -> {
                        if (!cancelled) base().onNeedsConfirm(bytes);
                    });
        }

        void postFailure(final Failure f) {
            main.execute(
                    () -> {
                        if (!cancelled) base().onFailure(f);
                    });
        }

        void postBitmap(final Bitmap b, final int w, final int h) {
            main.execute(
                    () -> {
                        if (!cancelled && cb != null) cb.onBitmap(b, w, h);
                    });
        }

        void postFile(final File f) {
            main.execute(
                    () -> {
                        if (cancelled || tcb == null) deleteTemp(f);
                        else tcb.onFile(f);
                    });
        }
    }

    private Handle submit(Request r) {
        Spec s = r.spec;
        if (s == null || s.url == null) {
            r.postFailure(new Failure(Failure.Kind.HTTP, 0, 0, "no url"));
            return NOOP;
        }
        if (r.kind != TEMP) {
            Entry hit = (s.encrypted ? secureMem : plainMem).get(memKey(s, r.kind));
            if (hit != null) {
                if (r.cb != null) r.cb.onBitmap(hit.bitmap, hit.srcW, hit.srcH);
                return NOOP;
            }
        }
        String dk = s.diskKey();
        if (!s.force) {
            Failure remembered = remembered(dk);
            if (remembered == null && s.encrypted) remembered = remembered(s.cryptoFailKey());
            if (remembered != null) {
                r.postFailure(remembered);
                return NOOP;
            }
        }
        File raw = rawFile(dk);
        if (raw.isFile() && raw.length() > 0) {
            raw.setLastModified(System.currentTimeMillis());
            r.raw = raw;
            decodePool.execute(() -> runStage(r));
            return r;
        }
        join(r);
        return r;
    }

    private final class Task implements Runnable {
        final String dk;
        final Spec spec;
        final List<Request> reqs = new ArrayList<>();
        volatile boolean cancelled;
        volatile Transport.Connection conn;
        volatile long done, total = -1;
        boolean finished;

        Task(String dk, Spec spec) {
            this.dk = dk;
            this.spec = spec;
        }

        boolean forceAny() {
            synchronized (lock) {
                for (Request r : reqs) if (r.spec.force) return true;
            }
            return false;
        }

        @Override
        public void run() {
            runTask(this);
        }
    }

    private void join(Request r) {
        String dk = r.spec.diskKey();
        Task t;
        boolean created = false;
        long done = 0, total = -1;
        synchronized (lock) {
            t = tasks.get(dk);
            if (t == null || t.finished) {
                t = new Task(dk, r.spec);
                tasks.put(dk, t);
                created = true;
            } else {
                t.cancelled = false;
                done = t.done;
                total = t.total;
            }
            t.reqs.add(r);
            r.task = t;
        }
        if (created) {
            boolean big = r.spec.encrypted || r.spec.sizeHint > BIG_THRESHOLD;
            (big ? bigNet : smallNet).execute(t);
        } else if (total > 0) {
            r.postProgress(done, total);
        }
    }

    private enum Type {
        OK,
        CONFIRM,
        TOO_LARGE,
        RETRY,
        FATAL,
        CANCELLED
    }

    private static final class Result {
        final Type type;
        final long bytes;
        final int code;
        final long retryAfterMs;

        Result(Type type, long bytes, int code, long retryAfterMs) {
            this.type = type;
            this.bytes = bytes;
            this.code = code;
            this.retryAfterMs = retryAfterMs;
        }

        static Result of(Type t) {
            return new Result(t, 0, 0, 0);
        }
    }

    private void runTask(Task t) {
        Result outcome;
        while (true) {
            outcome = download(t);
            synchronized (lock) {
                if (outcome.type == Type.CANCELLED && !t.reqs.isEmpty()) {
                    t.cancelled = false;
                    continue;
                }
                t.finished = true;
                if (tasks.get(t.dk) == t) tasks.remove(t.dk);
                break;
            }
        }
        List<Request> reqs;
        synchronized (lock) {
            reqs = new ArrayList<>(t.reqs);
        }
        for (Request r : reqs) r.task = null;

        switch (outcome.type) {
            case OK:
                for (Request r : reqs) {
                    r.raw = rawFile(t.dk);
                    r.fresh = true;
                    decodePool.execute(() -> runStage(r));
                }
                break;
            case CONFIRM:
                for (Request r : reqs) {
                    if (r.spec.force && !r.resubmitted) {
                        r.resubmitted = true;
                        submit(r);
                    } else {
                        r.postConfirm(outcome.bytes);
                    }
                }
                break;
            case TOO_LARGE:
                fail(
                        t.dk,
                        reqs,
                        new Failure(Failure.Kind.TOO_LARGE, 0, outcome.bytes, "too large"),
                        0);
                break;
            case FATAL:
                fail(
                        t.dk,
                        reqs,
                        new Failure(Failure.Kind.HTTP, outcome.code, 0, "http " + outcome.code),
                        120_000);
                break;
            case CANCELLED:
                break;
            default:
                fail(t.dk, reqs, new Failure(Failure.Kind.NETWORK, 0, 0, "network"), 10_000);
        }
    }

    private Failure remembered(String key) {
        FailEntry fe = failures.get(key);
        if (fe == null) return null;
        if (fe.until > System.currentTimeMillis()) return fe.failure;
        failures.remove(key);
        return null;
    }

    private void fail(String dk, List<Request> reqs, Failure f, long rememberMs) {
        if (rememberMs > 0)
            failures.put(dk, new FailEntry(f, System.currentTimeMillis() + rememberMs));
        for (Request r : reqs) r.postFailure(f);
    }

    private Result download(Task t) {
        Spec s = t.spec;
        String url = s.url;
        boolean triedFallback = false;
        File part = partFile(t.dk), fin = rawFile(t.dk);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (t.cancelled) return Result.of(Type.CANCELLED);
            Result r = fetchOnce(t, url, part, fin);
            switch (r.type) {
                case OK:
                case CONFIRM:
                case TOO_LARGE:
                    return r;
                case CANCELLED:
                    if (t.cancelled) return r;
                    break;
                case FATAL:
                    if (!triedFallback && s.fallbackUrl != null && !s.fallbackUrl.equals(url)) {
                        triedFallback = true;
                        url = s.fallbackUrl;
                        part.delete();
                        attempt--;
                        continue;
                    }
                    return r;
                default:
            }
            if (attempt + 1 < MAX_ATTEMPTS) {
                long wait =
                        r.retryAfterMs > 0
                                ? Math.min(r.retryAfterMs, 10_000)
                                : (long) (300 * Math.pow(2, attempt))
                                        + (long) (Math.random() * 200);
                if (!sleep(t, wait)) return Result.of(Type.CANCELLED);
            }
        }
        return Result.of(Type.RETRY);
    }

    private boolean sleep(Task t, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (t.cancelled) return false;
            try {
                Thread.sleep(Math.min(50, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                return false;
            }
        }
        return !t.cancelled;
    }

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");

    private Result fetchOnce(Task t, String url, File part, File fin) {
        Spec s = t.spec;
        partDir.mkdirs();
        long have = part.isFile() ? part.length() : 0;
        Transport.Connection c = null;
        try {
            c = transport.open(url, have);
            t.conn = c;
            if (t.cancelled) {
                c.cancel();
                return Result.of(Type.CANCELLED);
            }
            int code = c.code();
            if (code == 416) {
                part.delete();
                return new Result(Type.RETRY, 0, code, 1);
            }
            if (code == 429 || code == 408 || code >= 500) {
                return new Result(Type.RETRY, 0, code, retryAfter(c));
            }
            if (code != 200 && code != 206) return new Result(Type.FATAL, 0, code, 0);

            long total;
            boolean append;
            if (code == 206 && have > 0) {
                Matcher m =
                        CONTENT_RANGE.matcher(
                                c.header("Content-Range") == null ? "" : c.header("Content-Range"));
                if (!m.matches() || Long.parseLong(m.group(1)) != have) {
                    part.delete();
                    return new Result(Type.RETRY, 0, code, 1);
                }
                total = "*".equals(m.group(3)) ? -1 : Long.parseLong(m.group(3));
                append = true;
            } else {
                total = c.contentLength();
                have = 0;
                append = false;
            }

            if (total > s.maxBytes) {
                part.delete();
                return new Result(Type.TOO_LARGE, total, 0, 0);
            }
            if (total > s.autoBytes && !t.forceAny()) {
                return new Result(Type.CONFIRM, total, 0, 0);
            }

            t.total = total;
            t.done = have;
            long written = have;
            long lastProgress = 0;
            try (InputStream in = c.body();
                    OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[BUF];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (t.cancelled) return Result.of(Type.CANCELLED);
                    out.write(buf, 0, n);
                    written += n;
                    t.done = written;
                    if (written > s.maxBytes) {
                        out.close();
                        part.delete();
                        return new Result(Type.TOO_LARGE, written, 0, 0);
                    }
                    if (total < 0 && written > s.autoBytes && !t.forceAny()) {
                        return new Result(Type.CONFIRM, written, 0, 0);
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastProgress > 120) {
                        lastProgress = now;
                        progress(t, written, total);
                    }
                }
            }
            if (t.cancelled) return Result.of(Type.CANCELLED);
            if (total >= 0 && written != total) return new Result(Type.RETRY, 0, code, 0);
            if (written == 0) {
                part.delete();
                return new Result(Type.RETRY, 0, code, 0);
            }
            File shard = fin.getParentFile();
            shard.mkdirs();
            if (fin.exists()) fin.delete();
            if (!part.renameTo(fin)) {
                part.delete();
                return new Result(Type.RETRY, 0, code, 0);
            }
            diskBytes.addAndGet(fin.length());
            trimIfNeeded();
            progress(t, written, total);
            return Result.of(Type.OK);
        } catch (IOException e) {
            if (t.cancelled) return Result.of(Type.CANCELLED);
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("ENOSPC") || msg.contains("No space")) trim();
            return Result.of(Type.RETRY);
        } finally {
            t.conn = null;
            if (c != null) c.close();
        }
    }

    private static long retryAfter(Transport.Connection c) {
        try {
            String h = c.header("Retry-After");
            return h == null ? 0 : Long.parseLong(h.trim()) * 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    private void progress(Task t, long done, long total) {
        List<Request> reqs;
        synchronized (lock) {
            reqs = new ArrayList<>(t.reqs);
        }
        for (Request r : reqs) r.postProgress(done, total);
    }

    private void runStage(Request r) {
        if (r.cancelled) return;
        Spec s = r.spec;
        File plain = null;
        try {
            if (r.kind == TEMP) {
                if (!s.encrypted) {
                    r.postFailure(new Failure(Failure.Kind.DECODE, 0, 0, "not encrypted"));
                    return;
                }
                plain = tempPlain();
                if (!decrypt(r.raw, plain, s)) {
                    deleteTemp(plain);
                    r.postFailure(failureDecrypt(s));
                    return;
                }
                File out = plain;
                plain = null;
                r.postFile(out);
                return;
            }

            File source = r.raw;
            if (s.encrypted) {
                plain = tempPlain();
                if (!decrypt(r.raw, plain, s)) {
                    r.postFailure(failureDecrypt(s));
                    return;
                }
                source = plain;
            }

            int[] dims = new int[2];
            Bitmap bmp =
                    r.kind == THUMB
                            ? decoder.videoFrame(source, Math.max(s.targetW, 64), dims)
                            : decoder.decode(source, s.targetW, s.targetH, dims);
            if (plain != null) {
                deleteTemp(plain);
                plain = null;
            }
            if (r.cancelled) return;
            if (bmp == null) {
                if (!s.encrypted && !r.fresh && !r.redownloaded) {
                    r.redownloaded = true;
                    dropRaw(s.diskKey());
                    submit(r);
                    return;
                }
                if (!s.encrypted) dropRaw(s.diskKey());
                Failure f = new Failure(Failure.Kind.DECODE, 0, 0, "undecodable");
                failures.put(s.diskKey(), new FailEntry(f, System.currentTimeMillis() + 300_000));
                r.postFailure(f);
                return;
            }
            (s.encrypted ? secureMem : plainMem)
                    .put(memKey(s, r.kind), new Entry(bmp, dims[0], dims[1]));
            r.postBitmap(bmp, dims[0], dims[1]);
        } catch (Throwable t) {
            if (plain != null) {
                deleteTemp(plain);
                plain = null;
            }
            r.postFailure(new Failure(Failure.Kind.DECODE, 0, 0, String.valueOf(t)));
        } finally {
            if (plain != null) deleteTemp(plain);
        }
    }

    private Failure failureDecrypt(Spec s) {
        Failure f = new Failure(Failure.Kind.DECRYPT, 0, 0, "decrypt failed");
        failures.put(s.cryptoFailKey(), new FailEntry(f, System.currentTimeMillis() + 60_000));
        return f;
    }

    private boolean decrypt(File cipher, File plain, Spec s) {
        if (s.key == null || s.channelId == null || s.senderId == null) return false;
        try (InputStream in = new FileInputStream(cipher);
                OutputStream out = new BufferedOutputStream(new FileOutputStream(plain), BUF)) {
            return Crypto.decryptStream(in, out, s.key, s.channelId, s.senderId);
        } catch (IOException e) {
            return false;
        }
    }

    private File tempPlain() {
        plainDir.mkdirs();
        return new File(plainDir, UUID.randomUUID().toString() + ".tmp");
    }

    private void dropRaw(String dk) {
        File f = rawFile(dk);
        long len = f.length();
        if (f.delete()) diskBytes.addAndGet(-len);
    }

    File rawFile(String dk) {
        return new File(new File(rawDir, dk.substring(0, 2)), dk);
    }

    File partFile(String dk) {
        return new File(partDir, dk + ".part");
    }

    private void startupScan() {
        try {
            long now = System.currentTimeMillis();
            File[] parts = partDir.listFiles();
            if (parts != null)
                for (File p : parts) if (now - p.lastModified() > PART_MAX_AGE_MS) p.delete();
            long total = 0;
            File[] shards = rawDir.listFiles();
            if (shards != null)
                for (File sh : shards) {
                    File[] files = sh.listFiles();
                    if (files != null) for (File f : files) total += f.length();
                }
            diskBytes.set(total);
            trimIfNeeded();
        } catch (Throwable ignored) {
        }
    }

    private void trimIfNeeded() {
        if (diskBytes.get() <= diskMax || !trimming.compareAndSet(false, true)) return;
        ioPool.execute(
                () -> {
                    try {
                        trim();
                    } finally {
                        trimming.set(false);
                    }
                });
    }

    void trim() {
        List<File> all = new ArrayList<>();
        long total = 0;
        File[] shards = rawDir.listFiles();
        if (shards == null) return;
        for (File sh : shards) {
            File[] files = sh.listFiles();
            if (files == null) continue;
            for (File f : files) {
                all.add(f);
                total += f.length();
            }
        }
        final long target = (long) (diskMax * 0.85);
        if (total > target) {
            final Map<File, Long> stamp = new java.util.HashMap<>();
            for (File f : all) stamp.put(f, f.lastModified());
            all.sort((a, b) -> Long.compare(stamp.get(a), stamp.get(b)));
            for (File f : all) {
                if (total <= target) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        }
        diskBytes.set(Math.max(0, total));
    }

    private static void deleteChildren(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) k.delete();
    }

    private static String memKey(Spec s, int kind) {
        return s.diskKey() + "|" + kind + "|" + s.targetW + "x" + s.targetH;
    }

    private static final String[] KEPT_PARAMS = {
        "animated", "format", "height", "quality", "size", "width"
    };

    static String normalize(String url) {
        int hash = url.indexOf('#');
        if (hash >= 0) url = url.substring(0, hash);
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        String query = q < 0 ? "" : url.substring(q + 1);
        int schemeEnd = base.indexOf("://");
        if (schemeEnd > 0) {
            int pathStart = base.indexOf('/', schemeEnd + 3);
            String head =
                    (pathStart < 0 ? base : base.substring(0, pathStart)).toLowerCase(Locale.ROOT);
            base = head + (pathStart < 0 ? "" : base.substring(pathStart));
        }
        List<String> kept = new ArrayList<>();
        if (!query.isEmpty()) {
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                String name = eq < 0 ? kv : kv.substring(0, eq);
                if (Arrays.binarySearch(KEPT_PARAMS, name) >= 0) kept.add(kv);
            }
            java.util.Collections.sort(kept);
        }
        return kept.isEmpty() ? base : base + "?" + join(kept);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('&');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    static String sha256Hex(String s) {
        try {
            byte[] d =
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String previewUrl(String url, String proxyUrl, int w, int h) {
        String base =
                proxyUrl != null && !proxyUrl.isEmpty() && !"null".equals(proxyUrl)
                        ? proxyUrl
                        : url;
        if (base == null || w <= 0 || h <= 0) return base;
        int schemeEnd = base.indexOf("://");
        if (schemeEnd < 0) return base;
        int pathStart = base.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) return base;
        String scheme = base.substring(0, schemeEnd);
        String host = base.substring(schemeEnd + 3, pathStart).toLowerCase(Locale.ROOT);
        String rest = base.substring(pathStart);
        boolean cdnAttachment =
                host.equals("cdn.discordapp.com") && rest.startsWith("/attachments/");
        boolean proxy = host.equals("media.discordapp.net") || host.endsWith(".discordapp.net");
        if (!cdnAttachment && !proxy) return base;
        if (rest.contains("width=") || rest.contains("format=")) return base;
        if (cdnAttachment) host = "media.discordapp.net";
        String sep = rest.contains("?") ? "&" : "?";
        return scheme
                + "://"
                + host
                + rest
                + sep
                + "format=webp&width="
                + Math.min(w, 2048)
                + "&height="
                + Math.min(h, 2048);
    }

    interface Sizer<V> {
        long size(V v);
    }

    static final class ByteLru<V> {
        private final LinkedHashMap<String, V> map = new LinkedHashMap<>(16, 0.75f, true);
        private final Sizer<V> sizer;
        private final long max;
        private long size;

        ByteLru(long max, Sizer<V> sizer) {
            this.max = max;
            this.sizer = sizer;
        }

        synchronized V get(String k) {
            return map.get(k);
        }

        synchronized void put(String k, V v) {
            V old = map.put(k, v);
            if (old != null) size -= sizer.size(old);
            size += sizer.size(v);
            java.util.Iterator<Map.Entry<String, V>> it = map.entrySet().iterator();
            while (size > max && map.size() > 1 && it.hasNext()) {
                Map.Entry<String, V> e = it.next();
                if (e.getKey().equals(k)) continue;
                size -= sizer.size(e.getValue());
                it.remove();
            }
        }

        synchronized void clear() {
            map.clear();
            size = 0;
        }

        synchronized long size() {
            return size;
        }

        long max() {
            return max;
        }
    }

    private static volatile MediaCache instance;

    public static MediaCache get(android.content.Context ctx) {
        MediaCache c = instance;
        if (c == null) {
            synchronized (MediaCache.class) {
                c = instance;
                if (c == null) {
                    instance = c = AndroidMedia.create(ctx.getApplicationContext());
                    ImageLoader.remember(c);
                }
            }
        }
        return c;
    }
}
