package dev.ic.timerlpe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Compatibility shim for io.airlift:aircompressor.
 *
 * <p>Two Android ART changes broke the upstream zstd decoder:
 * <ul>
 *   <li>{@code sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET} was removed (field);</li>
 *   <li>{@code sun.misc.Unsafe.copyMemory(Object,long,Object,long,long)} was
 *       removed (method) — Android 16 keeps only the address overload.</li>
 * </ul>
 *
 * <p>The build ships an aircompressor jar whose Unsafe field reads and method
 * calls are rewritten to this class.  All hot operations run on the byte[]
 * bases the zstd code actually uses, with an array-base offset computed at
 * startup; the rare non-array bases fall back to the platform Unsafe via
 * reflection.
 */
public final class UnsafeCompat {
    public static final int ARRAY_BYTE_BASE_OFFSET;

    private static final Object UNSAFE;
    private static final Method M_GET_BYTE, M_GET_SHORT, M_GET_INT, M_GET_LONG;
    private static final Method M_PUT_BYTE, M_PUT_SHORT, M_PUT_INT, M_PUT_LONG;
    private static final Method M_COPY_ADDR, M_FIELD_OFFSET;

    static {
        int value = 16;
        Object unsafe = null;
        Method gb = null, gs = null, gi = null, gl = null;
        Method pb = null, ps = null, pi = null, pl = null;
        Method copy = null, fieldOff = null;
        try {
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = uc.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
            value = (Integer) uc.getMethod("arrayBaseOffset", Class.class)
                    .invoke(unsafe, byte[].class);
            Class<?> obj = Object.class;
            gb = uc.getMethod("getByte", obj, long.class);
            gs = uc.getMethod("getShort", obj, long.class);
            gi = uc.getMethod("getInt", obj, long.class);
            gl = uc.getMethod("getLong", obj, long.class);
            pb = uc.getMethod("putByte", obj, long.class, byte.class);
            ps = uc.getMethod("putShort", obj, long.class, short.class);
            pi = uc.getMethod("putInt", obj, long.class, int.class);
            pl = uc.getMethod("putLong", obj, long.class, long.class);
            try {
                copy = uc.getMethod("copyMemory", long.class, long.class, long.class);
            } catch (NoSuchMethodException ignored) { }
            fieldOff = uc.getMethod("objectFieldOffset", Field.class);
        } catch (Throwable ignored) {
            /* Keep the array-header fallback; getters below still work on
             * byte[] bases without any platform Unsafe. */
        }
        ARRAY_BYTE_BASE_OFFSET = value;
        UNSAFE = unsafe;
        M_GET_BYTE = gb; M_GET_SHORT = gs; M_GET_INT = gi; M_GET_LONG = gl;
        M_PUT_BYTE = pb; M_PUT_SHORT = ps; M_PUT_INT = pi; M_PUT_LONG = pl;
        M_COPY_ADDR = copy; M_FIELD_OFFSET = fieldOff;
    }

    private UnsafeCompat() {
    }

    private static int idx(long offset) {
        return (int) (offset - ARRAY_BYTE_BASE_OFFSET);
    }

    /* Upstream Unsafe access is unchecked: the zstd decoder reads a few bytes
     * past the logical end of its input buffer (harmless heap padding on a
     * real Unsafe).  Emulate that with zero bytes instead of throwing. */
    private static int at(byte[] b, int i) {
        return (i >= 0 && i < b.length) ? (b[i] & 0xFF) : 0;
    }

