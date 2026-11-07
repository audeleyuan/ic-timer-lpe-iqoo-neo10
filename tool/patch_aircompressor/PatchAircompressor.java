import org.objectweb.asm.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;

/**
 * Rewrites io.airlift:aircompressor references to
 * sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET (removed in newer Android ART) so
 * they read dev.ic.timerlpe.UnsafeCompat.ARRAY_BYTE_BASE_OFFSET instead.
 *
 * Usage: java PatchAircompressor <in.jar> <out.jar>
 */
public final class PatchAircompressor {
    private static final String OLD_OWNER = "sun/misc/Unsafe";
    private static final String OLD_NAME = "ARRAY_BYTE_BASE_OFFSET";
    private static final String NEW_OWNER = "dev/ic/timerlpe/UnsafeCompat";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: PatchAircompressor <in.jar> <out.jar>");
            System.exit(2);
        }
        int patched = 0;
        int classes = 0;
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
                    byte[] rewritten = rewrite(data);
                    if (rewritten != data) patched++;
                    data = rewritten;
                }
                JarEntry ne = new JarEntry(name);
                ne.setTime(e.getTime());
                out.putNextEntry(ne);
                out.write(data);
                out.closeEntry();
            }
        }
        System.out.println("classes=" + classes + " patched=" + patched + " -> " + args[1]);
    }

    private static byte[] rewrite(byte[] cls) {
        ClassReader cr = new ClassReader(cls);
        ClassWriter cw = new ClassWriter(0);
        final int[] hits = {0};
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
                };
            }
        };
        cr.accept(cv, 0);
        return hits[0] == 0 ? cls : cw.toByteArray();
    }
}
