package com.solarbiscuit.aegis.net;

import java.util.concurrent.TimeUnit;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.ReadTimeoutHandler;

/**
 * The longer read timeout only applies once a connection logs in. Handshake and server-list ping (STATUS) connections
 * keep vanilla's 30 s, so the client's server list gives up on a dead server just as fast as before, and an
 * unauthenticated connection cannot hold a server socket open for the longer time.
 */
public final class ReadTimeouts {
    private ReadTimeouts() {
    }

    /** Marks a timeout handler Aegis already raised, so it is replaced only once. */
    public static final class Raised extends ReadTimeoutHandler {
        public Raised(int seconds) {
            super(seconds, TimeUnit.SECONDS);
        }
    }

    /**
     * Replaces vanilla's "timeout" handler with one of {@code seconds}. Leaves any other handler under that name (another
     * mod's) alone. Returns whether it replaced one.
     */
    public static boolean raise(Channel channel, int seconds) {
        ChannelPipeline pipeline = channel.pipeline();
        ChannelHandler current = pipeline.get("timeout");
        if (current == null || current.getClass() != ReadTimeoutHandler.class) {
            return false;
        }
        pipeline.replace("timeout", "timeout", new Raised(seconds));
        return true;
    }
}