    private static Object call(Method m, Object... args) {
        if (m == null) throw new IllegalStateException("unsupported Unsafe call");
        try {
            return m.invoke(UNSAFE, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unsupported Unsafe call", e);
        }
    }

    // ---- reads -------------------------------------------------------
    public static byte getByte(Object u, Object base, long off) {
        if (base instanceof byte[]) return (byte) at((byte[]) base, idx(off));
        return (Byte) call(M_GET_BYTE, base, off);
    }

    public static short getShort(Object u, Object base, long off) {
        if (base instanceof byte[]) {
            byte[] b = (byte[]) base;
            int i = idx(off);
            return (short) (at(b, i) | (at(b, i + 1) << 8));
        }
        return (Short) call(M_GET_SHORT, base, off);
    }

    public static int getInt(Object u, Object base, long off) {
        if (base instanceof byte[]) {
            byte[] b = (byte[]) base;
            int i = idx(off);
            return at(b, i) | (at(b, i + 1) << 8)
                    | (at(b, i + 2) << 16) | (at(b, i + 3) << 24);
        }
        return (Integer) call(M_GET_INT, base, off);
    }

    public static long getLong(Object u, Object base, long off) {
        if (base instanceof byte[]) {
            byte[] b = (byte[]) base;
            int i = idx(off);
            long lo = at(b, i) | (at(b, i + 1) << 8)
                    | (at(b, i + 2) << 16) | ((long) at(b, i + 3) << 24);
            long hi = at(b, i + 4) | (at(b, i + 5) << 8)
                    | (at(b, i + 6) << 16) | ((long) at(b, i + 7) << 24);
            return lo | (hi << 32);
        }
        return (Long) call(M_GET_LONG, base, off);
    }

    // ---- writes ------------------------------------------------------
    private static void set(byte[] b, int i, int v) {
        if (i >= 0 && i < b.length) b[i] = (byte) v;
    }

    public static void putByte(Object u, Object base, long off, byte v) {
        if (base instanceof byte[]) {
            set((byte[]) base, idx(off), v);
            return;
        }
        call(M_PUT_BYTE, base, off, v);
    }

    public static void putShort(Object u, Object base, long off, short v) {
        if (base instanceof byte[]) {
            byte[] b = (byte[]) base;
            int i = idx(off);
            set(b, i, v);
            set(b, i + 1, v >>> 8);
            return;
        }
        call(M_PUT_SHORT, base, off, v);
    }

    public static void putInt(Object u, Object base, long off, int v) {
        if (base instanceof byte[]) {
            byte[] b = (byte[]) base;
            int i = idx(off);
            set(b, i, v);
            set(b, i + 1, v >>> 8);
            set(b, i + 2, v >>> 16);
            set(b, i + 3, v >>> 24);
            return;
        }
        call(M_PUT_INT, base, off, v);
    }

    public static void putLong(Object u, Object base, long off, long v) {
        if (base instanceof byte[]) {
            byte[] b = (byte[]) base;
            int i = idx(off);
            set(b, i, (int) v);
            set(b, i + 1, (int) (v >>> 8));
            set(b, i + 2, (int) (v >>> 16));
            set(b, i + 3, (int) (v >>> 24));
            set(b, i + 4, (int) (v >>> 32));
            set(b, i + 5, (int) (v >>> 40));
            set(b, i + 6, (int) (v >>> 48));
            set(b, i + 7, (int) (v >>> 56));
            return;
        }
        call(M_PUT_LONG, base, off, v);
    }

    public static void copyMemory(Object u, Object srcBase, long srcOff,
                                  Object dstBase, long dstOff, long len) {
        if (srcBase instanceof byte[] && dstBase instanceof byte[]) {
            byte[] src = (byte[]) srcBase;
            byte[] dst = (byte[]) dstBase;
            int si = idx(srcOff);
            int di = idx(dstOff);
            for (int k = 0; k < len; k++) {
                int v = at(src, si + k);
                set(dst, di + k, v);
            }
            return;
        }
        if (srcBase == null && dstBase == null) {
            call(M_COPY_ADDR, srcOff, dstOff, len);
            return;
        }
        throw new IllegalStateException("UnsafeCompat.copyMemory: unsupported bases");
    }

    public static long objectFieldOffset(Object u, Field f) {
        return (Long) call(M_FIELD_OFFSET, f);
    }
}
