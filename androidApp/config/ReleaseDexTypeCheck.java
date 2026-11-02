import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.analysis.AnalyzedInstruction;
import com.android.tools.smali.dexlib2.analysis.ClassPath;
import com.android.tools.smali.dexlib2.analysis.ClassProvider;
import com.android.tools.smali.dexlib2.analysis.DexClassProvider;
import com.android.tools.smali.dexlib2.analysis.MethodAnalyzer;
import com.android.tools.smali.dexlib2.analysis.RegisterType;
import com.android.tools.smali.dexlib2.analysis.TypeProto;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks minified release dex code against the reference-type rule that ART's verifier enforced
 * in #1365 and #1368: a register an instruction throws, passes, stores or returns must hold a
 * type assignable to the type that instruction requires. ART rejects a class that breaks this
 * with VerifyError when the class is loaded. R8 from AGP 9.5 emitted such code for
 * SyncManager.pushLocalChanges, which blocked app startup on Android 17.
 *
 * Register types come from smali's dexlib2 analyzer. The compile SDK's android.jar supplies the
 * framework class hierarchy. Types that do not resolve are skipped, because ART defers those
 * checks to run time instead of rejecting the class. This approximates one family of ART
 * verifier failures; it is not a full verifier.
 *
 * Usage: java -cp <dexlib2 classpath> ReleaseDexTypeCheck.java
 *            --boot <android.jar> --artifacts <dir> [--artifacts <dir>] [--mapping <mapping.txt>]
 * Exit status 1 when any artifact contains a mismatch or a method the analyzer cannot process.
 */
public final class ReleaseDexTypeCheck {
    private static final Pattern DEX_ENTRY = Pattern.compile("(^|/)classes\\d*\\.dex$");
    private static final Set<Opcode> INVOKES = Set.of(
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_SUPER, Opcode.INVOKE_DIRECT, Opcode.INVOKE_STATIC,
            Opcode.INVOKE_INTERFACE, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_SUPER_RANGE,
            Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_INTERFACE_RANGE);

    private final ClassPath classPath;
    private final List<Problem> problems = new ArrayList<>();
    private int methods;

    private ReleaseDexTypeCheck(ClassPath classPath) {
        this.classPath = classPath;
    }

    /** One rejected instruction, or a method the analyzer could not process. */
    private static final class Problem {
        final Method method;
        final String detail;

        Problem(Method method, String detail) {
            this.method = method;
            this.detail = detail;
        }

        String signature() {
            return method.getDefiningClass() + "->" + method.getName()
                    + "(" + String.join("", method.getParameterTypes()) + ")" + method.getReturnType();
        }
    }

