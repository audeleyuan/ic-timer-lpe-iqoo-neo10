# libs/

## aircompressor-0.27-android.jar

Patched copy of `io.airlift:aircompressor:0.27` (Apache-2.0, see the jar's
`META-INF/NOTICE`). Only one change was made:

* every `getstatic sun/misc/Unsafe.ARRAY_BYTE_BASE_OFFSET:I` is rewritten to
  `getstatic dev/ic/timerlpe/UnsafeCompat.ARRAY_BYTE_BASE_OFFSET:I`;
* every `sun.misc.Unsafe.getByte/getShort/getInt/getLong`,
  `putByte/putShort/putInt/putLong`, `copyMemory(Object,long,Object,long,long)`
  and `objectFieldOffset(Field)` call is rewritten to a static
  `dev.ic.timerlpe.UnsafeCompat.<same>(Object receiver, ...)`, which performs
  the byte[]-based operation without any platform Unsafe method.

Reason: newer Android ART releases removed the legacy offset constants from
`sun.misc.Unsafe`. The upstream zstd decoder reads that field directly, so
decompressing an OTA `payload.bin` crashed with:

```
NoSuchFieldError: No field ARRAY_BYTE_BASE_OFFSET of type I in class Lsun/misc/Unsafe
```

`UnsafeCompat` computes the array base with `Unsafe.arrayBaseOffset(byte[].class)`
and implements the object-offset operations on byte[] directly (newer ART also
removed the object-based `copyMemory`).

To regenerate, use `tool/patch_aircompressor/PatchAircompressor.java` (ASM 9.7.1):

```
javac -cp asm-9.7.1.jar PatchAircompressor.java
java -cp .:asm-9.7.1.jar PatchAircompressor aircompressor-0.27.jar aircompressor-0.27-android.jar
```

## jniLibs/arm64-v8a/libextract.so

Native `REPLACE_ZSTD` decoder used by `ZstdNative.kt`, taken from the
ghostlock-app fork's `tools/extract_rs` build (Apache-2.0).  It is invoked as
`libextract.so --zstd-decode <in> <out> <expected>` and links the Rust `zstd`
crate, so full-OTA zstd payloads decode without any `sun.misc.Unsafe` call.

`android.packaging.jniLibs.useLegacyPackaging = true` is required: the installer
must extract it to `nativeLibraryDir` because it is executed, not dlopen'd.

sha256: `4dfc536d5ae87347ab07df01ce477de57309246f0ee61a56ca1bc6146742c6c3`
