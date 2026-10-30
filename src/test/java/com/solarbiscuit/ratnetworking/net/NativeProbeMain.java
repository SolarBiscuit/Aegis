package com.solarbiscuit.ratnetworking.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.internal.PlatformDependent;

/** Prints why velocity-native picks (or does not pick) its native variants on this machine. */
public final class NativeProbeMain {
    private NativeProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("os.name=" + System.getProperty("os.name") + " os.arch=" + System.getProperty("os.arch"));
        System.out.println("netty hasUnsafe=" + PlatformDependent.hasUnsafe() + " unsafeCause=" + PlatformDependent.getUnsafeUnavailabilityCause()
                + " directBufferPreferred=" + PlatformDependent.directBufferPreferred());
        ByteBuf test = Unpooled.directBuffer();
        System.out.println("Unpooled.directBuffer " + test.getClass().getSimpleName() + " hasMemoryAddress=" + test.hasMemoryAddress());
        test.release();
        Class<?> constraints = Class.forName("com.velocitypowered.natives.util.NativeConstraints");
        for (String f : new String[]{"NATIVES_ENABLED", "IS_AMD64", "IS_AARCH64", "CAN_GET_MEMORYADDRESS", "IS_LINUX", "IS_MUSL_LIBC"}) {
            java.lang.reflect.Field field = constraints.getDeclaredField(f);
            field.setAccessible(true);
            System.out.println("NativeConstraints." + f + "=" + field.get(null));
        }
        try {
            System.out.println("compress variant: " + com.velocitypowered.natives.util.Natives.compress.getLoadedVariant());
            System.out.println("cipher variant: " + com.velocitypowered.natives.util.Natives.cipher.getLoadedVariant());
        } catch (Throwable t) {
            t.printStackTrace(System.out);
        }
    }
}
