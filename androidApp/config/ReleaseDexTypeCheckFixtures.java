import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableField;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates the ReleaseDexTypeCheck fixture set (#1368): a tiny boot class hierarchy, one APK
 * whose dex obeys the reference-type rule the checker enforces, and one APK that breaks it the
 * way R8 broke SyncManager.pushLocalChanges in #1365: a register holding String where the
 * instruction requires Throwable. :androidApp:testReleaseDexTypes runs the checker over these
 * and requires the valid APK to pass, the invalid APK to fail, and a missing-artifact run to
 * fail closed, so the checker cannot silently stop detecting the #1365/#1368 failure class.
 *
 * The fixtures are synthetic and SDK-free: boot.jar carries only class-file headers for the
 * java/lang types the checker's assignability walk needs (String must resolve, or an
 * unresolved type is skipped as ART-deferred and the invalid fixture would wrongly pass).
 *
 * Usage: java -cp <dexlib2 classpath> ReleaseDexTypeCheckFixtures.java <output-dir>
 * Writes <output-dir>/boot.jar, <output-dir>/valid/valid.apk, <output-dir>/invalid/invalid.apk.
 */
public final class ReleaseDexTypeCheckFixtures {
    private static final String CHECKED = "Lfixture/Checked;";
    private static final int PUBLIC = AccessFlags.PUBLIC.getValue();
    private static final int STATIC = AccessFlags.STATIC.getValue();

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: ReleaseDexTypeCheckFixtures.java <output-dir>");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        Files.createDirectories(out.resolve("no-artifacts"));
        writeBootJar(out.resolve("boot.jar"));
        writeApk(out.resolve("valid").resolve("valid.apk"), validDex());
        writeApk(out.resolve("invalid").resolve("invalid.apk"), invalidDex());
        System.out.println("Wrote checker fixtures to " + out + " (boot.jar, valid/valid.apk, invalid/invalid.apk)");
    }

    /** Correct reference typing: Throwable-typed registers are thrown and passed where Throwable is expected. */
    private static byte[] validDex() throws IOException {
        List<Field> fields = checkedFields();
        List<Method> methods = new ArrayList<>();
        methods.add(method("run", Collections.emptyList(), "Ljava/lang/String;", 2,
                new ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, fieldRef("t", "Ljava/lang/Throwable;")),
                invoke(0, "handle", "Ljava/lang/Throwable;", "V"),
                new ImmutableInstruction21c(Opcode.SGET_OBJECT, 1, fieldRef("e", "Ljava/lang/Exception;")),
                invoke(1, "handle", "Ljava/lang/Throwable;", "V"),
                new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference("x")),
                invoke(0, "take", "Ljava/lang/String;", "V"),
                new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference("y")),
                new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)));
        methods.add(method("fail", Collections.emptyList(), "V", 1,
                new ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, fieldRef("e", "Ljava/lang/Exception;")),
                new ImmutableInstruction11x(Opcode.THROW, 0)));
        methods.add(method("take", Collections.singletonList("Ljava/lang/String;"), "V", 1,
                new ImmutableInstruction10x(Opcode.RETURN_VOID)));
        methods.add(method("handle", Collections.singletonList("Ljava/lang/Throwable;"), "V", 1,
                new ImmutableInstruction10x(Opcode.RETURN_VOID)));
        return dex(fields, methods);
    }

    /**
     * The #1365/#1368 failure class: a String is passed as a Throwable argument and thrown as
     * a Throwable. ART rejects this bytecode with VerifyError at class load; the checker must
     * reject both methods.
     */
    private static byte[] invalidDex() throws IOException {
        List<Method> methods = new ArrayList<>();
        methods.add(method("run", Collections.emptyList(), "V", 1,
                new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference("x")),
                invoke(0, "handle", "Ljava/lang/Throwable;", "V"),
                new ImmutableInstruction10x(Opcode.RETURN_VOID)));
        methods.add(method("boom", Collections.emptyList(), "V", 1,
                new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference("x")),
                new ImmutableInstruction11x(Opcode.THROW, 0)));
        methods.add(method("handle", Collections.singletonList("Ljava/lang/Throwable;"), "V", 1,
                new ImmutableInstruction10x(Opcode.RETURN_VOID)));
        return dex(Collections.emptyList(), methods);
    }

    private static List<Field> checkedFields() {
        List<Field> fields = new ArrayList<>();
        fields.add(new ImmutableField(CHECKED, "t", "Ljava/lang/Throwable;", PUBLIC | STATIC,
                null, Collections.emptySet(), Collections.emptySet()));
        fields.add(new ImmutableField(CHECKED, "e", "Ljava/lang/Exception;", PUBLIC | STATIC,
                null, Collections.emptySet(), Collections.emptySet()));
        return fields;
    }

    private static ImmutableFieldReference fieldRef(String name, String type) {
        return new ImmutableFieldReference(CHECKED, name, type);
    }

    private static Instruction invoke(int register, String name, String parameter, String returnType) {
        return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, register, 0, 0, 0, 0,
                new ImmutableMethodReference(CHECKED, name, Collections.singletonList(parameter), returnType));
    }

    private static Method method(String name, List<String> parameters, String returnType,
            int registers, Instruction... code) {
        List<MethodParameter> methodParameters = new ArrayList<>();
        for (String parameter : parameters) {
            methodParameters.add(new ImmutableMethodParameter(parameter, Collections.emptySet(), null));
        }
        MethodImplementation implementation = new ImmutableMethodImplementation(
                registers, Arrays.asList(code), Collections.emptyList(), Collections.emptyList());
        return new ImmutableMethod(CHECKED, name, methodParameters, returnType, PUBLIC | STATIC,
                Collections.emptySet(), Collections.emptySet(), implementation);
    }

    private static byte[] dex(List<Field> fields, List<Method> methods) throws IOException {
        DexPool pool = new DexPool(Opcodes.forApi(26));
        pool.internClass(new ImmutableClassDef(CHECKED, PUBLIC, "Ljava/lang/Object;",
                Collections.emptyList(), null, Collections.emptySet(), fields, methods));
        MemoryDataStore store = new MemoryDataStore();
        pool.writeTo(store);
        return store.getData();
    }

    private static void writeApk(Path apk, byte[] dex) throws IOException {
        Files.createDirectories(apk.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk))) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(dex);
            zip.closeEntry();
        }
    }

    /**
     * Minimal .class headers (constant pool, this/super class, empty bodies) for the java/lang
     * types the checker's assignability walk resolves. Header-only is enough: the checker's
     * ClassFileHeaderProvider reads exactly class names and superclass chains.
     */
    private static void writeBootJar(Path jar) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            putClass(zip, "java/lang/Object", null);
            putClass(zip, "java/lang/String", "java/lang/Object");
            putClass(zip, "java/lang/Throwable", "java/lang/Object");
            putClass(zip, "java/lang/Exception", "java/lang/Throwable");
        }
    }

    private static void putClass(ZipOutputStream zip, String name, String superName) throws IOException {
        zip.putNextEntry(new ZipEntry(name + ".class"));
        zip.write(classHeader(name, superName));
        zip.closeEntry();
    }

    private static byte[] classHeader(String name, String superName) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);  // minor version
            out.writeShort(52); // major version (Java 8)
            // Constant pool: 1 = this class name, 2 = this class, 3 = super name, 4 = super class.
            out.writeShort(superName == null ? 3 : 5);
            out.writeByte(1);
            out.writeUTF(name);
            out.writeByte(7);
            out.writeShort(1);
            if (superName != null) {
                out.writeByte(1);
                out.writeUTF(superName);
                out.writeByte(7);
                out.writeShort(3);
            }
            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2);      // this_class
            out.writeShort(superName == null ? 0 : 4); // super_class
            out.writeShort(0);      // interfaces
            out.writeShort(0);      // fields
            out.writeShort(0);      // methods
            out.writeShort(0);      // attributes
        }
        return bytes.toByteArray();
    }
}
