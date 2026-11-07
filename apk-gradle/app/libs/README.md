# libs/

## aircompressor-0.27-android.jar

Patched copy of `io.airlift:aircompressor:0.27` (Apache-2.0, see the jar's
`META-INF/NOTICE`). Only one change was made:

* every `getstatic sun/misc/Unsafe.ARRAY_BYTE_BASE_OFFSET:I` is rewritten to
  `getstatic dev/ic/timerlpe/UnsafeCompat.ARRAY_BYTE_BASE_OFFSET:I`.

Reason: newer Android ART releases removed the legacy offset constants from
`sun.misc.Unsafe`. The upstream zstd decoder reads that field directly, so
decompressing an OTA `payload.bin` crashed with:

```
NoSuchFieldError: No field ARRAY_BYTE_BASE_OFFSET of type I in class Lsun/misc/Unsafe
```

`UnsafeCompat` computes the same value with the public
`Unsafe.arrayBaseOffset(byte[].class)` method.

To regenerate, use `tool/patch_aircompressor/PatchAircompressor.java` (ASM 9.7.1):

```
javac -cp asm-9.7.1.jar PatchAircompressor.java
java -cp .:asm-9.7.1.jar PatchAircompressor aircompressor-0.27.jar aircompressor-0.27-android.jar
```
