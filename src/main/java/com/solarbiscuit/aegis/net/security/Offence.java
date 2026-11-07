package com.solarbiscuit.aegis.net.security;

/**
 * Why a connection was refused or closed. The name is the {@code reason=} field of the security log; the codes are part
 * of its documented format, so they are never renamed.
 */
public enum Offence {
    /** Too many new connections from one address. Not a ban reason: busy NATs can hit it. */
    THROTTLE_RATE(false),
    /** Too many connections from one address that have not logged in. Not a ban reason. */
    THROTTLE_PENDING(false),
    /** Refused because the address is serving a ban for an earlier offence. */
    BANNED(false),
    /** A frame length header that is not a valid VarInt (longer than 5 bytes, or negative). */
    BAD_FRAME(true),
    /** A frame larger than allowed (before login: the vanilla 2 MiB). */
    OVERSIZED_FRAME(true),
    /** A compressed packet with an impossible header (negative or below-threshold size, truncated, wrong length). */
    BAD_COMPRESSION(true),
    /** A compressed packet claiming more than the limit or more than deflate can produce from its size. */
    COMPRESSION_BOMB(true),
    /** A zlib stream asking for a preset dictionary (nothing legitimate sends one; it hung Netty threads before 0.1.2). */
    PRESET_DICTIONARY(true);

    /** Whether the sender is banned for it (a vanilla or Forge client never does it). */
    public final boolean bans;

    Offence(boolean bans) {
        this.bans = bans;
    }
}
