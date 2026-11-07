# Aegis on newer Java versions

Aegis runs on Java 17 and later. Newer Java versions add one thing: native compression on Windows.

| Platform | Java 17-21 | Java 22+ |
|---|---|---|
| Linux x86_64 / aarch64, macOS arm64 | libdeflate (velocity-native, JNI) | same |
| Windows x64 | java.util.zip | libdeflate 1.26 (bundled, through Java's FFM API) |
| Anything else | java.util.zip | java.util.zip |

On Windows with Java 22+, compression is about 4.9x faster and decompression about 1.8x faster than Java's zlib, with
slightly smaller output (PipelineBench, Temurin 25). The startup log shows which one is used:
`Compression: libdeflate 1.26 (FFM)` or `Compression: Java`. `pipeline.natives=false` in `config/aegis.properties`
turns all native code off.

The Windows library is copied to `%TEMP%\aegis-natives\` on first use and reused after that.

## Native access warnings (Java 24+)

Java 24 and later print a warning the first time a mod loads native code:

```
WARNING: A restricted method in java.lang.foreign.SymbolLookup has been called
WARNING: java.lang.foreign.SymbolLookup::libraryLookup has been called by ... in module aegis (...)
WARNING: Use --enable-native-access=aegis to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

The warning is harmless. The flag it suggests does not work for Forge mods: Java answers
`Unknown module: aegis specified to --enable-native-access`, because Forge loads mods into a module layer of its own and
the flag only reaches modules Java itself started with. `--enable-native-access=ALL-UNNAMED` does not cover mods either.

To silence these warnings for every mod, add this to `user_jvm_args.txt`:

```
--illegal-native-access=allow
```

`--illegal-native-access=deny` blocks native code. Aegis then uses its Java implementations and logs one line saying so.
Other mods that need native code may not cope as well.

Java 24+ also warns about `sun.misc.Unsafe`. Minecraft's Netty and several mods use it, but Aegis does not.
`--sun-misc-unsafe-memory-access=allow` silences those warnings. Do not set it to `deny`: Netty then loses its direct
memory access, and native compression turns itself off.
