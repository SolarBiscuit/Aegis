package com.solarbiscuit.aegis.net;

import io.netty.handler.codec.DecoderException;

/**
 * A decoder error without a stack trace. Malformed or hostile input should not cost a stack walk per packet or fill the
 * log with traces (from Krypton).
 */
public class QuietDecoderException extends DecoderException {
    public static final QuietDecoderException BAD_LENGTH = new QuietDecoderException("Bad packet length");
    public static final QuietDecoderException VARINT_TOO_BIG = new QuietDecoderException("VarInt too big");

    public QuietDecoderException(String message) {
        super(message);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
