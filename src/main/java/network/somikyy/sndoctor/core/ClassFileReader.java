/*
 * SNDoctor - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.sndoctor.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal, dependency-free JVM class file reader.
 *
 * <p>SNDoctor never executes plugin code and never loads plugin classes into the JVM.
 * It only parses the constant pool, which is enough to know:
 * <ul>
 *   <li>which classes a plugin references,</li>
 *   <li>which methods and fields it calls,</li>
 *   <li>which string literals it carries,</li>
 *   <li>which Java version it was compiled for.</li>
 * </ul>
 *
 * <p>This is deliberately hand-written instead of using ASM: SNDoctor has to run on a
 * server that is already broken, so it must not pull a single dependency and must not
 * relocate anything into the plugin classpath.
 *
 * <p>Format reference: JVMS chapter 4 (ClassFile / constant pool).
 */
public final class ClassFileReader {

    // Constant pool tags (JVMS Table 4.4-B)
    private static final int CP_UTF8 = 1;
    private static final int CP_INTEGER = 3;
    private static final int CP_FLOAT = 4;
    private static final int CP_LONG = 5;
    private static final int CP_DOUBLE = 6;
    private static final int CP_CLASS = 7;
    private static final int CP_STRING = 8;
    private static final int CP_FIELDREF = 9;
    private static final int CP_METHODREF = 10;
    private static final int CP_INTERFACE_METHODREF = 11;
    private static final int CP_NAME_AND_TYPE = 12;
    private static final int CP_METHOD_HANDLE = 15;
    private static final int CP_METHOD_TYPE = 16;
    private static final int CP_DYNAMIC = 17;
    private static final int CP_INVOKE_DYNAMIC = 18;
    private static final int CP_MODULE = 19;
    private static final int CP_PACKAGE = 20;

    private ClassFileReader() {
    }

    /** Everything SNDoctor extracts from a single .class entry. */
    public static final class ClassFacts {
        /** Internal name of the class itself, e.g. {@code com/example/Foo}. */
        public String ownName = "";
        /** Class file major version (52 = Java 8, 61 = Java 17, 65 = Java 21, 69 = Java 25). */
        public int majorVersion;
        /** Internal names of every class referenced from the constant pool. */
        public final Set<String> referencedClasses = new LinkedHashSet<>();
        /** {@code owner#member} for every field / method reference. */
        public final Set<String> memberRefs = new LinkedHashSet<>();
        /** String literals present in the class. */
        public final List<String> stringConstants = new ArrayList<>();

        /** Java feature version this class requires, e.g. 17 for major 61. */
        public int javaVersion() {
            return majorVersion >= 44 ? majorVersion - 44 : 0;
        }
    }

    /**
     * Reads one class file.
     *
     * @throws IOException if the stream is not a readable class file
     */
    public static ClassFacts read(InputStream rawIn) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        ClassFacts facts = new ClassFacts();

        int magic = in.readInt();
        if (magic != 0xCAFEBABE) {
            throw new IOException("not a class file (bad magic)");
        }
        in.readUnsignedShort();                       // minor version
        facts.majorVersion = in.readUnsignedShort();  // major version

