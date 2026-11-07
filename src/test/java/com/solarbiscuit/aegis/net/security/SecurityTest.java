package com.solarbiscuit.aegis.net.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.solarbiscuit.aegis.net.security.ConnectionThrottle.Cidr;
import com.solarbiscuit.aegis.net.security.ConnectionThrottle.Verdict;

class SecurityTest {
    private final AtomicLong now = new AtomicLong(1_000_000);

    private ConnectionThrottle throttle(List<Cidr> trusted) {
        // Defaults: 10 per 3 s, 8 pending, 60 s ban.
        return new ConnectionThrottle(10, 3000, 8, 60_000, trusted, this.now::get);
    }

    private static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    void burstThenRefillAtTheConfiguredRate() throws Exception {
        ConnectionThrottle t = throttle(List.of());
        InetAddress a = ip("203.0.113.7");
        for (int i = 0; i < 8; i++) {
            assertEquals(Verdict.ALLOW, t.tryAccept(a));
            t.leftPending(a); // a ping that finished right away
        }
        assertEquals(Verdict.ALLOW, t.tryAccept(a));
        t.leftPending(a);
        assertEquals(Verdict.ALLOW, t.tryAccept(a));
        t.leftPending(a);
        assertEquals(Verdict.RATE, t.tryAccept(a), "11th connection inside the window");
        assertEquals(Verdict.ALLOW, t.tryAccept(ip("203.0.113.8")), "other addresses are not affected");
        this.now.addAndGet(300); // 10 per 3000 ms = one token per 300 ms
        assertEquals(Verdict.ALLOW, t.tryAccept(a));
        assertEquals(Verdict.RATE, t.tryAccept(a));
    }

    @Test
    void pendingConnectionsAreCappedUntilTheyLogInOrClose() throws Exception {
        ConnectionThrottle t = throttle(List.of());
        InetAddress a = ip("198.51.100.1");
        for (int i = 0; i < 8; i++) {
            assertEquals(Verdict.ALLOW, t.tryAccept(a));
        }
        assertEquals(Verdict.PENDING, t.tryAccept(a));
        t.leftPending(a);
        assertEquals(Verdict.ALLOW, t.tryAccept(a));
    }

    @Test
    void offenceBansTheAddressForTheBanTime() throws Exception {
        ConnectionThrottle t = throttle(List.of());
        InetAddress a = ip("192.0.2.50");
        assertTrue(t.ban(a));
        assertEquals(Verdict.BANNED, t.tryAccept(a));
        this.now.addAndGet(59_999);
        assertEquals(Verdict.BANNED, t.tryAccept(a));
        this.now.addAndGet(1);
        assertEquals(Verdict.ALLOW, t.tryAccept(a));

        ConnectionThrottle noBans = new ConnectionThrottle(10, 3000, 8, 0, List.of(), this.now::get);
        assertFalse(noBans.ban(a));
        assertEquals(Verdict.ALLOW, noBans.tryAccept(a));
    }

    @Test
    void ipv6IsLimitedPerSlash64() throws Exception {
        ConnectionThrottle t = throttle(List.of());
        assertTrue(t.ban(ip("2001:db8:1:2::1")));
        assertEquals(Verdict.BANNED, t.tryAccept(ip("2001:db8:1:2:ffff::9")), "same /64");
        assertEquals(Verdict.ALLOW, t.tryAccept(ip("2001:db8:1:3::1")), "next /64");
    }

    @Test
    void loopbackAndTrustedAddressesAreNeverLimited() throws Exception {
        ConnectionThrottle t = throttle(List.of(Cidr.parse("10.0.0.0/8"), Cidr.parse("203.0.113.9")));
        for (String s : new String[] {"127.0.0.1", "::1", "10.20.30.40", "203.0.113.9"}) {
            InetAddress a = ip(s);
            for (int i = 0; i < 50; i++) {
                assertEquals(Verdict.UNTRACKED, t.tryAccept(a), s);
            }
            assertFalse(t.ban(a), s);
        }
        assertEquals(0, t.tracked());
    }

