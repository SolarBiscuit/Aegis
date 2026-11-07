package com.solarbiscuit.aegis.net.security;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.solarbiscuit.aegis.Features;
import com.solarbiscuit.aegis.config.AegisConfig;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * Server-side connection security: the pre-login throttle, bans for protocol offences, and the security log. Only real
 * server connections are looked at (never singleplayer's in-memory connection, never the client side).
 */
public final class Security {
    private static final Logger LOGGER = LogManager.getLogger("Aegis");
    /** Set on a connection that counts as pending in the throttle; cleared (once) when it reaches PLAY or closes. */
    private static final AttributeKey<AtomicBoolean> PENDING = AttributeKey.valueOf("aegis_pending");

    private static volatile boolean initialised;
    private static ConnectionThrottle throttle; // null = throttle off
    private static SecurityLog log;

    private Security() {
    }

    private static void init() {
        if (initialised) {
            return;
        }
        synchronized (Security.class) {
            if (initialised) {
                return;
            }
            if (Features.CONNECTION_THROTTLE) {
                List<ConnectionThrottle.Cidr> trusted = new ArrayList<>();
                for (String part : AegisConfig.trustedAddresses.split(",")) {
                    if (part.isBlank()) {
                        continue;
                    }
                    try {
                        trusted.add(ConnectionThrottle.Cidr.parse(part));
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("security.trustedAddresses: ignoring '{}' ({})", part.trim(), e.getMessage());
                    }
                }
                throttle = new ConnectionThrottle(AegisConfig.throttleConnections, AegisConfig.throttleWindowSeconds * 1000L,
                        AegisConfig.maxPendingPerAddress, AegisConfig.offenceBanSeconds * 1000L, trusted, System::currentTimeMillis);
            }
            log = new SecurityLog(Features.BLOCK_LOG ? logsDir().resolve("aegis-security.log") : null, System::currentTimeMillis);
            initialised = true;
        }
    }

    private static Path logsDir() {
        try {
            Path dir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
            if (dir != null) {
                return dir.resolve("logs");
            }
        } catch (Throwable ignored) {
            // outside of FML
        }
        return Paths.get("logs");
    }

    private static InetAddress address(Channel channel) {
        SocketAddress remote = channel.remoteAddress();
        return remote instanceof InetSocketAddress isa && isa.getAddress() != null ? isa.getAddress() : null;
    }

    /**
     * A server connection became active. Closes it right away if its address is over a limit or banned; otherwise it
     * counts as pending until {@link #loggedIn} or until it closes.
     */
    public static void onConnect(Channel channel) {
        init();
        ConnectionThrottle t = throttle;
        InetAddress address = address(channel);
        if (t == null || address == null) {
            return;
        }
        ConnectionThrottle.Verdict verdict = t.tryAccept(address);
        switch (verdict) {
            case ALLOW -> {
                AtomicBoolean pending = new AtomicBoolean(true);
                channel.attr(PENDING).set(pending);
                channel.closeFuture().addListener(f -> {
                    if (pending.getAndSet(false)) {
                        t.leftPending(address);
                    }
                });
            }
            case RATE -> refuse(channel, address, Offence.THROTTLE_RATE);
            case PENDING -> refuse(channel, address, Offence.THROTTLE_PENDING);
            case BANNED -> refuse(channel, address, Offence.BANNED);
            case UNTRACKED -> {
            }
        }
    }

    private static void refuse(Channel channel, InetAddress address, Offence reason) {
        channel.close();
        log.record(address, reason, null);
    }

    /** A server connection reached PLAY: it no longer counts against its address's pending limit. */
    public static void loggedIn(Channel channel) {
        AtomicBoolean pending = channel.attr(PENDING).get();
        ConnectionThrottle t = throttle;
        if (pending != null && t != null && pending.getAndSet(false)) {
            InetAddress address = address(channel);
            if (address != null) {
                t.leftPending(address);
            }
        }
    }

    /**
     * The decoder of a server connection rejected what the client sent. Logs it and, for offences no real client
     * commits, bans the address for security.offenceBanSeconds. The caller still throws, which closes the connection.
     */
    public static void offence(Channel channel, Offence reason, String detail) {
        if (!Features.SECURITY_LOG_ANY) {
            return;
        }
        init();
        InetAddress address = address(channel);
        if (address == null) {
            return;
        }
        ConnectionThrottle t = throttle;
        if (address.isLoopbackAddress() || (t != null && t.isExempt(address))) {
            return; // loopback / trusted: neither banned nor logged (behind a proxy this would be the proxy)
        }
        if (reason.bans && t != null) {
            t.ban(address);
        }
        log.record(address, reason, detail);
    }
}
