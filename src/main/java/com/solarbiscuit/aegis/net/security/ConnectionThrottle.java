package com.solarbiscuit.aegis.net.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-address limits for connections that have not logged in yet (handshake, server-list ping, login). Vanilla 1.20.1
 * has none, so one address can open sockets as fast as it likes and keep every one of them in the login phase.
 * <ul>
 * <li>New connections: a token bucket of {@code burst} connections, refilled at {@code burst} per {@code windowMillis}.</li>
 * <li>Pending connections: at most {@code maxPending} at once that have not reached PLAY.</li>
 * <li>Bans: an address that broke the protocol (see {@link Offence}) is refused for {@code banMillis}.</li>
 * </ul>
 * IPv6 addresses are grouped by /64, the block a single host or household usually gets. Loopback and trusted addresses
 * are never limited. Thread-safe: called from every Netty thread.
 */
public final class ConnectionThrottle {
    /** Above this many tracked addresses new addresses are let through untracked, so memory stays bounded in a flood. */
    static final int MAX_TRACKED = 65536;

    public enum Verdict {
        ALLOW, RATE, PENDING, BANNED, UNTRACKED
    }

    private final int burst;
    private final long windowMillis;
    private final int maxPending;
    private final long banMillis;
    private final List<Cidr> trusted;
    private final LongSupplier clock;
    private final ConcurrentHashMap<InetAddress, Entry> entries = new ConcurrentHashMap<>();
    private volatile long lastSweep;

    public ConnectionThrottle(int burst, long windowMillis, int maxPending, long banMillis, List<Cidr> trusted, LongSupplier clock) {
        this.burst = burst;
        this.windowMillis = windowMillis;
        this.maxPending = maxPending;
        this.banMillis = banMillis;
        this.trusted = List.copyOf(trusted);
        this.clock = clock;
        this.lastSweep = clock.getAsLong();
    }

    private static final class Entry {
        double tokens;
        long refilledAt;
        int pending;
        long bannedUntil;
        boolean removed; // dropped by the sweep; a caller still holding it looks the address up again

        Entry(double tokens, long now) {
            this.tokens = tokens;
            this.refilledAt = now;
        }
    }

    /** Whether {@code address} is never limited (loopback or trusted). */
    public boolean isExempt(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        for (Cidr c : this.trusted) {
            if (c.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decides about a new connection from {@code address}. On {@link Verdict#ALLOW} the connection counts as pending until
     * {@link #leftPending} is called for it (exactly once).
     */
    public Verdict tryAccept(InetAddress address) {
        if (isExempt(address)) {
            return Verdict.UNTRACKED;
        }
        long now = this.clock.getAsLong();
        sweepIfDue(now);
        InetAddress key = key(address);
        while (true) {
            Entry entry = entry(key, now);
            if (entry == null) {
                return Verdict.UNTRACKED;
            }
            synchronized (entry) {
                if (!entry.removed) {
                    return decide(entry, now);
                }
            }
        }
    }

    /** Called with the entry's lock held. */
    private Verdict decide(Entry entry, long now) {
        if (now < entry.bannedUntil) {
            return Verdict.BANNED;
        }
        refill(entry, now);
        if (entry.tokens < 1) {
            return Verdict.RATE;
        }
        if (entry.pending >= this.maxPending) {
            return Verdict.PENDING;
        }
        entry.tokens -= 1;
        entry.pending++;
        return Verdict.ALLOW;
    }

    /** A connection allowed by {@link #tryAccept} reached PLAY or closed. */
    public void leftPending(InetAddress address) {
        Entry entry = this.entries.get(key(address));
        if (entry != null) {
            synchronized (entry) {
                if (entry.pending > 0) {
                    entry.pending--;
                }
            }
        }
    }

    /** Refuses every connection from {@code address} (its /64 for IPv6) for the ban time. Returns whether it banned. */
    public boolean ban(InetAddress address) {
        if (this.banMillis <= 0 || isExempt(address)) {
            return false;
        }
        long now = this.clock.getAsLong();
        InetAddress key = key(address);
        while (true) {
            Entry entry = entry(key, now);
            if (entry == null) {
                return false;
            }
            synchronized (entry) {
                if (!entry.removed) {
                    entry.bannedUntil = Math.max(entry.bannedUntil, now + this.banMillis);
                    return true;
                }
            }
        }
    }

    /** The entry for {@code key}, created if needed; null when too many addresses are tracked already. */
    private Entry entry(InetAddress key, long now) {
        Entry entry = this.entries.get(key);
        if (entry == null) {
            if (this.entries.size() >= MAX_TRACKED) {
                return null;
            }
            entry = this.entries.computeIfAbsent(key, k -> new Entry(this.burst, now));
        }
        return entry;
    }

    private void refill(Entry entry, long now) {
        long elapsed = now - entry.refilledAt;
        if (elapsed > 0) {
            entry.tokens = Math.min(this.burst, entry.tokens + elapsed * (double) this.burst / this.windowMillis);
            entry.refilledAt = now;
        }
    }

    /** Drops addresses that have nothing left to remember, at most once per window. */
    private void sweepIfDue(long now) {
        if (now - this.lastSweep < this.windowMillis) {
            return;
        }
        this.lastSweep = now;
        this.entries.entrySet().removeIf(e -> {
            Entry entry = e.getValue();
            synchronized (entry) {
                refill(entry, now);
                entry.removed = entry.pending == 0 && entry.tokens >= this.burst && now >= entry.bannedUntil;
                return entry.removed;
            }
        });
    }

    int tracked() {
        return this.entries.size();
    }

    /** IPv4 as is, IPv6 reduced to its /64. */
    static InetAddress key(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] b = Arrays.copyOf(address.getAddress(), 16);
            Arrays.fill(b, 8, 16, (byte) 0);
            try {
                return InetAddress.getByAddress(b);
            } catch (UnknownHostException e) {
                return address; // cannot happen for 16 bytes
            }
        }
        return address;
    }

    /** An address block, "203.0.113.7", "10.0.0.0/8" or "2001:db8::/32". */
    public record Cidr(byte[] network, int prefix) {
        public static Cidr parse(String text) {
            String t = text.trim();
            int slash = t.indexOf('/');
            String host = slash < 0 ? t : t.substring(0, slash);
            if (!host.matches("[0-9.]+") && !(host.contains(":") && host.matches("[0-9A-Fa-f:.]+"))) {
                throw new IllegalArgumentException("not an IP address: " + text); // never a DNS lookup
            }
            byte[] addr;
            try {
                addr = InetAddress.getByName(host).getAddress();
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("not an IP address: " + text);
            }
            int bits = addr.length * 8;
            int prefix = slash < 0 ? bits : Integer.parseInt(t.substring(slash + 1).trim());
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("bad prefix length: " + text);
            }
            return new Cidr(addr, prefix);
        }

        public boolean contains(InetAddress address) {
            byte[] a = address.getAddress();
            if (a.length != this.network.length) {
                return false;
            }
            int full = this.prefix / 8;
            for (int i = 0; i < full; i++) {
                if (a[i] != this.network[i]) {
                    return false;
                }
            }
            int rest = this.prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (a[full] & mask) == (this.network[full] & mask);
        }
    }
}
