package dev.ic.timerlpe;

/**
 * Drop-in replacement for {@code sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET}.
 *
 * <p>Newer Android ART releases removed the legacy offset constants from
 * {@code sun.misc.Unsafe}; io.airlift:aircompressor reads that field directly,
 * so the OTA payload's zstd chunks failed with NoSuchFieldError on those
 * devices.  The build ships a patched aircompressor jar whose field reads
 * point here instead.  The value is computed once through the public
 * {@code arrayBaseOffset(Class)} method.
 */
public final class UnsafeCompat {
    public static final int ARRAY_BYTE_BASE_OFFSET;

    static {
        int value;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            value = (Integer) unsafeClass
                    .getMethod("arrayBaseOffset", Class.class)
                    .invoke(unsafe, byte[].class);
        } catch (Throwable ignored) {
            /* ART and HotSpot both use a 16-byte array header; this only
             * matters if reflection is blocked by a hardened ROM. */
            value = 16;
        }
        ARRAY_BYTE_BASE_OFFSET = value;
    }

    private UnsafeCompat() {
    }
}