        int cpCount = in.readUnsignedShort();
        // Constant pool is 1-indexed; index 0 is unused.
        int[] tags = new int[cpCount];
        String[] utf8 = new String[cpCount];
        int[] ref1 = new int[cpCount];   // class_index / name_index / string_index
        int[] ref2 = new int[cpCount];   // name_and_type_index / descriptor_index

        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case CP_UTF8 -> {
                    int len = in.readUnsignedShort();
                    byte[] bytes = new byte[len];
                    in.readFully(bytes);
                    // Modified UTF-8; plain UTF-8 decoding is accurate enough for our
                    // purposes (identifiers and ASCII literals) and never throws.
                    utf8[i] = new String(bytes, StandardCharsets.UTF_8);
                }
                case CP_INTEGER, CP_FLOAT -> in.skipBytes(4);
                case CP_LONG, CP_DOUBLE -> {
                    in.skipBytes(8);
                    i++; // long and double take two constant pool slots (JVMS 4.4.5)
                }
                case CP_CLASS, CP_STRING, CP_METHOD_TYPE, CP_MODULE, CP_PACKAGE ->
                        ref1[i] = in.readUnsignedShort();
                case CP_FIELDREF, CP_METHODREF, CP_INTERFACE_METHODREF,
                     CP_NAME_AND_TYPE, CP_DYNAMIC, CP_INVOKE_DYNAMIC -> {
                    ref1[i] = in.readUnsignedShort();
                    ref2[i] = in.readUnsignedShort();
                }
                case CP_METHOD_HANDLE -> {
                    in.readUnsignedByte();
                    ref1[i] = in.readUnsignedShort();
                }
                default -> throw new IOException("unknown constant pool tag " + tag + " at #" + i);
            }
        }

        in.readUnsignedShort(); // access flags
        int thisClass = in.readUnsignedShort();
        if (thisClass > 0 && thisClass < cpCount && tags[thisClass] == CP_CLASS) {
            String own = utf8[ref1[thisClass]];
            if (own != null) {
                facts.ownName = own;
            }
        }
        in.readUnsignedShort(); // super class - already a CONSTANT_Class entry
        int interfaceCount = in.readUnsignedShort();
        skipFully(in, interfaceCount * 2L); // likewise CONSTANT_Class entries

        // Field and method descriptors matter: a class that declares
        //     public EntityPlayer handle;
        // and never touches that field produces NO CONSTANT_Class and NO Fieldref for
        // EntityPlayer - the type survives only inside the field descriptor. Skipping this
        // section silently loses real NMS usage, which is the single most important thing
        // SNDoctor has to find.
        readMembers(in, cpCount, tags, utf8, facts);   // fields
        readMembers(in, cpCount, tags, utf8, facts);   // methods

        for (int i = 1; i < cpCount; i++) {
            switch (tags[i]) {
                case CP_CLASS -> {
                    String name = utf8[ref1[i]];
                    if (name != null && !name.isEmpty()) {
                        addClassName(facts.referencedClasses, name);
                    }
                }
                case CP_STRING -> {
                    String value = utf8[ref1[i]];
                    if (value != null) {
                        facts.stringConstants.add(value);
                    }
                }
                case CP_FIELDREF, CP_METHODREF, CP_INTERFACE_METHODREF -> {
                    int ownerIdx = ref1[i];
                    int natIdx = ref2[i];
                    if (ownerIdx <= 0 || ownerIdx >= cpCount || natIdx <= 0 || natIdx >= cpCount) {
                        continue;
                    }
                    String owner = tags[ownerIdx] == CP_CLASS ? utf8[ref1[ownerIdx]] : null;
                    String member = tags[natIdx] == CP_NAME_AND_TYPE ? utf8[ref1[natIdx]] : null;
                    String descriptor = tags[natIdx] == CP_NAME_AND_TYPE ? utf8[ref2[natIdx]] : null;
                    if (owner != null && member != null) {
                        facts.memberRefs.add(stripArray(owner) + "#" + member);
                    }
                    if (descriptor != null) {
                        collectTypesFromDescriptor(descriptor, facts.referencedClasses);
                    }
                }
                default -> {
                    // nothing else carries references we care about
                }
            }
        }
        return facts;
    }

    /**
     * Reads one {@code field_info[]} or {@code method_info[]} table, collecting the declared
     * types. Both tables have an identical shape (JVMS 4.5 / 4.6).
     */
    private static void readMembers(DataInputStream in, int cpCount, int[] tags, String[] utf8,
                                    ClassFacts facts) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort(); // access flags
            in.readUnsignedShort(); // name index
            int descriptorIndex = in.readUnsignedShort();
            if (descriptorIndex > 0 && descriptorIndex < cpCount && tags[descriptorIndex] == CP_UTF8) {
                String descriptor = utf8[descriptorIndex];
                if (descriptor != null) {
                    collectTypesFromDescriptor(descriptor, facts.referencedClasses);
                }
            }
            skipAttributes(in);
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort();                       // attribute_name_index
            long length = in.readInt() & 0xFFFFFFFFL;     // attribute_length is u4
            skipFully(in, length);
        }
    }

    /** {@link DataInputStream#skipBytes} may skip fewer bytes than asked; this does not. */
    private static void skipFully(DataInputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, Integer.MAX_VALUE);
            int skipped = in.skipBytes(chunk);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("unexpected end of class file");
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static void addClassName(Set<String> out, String name) {
        String cleaned = stripArray(name);
        if (!cleaned.isEmpty()) {
            out.add(cleaned);
        }
    }

    /** Array class references look like {@code [Lcom/example/Foo;} in the pool. */
    private static String stripArray(String name) {
        int i = 0;
        while (i < name.length() && name.charAt(i) == '[') {
            i++;
        }
        if (i == 0) {
            return name;
        }
        String rest = name.substring(i);
        if (rest.startsWith("L") && rest.endsWith(";")) {
            return rest.substring(1, rest.length() - 1);
        }
        return ""; // primitive array, nothing to record
    }

    /** Pulls {@code Lcom/example/Foo;} object types out of a field or method descriptor. */
    static void collectTypesFromDescriptor(String descriptor, Set<String> out) {
        int i = 0;
        int n = descriptor.length();
        while (i < n) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0) {
                    return;
                }
                String type = descriptor.substring(i + 1, end);
                if (!type.isEmpty()) {
                    out.add(type);
                }
                i = end + 1;
            } else {
                i++;
            }
        }
    }
}
