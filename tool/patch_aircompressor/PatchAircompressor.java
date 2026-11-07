import org.objectweb.asm.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;

/**
 * Rewrites io.airlift:aircompressor's direct sun.misc.Unsafe usage so it keeps
 * working on newer Android ART releases:
 *
 *  - field  sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET  -> dev.ic.timerlpe.UnsafeCompat
 *  - method sun.misc.Unsafe.<get|put><Type>(Object,long[,v]),
 *           copyMemory(Object,long,Object,long,long), objectFieldOffset(Field)
 *           -> static dev.ic.timerlpe.UnsafeCompat.<same>(Object receiver, ...)
 *
 * Newer ART keeps only the address forms of copyMemory and removed the legacy
 * offset constant; the compat class implements the byte[]-based operations.
 *
 * Usage: java PatchAircompressor <in.jar> <out.jar>
 */
public final class PatchAircompressor {
    private static final String OLD_OWNER = "sun/misc/Unsafe";
    private static final String OLD_NAME = "ARRAY_BYTE_BASE_OFFSET";
    private static final String NEW_OWNER = "dev/ic/timerlpe/UnsafeCompat";

    private static final Set<String> METHODS = new HashSet<>(Arrays.asList(
            "getByte", "getShort", "getInt", "getLong",
            "putByte", "putShort", "putInt", "putLong",
            "copyMemory", "objectFieldOffset"));

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: PatchAircompressor <in.jar> <out.jar>");
            System.exit(2);
        }
        int fields = 0, calls = 0, classes = 0;
        try (JarInputStream in = new JarInputStream(new FileInputStream(args[0]));
             JarOutputStream out = new JarOutputStream(new FileOutputStream(args[1]))) {
            JarEntry e;
            byte[] buf = new byte[65536];
            while ((e = in.getNextJarEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".DSA") ||
                         name.endsWith(".RSA") || name.endsWith(".EC"))) {
                    continue; // drop signatures: the jar is modified
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                byte[] data = bo.toByteArray();
                if (name.endsWith(".class")) {
                    classes++;
                    int[] hits = new int[2];
                    data = rewrite(data, hits);
                    fields += hits[0];
                    calls += hits[1];
                }
                JarEntry ne = new JarEntry(name);
                ne.setTime(e.getTime());
                out.putNextEntry(ne);
                out.write(data);
                out.closeEntry();
            }
        }
        System.out.println("classes=" + classes + " fieldRefs=" + fields +
                " methodCalls=" + calls + " -> " + args[1]);
    }

    private static byte[] rewrite(byte[] cls, int[] hits) {
        ClassReader cr = new ClassReader(cls);
        ClassWriter cw = new ClassWriter(0);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, ex)) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
                        if (owner.equals(OLD_OWNER) && fname.equals(OLD_NAME) && fdesc.equals("I")) {
                            hits[0]++;
                            super.visitFieldInsn(opcode, NEW_OWNER, fname, fdesc);
                        } else {
                            super.visitFieldInsn(opcode, owner, fname, fdesc);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                                                String mdesc, boolean itf) {
                        if (owner.equals(OLD_OWNER) && METHODS.contains(mname)) {
                            /* Prepend the receiver so the static shim keeps the
                             * original stack shape: (Object receiver, args...). */
                            String nd = "(Ljava/lang/Object;" + mdesc.substring(1);
                            hits[1]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, NEW_OWNER,
                                    mname, nd, false);
                        } else {
                            super.visitMethodInsn(opcode, owner, mname, mdesc, itf);
                        }
                    }
                };
            }
        };
        cr.accept(cv, 0);
        return (hits[0] == 0 && hits[1] == 0) ? cls : cw.toByteArray();
    }
}
