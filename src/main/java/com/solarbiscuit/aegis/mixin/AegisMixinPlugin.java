package com.solarbiscuit.aegis.mixin;

import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.solarbiscuit.aegis.Features;
import com.solarbiscuit.aegis.compat.ModCompat;

/**
 * Decides per mixin (by package) whether it is applied, and sets the few JVM properties that must exist before the
 * classes reading them are loaded.
 */
public class AegisMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger("Aegis");
    private static final String ROOT = "com.solarbiscuit.aegis.mixin.";

    @Override
    public void onLoad(String mixinPackage) {
        Features.init();
        for (String note : ModCompat.notes()) {
            LOGGER.warn(note);
        }

        if (Features.LIMITS && System.getProperty("forge.disablePacketCompressionDebug") == null) {
            // Forge's vanilla CompressionEncoder would hex-dump every packet above 8 MiB into the log.
            System.setProperty("forge.disablePacketCompressionDebug", "true");
        }
        LOGGER.info("Aegis modules: {}", Features.describe());
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(ROOT)) {
            return true;
        }
        String name = mixinClassName.substring(ROOT.length());
        String pkg = name.substring(0, name.indexOf('.'));
        return switch (pkg) {
            case "access" -> true;
            case "frame" -> Features.FRAME_DECODER || Features.PREPENDER;
            case "compression" -> Features.COMPRESSION;
            case "encryption" -> Features.ENCRYPTION;
            case "varint" -> Features.FAST_VARINT;
            case "leaks" -> name.startsWith("leaks.LegacyQuery") ? Features.LEGACY_QUERY_FIX : Features.HEAP_COPY_PAYLOADS;
            case "flush" -> Features.FLUSH;
            case "disconnect" -> Features.NON_BLOCKING_DISCONNECT;
            case "tracking" -> name.startsWith("tracking.ChunkMapEntityLookup") ? Features.CHUNK_ENTITY_LOOKUP : Features.TRACKER_FILTER;
            case "limits" -> name.startsWith("limits.PacketEncoderLimit") ? Features.OUTBOUND_PACKET_LIMIT : Features.LIMITS;
            case "timeouts" -> Features.TIMEOUTS;
            case "login" -> Features.LOGIN_COMPRESSION_ORDER;
            case "security" -> Features.CONNECTION_THROTTLE;
            default -> true;
        };
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