    public static void main(String[] args) throws IOException {
        List<File> boot = new ArrayList<>();
        List<File> artifactDirs = new ArrayList<>();
        File mappingFile = null;
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) usage("missing value for " + args[i]);
            File value = new File(args[i + 1]);
            switch (args[i]) {
                case "--boot": boot.add(value); break;
                case "--artifacts": artifactDirs.add(value); break;
                case "--mapping": mappingFile = value; break;
                default: usage("unknown option " + args[i]);
            }
        }
        if (boot.isEmpty() || artifactDirs.isEmpty()) usage("--boot and --artifacts are required");

        List<File> artifacts = new ArrayList<>();
        for (File dir : artifactDirs) {
            if (!dir.isDirectory()) continue;
            try (Stream<java.nio.file.Path> files = Files.walk(dir.toPath())) {
                files.map(java.nio.file.Path::toFile)
                        .filter(f -> f.isFile() && (f.getName().endsWith(".apk") || f.getName().endsWith(".aab")))
                        .sorted()
                        .forEach(artifacts::add);
            }
        }
        if (artifacts.isEmpty()) {
            System.err.println("No Android release APK or AAB artifacts found in " + artifactDirs
                    + ". Run :androidApp:assembleRelease or :androidApp:bundleRelease first.");
            System.exit(1);
        }

        boolean failed = false;
        for (File artifact : artifacts) {
            failed |= !checkArtifact(artifact, boot, mappingFile);
        }
        System.exit(failed ? 1 : 0);
    }

    private static void usage(String message) {
        System.err.println("ReleaseDexTypeCheck: " + message);
        System.err.println("usage: --boot <android.jar> --artifacts <dir> [--artifacts <dir>] [--mapping <mapping.txt>]");
        System.exit(2);
    }

    private static boolean checkArtifact(File artifact, List<File> boot, File mappingFile) throws IOException {
        Opcodes opcodes = Opcodes.forApi(26);
        List<ClassProvider> providers = new ArrayList<>();
        for (File jar : boot) providers.add(new ClassFileHeaderProvider(jar));
        List<DexBackedDexFile> dexFiles = new ArrayList<>();
        try (ZipFile zip = new ZipFile(artifact)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !DEX_ENTRY.matcher(entry.getName()).find()) continue;
                try (InputStream in = new BufferedInputStream(zip.getInputStream(entry))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(opcodes, in);
                    dexFiles.add(dex);
                    providers.add(new DexClassProvider(dex));
                }
            }
        }
        if (dexFiles.isEmpty()) {
            System.err.println(artifact + ": no classes*.dex entries found.");
            return false;
        }

        ReleaseDexTypeCheck check = new ReleaseDexTypeCheck(new ClassPath(providers, false, ClassPath.NOT_ART));
        for (DexBackedDexFile dex : dexFiles) {
            for (ClassDef classDef : dex.getClasses()) {
                for (Method method : classDef.getMethods()) {
                    if (method.getImplementation() != null) check.checkMethod(method);
                }
            }
        }

        if (check.problems.isEmpty()) {
            System.out.printf("Verified reference types in %d methods of %s (%d dex files).%n",
                    check.methods, artifact, dexFiles.size());
            return true;
        }
        Map<String, List<Problem>> byMethod = new LinkedHashMap<>();
        check.problems.forEach(p -> byMethod.computeIfAbsent(p.signature(), k -> new ArrayList<>()).add(p));
        System.err.printf("%s: %d method(s) contain code that ART's verifier rejects with VerifyError, or that the "
                + "analyzer cannot check (see #1365 and #1368):%n", artifact, byMethod.size());
        Set<String> classes = new LinkedHashSet<>();
        check.problems.forEach(p -> classes.add(p.method.getDefiningClass()));
        Mapping mapping = Mapping.read(mappingFile, classes);
        byMethod.forEach((signature, methodProblems) -> {
            Method method = methodProblems.get(0).method;
            String original = mapping.original(method.getDefiningClass(), method.getName());
            System.err.println("  - " + (original == null ? signature : original + " [" + signature + "]"));
            methodProblems.stream().limit(3).forEach(p -> System.err.println("      " + p.detail));
            if (methodProblems.size() > 3) {
                System.err.println("      ... and " + (methodProblems.size() - 3) + " more in this method");
            }
        });
        System.err.println("Keep each affected method unoptimized with a method-scoped "
                + "-keepclassmembers,allowobfuscation rule in androidApp/proguard-rules.pro, or move to an "
                + "R8 version that fixes the miscompilation, then rebuild.");
        return false;
    }

    private void checkMethod(Method method) {
        methods++;
        MethodAnalyzer analyzer;
        try {
            analyzer = new MethodAnalyzer(classPath, method, null, false);
        } catch (RuntimeException e) {
            problems.add(new Problem(method, "analyzer failed: " + e));
            return;
        }
        if (analyzer.getAnalysisException() != null) {
            problems.add(new Problem(method, "analyzer failed: " + analyzer.getAnalysisException().getMessage()));
            return;
        }
        for (AnalyzedInstruction analyzed : analyzer.getAnalyzedInstructions()) {
            Instruction instruction = analyzed.getInstruction();
            Opcode opcode = instruction.getOpcode();
            int pc = analyzer.getInstructionAddress(analyzed);
            switch (opcode) {
                case THROW:
                    expect(method, pc, analyzed, ((OneRegisterInstruction) instruction).getRegisterA(),
                            "Ljava/lang/Throwable;", "throw");
                    break;
                case RETURN_OBJECT:
                    expect(method, pc, analyzed, ((OneRegisterInstruction) instruction).getRegisterA(),
                            method.getReturnType(), "return-object");
                    break;
                case IPUT_OBJECT:
                case SPUT_OBJECT: {
                    FieldReference field = (FieldReference) ((ReferenceInstruction) instruction).getReference();
                    expect(method, pc, analyzed, ((OneRegisterInstruction) instruction).getRegisterA(),
                            field.getType(), opcode.name + " " + field.getName());
                    break;
                }
                default:
                    if (INVOKES.contains(opcode)) checkInvoke(method, pc, analyzed, instruction, opcode);
                    break;
            }
        }
    }

    private void checkInvoke(Method method, int pc, AnalyzedInstruction analyzed, Instruction instruction, Opcode opcode) {
        MethodReference target = (MethodReference) ((ReferenceInstruction) instruction).getReference();
        List<Integer> registers = new ArrayList<>();
        if (instruction instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction range = (RegisterRangeInstruction) instruction;
            for (int i = 0; i < range.getRegisterCount(); i++) registers.add(range.getStartRegister() + i);
        } else {
            FiveRegisterInstruction five = (FiveRegisterInstruction) instruction;
            int[] all = {five.getRegisterC(), five.getRegisterD(), five.getRegisterE(), five.getRegisterF(), five.getRegisterG()};
            for (int i = 0; i < five.getRegisterCount(); i++) registers.add(all[i]);
        }
        String callee = target.getDefiningClass() + "->" + target.getName();
        int index = 0;
        if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) {
            boolean constructor = target.getName().equals("<init>");
            boolean viaInterface = opcode == Opcode.INVOKE_INTERFACE || opcode == Opcode.INVOKE_INTERFACE_RANGE;
            if (!constructor && !viaInterface && !target.getDefiningClass().startsWith("[")) {
                expect(method, pc, analyzed, registers.get(0), target.getDefiningClass(), "receiver of " + callee);
            }
            index = 1;
        }
        int parameter = 0;
        for (CharSequence typeName : target.getParameterTypes()) {
            String type = typeName.toString();
            if (type.length() > 1) {
                expect(method, pc, analyzed, registers.get(index), type, "argument " + parameter + " of " + callee);
            }
            index += type.equals("J") || type.equals("D") ? 2 : 1;
            parameter++;
        }
    }

    private void expect(Method method, int pc, AnalyzedInstruction analyzed, int register, String expected, String use) {
        if (expected.length() == 1) return;
        RegisterType actual = analyzed.getPreInstructionRegisterType(register);
        if (isAssignable(expected, actual) != Boolean.FALSE) return;
        problems.add(new Problem(method, String.format("@0x%04x: v%d (%s) has %s, expected %s",
                pc, register, use, actual, expected)));
    }

    /** TRUE or FALSE when decidable; null when a type does not resolve and ART would defer the check. */
    private Boolean isAssignable(String expected, RegisterType actual) {
        if (expected.equals("Ljava/lang/Object;") || actual.category == RegisterType.NULL) return true;
        if (actual.category == RegisterType.UNINIT_REF || actual.category == RegisterType.UNINIT_THIS) return null;
        if (actual.category != RegisterType.REFERENCE) return false;
        String actualType = actual.type.getType();
        if (actualType.equals(expected)) return true;
        if (expected.startsWith("[")) return actualType.startsWith("[") ? null : false;
        try {
            // ART treats interface-typed slots as Object.
            if (classPath.getClass(expected).isInterface()) return true;
        } catch (RuntimeException unresolved) {
            return null;
        }
        if (actualType.startsWith("[")) return false;
        for (String type = actualType; type != null; ) {
            if (type.equals(expected)) return true;
            TypeProto proto = classPath.getClass(type);
            try {
                type = proto.getSuperclass();
            } catch (RuntimeException unresolved) {
                return null;
            }
        }
        return false;
    }

    /** Original class and method names from R8's mapping.txt, read only for the classes in a report. */
    private static final class Mapping {
        private static final Pattern CLASS_LINE = Pattern.compile("^(\\S+) -> (\\S+):$");
        // Group 1: optional minified line range; consecutive lines sharing it list inlined frames, outermost last.
        private static final Pattern METHOD_LINE = Pattern.compile("^\\s+((?:\\d+:\\d+:)?)\\S+ ([^\\s(]+)\\(.*\\)(?::\\d+(?::\\d+)?)? -> (\\S+)$");
        private final Map<String, String> classes = new HashMap<>();
        private final Map<String, Set<String>> methods = new HashMap<>();

        static Mapping read(File file, Set<String> wantedClasses) throws IOException {
            Mapping mapping = new Mapping();
            if (file == null || !file.isFile()) return mapping;
            try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
                String obfuscatedClass = null;
                String runKey = null;
                String runName = null;
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (line.trim().startsWith("#")) continue; // Metadata; may sit inside an inline run.
                    Matcher methodLine = METHOD_LINE.matcher(line);
                    boolean isMethod = line.startsWith(" ") && methodLine.matches();
                    String key = isMethod && !methodLine.group(1).isEmpty() ? methodLine.group(1) + methodLine.group(3) : null;
                    if (runKey != null && (key == null || !key.equals(runKey))) {
                        mapping.addOutermostFrame(obfuscatedClass, runKey, runName);
                        runKey = null;
                    }
                    if (!line.startsWith(" ")) {
                        Matcher classLine = CLASS_LINE.matcher(line);
                        obfuscatedClass = classLine.matches() ? descriptor(classLine.group(2)) : null;
                        if (obfuscatedClass != null && wantedClasses.contains(obfuscatedClass)) {
                            mapping.classes.put(obfuscatedClass, classLine.group(1));
                        } else {
                            obfuscatedClass = null;
                        }
                    } else if (obfuscatedClass != null && isMethod) {
                        if (key == null) {
                            mapping.addOutermostFrame(obfuscatedClass, ":" + methodLine.group(3), methodLine.group(2));
                        } else {
                            runKey = key;
                            runName = methodLine.group(2);
                        }
                    }
                }
                if (runKey != null) mapping.addOutermostFrame(obfuscatedClass, runKey, runName);
            }
            return mapping;
        }

        private void addOutermostFrame(String obfuscatedClass, String runKey, String name) {
            if (obfuscatedClass == null) return;
            String obfuscatedMethod = runKey.substring(runKey.lastIndexOf(':') + 1);
            // R8 may qualify the outermost frame with its original class; the report names the class already.
            methods.computeIfAbsent(obfuscatedClass + "->" + obfuscatedMethod, k -> new TreeSet<>())
                    .add(name.substring(name.lastIndexOf('.') + 1));
        }

        String original(String obfuscatedClass, String obfuscatedMethod) {
            String className = classes.get(obfuscatedClass);
            if (className == null) return null;
            Set<String> names = methods.getOrDefault(obfuscatedClass + "->" + obfuscatedMethod, Collections.emptySet());
            String methodNames = names.isEmpty() ? obfuscatedMethod : String.join("|", names);
            return className + "." + methodNames;
        }

        private static String descriptor(String javaName) {
            return "L" + javaName.replace('.', '/') + ";";
        }
    }

    /** Class hierarchy (no members) from JVM class-file headers, e.g. the compile SDK's android.jar. */
    private static final class ClassFileHeaderProvider implements ClassProvider {
        private final ZipFile jar;
        private final Map<String, ClassDef> cache = new HashMap<>();

        ClassFileHeaderProvider(File jar) throws IOException {
            this.jar = new ZipFile(jar);
        }

        @Override
        public ClassDef getClassDef(String type) {
            if (cache.containsKey(type)) return cache.get(type);
            ClassDef classDef = null;
            if (type.startsWith("L") && type.endsWith(";")) {
                ZipEntry entry = jar.getEntry(type.substring(1, type.length() - 1) + ".class");
                if (entry != null) {
                    try (DataInputStream in = new DataInputStream(new BufferedInputStream(jar.getInputStream(entry)))) {
                        classDef = parse(in);
                    } catch (IOException e) {
                        throw new IllegalStateException("Cannot read " + entry.getName(), e);
                    }
                }
            }
            cache.put(type, classDef);
            return classDef;
        }

        private static ClassDef parse(DataInputStream in) throws IOException {
            in.readInt();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classNameIndex = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: utf8[i] = in.readUTF(); break;
                    case 7: classNameIndex[i] = in.readUnsignedShort(); break;
                    case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                    case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
                    case 5: case 6: in.readLong(); i++; break;
                    case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                    default: throw new IOException("Unknown constant pool tag " + tag);
                }
            }
            int accessFlags = in.readUnsignedShort() & ~0x20; // ACC_SUPER means ACC_SYNCHRONIZED in dex.
            String type = "L" + utf8[classNameIndex[in.readUnsignedShort()]] + ";";
            int superIndex = in.readUnsignedShort();
            String superclass = superIndex == 0 ? null : "L" + utf8[classNameIndex[superIndex]] + ";";
            int interfaceCount = in.readUnsignedShort();
            Set<String> interfaces = new LinkedHashSet<>();
            for (int i = 0; i < interfaceCount; i++) {
                interfaces.add("L" + utf8[classNameIndex[in.readUnsignedShort()]] + ";");
            }
            return new ImmutableClassDef(type, accessFlags, superclass, new ArrayList<>(interfaces), null, null, null, null);
        }
    }
}
