package com.solarbiscuit.aegis.net.security;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Records refused connections and protocol offences, rate limited so a flood cannot fill the console or the disk:
 * <ul>
 * <li>Console: one WARN line per address and reason per minute, with the number of repeats since the last line.</li>
 * <li>File (optional, {@code security.blockLog}): one line per address and reason per 10 seconds in
 * {@code logs/aegis-security.log}, written by a background thread (never by a Netty thread). The format is fixed so
 * fail2ban or CrowdSec can act on it:
 * {@code 2026-11-05T13:40:00+0100 reason=OVERSIZED_FRAME ip=203.0.113.7 count=1 detail="..."}</li>
 * </ul>
 */
public final class SecurityLog {
    private static final Logger LOGGER = LogManager.getLogger("Aegis");
    static final long CONSOLE_INTERVAL_MILLIS = 60_000;
    static final long FILE_INTERVAL_MILLIS = 10_000;
    private static final int MAX_KEYS = 16384;
    /** ISO 8601 with a "+hhmm" offset, which fail2ban's %z parses. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx");

    private final LongSupplier clock;
    private final Path file;
    private final ConcurrentHashMap<String, Limiter> console = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Limiter> fileLimits = new ConcurrentHashMap<>();
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(4096);
    private volatile Thread writer;
    private volatile boolean hintShown;

    /** {@code file} null = console only. */
    public SecurityLog(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
    }

    /** Counts repeats of one address + reason and says when the next line is due. */
    static final class Limiter {
        private long lastWritten = Long.MIN_VALUE;
        private int count;

        /** Returns the number of events this line stands for, or 0 if no line is due yet. */
        synchronized int hit(long now, long interval) {
            this.count++;
            if (this.lastWritten != Long.MIN_VALUE && now - this.lastWritten < interval) {
                return 0;
            }
            this.lastWritten = now;
            int n = this.count;
            this.count = 0;
            return n;
        }

        synchronized boolean idle(long now, long interval) {
            return now - this.lastWritten >= interval;
        }
    }

    public void record(InetAddress address, Offence reason, String detail) {
        long now = this.clock.getAsLong();
        String ip = address.getHostAddress();
        String key = reason.name() + ' ' + ip;
        int n = limiter(this.console, key, now, CONSOLE_INTERVAL_MILLIS).hit(now, CONSOLE_INTERVAL_MILLIS);
        if (n > 0) {
            LOGGER.warn("Connection from {} refused/closed: {}{}{}", ip, reason, n > 1 ? " (" + n + " times since the last message)" : "",
                    detail == null || detail.isEmpty() ? "" : " - " + detail);
            if ((reason == Offence.THROTTLE_RATE || reason == Offence.THROTTLE_PENDING) && !this.hintShown) {
                this.hintShown = true;
                LOGGER.warn("If all your players come from one address (a proxy, load balancer or NAT), add it to "
                        + "security.trustedAddresses in config/aegis.properties, or set security.connectionThrottle=false.");
            }
        }
        if (this.file != null) {
            int m = limiter(this.fileLimits, key, now, FILE_INTERVAL_MILLIS).hit(now, FILE_INTERVAL_MILLIS);
            if (m > 0) {
                enqueue(line(OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS), reason, ip, m, detail));
            }
        }
    }

    private Limiter limiter(ConcurrentHashMap<String, Limiter> map, String key, long now, long interval) {
        Limiter l = map.get(key);
        if (l == null) {
            if (map.size() >= MAX_KEYS) {
                map.entrySet().removeIf(e -> e.getValue().idle(now, interval));
            }
            l = map.computeIfAbsent(key, k -> new Limiter());
        }
        return l;
    }

    /** One line of the file format. */
    static String line(OffsetDateTime time, Offence reason, String ip, int count, String detail) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(TIME.format(time)).append(" reason=").append(reason.name()).append(" ip=").append(ip).append(" count=").append(count);
        if (detail != null && !detail.isEmpty()) {
            sb.append(" detail=\"");
            for (int i = 0, n = Math.min(detail.length(), 200); i < n; i++) {
                char c = detail.charAt(i);
                sb.append(c == '"' ? '\'' : c < 0x20 ? ' ' : c);
            }
            sb.append('"');
        }
        return sb.toString();
    }

    private void enqueue(String line) {
        if (!this.queue.offer(line)) {
            return; // the writer is behind; the rate limit means the same addresses will show up again
        }
        if (this.writer == null) {
            startWriter();
        }
    }

    private synchronized void startWriter() {
        if (this.writer != null) {
            return;
        }
        Thread t = new Thread(this::writeLoop, "Aegis security log");
        t.setDaemon(true);
        this.writer = t;
        t.start();
    }

    private void writeLoop() {
        List<String> batch = new ArrayList<>();
        try {
            Files.createDirectories(this.file.getParent());
        } catch (IOException e) {
            LOGGER.warn("Could not create {}: {}", this.file.getParent(), e.toString());
        }
        while (true) {
            try {
                String first = this.queue.poll(60, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                this.queue.drainTo(batch);
                try (BufferedWriter w = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                    for (String line : batch) {
                        w.write(line);
                        w.write('\n');
                    }
                } catch (IOException e) {
                    LOGGER.warn("Could not write {}: {}", this.file, e.toString());
                }
                batch.clear();
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