    @Test
    void idleAddressesAreForgotten() throws Exception {
        ConnectionThrottle t = throttle(List.of());
        InetAddress a = ip("203.0.113.20");
        t.tryAccept(a);
        t.leftPending(a);
        t.tryAccept(ip("203.0.113.21")); // still pending: kept
        assertEquals(2, t.tracked());
        this.now.addAndGet(3000);
        t.tryAccept(ip("203.0.113.22")); // triggers the sweep
        assertEquals(2, t.tracked(), "the idle address was dropped, the pending one and the new one stay");
    }

    @Test
    void cidrParsing() throws Exception {
        assertTrue(Cidr.parse("10.0.0.0/8").contains(ip("10.255.0.1")));
        assertFalse(Cidr.parse("10.0.0.0/8").contains(ip("11.0.0.1")));
        assertTrue(Cidr.parse("192.168.1.128/25").contains(ip("192.168.1.200")));
        assertFalse(Cidr.parse("192.168.1.128/25").contains(ip("192.168.1.100")));
        assertTrue(Cidr.parse("2001:db8::/32").contains(ip("2001:db8:ffff::1")));
        assertFalse(Cidr.parse("2001:db8::/32").contains(ip("10.0.0.1")), "IPv4 never matches an IPv6 block");
        assertThrows(IllegalArgumentException.class, () -> Cidr.parse("example.com"), "never a DNS lookup");
        assertThrows(IllegalArgumentException.class, () -> Cidr.parse("cafe"), "hex word, not an address");
        assertThrows(IllegalArgumentException.class, () -> Cidr.parse("10.0.0.0/33"));
    }

    @Test
    void logLineFormatMatchesTheFail2banFilter() throws Exception {
        String line = SecurityLog.line(OffsetDateTime.of(2026, 11, 5, 13, 40, 0, 0, ZoneOffset.ofHours(1)),
                Offence.OVERSIZED_FRAME, "203.0.113.7", 3, "a \"quoted\"\nline");
        assertEquals("2026-11-05T13:40:00+0100 reason=OVERSIZED_FRAME ip=203.0.113.7 count=3 detail=\"a 'quoted' line\"", line);

        // The failregex shipped in docs/fail2ban/filter.d/aegis.conf, with fail2ban's <HOST> as a plain group.
        Pattern failregex = Pattern.compile(shippedFailregex().replace("<HOST>", "(\\S+)"));
        var m = failregex.matcher(line);
        assertTrue(m.find());
        assertEquals("203.0.113.7", m.group(1));
        String v6 = SecurityLog.line(OffsetDateTime.of(2026, 11, 5, 13, 40, 0, 0, ZoneOffset.UTC), Offence.BANNED, "2001:db8::1", 1, null);
        assertEquals("2026-11-05T13:40:00+0000 reason=BANNED ip=2001:db8::1 count=1", v6);
        m = failregex.matcher(v6);
        assertTrue(m.find());
        assertEquals("2001:db8::1", m.group(1));
        String throttle = SecurityLog.line(OffsetDateTime.now(), Offence.THROTTLE_RATE, "203.0.113.7", 1, null);
        assertFalse(failregex.matcher(throttle).find(), "busy NATs hit the throttle: not a fail2ban reason by default");
    }

    private static String shippedFailregex() throws Exception {
        // Tests run in build/test-run.
        Path filter = Paths.get("").toAbsolutePath().getParent().getParent().resolve("docs/fail2ban/filter.d/aegis.conf");
        for (String l : Files.readAllLines(filter)) {
            if (l.startsWith("failregex = ")) {
                return l.substring("failregex = ".length());
            }
        }
        throw new AssertionError("no failregex in " + filter);
    }

    @Test
    void logLimiterCountsRepeatsBetweenLines() {
        SecurityLog.Limiter l = new SecurityLog.Limiter();
        assertEquals(1, l.hit(0, 10_000));
        assertEquals(0, l.hit(1_000, 10_000));
        assertEquals(0, l.hit(9_999, 10_000));
        assertEquals(3, l.hit(10_000, 10_000), "the two suppressed events plus this one");
    }
}
