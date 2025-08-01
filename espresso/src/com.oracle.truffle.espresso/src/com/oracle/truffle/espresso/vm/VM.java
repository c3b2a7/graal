/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.vm;

import static com.oracle.truffle.espresso.classfile.Constants.ACC_ABSTRACT;
import static com.oracle.truffle.espresso.classfile.Constants.ACC_FINAL;
import static com.oracle.truffle.espresso.classfile.Constants.ACC_LAMBDA_FORM_COMPILED;
import static com.oracle.truffle.espresso.classfile.Constants.ACC_PUBLIC;
import static com.oracle.truffle.espresso.jni.JniEnv.JNI_EDETACHED;
import static com.oracle.truffle.espresso.jni.JniEnv.JNI_ERR;
import static com.oracle.truffle.espresso.jni.JniEnv.JNI_EVERSION;
import static com.oracle.truffle.espresso.jni.JniEnv.JNI_OK;
import static com.oracle.truffle.espresso.meta.EspressoError.cat;
import static com.oracle.truffle.espresso.runtime.Classpath.JAVA_BASE;
import static com.oracle.truffle.espresso.substitutions.standard.Target_java_lang_invoke_MethodHandleNatives.Constants.ACCESS_VM_ANNOTATIONS;
import static com.oracle.truffle.espresso.substitutions.standard.Target_java_lang_invoke_MethodHandleNatives.Constants.HIDDEN_CLASS;
import static com.oracle.truffle.espresso.substitutions.standard.Target_java_lang_invoke_MethodHandleNatives.Constants.NESTMATE_CLASS;
import static com.oracle.truffle.espresso.substitutions.standard.Target_java_lang_invoke_MethodHandleNatives.Constants.STRONG_LOADER_LINK;
import static com.oracle.truffle.espresso.threads.ThreadState.OBJECT_WAIT;
import static com.oracle.truffle.espresso.threads.ThreadState.TIMED_OBJECT_WAIT;

import java.io.File;
import java.lang.invoke.MethodType;
import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.IntFunction;
import java.util.logging.Level;

import org.graalvm.collections.EconomicMap;
import org.graalvm.options.OptionValues;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.espresso.EspressoLanguage;
import com.oracle.truffle.espresso.EspressoOptions;
import com.oracle.truffle.espresso.EspressoOptions.MemoryAccessOption;
import com.oracle.truffle.espresso.blocking.GuestInterruptedException;
import com.oracle.truffle.espresso.cds.CDSSupport;
import com.oracle.truffle.espresso.classfile.ClasspathEntry;
import com.oracle.truffle.espresso.classfile.ConstantPool;
import com.oracle.truffle.espresso.classfile.Constants;
import com.oracle.truffle.espresso.classfile.JavaKind;
import com.oracle.truffle.espresso.classfile.ParserKlass;
import com.oracle.truffle.espresso.classfile.attributes.Attribute;
import com.oracle.truffle.espresso.classfile.attributes.EnclosingMethodAttribute;
import com.oracle.truffle.espresso.classfile.attributes.InnerClassesAttribute;
import com.oracle.truffle.espresso.classfile.attributes.MethodParametersAttribute;
import com.oracle.truffle.espresso.classfile.attributes.PermittedSubclassesAttribute;
import com.oracle.truffle.espresso.classfile.attributes.RecordAttribute;
import com.oracle.truffle.espresso.classfile.attributes.SignatureAttribute;
import com.oracle.truffle.espresso.classfile.descriptors.ByteSequence;
import com.oracle.truffle.espresso.classfile.descriptors.Name;
import com.oracle.truffle.espresso.classfile.descriptors.Signature;
import com.oracle.truffle.espresso.classfile.descriptors.Symbol;
import com.oracle.truffle.espresso.classfile.descriptors.Type;
import com.oracle.truffle.espresso.classfile.descriptors.TypeSymbols;
import com.oracle.truffle.espresso.classfile.descriptors.Validation;
import com.oracle.truffle.espresso.classfile.tables.EntryTable;
import com.oracle.truffle.espresso.constantpool.RuntimeConstantPool;
import com.oracle.truffle.espresso.descriptors.EspressoSymbols.Names;
import com.oracle.truffle.espresso.descriptors.EspressoSymbols.Signatures;
import com.oracle.truffle.espresso.descriptors.EspressoSymbols.Types;
import com.oracle.truffle.espresso.ffi.NativeSignature;
import com.oracle.truffle.espresso.ffi.NativeType;
import com.oracle.truffle.espresso.ffi.Pointer;
import com.oracle.truffle.espresso.ffi.RawPointer;
import com.oracle.truffle.espresso.ffi.nfi.NativeUtils;
import com.oracle.truffle.espresso.impl.ArrayKlass;
import com.oracle.truffle.espresso.impl.BootClassRegistry;
import com.oracle.truffle.espresso.impl.ClassRegistries;
import com.oracle.truffle.espresso.impl.ClassRegistry;
import com.oracle.truffle.espresso.impl.EspressoClassLoadingException;
import com.oracle.truffle.espresso.impl.Field;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.impl.ModuleTable;
import com.oracle.truffle.espresso.impl.ModuleTable.ModuleEntry;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.impl.PackageTable;
import com.oracle.truffle.espresso.impl.PackageTable.PackageEntry;
import com.oracle.truffle.espresso.impl.SuppressFBWarnings;
import com.oracle.truffle.espresso.jni.JniEnv;
import com.oracle.truffle.espresso.jni.JniVersion;
import com.oracle.truffle.espresso.jni.NativeEnv;
import com.oracle.truffle.espresso.jni.NoSafepoint;
import com.oracle.truffle.espresso.jvmti.JVMTI;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.meta.MetaUtil;
import com.oracle.truffle.espresso.nodes.EspressoFrame;
import com.oracle.truffle.espresso.nodes.EspressoRootNode;
import com.oracle.truffle.espresso.nodes.interop.ToEspressoNode;
import com.oracle.truffle.espresso.nodes.interop.ToEspressoNodeFactory;
import com.oracle.truffle.espresso.ref.EspressoReference;
import com.oracle.truffle.espresso.runtime.Classpath;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.EspressoException;
import com.oracle.truffle.espresso.runtime.EspressoExitException;
import com.oracle.truffle.espresso.runtime.EspressoProperties;
import com.oracle.truffle.espresso.runtime.MethodHandleIntrinsics;
import com.oracle.truffle.espresso.runtime.OS;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.CallableFromNative;
import com.oracle.truffle.espresso.substitutions.GenerateNativeEnv;
import com.oracle.truffle.espresso.substitutions.Inject;
import com.oracle.truffle.espresso.substitutions.JImageExtensions;
import com.oracle.truffle.espresso.substitutions.JavaType;
import com.oracle.truffle.espresso.substitutions.ModuleExtension;
import com.oracle.truffle.espresso.substitutions.SubstitutionProfiler;
import com.oracle.truffle.espresso.substitutions.continuations.Target_org_graalvm_continuations_IdentityHashCodes;
import com.oracle.truffle.espresso.substitutions.standard.Target_java_lang_System;
import com.oracle.truffle.espresso.substitutions.standard.Target_java_lang_Thread;
import com.oracle.truffle.espresso.substitutions.standard.Target_java_lang_ref_Reference;
import com.oracle.truffle.espresso.threads.ThreadAccess;
import com.oracle.truffle.espresso.threads.ThreadState;
import com.oracle.truffle.espresso.threads.Transition;
import com.oracle.truffle.espresso.vm.npe.ExtendedNPEMessage;
import com.oracle.truffle.espresso.vm.structs.JavaVMAttachArgs;
import com.oracle.truffle.espresso.vm.structs.Structs;
import com.oracle.truffle.espresso.vm.structs.StructsAccess;

/**
 * Espresso implementation of the VM interface (libjvm).
 * <p>
 * Adding a new VM method requires doing a few things in package
 * com.oracle.truffle.espresso.mokapot:
 * <p>
 * - adding it in include/mokapot.h
 * <p>
 * - implementing it in src/mokapot.c
 * <p>
 * - registering it in mapfile-vers
 * <p>
 * - for new VM methods (/ex: upgrading from java 8 to 11), updating include/jvm.h
 */
@GenerateNativeEnv(target = VmImpl.class, reachableForAutoSubstitution = true)
public final class VM extends NativeEnv {
    private static final TruffleLogger LOGGER = TruffleLogger.getLogger(EspressoLanguage.ID, VM.class);

    private final @Pointer TruffleObject disposeMokapotContext;

    private final @Pointer TruffleObject getJavaVM;
    private final @Pointer TruffleObject mokapotAttachThread;
    private final @Pointer TruffleObject mokapotCaptureState;
    private final @Pointer TruffleObject getPackageAt;

    private final long rtldDefaultValue;
    private final long processHandleValue;

    private final Structs structs;

    private final JniEnv jniEnv;
    private final Management management;
    private final JVMTI jvmti;

    private @Pointer TruffleObject mokapotEnvPtr;
    private @Pointer TruffleObject javaLibrary;

    private final Object zipLoadLock = new Object() {
    };
    private volatile @Pointer TruffleObject zipLibrary;

    // The initial system properties
    private Map<String, String> systemProperties;

    public void attachThread(Thread hostThread) {
        if (hostThread != Thread.currentThread()) {
            getLogger().warning("unimplemented: attachThread for non-current thread: " + hostThread);
            return;
        }
        assert hostThread == Thread.currentThread();
        try {
            getUncached().execute(mokapotAttachThread, mokapotEnvPtr);
            // Initialize external threads e.g. ctype TLS data must be initialized for threads
            // created outside the isolated namespace using the nfi-dlmopen backend.
            getNativeAccess().prepareThread();
        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere("mokapotAttachThread failed", e);
        }
    }

    public Management getManagement() {
        return management;
    }

    public @Pointer TruffleObject getJavaLibrary() {
        return javaLibrary;
    }

    public static final class GlobalFrameIDs {
        private static final AtomicLong id = new AtomicLong();

        public static long getID() {
            return id.incrementAndGet();
        }
    }

    private @Pointer TruffleObject loadJavaLibraryImpl(List<Path> bootLibraryPath) {
        // Comment from HotSpot:
        // Try to load verify dll first. In 1.3 java dll depends on it and is not
        // always able to find it when the loading executable is outside the JDK.
        // In order to keep working with 1.2 we ignore any loading errors.

        /* verifyLibrary = */ getNativeAccess().loadLibrary(bootLibraryPath, "verify", false);
        return getNativeAccess().loadLibrary(bootLibraryPath, "java", true);
    }

    public void loadJavaLibrary(List<Path> searchPaths) {
        assert javaLibrary == null : "java library already initialized";
        this.javaLibrary = loadJavaLibraryImpl(searchPaths);
    }

    public void initializeJavaLibrary() {
        // HotSpot calls libjava's JNI_OnLoad only on 8.
        if (getJavaVersion().java8OrEarlier()) {
            /*
             * The JNI_OnLoad handling is normally done by method load in
             * java.lang.ClassLoader$NativeLibrary, but the VM loads the base library explicitly so
             * we have to check for JNI_OnLoad as well.
             */
            EspressoError.guarantee(getVM() != null, "The VM must be initialized before libjava's JNI_OnLoad");
            NativeSignature jniOnLoadSignature = NativeSignature.create(NativeType.INT, NativeType.POINTER, NativeType.POINTER);
            TruffleObject jniOnLoad = getNativeAccess().lookupAndBindSymbol(this.javaLibrary, "JNI_OnLoad", jniOnLoadSignature, false, true);
            if (jniOnLoad != null) {
                try {
                    getUncached().execute(jniOnLoad, mokapotEnvPtr, RawPointer.nullInstance());
                } catch (UnsupportedTypeException | UnsupportedMessageException | ArityException e) {
                    throw EspressoError.shouldNotReachHere(e);
                }
            }
        }
    }

    private VM(JniEnv jniEnv) {
        super(jniEnv.getContext());
        this.jniEnv = jniEnv;
        try {
            EspressoProperties props = getContext().getVmProperties();

            // Load Espresso's libjvm:
            /*
             * jvm.dll (Windows) or libjvm.so (Unixes) is the Espresso implementation of the VM
             * interface (libjvm). Espresso loads all shared libraries in a private namespace (e.g.
             * using dlmopen on Linux). Espresso's libjvm must be loaded strictly before any other
             * library in the private namespace to avoid linking with HotSpot libjvm, then libjava
             * is loaded and further system libraries, libzip, libnet, libnio ...
             */
            @Pointer
            TruffleObject mokapotLibrary = getNativeAccess().loadLibrary(props.jvmLibraryPath(), "jvm", true);
            assert mokapotLibrary != null;

            @Pointer
            TruffleObject initializeMokapotContext = getNativeAccess().lookupAndBindSymbol(mokapotLibrary, "initializeMokapotContext",
                            NativeSignature.create(NativeType.POINTER, NativeType.POINTER, NativeType.POINTER));

            disposeMokapotContext = getNativeAccess().lookupAndBindSymbol(mokapotLibrary, "disposeMokapotContext",
                            NativeSignature.create(NativeType.VOID, NativeType.POINTER, NativeType.POINTER));

            if (getContext().getEspressoEnv().EnableManagement) {
                management = new Management(getContext(), mokapotLibrary);
            } else {
                management = null;
            }

            structs = StructsAccess.getStructs(getContext(), mokapotLibrary);

            jvmti = new JVMTI(getContext(), mokapotLibrary);

            getJavaVM = getNativeAccess().lookupAndBindSymbol(mokapotLibrary,
                            "getJavaVM",
                            NativeSignature.create(NativeType.POINTER, NativeType.POINTER));

            mokapotAttachThread = getNativeAccess().lookupAndBindSymbol(mokapotLibrary,
                            "mokapotAttachThread",
                            NativeSignature.create(NativeType.VOID, NativeType.POINTER));

            mokapotCaptureState = getNativeAccess().lookupAndBindSymbol(mokapotLibrary,
                            "mokapotCaptureState",
                            NativeSignature.create(NativeType.VOID, NativeType.POINTER, NativeType.INT));

            @Pointer
            TruffleObject mokapotGetRTLDDefault = getNativeAccess().lookupAndBindSymbol(mokapotLibrary,
                            "mokapotGetRTLD_DEFAULT",
                            NativeSignature.create(NativeType.POINTER));
            @Pointer
            TruffleObject mokapotGetProcessHandle = getNativeAccess().lookupAndBindSymbol(mokapotLibrary,
                            "mokapotGetProcessHandle",
                            NativeSignature.create(NativeType.POINTER));

            getPackageAt = getNativeAccess().lookupAndBindSymbol(mokapotLibrary,
                            "getPackageAt",
                            NativeSignature.create(NativeType.POINTER, NativeType.POINTER, NativeType.INT));
            this.mokapotEnvPtr = initializeAndGetEnv(true, initializeMokapotContext, jniEnv.getNativePointer());
            this.rtldDefaultValue = getUncached().asPointer(getUncached().execute(mokapotGetRTLDDefault));
            this.processHandleValue = getUncached().asPointer(getUncached().execute(mokapotGetProcessHandle));
            getLogger().finest(() -> String.format("Got RTLD_DEFAULT=0x%016x and ProcessHandle=0x%016x", rtldDefaultValue, processHandleValue));
            assert getUncached().isPointer(this.mokapotEnvPtr);
            assert !getUncached().isNull(this.mokapotEnvPtr);
        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    @Override
    protected TruffleLogger getLogger() {
        return LOGGER;
    }

    @Override
    protected String getName() {
        return "VM";
    }

    public @Pointer TruffleObject getJavaVM() {
        try {
            @Pointer
            TruffleObject ptr = (TruffleObject) getUncached().execute(getJavaVM, mokapotEnvPtr);
            assert getUncached().isPointer(ptr);
            return ptr;
        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw EspressoError.shouldNotReachHere("getJavaVM failed", e);
        }
    }

    public Structs getStructs() {
        return structs;
    }

    public JVMTI getJvmti() {
        return jvmti;
    }

    private static final List<CallableFromNative.Factory> VM_IMPL_FACTORIES = VmImplCollector.getInstances(CallableFromNative.Factory.class);
    private static final int VM_LOOKUP_CALLBACK_ARGS = 2;

    /**
     * Maps native function pointers to node factories for VM methods.
     */
    private final EconomicMap<Long, CallableFromNative.Factory> knownVmMethods = EconomicMap.create();

    @Override
    protected List<CallableFromNative.Factory> getCollector() {
        return VM_IMPL_FACTORIES;
    }

    @Override
    protected int lookupCallBackArgsCount() {
        return VM_LOOKUP_CALLBACK_ARGS;
    }

    @Override
    protected NativeSignature lookupCallbackSignature() {
        return NativeSignature.create(NativeType.POINTER, NativeType.POINTER, NativeType.POINTER);
    }

    /**
     * Registers this known VM method's function pointer. Later native method bindings can perform a
     * lookup when trying to bind to a function pointer, and if a match happens, this is a known VM
     * method, and we can link directly to it thus bypassing native calls.
     *
     * @param name The name of the VM method, previously extracted from {@code args[0]}.
     * @param factory The node factory of the requested VM method.
     * @param args A length {@linkplain #lookupCallBackArgsCount() 2} arguments array: At position 0
     *            is a native pointer to the name of the method. At position 1 is the address of the
     *            {@code JVM_*} symbol exported by {@code mokapot}.
     */
    @Override
    @TruffleBoundary
    protected void processCallBackResult(String name, CallableFromNative.Factory factory, Object... args) {
        super.processCallBackResult(name, factory, args);
        try {
            InteropLibrary uncached = InteropLibrary.getUncached();
            Object ptr = args[1];
            if (factory != null && !uncached.isNull(ptr) && uncached.isPointer(ptr)) {
                long jvmMethodAddress = uncached.asPointer(ptr);
                knownVmMethods.put(jvmMethodAddress, factory);
            }
        } catch (UnsupportedMessageException e) {
            /* Ignore */
        }
    }

    @TruffleBoundary
    public CallableFromNative.Factory lookupKnownVmMethod(long functionPointer) {
        return knownVmMethods.get(functionPointer);
    }

    public static VM create(JniEnv jniEnv) {
        return new VM(jniEnv);
    }

    public void dispose() {
        if (mokapotEnvPtr == null || getUncached().isNull(mokapotEnvPtr)) {
            return; // Mokapot disposed or uninitialized.
        }
        try {
            if (management != null) {
                assert getContext().getEspressoEnv().EnableManagement;
                management.dispose();
            }
            if (jvmti != null) {
                jvmti.dispose();
            }
            getUncached().execute(disposeMokapotContext, mokapotEnvPtr, RawPointer.nullInstance());
            this.mokapotEnvPtr = RawPointer.nullInstance();
        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere("Cannot dispose Espresso libjvm (mokapot).");
        }
        assert mokapotEnvPtr == null || getUncached().isNull(mokapotEnvPtr);
    }

    private StaticObject nonReflectionClassLoader(StaticObject loader) {
        if (getJavaVersion().java21OrLater()) {
            return loader;
        }
        if (StaticObject.notNull(loader)) {
            Meta meta = getMeta();
            if (meta.sun_reflect_DelegatingClassLoader.isAssignableFrom(loader.getKlass())) {
                return meta.java_lang_ClassLoader_parent.getObject(loader);
            }
        }
        return loader;
    }

    // Checkstyle: stop method name check

    public TruffleObject getMokapotCaptureState() {
        return mokapotCaptureState;
    }

    // region system

    @VmImpl(isJni = true)
    // SVM windows has System.currentTimeMillis() blocked for PE.
    @TruffleBoundary(allowInlining = true)
    public static long JVM_CurrentTimeMillis(
                    @SuppressWarnings("unused") @JavaType(Class/* <System> */.class) StaticObject ignored) {
        return System.currentTimeMillis();
    }

    @VmImpl(isJni = true)
    public static long JVM_NanoTime(@SuppressWarnings("unused") @JavaType(Class/* <System> */.class) StaticObject ignored) {
        return System.nanoTime();
    }

    @TruffleBoundary(allowInlining = true)
    @VmImpl(isJni = true)
    public static int JVM_IHashCode(@JavaType(Object.class) StaticObject object,
                    @Inject Meta meta,
                    @Inject EspressoLanguage language) {
        /*
         * On SVM + Windows, the System.identityHashCode substitution calls methods blocked for PE
         * (System.currentTimeMillis?).
         */
        if (object.isForeignObject()) {
            Object foreignObject = object.rawForeignObject(language);
            InteropLibrary library = InteropLibrary.getUncached(foreignObject);
            if (library.hasIdentity(foreignObject)) {
                try {
                    return library.identityHashCode(foreignObject);
                } catch (UnsupportedMessageException e) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    throw EspressoError.shouldNotReachHere();
                }
            }
        }
        if (meta.getLanguage().isContinuumEnabled()) {
            return Target_org_graalvm_continuations_IdentityHashCodes.getIHashCode(object, meta, language);
        }
        return System.identityHashCode(MetaUtil.maybeUnwrapNull(object));
    }

    @VmImpl(isJni = true)
    public static void JVM_ArrayCopy(@SuppressWarnings("unused") @JavaType(Class/* <System> */.class) StaticObject ignored,
                    @JavaType(Object.class) StaticObject src, int srcPos, @JavaType(Object.class) StaticObject dest, int destPos, int length,
                    @Inject EspressoLanguage language, @Inject Meta meta, @Inject SubstitutionProfiler profile) {
        Target_java_lang_System.arraycopy(src, srcPos, dest, destPos, length, language, meta, profile);
    }

    @VmImpl
    @TruffleBoundary(allowInlining = true)
    public static long JVM_TotalMemory() {
        // TODO(peterssen): What to report here?
        return Runtime.getRuntime().totalMemory();
    }

    @VmImpl
    @TruffleBoundary(allowInlining = true)
    public static long JVM_MaxMemory() {
        return Runtime.getRuntime().maxMemory();
    }

    @VmImpl
    @TruffleBoundary(allowInlining = true)
    public static void JVM_GC() {
        System.gc();
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public @JavaType(String.class) StaticObject JVM_GetSystemPackage(@JavaType(String.class) StaticObject name, @Inject Meta meta) {
        if (StaticObject.isNull(name)) {
            throw meta.throwNullPointerException();
        }
        ByteSequence pkg = meta.toByteSequence(name);
        if (pkg.length() > 0 && pkg.byteAt(pkg.length() - 1) == '/') {
            pkg = pkg.subSequence(0, pkg.length() - 1);
        }
        Symbol<Name> pkgName = getNames().lookup(pkg);
        if (pkgName == null) {
            return StaticObject.NULL;
        }
        BootClassRegistry bootClassRegistry = getRegistries().getBootClassRegistry();
        PackageEntry packageEntry = bootClassRegistry.packages().lookup(pkgName);
        if (packageEntry == null) {
            return StaticObject.NULL;
        }
        ModuleEntry moduleEntry = packageEntry.module();
        if (moduleEntry != null) {
            String location = moduleEntry.location();
            if (location != null) {
                return meta.toGuestString(location);
            }
        }
        return meta.toGuestString(packageEntry.getBootClasspathLocation());
    }

    @VmImpl(isJni = true)
    public @JavaType(String[].class) StaticObject JVM_GetSystemPackages(@Inject Meta meta) {
        List<StaticObject> packageNames = new ArrayList<>();
        getRegistries().getBootClassRegistry().packages().collectEntries((s, p) -> {
            if (p.getBootClasspathLocation() != null) {
                packageNames.add(meta.toGuestString(s));
            }
        });
        return StaticObject.createArray(meta.java_lang_String.getArrayKlass(), packageNames.toArray(StaticObject.EMPTY_ARRAY), getContext());
    }

    @VmImpl
    @TruffleBoundary(allowInlining = true)
    public static long JVM_FreeMemory() {
        return Runtime.getRuntime().freeMemory();
    }

    @VmImpl
    @TruffleBoundary(allowInlining = true)
    public static int JVM_ActiveProcessorCount() {
        return Runtime.getRuntime().availableProcessors();
    }

    @VmImpl
    @NoSafepoint
    public static boolean JVM_IsNaN(double d) {
        return Double.isNaN(d);
    }

    private static final class LongCASProbe {
        static final boolean HOST_SUPPORTS_LONG_CAS;
        @SuppressWarnings("unused") volatile long foo;
        static {
            AtomicLongFieldUpdater<LongCASProbe> updater = AtomicLongFieldUpdater.newUpdater(LongCASProbe.class, "foo");
            String updaterName = updater.getClass().getSimpleName();
            if ("CASUpdater".equals(updaterName)) {
                HOST_SUPPORTS_LONG_CAS = true;
            } else if ("LockedUpdater".equals(updaterName)) {
                HOST_SUPPORTS_LONG_CAS = false;
            } else {
                throw EspressoError.shouldNotReachHere("Unknown host long updater: " + updaterName);
            }
        }
    }

    @VmImpl
    @TruffleBoundary
    public static boolean JVM_SupportsCX8() {
        return LongCASProbe.HOST_SUPPORTS_LONG_CAS;
    }

    @VmImpl(isJni = true)
    public @JavaType(String.class) StaticObject JVM_InternString(@JavaType(String.class) StaticObject self) {
        return getInterpreterToVM().intern(self);
    }

    // endregion system

    @VmImpl(isJni = true)
    public static boolean JVM_IsFinalizationEnabled() {
        // TODO: implement '--finalization' flag.
        return true;
    }

    @VmImpl
    public static boolean JVM_IsPreviewEnabled(@Inject EspressoLanguage language) {
        return language.isPreviewEnabled();
    }

    @VmImpl
    public static boolean JVM_IsForeignLinkerSupported() {
        // Currently no wiring for the "default" linker
        return true;
    }

    // region objects

    private static Object readForeignArrayElement(StaticObject array, int index, InteropLibrary interop,
                    EspressoLanguage language, Meta meta, SubstitutionProfiler profiler, char exceptionBranch) {
        try {
            return interop.readArrayElement(array.rawForeignObject(language), index);
        } catch (UnsupportedMessageException e) {
            profiler.profile(exceptionBranch);
            throw meta.throwExceptionWithMessage(meta.java_lang_ClassCastException, "The foreign object is not a readable array");
        } catch (InvalidArrayIndexException e) {
            profiler.profile(exceptionBranch);
            throw meta.throwExceptionWithMessage(meta.java_lang_CloneNotSupportedException, "Foreign array length changed during clone");
        }
    }

    private static StaticObject cloneForeignArray(StaticObject array, EspressoLanguage language, Meta meta, InteropLibrary interop, ToEspressoNode.DynamicToEspresso toEspressoNode,
                    SubstitutionProfiler profiler,
                    char exceptionBranch) {
        assert array.isForeignObject();
        assert array.isArray();
        int length;
        try {
            long longLength = interop.getArraySize(array.rawForeignObject(language));
            if (longLength > Integer.MAX_VALUE) {
                profiler.profile(exceptionBranch);
                throw meta.throwExceptionWithMessage(meta.java_lang_CloneNotSupportedException, "Cannot clone a foreign array whose length does not fit in int");
            }
            if (longLength < 0) {
                profiler.profile(exceptionBranch);
                throw meta.throwExceptionWithMessage(meta.java_lang_NegativeArraySizeException, "Cannot clone a foreign array with negative length");
            }
            length = (int) longLength;
        } catch (UnsupportedMessageException e) {
            profiler.profile(exceptionBranch);
            throw meta.throwExceptionWithMessage(meta.java_lang_CloneNotSupportedException, "Cannot clone a non-array foreign object as an array");
        }

        ArrayKlass arrayKlass = (ArrayKlass) array.getKlass();
        Klass componentType = arrayKlass.getComponentType();
        if (componentType.isPrimitive()) {
            try {
                switch (componentType.getJavaKind()) {
                    case Boolean:
                        boolean[] booleanArray = new boolean[length];
                        for (int i = 0; i < length; ++i) {
                            Object foreignElement = readForeignArrayElement(array, i, interop, language, meta, profiler, exceptionBranch);
                            booleanArray[i] = (boolean) toEspressoNode.execute(foreignElement, componentType);
                        }
                        return StaticObject.createArray(arrayKlass, booleanArray, meta.getContext());
                    case Byte:
                        byte[] byteArray = new byte[length];
                        for (int i = 0; i < length; ++i) {
                            Object foreignElement = readForeignArrayElement(array, i, interop, language, meta, profiler, exceptionBranch);
                            byteArray[i] = (byte) toEspressoNode.execute(foreignElement, componentType);
                        }
                        return StaticObject.createArray(arrayKlass, byteArray, meta.getContext());
                    case Short:
                        short[] shortArray = new short[length];
                        for (int i = 0; i < length; ++i) {
                            Object foreignElement = readForeignArrayElement(array, i, interop, language, meta, profiler, exceptionBranch);
                            shortArray[i] = (short) toEspressoNode.execute(foreignElement, componentType);
                        }
                        return StaticObject.createArray(arrayKlass, shortArray, meta.getContext());
                    case Char:
                        char[] charArray = new char[length];
                        for (int i = 0; i < length; ++i) {
                            Object foreignElement = readForeignArrayElement(array, i, interop, language, meta, profiler, exceptionBranch);
                            charArray[i] = (char) toEspressoNode.execute(foreignElement, componentType);
                        }
                        return StaticObject.createArray(arrayKlass, charArray, meta.getContext());
                    case Int:
                        int[] intArray = new int[length];
                        for (int i = 0; i < length; ++i) {
                            Object foreignElement = readForeignArrayElement(array, i, interop, language, meta, profiler, exceptionBranch);
                            intArray[i] = (int) toEspressoNode.execute(foreignElement, componentType);
                        }
                        return StaticObject.createArray(arrayKlass, intArray, meta.getContext());
                    case Float:
                        float[] floatArray = new float[length];
                        for (int i = 0; i < length; ++i) {
                            Object foreignElement = readForeignArrayElement(array, i, interop, language, meta, profiler, exceptionBranch);
                            floatArray[i] = (float) toEspressoNode.execute(foreignElement, componentType);
                        }
                        return StaticObject.createArray(arrayKlass, floatArray, meta.getContext());
                    case Long:
                        long[] longArray = new long[length];
                        for (int i = 0; i < length; ++i) {
                            Object foreignElement = readForeignArrayElement(array, i, interop, language, meta, profiler, exceptionBranch);
                            longArray[i] = (long) toEspressoNode.execute(foreignElement, componentType);
                        }
                        return StaticObject.createArray(arrayKlass, longArray, meta.getContext());
                    case Double:
                        double[] doubleArray = new double[length];
                        for (int i = 0; i < length; ++i) {
                            Object foreignElement = readForeignArrayElement(array, i, interop, language, meta, profiler, exceptionBranch);
                            doubleArray[i] = (double) toEspressoNode.execute(foreignElement, componentType);
                        }
                        return StaticObject.createArray(arrayKlass, doubleArray, meta.getContext());
                    case Object:
                    case Void:
                    case ReturnAddress:
                    case Illegal:
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw EspressoError.shouldNotReachHere("Unexpected primitive kind: " + componentType.getJavaKind());
                }

            } catch (UnsupportedTypeException e) {
                profiler.profile(exceptionBranch);
                throw meta.throwExceptionWithMessage(meta.java_lang_ClassCastException, "Cannot cast an element of a foreign array to the declared component type");
            }
        }
        StaticObject[] newArray = new StaticObject[length];
        for (int i = 0; i < length; ++i) {
            Object foreignElement = readForeignArrayElement(array, i, interop, language, meta, profiler, exceptionBranch);

            try {
                newArray[i] = (StaticObject) toEspressoNode.execute(foreignElement, componentType);
            } catch (UnsupportedTypeException e) {
                profiler.profile(exceptionBranch);
                throw meta.throwExceptionWithMessage(meta.java_lang_ClassCastException, "Cannot cast an element of a foreign array to the declared component type");
            }
        }
        return StaticObject.createArray(arrayKlass, newArray, meta.getContext());
    }

    @VmImpl(isJni = true)
    public static @JavaType(Object.class) StaticObject JVM_Clone(@JavaType(Object.class) StaticObject self,
                    @Inject EspressoLanguage language, @Inject Meta meta, @Inject SubstitutionProfiler profiler) {
        assert StaticObject.notNull(self);
        char exceptionBranch = 3;
        if (self.isArray()) {
            // Arrays are always cloneable.
            if (self.isForeignObject()) {
                profiler.profile(4);
                return cloneForeignArray(self, language, meta, InteropLibrary.getUncached(self.rawForeignObject(language)), ToEspressoNodeFactory.DynamicToEspressoNodeGen.getUncached(), profiler,
                                exceptionBranch);
            }
            return self.copy(meta.getContext());
        }

        if (self.isForeignObject()) {
            profiler.profile(exceptionBranch);
            throw meta.throwExceptionWithMessage(meta.java_lang_CloneNotSupportedException, "Clone not supported for non-array foreign objects");
        }

        if (!meta.java_lang_Cloneable.isAssignableFrom(self.getKlass())) {
            profiler.profile(0);
            throw meta.throwException(meta.java_lang_CloneNotSupportedException);
        }

        if (InterpreterToVM.instanceOf(self, meta.java_lang_ref_Reference)) {
            profiler.profile(5);
            // HotSpot 8202260: The semantics of cloning a Reference object is not clearly defined.
            // In addition, it is questionable whether it should be supported due to its tight
            // interaction with garbage collector.
            //
            // The reachability state of a Reference object may change during GC reference
            // processing. The referent may have been cleared when it reaches its reachability
            // state. On the other hand, it may be enqueued or pending for enqueuing. Cloning a
            // Reference object with a referent that is unreachable but not yet cleared might mean
            // to resurrect the referent. A cloned enqueued Reference object will never be enqueued.
            //
            // A Reference object cannot be meaningfully cloned.

            // Non-strong references are not cloneable.
            if (InterpreterToVM.instanceOf(self, meta.java_lang_ref_WeakReference) //
                            || InterpreterToVM.instanceOf(self, meta.java_lang_ref_SoftReference) //
                            || InterpreterToVM.instanceOf(self, meta.java_lang_ref_FinalReference) //
                            || InterpreterToVM.instanceOf(self, meta.java_lang_ref_PhantomReference)) {
                profiler.profile(1);
                throw meta.throwExceptionWithMessage(meta.java_lang_CloneNotSupportedException, self.getKlass().getName().toString());
            }
        }

        final StaticObject clone = self.copy(meta.getContext());

        // If the original object is finalizable, so is the copy.
        assert self.getKlass() instanceof ObjectKlass;
        if (((ObjectKlass) self.getKlass()).hasFinalizer(meta.getContext())) {
            profiler.profile(2);
            meta.java_lang_ref_Finalizer_register.invokeDirectStatic(clone);
        }

        return clone;
    }

    @VmImpl(isJni = true)
    @SuppressFBWarnings(value = {"IMSE"}, justification = "Not dubious, .notifyAll is just forwarded from the guest.")
    public void JVM_MonitorNotifyAll(@JavaType(Object.class) StaticObject self, @Inject SubstitutionProfiler profiler) {
        try {
            InterpreterToVM.monitorNotifyAll(self.getLock(getContext()));
        } catch (IllegalMonitorStateException e) {
            profiler.profile(0);
            Meta meta = getMeta();
            throw meta.throwException(meta.java_lang_IllegalMonitorStateException);
        }
    }

    @VmImpl(isJni = true)
    @SuppressFBWarnings(value = {"IMSE"}, justification = "Not dubious, .notify is just forwarded from the guest.")
    public void JVM_MonitorNotify(@JavaType(Object.class) StaticObject self, @Inject SubstitutionProfiler profiler) {
        try {
            InterpreterToVM.monitorNotify(self.getLock(getContext()));
        } catch (IllegalMonitorStateException e) {
            profiler.profile(0);
            Meta meta = getMeta();
            throw meta.throwException(meta.java_lang_IllegalMonitorStateException);
        }
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    @SuppressFBWarnings(value = {"IMSE"}, justification = "Not dubious, .wait is just forwarded from the guest.")
    @SuppressWarnings("try")
    public void JVM_MonitorWait(@JavaType(Object.class) StaticObject self, long timeout,
                    @Inject Meta meta,
                    @Inject SubstitutionProfiler profiler) {

        EspressoContext context = getContext();
        StaticObject currentThread = context.getCurrentPlatformThread();
        ThreadState state = timeout > 0 ? TIMED_OBJECT_WAIT : OBJECT_WAIT;
        Transition transition = Transition.transition(state, profiler);
        try {
            final boolean report = context.shouldReportVMEvents();
            if (report) {
                context.reportMonitorWait(self, timeout);
            }
            boolean timedOut;
            if (context.getEspressoEnv().EnableManagement) {
                Target_java_lang_Thread.incrementThreadCounter(currentThread, meta.HIDDEN_THREAD_WAITED_COUNT);
                timedOut = !InterpreterToVM.monitorWait(self.getLock(context), timeout, currentThread, self);
            } else {
                timedOut = !InterpreterToVM.monitorWait(self.getLock(context), timeout);
            }
            if (report) {
                context.reportMonitorWaited(self, timedOut);
            }
        } catch (GuestInterruptedException e) {
            profiler.profile(0);
            if (getThreadAccess().isInterrupted(currentThread, true)) {
                throw meta.throwExceptionWithMessage(meta.java_lang_InterruptedException, e.getMessage());
            }
            getThreadAccess().checkDeprecatedThreadStatus(currentThread);
        } catch (IllegalMonitorStateException e) {
            profiler.profile(1);
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalMonitorStateException, e.getMessage());
        } catch (IllegalArgumentException e) {
            profiler.profile(2);
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, e.getMessage());
        } finally {
            transition.restore(profiler);
        }
    }

    // endregion objects

    // region class

    @VmImpl(isJni = true)
    public int JVM_GetClassModifiers(@JavaType(Class.class) StaticObject clazz) {
        Klass klass = clazz.getMirrorKlass(getMeta());
        if (klass.isPrimitive()) {
            final int primitiveModifiers = ACC_ABSTRACT | ACC_FINAL | ACC_PUBLIC;
            assert klass.getClassModifiers() == primitiveModifiers;
            return klass.getClassModifiers();
        }
        return klass.getClassModifiers();
    }

    @VmImpl(isJni = true)
    public @JavaType(String.class) StaticObject JVM_InitClassName(@JavaType(Class.class) StaticObject self) {
        StaticObject name = JVM_GetClassName(self);
        getMeta().java_lang_Class_name.set(self, name);
        return name;
    }

    @VmImpl(isJni = true)
    public @JavaType(Class[].class) StaticObject JVM_GetClassInterfaces(@JavaType(Class.class) StaticObject self) {
        final Klass[] superInterfaces = self.getMirrorKlass(getMeta()).getImplementedInterfaces();

        StaticObject instance = getMeta().java_lang_Class.allocateReferenceArray(superInterfaces.length, new IntFunction<StaticObject>() {
            @Override
            public StaticObject apply(int i) {
                return superInterfaces[i].mirror();
            }
        });

        return instance;
    }

    @VmImpl(isJni = true)
    public boolean JVM_IsInterface(@JavaType(Class.class) StaticObject self) {
        return self.getMirrorKlass(getMeta()).isInterface();
    }

    @VmImpl(isJni = true)
    public @JavaType(Object[].class) StaticObject JVM_GetClassSigners(@JavaType(Class.class) StaticObject self, @Inject EspressoContext context) {
        Klass klass = self.getMirrorKlass(getMeta());
        if (klass.isPrimitive()) {
            return StaticObject.NULL;
        }
        assert getMeta().HIDDEN_SIGNERS != null;
        StaticObject signersArray = (StaticObject) getMeta().HIDDEN_SIGNERS.getHiddenObject(self);
        if (signersArray == null || StaticObject.isNull(signersArray)) {
            return StaticObject.NULL;
        }
        return signersArray.copy(context);
    }

    @VmImpl(isJni = true)
    public void JVM_SetClassSigners(@JavaType(Class.class) StaticObject self, @JavaType(Object[].class) StaticObject signers) {
        Klass klass = self.getMirrorKlass(getMeta());
        if (!klass.isPrimitive() && !klass.isArray()) {
            assert getMeta().HIDDEN_SIGNERS != null;
            getMeta().HIDDEN_SIGNERS.setHiddenObject(self, signers);
        }
    }

    @VmImpl(isJni = true)
    public boolean JVM_IsArrayClass(@JavaType(Class.class) StaticObject self) {
        return self.getMirrorKlass(getMeta()).isArray();
    }

    @VmImpl(isJni = true)
    public boolean JVM_IsHiddenClass(@JavaType(Class.class) StaticObject self) {
        return self.getMirrorKlass(getMeta()).isHidden();
    }

    @VmImpl(isJni = true)
    public boolean JVM_IsPrimitiveClass(@JavaType(Class.class) StaticObject self) {
        return self.getMirrorKlass(getMeta()).isPrimitive();
    }

    @TruffleBoundary
    @VmImpl(isJni = true)
    public @JavaType(java.lang.reflect.Field[].class) StaticObject JVM_GetClassDeclaredFields(@JavaType(Class.class) StaticObject self, boolean publicOnly) {
        // TODO(peterssen): From Hostpot: 4496456 We need to filter out
        // java.lang.Throwable.backtrace.
        Meta meta = getMeta();
        ArrayList<Field> collectedFields = new ArrayList<>();
        Klass klass = self.getMirrorKlass(getMeta());
        klass.ensureLinked();
        for (Field f : klass.getDeclaredFields()) {
            if (!publicOnly || f.isPublic()) {
                collectedFields.add(f);
            }
        }
        final Field[] fields = collectedFields.toArray(Field.EMPTY_ARRAY);

        StaticObject fieldsArray = meta.java_lang_reflect_Field.allocateReferenceArray(fields.length, new IntFunction<StaticObject>() {
            @Override
            public StaticObject apply(int i) {
                return fields[i].makeMirror(meta);
            }
        });

        return fieldsArray;
    }

    // TODO(tg): inject constructor calltarget.
    @TruffleBoundary
    @VmImpl(isJni = true)
    public @JavaType(Constructor[].class) StaticObject JVM_GetClassDeclaredConstructors(@JavaType(Class.class) StaticObject self, boolean publicOnly) {
        Meta meta = getMeta();
        ArrayList<Method> collectedMethods = new ArrayList<>();
        Klass klass = self.getMirrorKlass(getMeta());
        klass.ensureLinked();
        for (Method m : klass.getDeclaredConstructors()) {
            if (Names._init_.equals(m.getName()) && (!publicOnly || m.isPublic())) {
                collectedMethods.add(m);
            }
        }
        final Method[] constructors = collectedMethods.toArray(Method.EMPTY_ARRAY);

        StaticObject arr = meta.java_lang_reflect_Constructor.allocateReferenceArray(constructors.length, new IntFunction<StaticObject>() {
            @Override
            public StaticObject apply(int i) {
                return constructors[i].makeConstructorMirror(meta);
            }
        });

        return arr;
    }

    // TODO(tg): inject constructor calltarget.
    @TruffleBoundary
    @VmImpl(isJni = true)
    public @JavaType(java.lang.reflect.Method[].class) StaticObject JVM_GetClassDeclaredMethods(@JavaType(Class.class) StaticObject self, boolean publicOnly) {
        Meta meta = getMeta();
        ArrayList<Method> collectedMethods = new ArrayList<>();
        Klass klass = self.getMirrorKlass(getMeta());
        klass.ensureLinked();
        for (Method m : klass.getDeclaredMethods()) {
            if ((!publicOnly || m.isPublic()) &&
                            // Filter out <init> and <clinit> from reflection.
                            !Names._init_.equals(m.getName()) && !Names._clinit_.equals(m.getName())) {
                collectedMethods.add(m);
            }
        }
        final Method[] methods = collectedMethods.toArray(Method.EMPTY_ARRAY);

        return meta.java_lang_reflect_Method.allocateReferenceArray(methods.length, new IntFunction<StaticObject>() {
            @Override
            public StaticObject apply(int i) {
                return methods[i].makeMethodMirror(meta);
            }
        });

    }

    @TruffleBoundary
    @VmImpl(isJni = true)
    public @JavaType(Class[].class) StaticObject JVM_GetDeclaredClasses(@JavaType(Class.class) StaticObject self) {
        Meta meta = getMeta();
        Klass klass = self.getMirrorKlass(getMeta());
        if (klass.isPrimitive() || klass.isArray()) {
            return meta.java_lang_Class.allocateReferenceArray(0);
        }
        ObjectKlass instanceKlass = (ObjectKlass) klass;
        InnerClassesAttribute innerClasses = (InnerClassesAttribute) instanceKlass.getAttribute(InnerClassesAttribute.NAME);

        if (innerClasses == null || innerClasses.entryCount() == 0) {
            return meta.java_lang_Class.allocateReferenceArray(0);
        }

        RuntimeConstantPool pool = instanceKlass.getConstantPool();
        List<Klass> innerKlasses = new ArrayList<>();

        for (int i = 0; i < innerClasses.entryCount(); i++) {
            InnerClassesAttribute.Entry entry = innerClasses.entryAt(i);
            if (entry.innerClassIndex != 0 && entry.outerClassIndex != 0) {
                // Check to see if the name matches the class we're looking for
                // before attempting to find the class.
                Symbol<Name> outerDescriptor = pool.className(entry.outerClassIndex);

                // Check descriptors/names before resolving.
                if (outerDescriptor.equals(instanceKlass.getName())) {
                    Klass outerKlass = pool.resolvedKlassAt(instanceKlass, entry.outerClassIndex);
                    if (outerKlass == instanceKlass) {
                        Klass innerKlass = pool.resolvedKlassAt(instanceKlass, entry.innerClassIndex);
                        // HotSpot:
                        // Throws an exception if outer klass has not declared k as
                        // an inner klass
                        // Reflection::check_for_inner_class(k, inner_klass, true, CHECK_NULL);
                        // TODO(peterssen): The check in HotSpot is redundant.
                        innerKlasses.add(innerKlass);
                    }
                }
            }
        }

        return meta.java_lang_Class.allocateReferenceArray(innerKlasses.size(), new IntFunction<StaticObject>() {
            @Override
            public StaticObject apply(int index) {
                return innerKlasses.get(index).mirror();
            }
        });
    }

    /**
     * Return the enclosing class; or null for: primitives, arrays, anonymous classes (declared
     * inside methods).
     */
    private static Klass computeEnclosingClass(ObjectKlass klass) {
        InnerClassesAttribute innerClasses = (InnerClassesAttribute) klass.getAttribute(InnerClassesAttribute.NAME);
        if (innerClasses == null) {
            return null;
        }
        RuntimeConstantPool pool = klass.getConstantPool();

        // TODO(peterssen): Follow HotSpot implementation described below.
        // Throws an exception if outer klass has not declared k as an inner klass
        // We need evidence that each klass knows about the other, or else
        // the system could allow a spoof of an inner class to gain access rights.
        for (int i = 0; i < innerClasses.entryCount(); i++) {
            InnerClassesAttribute.Entry entry = innerClasses.entryAt(i);
            if (entry.innerClassIndex != 0) {
                Symbol<Name> innerDescriptor = pool.className(entry.innerClassIndex);
                // Check decriptors/names before resolving.
                if (innerDescriptor.equals(klass.getName())) {
                    Klass innerKlass = pool.resolvedKlassAt(klass, entry.innerClassIndex);
                    if (innerKlass == klass) {
                        if (entry.outerClassIndex != 0) {
                            return pool.resolvedKlassAt(klass, entry.outerClassIndex);
                        } else {
                            return null;
                        }
                    }
                }
            }
        }
        return null;
    }

    @VmImpl(isJni = true)
    public @JavaType(Class.class) StaticObject JVM_GetDeclaringClass(@JavaType(Class.class) StaticObject self) {
        // Primitives and arrays are not "enclosed".
        if (!(self.getMirrorKlass(getMeta()) instanceof ObjectKlass k)) {
            return StaticObject.NULL;
        }
        Klass outerKlass = computeEnclosingClass(k);
        if (outerKlass == null) {
            return StaticObject.NULL;
        }
        return outerKlass.mirror();
    }

    @SuppressWarnings("unchecked")
    @VmImpl(isJni = true)
    public @JavaType(String.class) StaticObject JVM_GetSimpleBinaryName(@JavaType(Class.class) StaticObject self) {
        Klass k = self.getMirrorKlass(getMeta());
        if (k.isPrimitive() || k.isArray()) {
            return StaticObject.NULL;
        }
        ObjectKlass klass = (ObjectKlass) k;
        RuntimeConstantPool pool = klass.getConstantPool();
        InnerClassesAttribute inner = klass.getInnerClasses();
        if (inner == null) {
            return StaticObject.NULL;
        }
        for (int i = 0; i < inner.entryCount(); i++) {
            InnerClassesAttribute.Entry entry = inner.entryAt(i);
            int innerClassIndex = entry.innerClassIndex;
            if (innerClassIndex != 0) {
                if (pool.className(innerClassIndex) == klass.getName() && pool.resolvedKlassAt(klass, innerClassIndex) == k) {
                    if (entry.innerNameIndex == 0) {
                        break;
                    } else {
                        // Cast is safe-ish
                        Symbol<Name> innerName = (Symbol<Name>) pool.utf8At(entry.innerNameIndex, "inner class name");
                        return getMeta().toGuestString(innerName);
                    }
                }
            }
        }
        return StaticObject.NULL;
    }

    @VmImpl(isJni = true)
    public @JavaType(String.class) StaticObject JVM_GetClassSignature(@JavaType(Class.class) StaticObject self) {
        if (self.getMirrorKlass(getMeta()) instanceof ObjectKlass klass) {
            SignatureAttribute signature = (SignatureAttribute) klass.getAttribute(Names.Signature);
            if (signature != null) {
                String sig = klass.getConstantPool().utf8At(signature.getSignatureIndex()).toString();
                return getMeta().toGuestString(sig);
            }
        }
        return StaticObject.NULL;
    }

    @VmImpl(isJni = true)
    public @JavaType(byte[].class) StaticObject JVM_GetClassAnnotations(@JavaType(Class.class) StaticObject self) {
        Klass klass = self.getMirrorKlass(getMeta());
        if (klass instanceof ObjectKlass) {
            Attribute annotations = ((ObjectKlass) klass).getAttribute(Names.RuntimeVisibleAnnotations);
            if (annotations != null) {
                return StaticObject.wrap(annotations.getData(), getMeta());
            }
        }
        return StaticObject.NULL;
    }

    @VmImpl(isJni = true)
    public @JavaType(byte[].class) StaticObject JVM_GetClassTypeAnnotations(@JavaType(Class.class) StaticObject self) {
        Klass klass = self.getMirrorKlass(getMeta());
        if (klass instanceof ObjectKlass) {
            Attribute annotations = ((ObjectKlass) klass).getAttribute(Names.RuntimeVisibleTypeAnnotations);
            if (annotations != null) {
                return StaticObject.wrap(annotations.getData(), getMeta());
            }
        }
        return StaticObject.NULL;
    }

    @VmImpl(isJni = true)
    public @JavaType(internalName = "Lsun/reflect/ConstantPool;") StaticObject JVM_GetClassConstantPool(@JavaType(Class.class) StaticObject self) {
        Klass klass = self.getMirrorKlass(getMeta());
        if (klass.isArray() || klass.isPrimitive()) {
            // No constant pool for arrays and primitives.
            return StaticObject.NULL;
        }
        StaticObject cp = getAllocator().createNew(getMeta().sun_reflect_ConstantPool);
        getMeta().sun_reflect_ConstantPool_constantPoolOop.setObject(cp, self);
        return cp;
    }

    @TruffleBoundary
    @VmImpl(isJni = true)
    public boolean JVM_DesiredAssertionStatus(@SuppressWarnings("unused") @JavaType(Class.class) StaticObject unused, @JavaType(Class.class) StaticObject clazz) {
        if (StaticObject.isNull(clazz.getMirrorKlass(getMeta()).getDefiningClassLoader())) {
            return EspressoOptions.EnableSystemAssertions.getValue(getMeta().getContext().getEnv().getOptions());
        }
        return EspressoOptions.EnableAssertions.getValue(getMeta().getContext().getEnv().getOptions());
    }

    @VmImpl(isJni = true)
    public @JavaType(Object[].class) StaticObject JVM_GetEnclosingMethodInfo(@JavaType(Class.class) StaticObject self, @Inject EspressoLanguage language) {
        Meta meta = getMeta();
        InterpreterToVM vm = meta.getInterpreterToVM();
        if (self.getMirrorKlass(getMeta()) instanceof ObjectKlass klass) {
            EnclosingMethodAttribute enclosingMethodAttr = klass.getEnclosingMethod();
            if (enclosingMethodAttr == null) {
                return StaticObject.NULL;
            }
            int classIndex = enclosingMethodAttr.getClassIndex();
            if (classIndex == 0) {
                return StaticObject.NULL;
            }
            StaticObject arr = meta.java_lang_Object.allocateReferenceArray(3);
            RuntimeConstantPool pool = klass.getConstantPool();
            Klass enclosingKlass = pool.resolvedKlassAt(klass, classIndex);

            vm.setArrayObject(language, enclosingKlass.mirror(), 0, arr);

            // Not a method, but a NameAndType entry.
            int nameAndTypeIndex = enclosingMethodAttr.getNameAndTypeIndex();
            if (nameAndTypeIndex != 0) {
                StaticObject name = meta.toGuestString(pool.nameAndTypeName(nameAndTypeIndex));
                StaticObject desc = meta.toGuestString(pool.nameAndTypeDescriptor(nameAndTypeIndex));

                vm.setArrayObject(language, name, 1, arr);
                vm.setArrayObject(language, desc, 2, arr);
            }

            return arr;
        }
        return StaticObject.NULL;
    }

    @SuppressWarnings("unchecked")
    public static StaticObject toGuestComponent(RecordAttribute.RecordComponentInfo recordInfo, Meta meta, ObjectKlass klass) {
        assert meta.getJavaVersion().java16OrLater();
        RuntimeConstantPool pool = klass.getConstantPool();
        StaticObject component = meta.java_lang_reflect_RecordComponent.allocateInstance(meta.getContext());
        Symbol<Name> nameSymbol = (Symbol<Name>) pool.utf8At(recordInfo.getNameIndex());
        Symbol<Type> typeSymbol = (Symbol<Type>) pool.utf8At(recordInfo.getDescriptorIndex());
        Symbol<Signature> signature = meta.getSignatures().makeRaw(typeSymbol);
        meta.java_lang_reflect_RecordComponent_clazz.setObject(component, klass.mirror());
        meta.java_lang_reflect_RecordComponent_name.setObject(component, meta.toGuestString(nameSymbol));
        meta.java_lang_reflect_RecordComponent_type.setObject(component, meta.resolveSymbolAndAccessCheck(typeSymbol, klass).mirror());

        // Find and set accessor
        Method m = klass.lookupMethod(nameSymbol, signature);
        boolean validMethod = m != null && !m.isStatic() && !m.isConstructor();
        meta.java_lang_reflect_RecordComponent_accessor.setObject(component, validMethod ? m.makeMirror(meta) : StaticObject.NULL);

        // Find and set generic signature
        SignatureAttribute genericSignatureAttribute = (SignatureAttribute) recordInfo.getAttribute(SignatureAttribute.NAME);
        meta.java_lang_reflect_RecordComponent_signature.setObject(component,
                        genericSignatureAttribute != null ? meta.toGuestString(pool.utf8At(genericSignatureAttribute.getSignatureIndex())) : StaticObject.NULL);

        // Find and set annotations
        doAnnotation(recordInfo, component, Names.RuntimeVisibleAnnotations, meta.java_lang_reflect_RecordComponent_annotations, meta);
        doAnnotation(recordInfo, component, Names.RuntimeVisibleTypeAnnotations, meta.java_lang_reflect_RecordComponent_typeAnnotations, meta);

        return component;
    }

    private static void doAnnotation(RecordAttribute.RecordComponentInfo recordInfo, StaticObject component, Symbol<Name> attrName, Field f, Meta meta) {
        Attribute attr = recordInfo.getAttribute(attrName);
        f.setObject(component, attr == null ? StaticObject.NULL : StaticObject.wrap(attr.getData(), meta));
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public @JavaType(internalName = "[Ljava/lang/reflect/RecordComponent;") StaticObject JVM_GetRecordComponents(@JavaType(Class.class) StaticObject self) {
        Klass k = self.getMirrorKlass(getMeta());
        if (!(k instanceof ObjectKlass klass)) {
            return StaticObject.NULL;
        }
        RecordAttribute record = (RecordAttribute) klass.getAttribute(RecordAttribute.NAME);
        if (record == null) {
            return StaticObject.NULL;
        }
        RecordAttribute.RecordComponentInfo[] components = record.getComponents();
        return getMeta().java_lang_reflect_RecordComponent.allocateReferenceArray(components.length, (i) -> toGuestComponent(components[i], getMeta(), klass));
    }

    @VmImpl(isJni = true)
    public boolean JVM_IsRecord(@JavaType(Class.class) StaticObject self) {
        Klass klass = self.getMirrorKlass(getMeta());
        if (klass instanceof ObjectKlass) {
            return ((ObjectKlass) klass).isRecord();
        }
        return false;
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public @JavaType(Class[].class) StaticObject JVM_GetPermittedSubclasses(@JavaType(Class.class) StaticObject self) {
        Klass k = self.getMirrorKlass(getMeta());
        if (!(k instanceof ObjectKlass klass)) {
            return StaticObject.NULL;
        }
        if (!klass.isSealed()) {
            return StaticObject.NULL;
        }
        char[] classes = ((PermittedSubclassesAttribute) klass.getAttribute(PermittedSubclassesAttribute.NAME)).getClasses();
        StaticObject[] permittedSubclasses = new StaticObject[classes.length];
        RuntimeConstantPool pool = klass.getConstantPool();
        int nClasses = 0;
        for (int index : classes) {
            Klass permitted;
            try {
                permitted = pool.resolvedKlassAt(klass, index);
            } catch (EspressoException e) {
                /* Suppress and continue */
                continue;
            }
            if (permitted instanceof ObjectKlass) {
                permittedSubclasses[nClasses++] = permitted.mirror();
            }
        }
        if (nClasses == permittedSubclasses.length) {
            return StaticObject.createArray(getMeta().java_lang_Class_array, permittedSubclasses, getContext());
        }
        return getMeta().java_lang_Class.allocateReferenceArray(nClasses, (i) -> permittedSubclasses[i]);
    }

    @VmImpl(isJni = true)
    public int JVM_GetClassAccessFlags(@JavaType(Class.class) StaticObject clazz) {
        Klass klass = clazz.getMirrorKlass(getMeta());
        if (klass.isPrimitive()) {
            final int primitiveFlags = ACC_ABSTRACT | ACC_FINAL | ACC_PUBLIC;
            assert klass.getModifiers() == primitiveFlags;
            return klass.getModifiers();
        }
        return klass.getModifiers() & Constants.JVM_ACC_WRITTEN_FLAGS;
    }

    @VmImpl(isJni = true)
    public int JVM_GetClassFileVersion(@JavaType(Class.class) StaticObject clazz, @Inject SubstitutionProfiler profiler) {
        if (StaticObject.isNull(clazz)) {
            profiler.profile(0);
            throw getMeta().throwNullPointerException();
        }
        /*
         * From HotSpot:
         * 
         * Return the current class's class file version. The low order 16 bits of the returned jint
         * contain the class's major version. The high order 16 bits contain the class's minor
         * version.
         */
        Klass klass = clazz.getMirrorKlass(getMeta());
        if (klass instanceof ObjectKlass objKlass) {
            profiler.profile(1);
            ParserKlass parser = objKlass.getLinkedKlass().getParserKlass();
            return ((parser.getMinorVersion() << 16) | (parser.getMajorVersion()));
        }
        profiler.profile(2);
        // For primitives, return latest (Same as HotSpot).
        // We do the same for arrays. HotSpot just crashes in that case.
        return getJavaVersion().classFileVersion();
    }

    @VmImpl(isJni = true)
    public boolean JVM_AreNestMates(@JavaType(Class.class) StaticObject current, @JavaType(Class.class) StaticObject member) {
        return current.getMirrorKlass(getMeta()).nest() == member.getMirrorKlass(getMeta()).nest();
    }

    @VmImpl(isJni = true)
    public @JavaType(Class.class) StaticObject JVM_GetNestHost(@JavaType(Class.class) StaticObject current) {
        return current.getMirrorKlass(getMeta()).nest().mirror();
    }

    @VmImpl(isJni = true)
    public @JavaType(Class[].class) StaticObject JVM_GetNestMembers(@JavaType(Class.class) StaticObject current) {
        Klass k = current.getMirrorKlass(getMeta());
        Klass[] nestMembers = k.getNestMembers();
        StaticObject[] array = new StaticObject[nestMembers.length];
        for (int i = 0; i < nestMembers.length; i++) {
            array[i] = nestMembers[i].mirror();
        }
        return StaticObject.createArray(getMeta().java_lang_Class_array, array, getContext());
    }

    @VmImpl(isJni = true)
    public @JavaType(internalName = "Ljava/security/ProtectionDomain;") StaticObject JVM_GetProtectionDomain(@JavaType(Class.class) StaticObject current) {
        if (StaticObject.isNull(current)) {
            return StaticObject.NULL;
        }
        StaticObject pd = getMeta().HIDDEN_PROTECTION_DOMAIN.getMaybeHiddenObject(current);
        return pd == null ? StaticObject.NULL : pd;
    }

    @VmImpl(isJni = true)
    public @JavaType(String.class) StaticObject JVM_GetClassName(@JavaType(Class.class) StaticObject self) {
        Klass klass = self.getMirrorKlass(getMeta());
        // Conversion from internal form.
        String externalName = klass.getExternalName();
        // Class names must be interned.
        StaticObject guestString = getMeta().toGuestString(externalName);
        return getStrings().intern(guestString);
    }

    @VmImpl(isJni = true)
    public @JavaType(Class.class) StaticObject JVM_GetComponentType(@JavaType(Class.class) StaticObject self) {
        if (self.getMirrorKlass(getMeta()).isArray()) {
            Klass componentType = ((ArrayKlass) self.getMirrorKlass(getMeta())).getComponentType();
            return componentType.mirror();
        }
        return StaticObject.NULL;
    }

    // endregion class

    // region JNI Invocation Interface
    @VmImpl
    public static int DestroyJavaVM(@Inject EspressoContext context) {
        assert context.getCurrentPlatformThread() != null;
        try {
            context.destroyVM();
        } catch (AbstractTruffleException exit) {
            // expected
        }
        return JNI_OK;
    }

    /*
    @formatter:off
    struct JavaVMAttachArgs {
        0      |     4     jint version;
     XXX  4-byte hole
        8      |     8     char *name;
       16      |     8     jobject group;
    }
    total size (bytes):   24
    @formatter:on
     */

    @VmImpl
    @TruffleBoundary
    public int AttachCurrentThread(@Pointer TruffleObject vmPtr_, @Pointer TruffleObject penvPtr, @Pointer TruffleObject argsPtr) {
        assert NativeUtils.interopAsPointer(getJavaVM()) == NativeUtils.interopAsPointer(vmPtr_);
        return attachCurrentThread(penvPtr, argsPtr, false);
    }

    private int attachCurrentThread(@SuppressWarnings("unused") @Pointer TruffleObject penvPtr, @Pointer TruffleObject argsPtr, boolean daemon) {
        StaticObject group = null;
        String name = null;
        if (InteropLibrary.getUncached().isNull(argsPtr)) {
            getLogger().fine("AttachCurrentThread with null args");
        } else {
            JavaVMAttachArgs.JavaVMAttachArgsWrapper attachArgs = getStructs().javaVMAttachArgs.wrap(getHandles(), argsPtr);
            if (JVM_IsSupportedJNIVersion(attachArgs.version())) {
                group = attachArgs.group();
                name = NativeUtils.fromUTF8Ptr(attachArgs.name());
            } else {
                getLogger().warning(String.format("AttachCurrentThread with unsupported JavaVMAttachArgs version: 0x%08x", attachArgs.version()));
            }
        }
        StaticObject thread = getContext().createThread(Thread.currentThread(), group, name);
        if (daemon) {
            getContext().getThreadAccess().setDaemon(thread, true);
        }
        NativeUtils.writeToPointerPointer(getUncached(), penvPtr, jniEnv.getNativePointer());
        return JNI_OK;
    }

    @VmImpl
    @TruffleBoundary
    public int DetachCurrentThread(@Inject EspressoContext context, @Inject EspressoLanguage language) {
        StaticObject currentThread = context.getCurrentPlatformThread();
        if (currentThread == null) {
            return JNI_OK;
        }
        getLogger().fine(() -> {
            String guestName = getThreadAccess().getThreadName(currentThread);
            return "DetachCurrentThread: " + guestName;
        });
        // HotSpot will wait forever if the current VM this thread was attached to has exited
        // Should we reproduce this behaviour?

        Method lastJavaMethod = Truffle.getRuntime().iterateFrames(
                        new FrameInstanceVisitor<Method>() {
                            @Override
                            public Method visitFrame(FrameInstance frameInstance) {
                                Method method = getMethodFromFrame(frameInstance);
                                if (method != null && method.getContext() == context) {
                                    return method;
                                }
                                return null;
                            }
                        });
        if (lastJavaMethod != null) {
            // this thread is executing
            getLogger().warning(() -> {
                String guestName = getThreadAccess().getThreadName(currentThread);
                return "DetachCurrentThread called while thread is still executing Java code (" + guestName + ")";
            });
            return JNI_ERR;
        }
        StaticObject pendingException = language.getPendingException();
        language.clearPendingException();

        Meta meta = context.getMeta();
        try {
            if (pendingException != null) {
                meta.java_lang_Thread_dispatchUncaughtException.invokeDirectVirtual(currentThread, pendingException);
            }

            getThreadAccess().terminate(currentThread);
        } catch (EspressoException e) {
            try {
                StaticObject ex = e.getGuestException();
                String exception = ex.getKlass().getExternalName();
                String threadName = getThreadAccess().getThreadName(currentThread);
                context.getLogger().warning(String.format("Exception: %s thrown while terminating thread \"%s\"", exception, threadName));
                meta.java_lang_Throwable_printStackTrace.invokeDirectVirtual(ex);
            } catch (EspressoException ee) {
                String exception = ee.getGuestException().getKlass().getExternalName();
                context.getLogger().warning(String.format("Exception: %s thrown while trying to print stack trace", exception));
            } catch (EspressoExitException ee) {
                // ignore
            }
        } catch (EspressoExitException e) {
            // ignore
        } catch (Throwable t) {
            context.getLogger().severe("Host exception thrown while trying to terminate thread");
            t.printStackTrace();
        } finally {
            context.unregisterThread(currentThread);
        }

        return JNI_OK;
    }

    /**
     * <h3>jint GetEnv(JavaVM *vm, void **env, jint version);</h3>
     *
     * @param vmPtr_ The virtual machine instance from which the interface will be retrieved.
     * @param envPtr pointer to the location where the env interface pointer for the current thread
     *            will be placed.
     * @param version The requested JNI version.
     *
     * @return If the current thread is not attached to the VM, sets *env to NULL, and returns
     *         JNI_EDETACHED. If the specified version is not supported, sets *env to NULL, and
     *         returns JNI_EVERSION. Otherwise, sets *env to the appropriate interface, and returns
     *         JNI_OK.
     */
    @VmImpl
    @TruffleBoundary
    public int GetEnv(@Pointer TruffleObject vmPtr_, @Pointer TruffleObject envPtr, int version) {
        assert NativeUtils.interopAsPointer(getJavaVM()) == NativeUtils.interopAsPointer(vmPtr_);
        if (getUncached().isNull(envPtr)) {
            // Pointer should have been pre-null-checked.
            return JNI_ERR;
        }
        TruffleObject interopPtr = null;
        if (getContext().getEspressoEnv().EnableNativeAgents && JVMTI.isJvmtiVersion(version)) {
            // JVMTI is requested before the main thread is created.
            // Also note that every request of a JVMTI env returns a freshly created structure.
            interopPtr = jvmti.create(version);
            if (interopPtr == null) {
                return JNI_EVERSION;
            }
        }
        if (JVM_IsSupportedJNIVersion(version)) {
            StaticObject currentThread = getContext().getCurrentPlatformThread();
            if (currentThread == null) {
                return JNI_EDETACHED;
            }
            interopPtr = jniEnv.getNativePointer();
        }
        if (interopPtr != null) {
            NativeUtils.writeToPointerPointer(getUncached(), envPtr, interopPtr);
            return JNI_OK;
        }
        return JNI_EVERSION;
    }

    @VmImpl
    @TruffleBoundary
    public int AttachCurrentThreadAsDaemon(@Pointer TruffleObject vmPtr_, @Pointer TruffleObject penvPtr, @Pointer TruffleObject argsPtr) {
        assert NativeUtils.interopAsPointer(getJavaVM()) == NativeUtils.interopAsPointer(vmPtr_);
        return attachCurrentThread(penvPtr, argsPtr, true);
    }

    // endregion JNI Invocation Interface

    // region exceptions

    public interface StackElement {
        Method getMethod();

        boolean hasInfo();

        String getDeclaringClassName();

        default StaticObject getGuestDeclaringClassName(Meta meta) {
            return meta.toGuestString(getDeclaringClassName());
        }

        String getMethodName();

        int getLineNumber();

        int getBci();

        String getFileName();
    }

    public static class EspressoStackElement implements StackElement {
        /**
         * @see StackTraceElement#isNativeMethod()
         */
        public static int NATIVE_BCI = -2;
        /**
         * @see StackTraceElement#toString()
         */
        public static int UNKNOWN_BCI = -1;

        private final Method m;
        private final int bci;

        public EspressoStackElement(Method m, int bci) {
            this.m = m;
            this.bci = bci;
        }

        @Override
        public Method getMethod() {
            return m;
        }

        @Override
        public boolean hasInfo() {
            return m != null;
        }

        @Override
        public String getDeclaringClassName() {
            return m.getDeclaringKlass().getExternalName();
        }

        @Override
        public StaticObject getGuestDeclaringClassName(Meta meta) {
            ObjectKlass declaringKlass = m.getDeclaringKlass();
            StaticObject mirror = declaringKlass.mirror();
            StaticObject name = (StaticObject) meta.java_lang_Class_name.get(mirror);
            if (StaticObject.notNull(name)) {
                return name;
            }
            return StackElement.super.getGuestDeclaringClassName(meta);
        }

        @Override
        public String getMethodName() {
            return m.getNameAsString();
        }

        @Override
        public int getLineNumber() {
            return m.bciToLineNumber(bci);
        }

        @Override
        public int getBci() {
            return bci;
        }

        @Override
        public String getFileName() {
            return m.getDeclaringKlass().getSourceFile();
        }
    }

    public static class ForeignStackElement implements StackElement {

        private final String declaringClassName;
        private final String methodName;
        private final String fileName;
        private final int lineNumber;

        public ForeignStackElement(String declaringClassName, String methodName, String fileName, int lineNumber) {
            this.declaringClassName = declaringClassName;
            this.methodName = methodName;
            this.fileName = fileName;
            this.lineNumber = lineNumber;
        }

        @Override
        public Method getMethod() {
            return null;
        }

        @Override
        public boolean hasInfo() {
            return true;
        }

        @Override
        public String getDeclaringClassName() {
            return declaringClassName;
        }

        @Override
        public String getMethodName() {
            return methodName;
        }

        @Override
        public int getLineNumber() {
            return lineNumber;
        }

        @Override
        public int getBci() {
            return EspressoStackElement.UNKNOWN_BCI;
        }

        @Override
        public String getFileName() {
            return fileName;
        }
    }

    public static class StackTrace {
        public static final StackTrace EMPTY_STACK_TRACE = new StackTrace(0);
        public static final StackTrace FOREIGN_MARKER_STACK_TRACE = new StackTrace(0);

        public StackElement[] trace;
        public int size;
        public int capacity;
        private boolean hiddenTop;

        public StackTrace() {
            this(32);
        }

        private StackTrace(int size) {
            this.trace = new StackElement[size];
            this.capacity = size;
            this.size = 0;
        }

        public StaticObject toGuest(EspressoContext ctx) {
            return ctx.getMeta().java_lang_StackTraceElement.allocateReferenceArray(size, i -> {
                StaticObject ste = ctx.getMeta().java_lang_StackTraceElement.allocateInstance(ctx);
                VM.fillInElement(ste, trace[i], ctx.getMeta());
                return ste;
            });
        }

        public StackElement top() {
            if (size > 0) {
                return trace[0];
            }
            return null;
        }

        public boolean isSkippedFramesHidden() {
            return hiddenTop;
        }

        void add(StackElement e) {
            if (size < capacity) {
                trace[size++] = e;
            } else {
                trace = Arrays.copyOf(trace, capacity <<= 1);
                trace[size++] = e;
            }
        }

        void markTopFrameHidden() {
            hiddenTop = true;
        }
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public @JavaType(String.class) StaticObject JVM_GetExtendedNPEMessage(@SuppressWarnings("unused") @JavaType(Throwable.class) StaticObject throwable) {
        if (!getContext().getEnv().getOptions().get(EspressoOptions.ShowCodeDetailsInExceptionMessages)) {
            return StaticObject.NULL;
        }
        return getMeta().toGuestString(ExtendedNPEMessage.getNPEMessage(throwable));
    }

    @VmImpl(isJni = true)
    public @JavaType(Throwable.class) StaticObject JVM_FillInStackTrace(@JavaType(Throwable.class) StaticObject self, @SuppressWarnings("unused") int dummy) {
        return InterpreterToVM.fillInStackTrace(self, getMeta());
    }

    @VmImpl(isJni = true)
    public int JVM_GetStackTraceDepth(@JavaType(Throwable.class) StaticObject self) {
        Meta meta = getMeta();
        StackTrace frames = EspressoException.getFrames(self, meta);
        if (frames == null) {
            return 0;
        }
        return frames.size;
    }

    @VmImpl(isJni = true)
    public @JavaType(StackTraceElement.class) StaticObject JVM_GetStackTraceElement(@JavaType(Throwable.class) StaticObject self, int index,
                    @Inject SubstitutionProfiler profiler) {
        Meta meta = getMeta();
        if (index < 0) {
            profiler.profile(0);
            throw meta.throwException(meta.java_lang_IndexOutOfBoundsException);
        }
        StaticObject ste = meta.java_lang_StackTraceElement.allocateInstance(getContext());
        StackTrace frames = EspressoException.getFrames(self, meta);
        if (frames == null || index >= frames.size) {
            profiler.profile(1);
            throw meta.throwException(meta.java_lang_IndexOutOfBoundsException);
        }
        StackElement stackElement = frames.trace[index];
        if (!stackElement.hasInfo()) {
            return StaticObject.NULL;
        }
        fillInElement(ste, stackElement, meta);
        return ste;
    }

    // endregion exceptions

    // region ConstantPool

    private static void checkTag(ConstantPool pool, int index, ConstantPool.Tag expected, Meta meta, SubstitutionProfiler profiler) {
        ConstantPool.Tag target = pool.tagAt(index);
        if (target != expected) {
            profiler.profile(0);
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "Wrong type at constant pool index");
        }
    }

    @VmImpl(isJni = true)
    public int JVM_ConstantPoolGetSize(@SuppressWarnings("unused") @JavaType(Object.class) StaticObject unused, @JavaType(Object.class) StaticObject jcpool) {
        return jcpool.getMirrorKlass(getMeta()).getConstantPool().length();
    }

    @VmImpl(isJni = true)
    public @JavaType(Class.class) StaticObject JVM_ConstantPoolGetClassAt(@SuppressWarnings("unused") @JavaType(Object.class) StaticObject unused, @JavaType(Object.class) StaticObject jcpool,
                    int index,
                    @Inject Meta meta, @Inject SubstitutionProfiler profiler) {
        checkTag(jcpool.getMirrorKlass(getMeta()).getConstantPool(), index, ConstantPool.Tag.CLASS, meta, profiler);
        return jcpool.getMirrorKlass(getMeta()).getConstantPool().resolvedKlassAt(null, index).mirror();
    }

    @VmImpl(isJni = true)
    public double JVM_ConstantPoolGetDoubleAt(@SuppressWarnings("unused") @JavaType(Object.class) StaticObject unused, @JavaType(Object.class) StaticObject jcpool, int index,
                    @Inject Meta meta, @Inject SubstitutionProfiler profiler) {
        checkTag(jcpool.getMirrorKlass(getMeta()).getConstantPool(), index, ConstantPool.Tag.DOUBLE, meta, profiler);
        return jcpool.getMirrorKlass(getMeta()).getConstantPool().doubleAt(index);
    }

    @VmImpl(isJni = true)
    public float JVM_ConstantPoolGetFloatAt(@SuppressWarnings("unused") @JavaType(Object.class) StaticObject unused, @JavaType(Object.class) StaticObject jcpool, int index,
                    @Inject Meta meta, @Inject SubstitutionProfiler profiler) {
        checkTag(jcpool.getMirrorKlass(getMeta()).getConstantPool(), index, ConstantPool.Tag.FLOAT, meta, profiler);
        return jcpool.getMirrorKlass(getMeta()).getConstantPool().floatAt(index);
    }

    @VmImpl(isJni = true)
    public @JavaType(String.class) StaticObject JVM_ConstantPoolGetStringAt(@SuppressWarnings("unused") @JavaType(Object.class) StaticObject unused, @JavaType(Object.class) StaticObject jcpool,
                    int index,
                    @Inject Meta meta, @Inject SubstitutionProfiler profiler) {
        checkTag(jcpool.getMirrorKlass(getMeta()).getConstantPool(), index, ConstantPool.Tag.STRING, meta, profiler);
        return jcpool.getMirrorKlass(getMeta()).getConstantPool().resolvedStringAt(index);
    }

    @VmImpl(isJni = true)
    public @JavaType(String.class) StaticObject JVM_ConstantPoolGetUTF8At(@SuppressWarnings("unused") @JavaType(Object.class) StaticObject unused, @JavaType(Object.class) StaticObject jcpool,
                    int index,
                    @Inject Meta meta, @Inject SubstitutionProfiler profiler) {
        checkTag(jcpool.getMirrorKlass(getMeta()).getConstantPool(), index, ConstantPool.Tag.UTF8, meta, profiler);
        return getMeta().toGuestString(jcpool.getMirrorKlass(getMeta()).getConstantPool().utf8At(index).toString());
    }

    @VmImpl(isJni = true)
    public int JVM_ConstantPoolGetIntAt(@SuppressWarnings("unused") @JavaType(Object.class) StaticObject unused, @JavaType(Object.class) StaticObject jcpool, int index,
                    @Inject Meta meta, @Inject SubstitutionProfiler profiler) {
        checkTag(jcpool.getMirrorKlass(getMeta()).getConstantPool(), index, ConstantPool.Tag.INTEGER, meta, profiler);
        return jcpool.getMirrorKlass(getMeta()).getConstantPool().intAt(index);
    }

    @VmImpl(isJni = true)
    public long JVM_ConstantPoolGetLongAt(@SuppressWarnings("unused") @JavaType(Object.class) StaticObject unused, @JavaType(Object.class) StaticObject jcpool, int index,
                    @Inject Meta meta, @Inject SubstitutionProfiler profiler) {
        checkTag(jcpool.getMirrorKlass(getMeta()).getConstantPool(), index, ConstantPool.Tag.LONG, meta, profiler);
        return jcpool.getMirrorKlass(getMeta()).getConstantPool().longAt(index);
    }

    // endregion ConstantPool

    // region class loading

    private Symbol<Type> namePtrToInternal(TruffleObject namePtr) {
        String name = NativeUtils.interopPointerToString(namePtr);
        return nameToInternal(name);
    }

    public Symbol<Type> nameToInternal(String name) {
        Symbol<Type> type = null;
        if (name != null) {
            String internalName = name;
            if (!name.startsWith("[")) {
                // Force 'L' type.
                internalName = "L" + name + ";";
            }
            if (!Validation.validTypeDescriptor(ByteSequence.create(internalName), false)) {
                throw getMeta().throwExceptionWithMessage(getMeta().java_lang_NoClassDefFoundError, name);
            }
            type = getTypes().fromClassGetName(internalName);
        }
        return type;
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public @JavaType(Class.class) StaticObject JVM_LookupDefineClass(
                    @JavaType(Class.class) StaticObject lookup,
                    @Pointer TruffleObject namePtr,
                    @Pointer TruffleObject bufPtr,
                    int len,
                    @JavaType(internalName = "Ljava/security/ProtectionDomain;") StaticObject pd,
                    boolean initialize,
                    int flags,
                    @JavaType(Object.class) StaticObject classData) {
        Symbol<Type> type = namePtrToInternal(namePtr); // can be null
        if (StaticObject.isNull(lookup)) {
            throw getMeta().throwExceptionWithMessage(getMeta().java_lang_InternalError, "Lookup class is null");
        }
        assert !getUncached().isNull(bufPtr);
        ByteBuffer buf = NativeUtils.directByteBuffer(bufPtr, len, JavaKind.Byte);
        final byte[] bytes = new byte[len];
        buf.get(bytes);

        return lookupDefineClass(lookup, type, bytes, pd, initialize, flags, classData);
    }

    public StaticObject lookupDefineClass(
                    StaticObject lookup,
                    Symbol<Type> type, byte[] bytes, StaticObject pd,
                    boolean initialize,
                    int flags,
                    StaticObject classData) {
        assert lookup.getMirrorKlass(getMeta()) instanceof ObjectKlass;

        boolean isNestMate = (flags & NESTMATE_CLASS) == NESTMATE_CLASS;
        boolean isHidden = (flags & HIDDEN_CLASS) == HIDDEN_CLASS;
        boolean isStrong = (flags & STRONG_LOADER_LINK) == STRONG_LOADER_LINK;
        boolean vmAnnotations = (flags & ACCESS_VM_ANNOTATIONS) == ACCESS_VM_ANNOTATIONS;

        ObjectKlass nest = null;
        if (isNestMate) {
            nest = (ObjectKlass) lookup.getMirrorKlass(getMeta()).nest();
        }
        if (!isHidden) {
            if (!StaticObject.isNull(classData)) {
                throw getMeta().throwExceptionWithMessage(getMeta().java_lang_IllegalArgumentException, "classData is only applicable for hidden classes");
            }
            if (isNestMate) {
                throw getMeta().throwExceptionWithMessage(getMeta().java_lang_IllegalArgumentException, "dynamic nestmate is only applicable for hidden classes");
            }
            if (!isStrong) {
                throw getMeta().throwExceptionWithMessage(getMeta().java_lang_IllegalArgumentException, "an ordinary class must be strongly referenced by its defining loader");
            }
            if (vmAnnotations) {
                throw getMeta().throwExceptionWithMessage(getMeta().java_lang_IllegalArgumentException, "vm annotations only allowed for hidden classes");
            }
            if (flags != STRONG_LOADER_LINK) {
                throw getMeta().throwExceptionWithMessage(getMeta().java_lang_IllegalArgumentException, String.format("invalid flag 0x%x", flags));
            }
        }
        StaticObject loader = lookup.getMirrorKlass(getMeta()).getDefiningClassLoader();

        ObjectKlass k;
        try {
            if (isHidden) {
                // Special handling
                k = getContext().getRegistries().defineKlass(type, bytes, loader, new ClassRegistry.ClassDefinitionInfo(pd, nest, classData, isStrong, vmAnnotations));
            } else {
                k = getContext().getRegistries().defineKlass(type, bytes, loader, new ClassRegistry.ClassDefinitionInfo(pd));
            }
        } catch (EspressoClassLoadingException e) {
            throw e.asGuestException(getMeta());
        }

        if (initialize) {
            k.safeInitialize();
        } else {
            k.ensureLinked();
        }
        return k.mirror();
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public @JavaType(Class.class) StaticObject JVM_DefineClass(@Pointer TruffleObject namePtr,
                    @JavaType(ClassLoader.class) StaticObject loader,
                    @Pointer TruffleObject bufPtr, int len,
                    @JavaType(internalName = "Ljava/security/ProtectionDomain;") StaticObject pd) {
        ByteBuffer buf = NativeUtils.directByteBuffer(bufPtr, len, JavaKind.Byte);
        final byte[] bytes = new byte[len];
        buf.get(bytes);

        Symbol<Type> type = namePtrToInternal(namePtr); // can be null
        return defineClass(type, loader, pd, bytes);
    }

    public StaticObject defineClass(Symbol<Type> type, StaticObject loader, StaticObject pd, byte[] bytes) {

        StaticObject clazz;
        try {
            clazz = getContext().getRegistries().defineKlass(type, bytes, loader, new ClassRegistry.ClassDefinitionInfo(pd)).mirror();
        } catch (EspressoClassLoadingException e) {
            throw e.asGuestException(getMeta());
        }
        assert clazz != null;
        return clazz;
    }

    @VmImpl(isJni = true)
    public @JavaType(Class.class) StaticObject JVM_DefineClassWithSource(@Pointer TruffleObject namePtr, @JavaType(ClassLoader.class) StaticObject loader, @Pointer TruffleObject bufPtr, int len,
                    @JavaType(internalName = "Ljava/security/ProtectionDomain;") StaticObject pd, @SuppressWarnings("unused") @Pointer TruffleObject source) {
        // FIXME(peterssen): Source is ignored.
        return JVM_DefineClass(namePtr, loader, bufPtr, len, pd);
    }

    @TruffleBoundary
    @VmImpl(isJni = true)
    public @JavaType(Class.class) StaticObject JVM_FindLoadedClass(@JavaType(ClassLoader.class) StaticObject loader, @JavaType(String.class) StaticObject name) {
        if (StaticObject.isNull(name)) {
            return StaticObject.NULL;
        }
        String classGetName = getMeta().toHostString(name);
        String internalName = TypeSymbols.internalFromClassName(classGetName);
        ByteSequence internalNameBytes = ByteSequence.create(internalName);
        /*
         * Possible optimization: Check if the elemental type (beware of arrays) symbol exists,
         * otherwise, the type is not loaded.
         */
        Symbol<Type> type = getTypes().getOrCreateValidType(internalNameBytes);
        if (type == null) {
            return StaticObject.NULL;
        }
        // HotSpot skips reflection (DelegatingClassLoader) class loaders.
        Klass klass = getRegistries().findLoadedClass(type, nonReflectionClassLoader(loader));
        if (klass == null) {
            return StaticObject.NULL;
        }
        return klass.mirror();
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public @JavaType(Class.class) StaticObject JVM_FindClassFromBootLoader(@Pointer TruffleObject namePtr) {
        String name = NativeUtils.interopPointerToString(namePtr);
        return findClassFromBootLoader(name);
    }

    public StaticObject findClassFromBootLoader(String name) {
        if (name == null) {
            return StaticObject.NULL;
        }

        String internalName = name;
        if (!name.startsWith("[")) {
            // Force 'L' type.
            internalName = "L" + name + ";";
        }

        if (!Validation.validTypeDescriptor(ByteSequence.create(internalName), false)) {
            return StaticObject.NULL;
        }

        Symbol<Type> type = getTypes().fromClassGetName(internalName);
        if (TypeSymbols.isPrimitive(type)) {
            return StaticObject.NULL;
        }
        Klass klass = getMeta().resolveSymbolOrNull(type, StaticObject.NULL, StaticObject.NULL);
        if (klass == null) {
            return StaticObject.NULL;
        }

        return klass.mirror();
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public @JavaType(Class.class) StaticObject JVM_FindClassFromCaller(@Pointer TruffleObject namePtr,
                    boolean init, @JavaType(ClassLoader.class) StaticObject loader,
                    @JavaType(Class.class) StaticObject caller) {
        Symbol<Type> type = namePtrToInternal(namePtr);
        return findClassFromCaller(type, init, loader, caller);
    }

    public StaticObject findClassFromCaller(Symbol<Type> type, boolean init, StaticObject loader, StaticObject caller) {
        Meta meta = getMeta();
        Klass result;
        if (TypeSymbols.isPrimitive(type)) {
            result = null;
        } else {
            StaticObject protectionDomain;
            // If loader is null, shouldn't call ClassLoader.checkPackageAccess; otherwise get
            // NPE. Put it in another way, the bootstrap class loader has all permission and
            // thus no checkPackageAccess equivalence in the VM class loader.
            // The caller is also passed as NULL by the java code if there is no security
            // manager to avoid the performance cost of getting the calling class.
            if (!StaticObject.isNull(caller) && !StaticObject.isNull(loader)) {
                protectionDomain = JVM_GetProtectionDomain(caller);
            } else {
                protectionDomain = StaticObject.NULL;
            }
            result = meta.resolveSymbolOrNull(type, loader, protectionDomain);
        }
        if (result == null) {
            throw meta.throwExceptionWithMessage(meta.java_lang_ClassNotFoundException, type.toString());
        }
        if (init) {
            result.safeInitialize();
        }
        return result.mirror();
    }

    @VmImpl(isJni = true)
    public @JavaType(Class.class) StaticObject JVM_FindPrimitiveClass(@Pointer TruffleObject namePtr) {
        Meta meta = getMeta();
        String hostName = NativeUtils.interopPointerToString(namePtr);
        return findPrimitiveClass(meta, hostName);
    }

    @JavaType(Class.class)
    public static StaticObject findPrimitiveClass(Meta meta, String hostName) {
        switch (hostName) {
            case "boolean":
                return meta._boolean.mirror();
            case "byte":
                return meta._byte.mirror();
            case "char":
                return meta._char.mirror();
            case "short":
                return meta._short.mirror();
            case "int":
                return meta._int.mirror();
            case "float":
                return meta._float.mirror();
            case "double":
                return meta._double.mirror();
            case "long":
                return meta._long.mirror();
            case "void":
                return meta._void.mirror();
            default:
                throw meta.throwExceptionWithMessage(meta.java_lang_ClassNotFoundException, hostName);
        }
    }

    // endregion class loading

    // region Library support

    private final ConcurrentHashMap<Long, @Pointer TruffleObject> handle2Lib = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, @Pointer TruffleObject> handle2Sym = new ConcurrentHashMap<>();

    private static final AtomicLong libraryHandles = new AtomicLong(1);
    private static final AtomicLong symbolHandles = new AtomicLong(1);

    public @Pointer TruffleObject getFunction(long handle) {
        return handle2Sym.get(handle);
    }

    private static boolean hasDynamicLoaderCacheValue = false;
    private static boolean hasDynamicLoaderCacheInit = false;

    private static boolean hasDynamicLoaderCache() {
        if (hasDynamicLoaderCacheInit) {
            return hasDynamicLoaderCacheValue;
        }
        // Implement JDK-8275703 on our side
        if (OS.getCurrent() == OS.Darwin) {
            String osVersion = System.getProperty("os.version");
            // dynamic linker cache support on os.version >= 11.x
            int major = 11;
            int i = osVersion.indexOf('.');
            try {
                major = Integer.parseInt(i < 0 ? osVersion : osVersion.substring(0, i));
            } catch (NumberFormatException e) {
            }
            hasDynamicLoaderCacheValue = major >= 11;
        } else {
            hasDynamicLoaderCacheValue = false;
        }
        hasDynamicLoaderCacheInit = true;
        return hasDynamicLoaderCacheValue;
    }

    @VmImpl
    @TruffleBoundary
    public @Pointer TruffleObject JVM_LoadLibrary(@Pointer TruffleObject namePtr) {
        String name = NativeUtils.interopPointerToString(namePtr);
        // We don't pass `throwException` down due to GR-37925, but even if Sulong would
        // be fixed, it might be garbage if the used base lib has a mismatching signature,
        // so we recompute its value instead on our side.
        boolean throwException = !hasDynamicLoaderCache();
        return JVM_LoadLibrary(name, throwException);
    }

    @TruffleBoundary
    public @Pointer TruffleObject JVM_LoadLibrary(String name, boolean throwException) {
        getLogger().fine(() -> String.format("JVM_LoadLibrary(%s, %s)", name, throwException));

        TruffleObject lib = getNativeAccess().loadLibrary(Paths.get(name));
        if (lib == null) {
            if (throwException) {
                Meta meta = getMeta();
                throw meta.throwExceptionWithMessage(meta.java_lang_UnsatisfiedLinkError, name);
            }
            return RawPointer.create(0);
        }
        long handle = getLibraryHandle(lib);
        handle2Lib.put(handle, lib);
        getLogger().fine(() -> String.format("JVM_LoadLibrary: Successfully loaded '%s' with handle %x", name, handle));
        return RawPointer.create(handle);
    }

    private static long getLibraryHandle(TruffleObject lib) {
        try {
            if (InteropLibrary.getUncached().isPointer(lib)) {
                return InteropLibrary.getUncached().asPointer(lib);
            } else {
                // Probably a Sulong library, cannot get its native handle, create a fake one.
                return libraryHandles.getAndIncrement();
            }
        } catch (UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    private static long getSymbolHandle(TruffleObject sym) {
        try {
            if (InteropLibrary.getUncached().isPointer(sym)) {
                return InteropLibrary.getUncached().asPointer(sym);
            } else {
                // Try to force to a pointer.
                InteropLibrary.getUncached().toNative(sym);
                if (InteropLibrary.getUncached().isPointer(sym)) {
                    return InteropLibrary.getUncached().asPointer(sym);
                } else {
                    // Not an actual pointer, cannot get its native handle, create a fake one.
                    return symbolHandles.getAndIncrement();
                }
            }
        } catch (UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    @VmImpl
    @TruffleBoundary
    public void JVM_UnloadLibrary(@Pointer TruffleObject libraryPtr) {
        long nativeLibraryPtr = NativeUtils.interopAsPointer(libraryPtr);
        TruffleObject library = handle2Lib.get(nativeLibraryPtr);
        if (library == null) {
            getLogger().severe("JVM_UnloadLibrary with unknown library (not loaded through JVM_LoadLibrary?): " + libraryPtr + " / " + Long.toHexString(nativeLibraryPtr));
        } else {
            getNativeAccess().unloadLibrary(library);
            handle2Lib.remove(nativeLibraryPtr);
        }
    }

    @VmImpl
    @TruffleBoundary
    @SuppressFBWarnings(value = "AT_OPERATION_SEQUENCE_ON_CONCURRENT_ABSTRACTION", justification = "benign race")
    public @Pointer TruffleObject JVM_FindLibraryEntry(@Pointer TruffleObject libraryPtr, @Pointer TruffleObject namePtr) {
        String name = NativeUtils.interopPointerToString(namePtr);
        long nativePtr = NativeUtils.interopAsPointer(libraryPtr);
        return RawPointer.create(findLibraryEntry(nativePtr, name));
    }

    @TruffleBoundary
    public long findLibraryEntry(long nativePtr, String name) {
        TruffleObject library = handle2Lib.get(nativePtr);
        if (library == null) {
            if (nativePtr == rtldDefaultValue || nativePtr == processHandleValue) {
                library = getNativeAccess().loadDefaultLibrary();
                if (library == null) {
                    getLogger().warning("JVM_FindLibraryEntry from default/global namespace is not supported: " + name);
                    return 0;
                }
                TruffleObject previous = handle2Lib.putIfAbsent(nativePtr, library);
                library = previous == null ? library : previous;
            } else {
                getLogger().warning("JVM_FindLibraryEntry with unknown handle (" + nativePtr + " / " + Long.toHexString(nativePtr) + "): " + name);
                return 0;
            }
        }
        try {
            TruffleObject function = getNativeAccess().lookupSymbol(library, name);
            if (getLogger().isLoggable(Level.FINEST)) {
                InteropLibrary interop = InteropLibrary.getUncached();
                String libraryName;
                if (nativePtr == rtldDefaultValue || nativePtr == processHandleValue) {
                    libraryName = "RTLD_DEFAULT";
                } else {
                    libraryName = interop.asString(interop.toDisplayString(library, false));
                }
                String functionName;
                if (function == null) {
                    functionName = "null";
                } else {
                    functionName = interop.asString(interop.toDisplayString(function, false));
                }
                getLogger().finest("JVM_FindLibraryEntry(%s, %s) -> %s".formatted(libraryName, name, functionName));
            }
            if (function == null) {
                return 0; // not found
            }
            if (!getUncached().isPointer(function)) {
                getUncached().toNative(function);
            }
            long handle = getSymbolHandle(function);
            handle2Sym.put(handle, function);
            return handle;
        } catch (UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    @VmImpl
    @TruffleBoundary
    public @Pointer TruffleObject JVM_LoadZipLibrary() {
        if (zipLibrary != null) {
            return zipLibrary;
        }
        synchronized (zipLoadLock) {
            TruffleObject tmpZipLib = getNativeAccess().loadLibrary(getContext().getVmProperties().bootLibraryPath(), "zip", false);
            if (tmpZipLib == null || getUncached().isNull(tmpZipLib)) {
                getLogger().severe("Unable to load zip library.");
            }
            zipLibrary = tmpZipLib;
            return zipLibrary;
        }
    }

    // endregion Library support

    // region JNI version

    @VmImpl
    public boolean JVM_IsSupportedJNIVersion(int version) {
        JniVersion jniVersion = JniVersion.decodeVersion(version);
        return jniVersion != null && jniVersion.getJavaVersion().compareTo(getJavaVersion()) <= 0;
    }

    @VmImpl
    public int JVM_GetInterfaceVersion() {
        if (getJavaVersion().java21OrLater()) {
            getLogger().warning("JVM_GetInterfaceVersion invoked for a 21+ context but it was removed in Java 21");
        }
        if (getJavaVersion().java8OrEarlier()) {
            return JniEnv.JVM_INTERFACE_VERSION_8;
        } else {
            return JniEnv.JVM_INTERFACE_VERSION_11;
        }
    }

    // endregion JNI version

    // region halting

    @VmImpl(isJni = true)
    public void JVM_BeforeHalt() {
        /*
         * currently nop
         */
    }

    @VmImpl
    public void JVM_Halt(int code, @Inject SubstitutionProfiler location) {
        getContext().truffleExit(location, code);
    }

    @VmImpl
    public void JVM_Exit(int code, @Inject SubstitutionProfiler location) {
        // Unlike Halt, finalizers were ran before in guest code.
        getContext().truffleExit(location, code);
    }

    // endregion halting

    // region properties

    private static String getModuleMain(OptionValues options) {
        String module = options.get(EspressoOptions.Module);
        if (module.isEmpty()) {
            return module;
        }
        int slash = module.indexOf('/');
        if (slash != -1) {
            module = module.substring(0, slash);
        }
        return module;
    }

    private static final class PropertiesMap {
        final Map<String, String> map = new HashMap<>();

        public void set(String key, String value, String provenance) {
            set(key, value, provenance, false);
        }

        public void set(String key, String value, String provenance, boolean ignoreOverwrite) {
            if (map.put(key, value) != null && !ignoreOverwrite) {
                LOGGER.severe(() -> "Overwriting property " + key);
            }
            LOGGER.finer(() -> "initial properties[" + provenance + "]: " + key + "=" + value);
        }

        public void set(String key, List<Path> paths, String provenance) {
            set(key, stringify(paths), provenance);
        }

        private static String stringify(List<Path> paths) {
            StringJoiner joiner = new StringJoiner(File.pathSeparator);
            for (Path p : paths) {
                joiner.add(p.toString());
            }
            return joiner.toString();
        }

        public void setPropertyIfExists(String propertyName, String value, String provenance) {
            if (value != null && !value.isEmpty()) {
                set(propertyName, value, provenance);
            }
        }

        public void setPropertyIfExists(String propertyName, List<Path> paths, String provenance) {
            if (paths != null && !paths.isEmpty()) {
                set(propertyName, paths, provenance);
            }
        }

        public int setNumberedProperty(String property, List<String> values, String provenance) {
            assert property.endsWith(".");
            int count = 0;
            while (map.containsKey(property + count)) {
                count++;
            }
            for (String value : values) {
                String key = property + count++;
                set(key, value, provenance);
            }
            return count;
        }
    }

    public synchronized Map<String, String> getSystemProperties() {
        if (systemProperties == null) {
            systemProperties = Collections.unmodifiableMap(buildPropertiesMap().map);
        }
        return systemProperties;
    }

    private PropertiesMap buildPropertiesMap() {
        PropertiesMap map = new PropertiesMap();
        OptionValues options = getContext().getEnv().getOptions();

        EspressoProperties props = getContext().getVmProperties();

        // Boot classpath setup requires special handling depending on the version.
        String bootClassPathProperty = getJavaVersion().java8OrEarlier() ? "sun.boot.class.path" : "jdk.boot.class.path.append";

        // Espresso uses VM properties, to ensure consistency the user-defined properties (that may
        // differ in some cases) are overwritten.
        map.set(bootClassPathProperty, props.bootClasspath(), "VM");
        map.set("java.class.path", props.classpath(), "VM");
        map.set("java.home", props.javaHome().toString(), "VM");
        map.set("java.library.path", props.javaLibraryPath(), "VM");
        map.set("sun.boot.library.path", props.bootLibraryPath(), "VM");
        map.set("java.ext.dirs", props.extDirs(), "VM");

        int addmodCount = 0;
        int addexportsCount = 0;
        // Modules properties.
        if (getJavaVersion().modulesEnabled()) {
            map.setPropertyIfExists("jdk.module.main", getModuleMain(options), "Module");
            map.setPropertyIfExists("jdk.module.path", options.get(EspressoOptions.ModulePath), "ModulePath");
            map.setNumberedProperty("jdk.module.addreads.", options.get(EspressoOptions.AddReads), "AddReads");
            addexportsCount = map.setNumberedProperty("jdk.module.addexports.", options.get(EspressoOptions.AddExports), "AddExports");
            map.setNumberedProperty("jdk.module.addopens.", options.get(EspressoOptions.AddOpens), "AddOpens");
            addmodCount = map.setNumberedProperty("jdk.module.addmods.", options.get(EspressoOptions.AddModules), "AddModules");
            map.setNumberedProperty("jdk.module.enable.native.access.", options.get(EspressoOptions.EnableNativeAccess), "EnableNativeAccess");
            map.setPropertyIfExists("jdk.module.illegal.native.access", options.get(EspressoOptions.IllegalNativeAccess), "IllegalNativeAccess");
        }

        if (getJavaVersion().java23OrLater()) {
            MemoryAccessOption memoryAccessOption = options.get(EspressoOptions.SunMiscUnsafeMemoryAccess);
            if (memoryAccessOption != MemoryAccessOption.defaultValue) {
                map.set("sun.misc.unsafe.memory.access", memoryAccessOption.name(), "SunMiscUnsafeMemoryAccess");
            }
        }

        // Applications expect different formats e.g. 1.8 vs. 11
        String specVersion = getJavaVersion().java8OrEarlier()
                        ? "1." + getJavaVersion()
                        : getJavaVersion().toString();

        // Set VM information.
        map.set("java.vm.specification.version", specVersion, "VM");
        map.set("java.vm.specification.name", EspressoLanguage.VM_SPECIFICATION_NAME, "VM");
        map.set("java.vm.specification.vendor", EspressoLanguage.VM_SPECIFICATION_VENDOR, "VM");
        map.set("java.vm.version", specVersion + "-" + EspressoLanguage.VM_VERSION, "VM");
        map.set("java.vm.name", EspressoLanguage.VM_NAME, "VM");
        map.set("java.vm.vendor", EspressoLanguage.VM_VENDOR, "VM");
        map.set("java.vm.info", EspressoLanguage.VM_INFO, "VM");
        map.set("jdk.debug", "release", "VM");

        map.set("sun.nio.MaxDirectMemorySize", Long.toString(options.get(EspressoOptions.MaxDirectMemorySize)), "MaxDirectMemorySize");

        boolean warnInternalModuleProp = false;
        // Set user-defined system properties.
        for (Map.Entry<String, String> entry : options.get(EspressoOptions.Properties).entrySet()) {
            String key = entry.getKey();
            if ("sun.nio.MaxDirectMemorySize".equals(key)) {
                // ignore this
                continue;
            }
            map.set(key, entry.getValue(), "Properties", true);
        }

        if (warnInternalModuleProp) {
            LOGGER.warning("Ignoring system property options whose names match the '-Djdk.module.*'. names that are reserved for internal use.");
        }

        for (ModuleExtension me : ModuleExtension.getAllExtensions(getContext())) {
            if (me.isAutoAdd()) {
                map.set("jdk.module.addmods." + addmodCount++, me.moduleName(), "VM (module extensions)");
            }
            for (Map.Entry<String, List<String>> entry : me.getRequiresConcealed().entrySet()) {
                for (String packageName : entry.getValue()) {
                    map.set("jdk.module.addexports." + addexportsCount++, entry.getKey() + "/" + packageName + "=" + me.moduleName(), "VM (module extensions)");
                }
            }
        }

        if (getLanguage().isInternalJVMCIEnabled()) {
            map.set("jdk.internal.vm.ci.enabled", "true", "VM (JVMCI)");
        }

        return map;
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public @JavaType(Properties.class) StaticObject JVM_InitProperties(@JavaType(Properties.class) StaticObject properties) {
        Map<String, String> props = getSystemProperties();
        Method setProperty = properties.getKlass().lookupMethod(Names.setProperty, Signatures.Object_String_String);
        for (Map.Entry<String, String> entry : props.entrySet()) {
            setProperty.invokeWithConversions(properties, entry.getKey(), entry.getValue());
        }
        return properties;
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public @JavaType(String[].class) StaticObject JVM_GetProperties(@Inject EspressoLanguage language) {
        Map<String, String> props = getSystemProperties();
        StaticObject array = getMeta().java_lang_String.allocateReferenceArray(props.size() * 2);
        int index = 0;
        for (Map.Entry<String, String> entry : props.entrySet()) {
            getInterpreterToVM().setArrayObject(language, getMeta().toGuestString(entry.getKey()), index * 2, array);
            getInterpreterToVM().setArrayObject(language, getMeta().toGuestString(entry.getValue()), index * 2 + 1, array);
            index++;
        }
        return array;
    }

    // endregion properties

    // region array

    @VmImpl(isJni = true)
    public int JVM_GetArrayLength(@JavaType(Object.class) StaticObject array, @Inject EspressoLanguage language, @Inject SubstitutionProfiler profiler) {
        if (array.isForeignObject()) {
            try {
                Object foreignObject = array.rawForeignObject(language);
                InteropLibrary library = InteropLibrary.getUncached(foreignObject);
                long arrayLength = library.getArraySize(foreignObject);
                if (arrayLength > Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
                return (int) arrayLength;
            } catch (UnsupportedMessageException e) {
                profiler.profile(0);
                Meta meta = getMeta();
                throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "can't get array length because foreign object is not an array");
            }
        } else {
            try {
                return Array.getLength(MetaUtil.unwrapArrayOrNull(language, array));
            } catch (IllegalArgumentException e) {
                profiler.profile(1);
                Meta meta = getMeta();
                throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, e.getMessage());
            } catch (NullPointerException e) {
                profiler.profile(2);
                throw getMeta().throwNullPointerException();
            }
        }
    }

    /**
     * Returns the value of the indexed component in the specified array object. The value is
     * automatically wrapped in an object if it has a primitive type.
     *
     * @param array the array
     * @param index the index
     * @throws NullPointerException If the specified object is null
     * @throws IllegalArgumentException If the specified object is not an array
     * @throws ArrayIndexOutOfBoundsException If the specified {@code index} argument is negative,
     *             or if it is greater than or equal to the length of the specified array
     * @return the (possibly wrapped) value of the indexed component in the specified array
     */
    @VmImpl(isJni = true)
    public @JavaType(Object.class) StaticObject JVM_GetArrayElement(@JavaType(Object.class) StaticObject array, int index, @Inject EspressoLanguage language, @Inject SubstitutionProfiler profiler) {
        Meta meta = getMeta();
        if (StaticObject.isNull(array)) {
            profiler.profile(7);
            throw meta.throwNullPointerException();
        }
        if (array.isArray()) {
            profiler.profile(6);
            return getInterpreterToVM().getArrayObject(language, index, array);
        }
        if (!array.getClass().isArray()) {
            profiler.profile(5);
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "Argument is not an array");
        }
        assert array.getClass().isArray() && array.getClass().getComponentType().isPrimitive();
        if (index < 0 || index >= JVM_GetArrayLength(array, language, profiler)) {
            profiler.profile(4);
            throw meta.throwExceptionWithMessage(meta.java_lang_ArrayIndexOutOfBoundsException, "index");
        }
        Object elem = Array.get(array, index);
        return getMeta().boxPrimitive(elem);
    }

    // endregion array

    // region assertion

    /**
     * Espresso only supports basic -ea and -esa options. Complex per-class/package filters are
     * unsupported.
     */
    @VmImpl(isJni = true)
    @TruffleBoundary
    public @JavaType(internalName = "Ljava/lang/AssertionStatusDirectives;") StaticObject JVM_AssertionStatusDirectives(@SuppressWarnings("unused") @JavaType(Class.class) StaticObject unused) {
        Meta meta = getMeta();
        StaticObject instance = meta.java_lang_AssertionStatusDirectives.allocateInstance(getContext());
        meta.java_lang_AssertionStatusDirectives.lookupMethod(Names._init_, Signatures._void).invokeDirectSpecial(instance);
        meta.java_lang_AssertionStatusDirectives_classes.set(instance, meta.java_lang_String.allocateReferenceArray(0));
        meta.java_lang_AssertionStatusDirectives_classEnabled.set(instance, meta._boolean.allocatePrimitiveArray(0));
        meta.java_lang_AssertionStatusDirectives_packages.set(instance, meta.java_lang_String.allocateReferenceArray(0));
        meta.java_lang_AssertionStatusDirectives_packageEnabled.set(instance, meta._boolean.allocatePrimitiveArray(0));
        boolean ea = getContext().getEnv().getOptions().get(EspressoOptions.EnableAssertions);
        meta.java_lang_AssertionStatusDirectives_deflt.set(instance, ea);
        return instance;
    }

    // endregion assertion

    // region stack inspection

    private static final int JVM_CALLER_DEPTH = -1;

    public static int jvmCallerDepth() {
        return JVM_CALLER_DEPTH;
    }

    private boolean isTrustedFrame(FrameInstance frameInstance, PrivilegedStack stack) {
        if (stack.compare(frameInstance)) {
            StaticObject loader = stack.classLoader();
            if (StaticObject.isNull(loader)) {
                return true;
            }
            if (isTrustedLoader(loader)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTrustedLoader(StaticObject loader) {
        StaticObject nonDelLoader = nonReflectionClassLoader(loader);
        StaticObject systemLoader = (StaticObject) getMeta().java_lang_ClassLoader_getSystemClassLoader.invokeDirectStatic();
        while (StaticObject.notNull(systemLoader)) {
            if (systemLoader == nonDelLoader) {
                return true;
            }
            systemLoader = getMeta().java_lang_ClassLoader_parent.getObject(systemLoader);
        }
        return false;
    }

    private static boolean isIgnoredBySecurityStackWalk(Method m, Meta meta) {
        Klass holderKlass = m.getDeclaringKlass();
        if (holderKlass == meta.java_lang_reflect_Method && m.getName() == Names.invoke) {
            return true;
        }
        if (meta.sun_reflect_MethodAccessorImpl.isAssignableFrom(holderKlass)) {
            return true;
        }
        if (MethodHandleIntrinsics.isMethodHandleIntrinsic(m) || (m.getModifiers() & ACC_LAMBDA_FORM_COMPILED) != 0) {
            return true;
        }
        return false;
    }

    private boolean isAuthorized(StaticObject context, Klass klass) {
        if (!StaticObject.isNull(getMeta().java_lang_System_securityManager.getObject(getMeta().java_lang_System.getStatics()))) {
            if (getMeta().java_security_ProtectionDomain_impliesCreateAccessControlContext == null) {
                return true;
            }
            if ((boolean) getMeta().java_security_AccessControlContext_isAuthorized.get(context)) {
                return true;
            }
            StaticObject pd = JVM_GetProtectionDomain(klass.mirror());
            if (pd != StaticObject.NULL) {
                return (boolean) getMeta().java_security_ProtectionDomain_impliesCreateAccessControlContext.invokeDirectVirtual(pd);
            }
        }
        return true;
    }

    /**
     * Returns the caller frame, 'depth' levels up. If securityStackWalk is true, some Espresso
     * frames are skipped according to {@link #isIgnoredBySecurityStackWalk}.
     *
     * May return null if there is no Java frame on the stack.
     */
    @TruffleBoundary
    private FrameInstance getCallerFrame(int depth, boolean securityStackWalk, Meta meta) {
        if (depth == JVM_CALLER_DEPTH) {
            return getCallerFrame(1, securityStackWalk, meta);
        }
        assert depth >= 0;

        // Ignores non-Espresso frames.
        //
        // The call stack at this point looks something like this:
        //
        // [0] [ current frame e.g. AccessController.doPrivileged, Reflection.getCallerClass ]
        // [.] [ (skipped intermediate frames) ]
        // ...
        // [n] [ caller ]
        return Truffle.getRuntime().iterateFrames(
                        new FrameInstanceVisitor<FrameInstance>() {
                            private int n;

                            @Override
                            public FrameInstance visitFrame(FrameInstance frameInstance) {
                                Method m = getMethodFromFrame(frameInstance);
                                if (m != null) {
                                    if (!securityStackWalk || !isIgnoredBySecurityStackWalk(m, meta)) {
                                        if (n == depth) {
                                            return frameInstance;
                                        }
                                        ++n;
                                    }
                                }
                                return null;
                            }
                        });
    }

    /**
     * Returns the espresso root node for this frame, event if it comes from a different context.
     */
    private static EspressoRootNode getRawEspressoRootFromFrame(FrameInstance frameInstance) {
        if (frameInstance.getCallTarget() instanceof RootCallTarget callTarget) {
            RootNode rootNode = callTarget.getRootNode();
            if (rootNode instanceof EspressoRootNode) {
                return ((EspressoRootNode) rootNode);
            }
        }
        return null;
    }

    @TruffleBoundary
    public EspressoRootNode getEspressoRootFromFrame(FrameInstance frameInstance) {
        return getEspressoRootFromFrame(frameInstance, getContext());
    }

    @TruffleBoundary
    public static EspressoRootNode getEspressoRootFromFrame(FrameInstance frameInstance, EspressoContext context) {
        EspressoRootNode root = getRawEspressoRootFromFrame(frameInstance);
        if (root == null) {
            return null;
        }
        Method method = root.getMethod();
        if (method.getContext() != context) {
            return null;
        }
        return root;
    }

    @TruffleBoundary
    public Method getMethodFromFrame(FrameInstance frameInstance) {
        EspressoRootNode root = getRawEspressoRootFromFrame(frameInstance);
        if (root == null) {
            return null;
        }
        Method method = root.getMethod();
        if (method.getContext() != getContext()) {
            return null;
        }
        return method;
    }

    @VmImpl(isJni = true)
    public @JavaType(Class.class) StaticObject JVM_GetCallerClass(int depth,
                    @Inject SubstitutionProfiler profiler) {
        // HotSpot comment:
        // Pre-JDK 8 and early builds of JDK 8 don't have a CallerSensitive annotation; or
        // sun.reflect.Reflection.getCallerClass with a depth parameter is provided
        // temporarily for existing code to use until a replacement API is defined.
        if (depth != JVM_CALLER_DEPTH) {
            FrameInstance callerFrame = getCallerFrame(depth, true, getMeta());
            if (callerFrame != null) {
                Method callerMethod = getMethodFromFrame(callerFrame);
                if (callerMethod != null) {
                    return callerMethod.getDeclaringKlass().mirror();
                }
            }
            // Not found.
            return StaticObject.NULL;
        }

        // Getting the class of the caller frame.
        //
        // The call stack at this point looks something like this:
        //
        // [0] [ @CallerSensitive public sun.reflect.Reflection.getCallerClass ]
        // [1] [ @CallerSensitive API.method ]
        // [.] [ (skipped intermediate frames) ]
        // [n] [ caller ]
        Meta meta = getMeta();
        StaticObject[] exception = new StaticObject[]{null};
        Method callerMethod = Truffle.getRuntime().iterateFrames(
                        new FrameInstanceVisitor<Method>() {
                            private int depth = 0;

                            @SuppressWarnings("fallthrough")
                            @Override
                            public Method visitFrame(FrameInstance frameInstance) {
                                Method method = getMethodFromFrame(frameInstance);
                                if (method != null) {
                                    switch (depth) {
                                        case 0:
                                            // This must only be called from
                                            // Reflection.getCallerClass.
                                            if (method != meta.sun_reflect_Reflection_getCallerClass) {
                                                exception[0] = Meta.initExceptionWithMessage(meta.java_lang_InternalError, "JVM_GetCallerClass must only be called from Reflection.getCallerClass");
                                                return /* ignore */ method;
                                            }
                                            // fall-through
                                        case 1:
                                            // Frame 0 and 1 must be caller sensitive.
                                            if (!method.isCallerSensitive()) {
                                                exception[0] = Meta.initExceptionWithMessage(meta.java_lang_InternalError, "CallerSensitive annotation expected at frame " + depth);
                                                return /* ignore */ method;
                                            }
                                            break;
                                        default:
                                            if (!isIgnoredBySecurityStackWalk(method, meta)) {
                                                return method;
                                            }
                                    }
                                    ++depth;
                                }
                                return null;
                            }
                        });

        // InternalError was recorded.
        StaticObject internalError = exception[0];
        if (internalError != null) {
            profiler.profile(0);
            assert InterpreterToVM.instanceOf(internalError, meta.java_lang_InternalError);
            throw meta.throwException(internalError);
        }

        if (callerMethod == null) {
            return StaticObject.NULL;
        }

        return callerMethod.getDeclaringKlass().mirror();
    }

    @VmImpl(isJni = true)
    public @JavaType(Class[].class) StaticObject JVM_GetClassContext() {
        // TODO(garcia) This must only be called from SecurityManager.getClassContext
        ArrayList<StaticObject> result = new ArrayList<>();
        Truffle.getRuntime().iterateFrames(
                        new FrameInstanceVisitor<>() {
                            @Override
                            public Object visitFrame(FrameInstance frameInstance) {
                                Method m = getMethodFromFrame(frameInstance);
                                if (m != null && !isIgnoredBySecurityStackWalk(m, getMeta()) && !m.isNative()) {
                                    result.add(m.getDeclaringKlass().mirror());
                                }
                                return null;
                            }
                        });
        return StaticObject.createArray(getMeta().java_lang_Class_array, result.toArray(StaticObject.EMPTY_ARRAY), getContext());
    }

    // region privileged

    private @JavaType(internalName = "Ljava/security/AccessControlContext;") StaticObject createACC(@JavaType(internalName = "[Ljava/security/ProtectionDomain;") StaticObject context,
                    boolean isPriviledged,
                    @JavaType(internalName = "Ljava/security/AccessControlContext;") StaticObject priviledgedContext) {
        Klass accKlass = getMeta().java_security_AccessControlContext;
        StaticObject acc = accKlass.allocateInstance(getContext());
        getMeta().java_security_AccessControlContext_context.setObject(acc, context);
        getMeta().java_security_AccessControlContext_privilegedContext.setObject(acc, priviledgedContext);
        getMeta().java_security_AccessControlContext_isPrivileged.setBoolean(acc, isPriviledged);
        if (getMeta().java_security_AccessControlContext_isAuthorized != null) {
            getMeta().java_security_AccessControlContext_isAuthorized.setBoolean(acc, true);
        }
        return acc;
    }

    private @JavaType(internalName = "Ljava/security/AccessControlContext;") StaticObject createDummyACC() {
        Klass pdKlass = getMeta().java_security_ProtectionDomain;
        StaticObject pd = pdKlass.allocateInstance(getContext());
        getMeta().java_security_ProtectionDomain_init_CodeSource_PermissionCollection.invokeDirectSpecial(pd, StaticObject.NULL, StaticObject.NULL);
        StaticObject context = StaticObject.wrap(new StaticObject[]{pd}, getMeta());
        return createACC(context, false, StaticObject.NULL);
    }

    public PrivilegedStack getPrivilegedStack() {
        return getContext().getLanguage().getThreadLocalState().getPrivilegedStack();
    }

    public static final class PrivilegedStack {
        private final EspressoContext espressoContext;
        private Element top;

        public PrivilegedStack(EspressoContext context) {
            this.espressoContext = context;
        }

        void push(FrameInstance frame, StaticObject context, Klass klass) {
            top = new Element(frame, context, klass, top, espressoContext);
        }

        void pop() {
            assert top != null : "popping empty privileged stack !";
            top = top.next;
        }

        boolean compare(FrameInstance frame) {
            return top != null && top.compare(frame, espressoContext);
        }

        StaticObject peekContext() {
            assert top != null;
            return top.context;
        }

        StaticObject classLoader() {
            assert top != null;
            return top.klass.getDefiningClassLoader();
        }

        static private class Element {
            long frameID;
            StaticObject context;
            Klass klass;
            Element next;

            Element(FrameInstance frame, StaticObject context, Klass klass, Element next, EspressoContext espressoContext) {
                this.frameID = getFrameId(frame, espressoContext);
                this.context = context;
                this.klass = klass;
                this.next = next;
            }

            boolean compare(FrameInstance other, EspressoContext espressoContext) {
                EspressoRootNode rootNode = getEspressoRootFromFrame(other, espressoContext);
                if (rootNode != null) {
                    Frame readOnlyFrame = other.getFrame(FrameInstance.FrameAccess.READ_ONLY);
                    long frameIdOrZero = rootNode.readFrameIdOrZero(readOnlyFrame);
                    return frameIdOrZero != 0 && frameIdOrZero == frameID;
                }
                return false;
            }

            private static long getFrameId(FrameInstance frame, EspressoContext espressoContext) {
                EspressoRootNode rootNode = getEspressoRootFromFrame(frame, espressoContext);
                Frame readOnlyFrame = frame.getFrame(FrameInstance.FrameAccess.READ_ONLY);
                return rootNode.readFrameIdOrZero(readOnlyFrame);
            }
        }
    }

    @VmImpl(isJni = true)
    @SuppressWarnings("unused")
    public @JavaType(Object.class) StaticObject JVM_DoPrivileged(@JavaType(Class.class) StaticObject cls,
                    /* PrivilegedAction or PrivilegedActionException */ @JavaType(Object.class) StaticObject action,
                    @JavaType(internalName = "Ljava/security/AccessControlContext;") StaticObject context,
                    boolean wrapException,
                    @Inject Meta meta,
                    @Inject SubstitutionProfiler profiler) {
        if (StaticObject.isNull(action)) {
            profiler.profile(0);
            throw meta.throwNullPointerException();
        }
        FrameInstance callerFrame = getCallerFrame(1, false, meta);
        assert callerFrame != null : "No caller ?";
        Klass caller = getMethodFromFrame(callerFrame).getDeclaringKlass();
        StaticObject acc = context;
        if (!StaticObject.isNull(context)) {
            if (!isAuthorized(context, caller)) {
                acc = createDummyACC();
            }
        }
        Method run = action.getKlass().lookupMethod(Names.run, Signatures.Object);
        if (run == null || !run.isPublic() || run.isStatic()) {
            profiler.profile(1);
            throw meta.throwException(meta.java_lang_InternalError);
        }

        // Prepare the privileged stack
        PrivilegedStack stack = getPrivilegedStack();
        stack.push(callerFrame, acc, caller);

        // Execute the action.
        StaticObject result;
        try {
            result = (StaticObject) run.invokeDirect(action);
        } catch (EspressoException e) {
            profiler.profile(2);
            if (meta.java_lang_Exception.isAssignableFrom(e.getGuestException().getKlass()) &&
                            !meta.java_lang_RuntimeException.isAssignableFrom(e.getGuestException().getKlass())) {
                profiler.profile(3);
                StaticObject wrapper = meta.java_security_PrivilegedActionException.allocateInstance(getContext());
                getMeta().java_security_PrivilegedActionException_init_Exception.invokeDirectSpecial(wrapper, e.getGuestException());
                throw meta.throwException(wrapper);
            }
            profiler.profile(4);
            throw e;
        } finally {
            stack.pop();
        }
        return result;
    }

    @VmImpl(isJni = true)
    @SuppressWarnings("unused")
    public @JavaType(Object.class) StaticObject JVM_GetStackAccessControlContext(@JavaType(Class.class) StaticObject cls) {
        if (getJavaVersion().java11OrEarlier()) {
            return getACCUntil11();
        } else {
            return getACCAfter12();
        }
    }

    private StaticObject getACCAfter12() {
        ArrayList<StaticObject> domains = new ArrayList<>();
        final boolean[] isPrivileged = new boolean[]{false};

        StaticObject context = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<StaticObject>() {
            StaticObject prevDomain = StaticObject.NULL;

            @Override
            public StaticObject visitFrame(FrameInstance frameInstance) {
                Method m = getMethodFromFrame(frameInstance);
                if (m != null) {
                    StaticObject domain = null;
                    StaticObject stackContext = null;
                    StaticObject domainKlass = null;
                    if (m.getDeclaringKlass() == getMeta().java_security_AccessController &&
                                    m.getName() == Names.executePrivileged) {
                        isPrivileged[0] = true;
                        Frame frame = frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY);
                        // 2nd argument: `AccessControlContext context`
                        stackContext = EspressoFrame.getLocalObject(frame, 1);
                        // 3rd argument: Class<?> caller
                        domainKlass = EspressoFrame.getLocalObject(frame, 2);
                    } else {
                        domainKlass = m.getDeclaringKlass().mirror();
                    }
                    domain = JVM_GetProtectionDomain(domainKlass);
                    if (domain != prevDomain && domain != StaticObject.NULL) {
                        domains.add(domain);
                        prevDomain = domain;
                    }
                    if (isPrivileged[0]) {
                        return stackContext;
                    }
                }
                return null;
            }
        });
        return getAccFromContext(domains, isPrivileged[0], context);
    }

    private StaticObject getACCUntil11() {
        ArrayList<StaticObject> domains = new ArrayList<>();
        final PrivilegedStack stack = getPrivilegedStack();
        final boolean[] isPrivileged = new boolean[]{false};

        StaticObject context = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<StaticObject>() {
            StaticObject prevDomain = StaticObject.NULL;

            @Override
            public StaticObject visitFrame(FrameInstance frameInstance) {
                Method m = getMethodFromFrame(frameInstance);
                if (m != null) {
                    if (stack.compare(frameInstance)) {
                        isPrivileged[0] = true;
                    }
                    StaticObject domain = JVM_GetProtectionDomain(m.getDeclaringKlass().mirror());
                    if (domain != prevDomain && domain != StaticObject.NULL) {
                        domains.add(domain);
                        prevDomain = domain;
                    }
                    if (isPrivileged[0]) {
                        return stack.peekContext();
                    }
                }
                return null;
            }
        });

        return getAccFromContext(domains, isPrivileged[0], context);
    }

    private StaticObject getAccFromContext(ArrayList<StaticObject> domains, boolean isPrivileged, StaticObject context) {
        if (domains.isEmpty()) {
            if (isPrivileged && StaticObject.isNull(context)) {
                return StaticObject.NULL;
            }
            return createACC(StaticObject.NULL, isPrivileged, context == null ? StaticObject.NULL : context);
        }

        StaticObject guestContext = StaticObject.createArray(getMeta().java_security_ProtectionDomain.array(), domains.toArray(StaticObject.EMPTY_ARRAY), getContext());
        return createACC(guestContext, isPrivileged, context == null ? StaticObject.NULL : context);
    }

    @VmImpl(isJni = true)
    @SuppressWarnings("unused")
    public @JavaType(Object.class) StaticObject JVM_GetInheritedAccessControlContext(@JavaType(Class.class) StaticObject cls) {
        return getMeta().java_lang_Thread_inheritedAccessControlContext.getObject(getContext().getCurrentPlatformThread());
    }

    // endregion privileged

    @VmImpl(isJni = true)
    public @JavaType(Object.class) StaticObject JVM_LatestUserDefinedLoader(@Inject Meta meta) {
        StaticObject result = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<StaticObject>() {
            @Override
            public StaticObject visitFrame(FrameInstance frameInstance) {
                Method m = getMethodFromFrame(frameInstance);
                if (m != null) {
                    Klass holder = m.getDeclaringKlass();
                    // vfst.skip_reflection_related_frames(); // Only needed for 1.4 reflection
                    if (meta.sun_reflect_MethodAccessorImpl.isAssignableFrom(holder) || meta.sun_reflect_ConstructorAccessorImpl.isAssignableFrom(holder)) {
                        return null;
                    }

                    StaticObject loader = holder.getDefiningClassLoader();
                    // if (loader != NULL && !SystemDictionary::is_ext_class_loader(loader))
                    if (getJavaVersion().java8OrEarlier()) {
                        if (StaticObject.notNull(loader) && !Types.sun_misc_Launcher$ExtClassLoader.equals(loader.getKlass().getType())) {
                            return loader;
                        }
                    } else {
                        if (StaticObject.notNull(loader) && !Types.jdk_internal_loader_ClassLoaders$PlatformClassLoader.equals(loader.getKlass().getType())) {
                            return loader;
                        }
                    }
                }
                return null;
            }
        });

        return result == null ? StaticObject.NULL : result;
    }

    @VmImpl(isJni = true)
    public @JavaType(Class.class) StaticObject JVM_CurrentLoadedClass() {
        PrivilegedStack stack = getPrivilegedStack();
        StaticObject mirrorKlass = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<StaticObject>() {
            @Override
            public StaticObject visitFrame(FrameInstance frameInstance) {
                Method m = getMethodFromFrame(frameInstance);
                if (m != null) {
                    if (isTrustedFrame(frameInstance, stack)) {
                        return StaticObject.NULL;
                    }
                    if (!m.isNative()) {
                        ObjectKlass klass = m.getDeclaringKlass();
                        StaticObject loader = klass.getDefiningClassLoader();
                        if (StaticObject.notNull(loader) && !isTrustedLoader(loader)) {
                            return klass.mirror();
                        }
                    }
                }
                return null;
            }
        });
        return mirrorKlass == null ? StaticObject.NULL : mirrorKlass;
    }

    @VmImpl(isJni = true)
    public @JavaType(Class.class) StaticObject JVM_CurrentClassLoader() {
        @JavaType(Class.class)
        StaticObject loadedClass = JVM_CurrentLoadedClass();
        return StaticObject.isNull(loadedClass) ? StaticObject.NULL : loadedClass.getMirrorKlass(getMeta()).getDefiningClassLoader();
    }

    @VmImpl(isJni = true)
    public int JVM_ClassLoaderDepth() {
        PrivilegedStack stack = getPrivilegedStack();
        Integer res = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<Integer>() {
            int depth = 0;

            @Override
            public Integer visitFrame(FrameInstance frameInstance) {
                Method m = getMethodFromFrame(frameInstance);
                if (m != null) {
                    if (isTrustedFrame(frameInstance, stack)) {
                        return -1;
                    }
                    if (!m.isNative()) {
                        ObjectKlass klass = m.getDeclaringKlass();
                        StaticObject loader = klass.getDefiningClassLoader();
                        if (StaticObject.notNull(loader) && !isTrustedLoader(loader)) {
                            return depth;
                        }
                        depth++;
                    }
                }
                return null;
            }
        });
        return res == null ? -1 : res;
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public int JVM_ClassDepth(@JavaType(String.class) StaticObject name) {
        Symbol<Name> className = getContext().getNames().lookup(getMeta().toHostString(name).replace('.', '/'));
        if (className == null) {
            return -1;
        }
        Integer res = Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<Integer>() {
            int depth = 0;

            @Override
            public Integer visitFrame(FrameInstance frameInstance) {
                Method m = getMethodFromFrame(frameInstance);
                if (m != null) {
                    if (className.equals(m.getDeclaringKlass().getName())) {
                        return depth;
                    }
                    depth++;
                }
                return null;
            }
        });
        return res == null ? -1 : res;
    }

    // endregion stack inspection

    // region annotations

    private static @JavaType(java.lang.reflect.Method.class) StaticObject getGuestReflectiveMethodRoot(@JavaType(java.lang.reflect.Method.class) StaticObject seed, Meta meta) {
        assert InterpreterToVM.instanceOf(seed, meta.java_lang_reflect_Method);
        StaticObject curMethod = seed;
        while (curMethod != null && StaticObject.notNull(curMethod)) {
            Method target = (Method) meta.HIDDEN_METHOD_KEY.getHiddenObject(curMethod);
            if (target != null) {
                return curMethod;
            }
            curMethod = meta.java_lang_reflect_Method_root.getObject(curMethod);
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw EspressoError.shouldNotReachHere("Could not find HIDDEN_METHOD_KEY");
    }

    private static @JavaType(java.lang.reflect.Field.class) StaticObject getGuestReflectiveFieldRoot(@JavaType(java.lang.reflect.Field.class) StaticObject seed, Meta meta) {
        assert InterpreterToVM.instanceOf(seed, meta.java_lang_reflect_Field);
        StaticObject curField = seed;
        while (curField != null && StaticObject.notNull(curField)) {
            Field target = (Field) meta.HIDDEN_FIELD_KEY.getHiddenObject(curField);
            if (target != null) {
                return curField;
            }
            curField = meta.java_lang_reflect_Field_root.getObject(curField);
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw EspressoError.shouldNotReachHere("Could not find HIDDEN_FIELD_KEY");
    }

    private static @JavaType(java.lang.reflect.Constructor.class) StaticObject getGuestReflectiveConstructorRoot(@JavaType(java.lang.reflect.Constructor.class) StaticObject seed, Meta meta) {
        assert InterpreterToVM.instanceOf(seed, meta.java_lang_reflect_Constructor);
        StaticObject curConstructor = seed;
        while (curConstructor != null && StaticObject.notNull(curConstructor)) {
            Method target = (Method) meta.HIDDEN_CONSTRUCTOR_KEY.getHiddenObject(curConstructor);
            if (target != null) {
                return curConstructor;
            }
            curConstructor = meta.java_lang_reflect_Constructor_root.getObject(curConstructor);
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw EspressoError.shouldNotReachHere("Could not find HIDDEN_CONSTRUCTOR_KEY");
    }

    @VmImpl(isJni = true)
    public @JavaType(Parameter[].class) StaticObject JVM_GetMethodParameters(@JavaType(Object.class) StaticObject executable,
                    @Inject EspressoLanguage language,
                    @Inject Meta meta,
                    @Inject SubstitutionProfiler profiler) {
        assert meta.java_lang_reflect_Executable.isAssignableFrom(executable.getKlass());
        StaticObject parameterTypes = (StaticObject) executable.getKlass().lookupMethod(Names.getParameterTypes, Signatures.Class_array).invokeDirect(executable);
        int numParams = parameterTypes.length(language);
        if (numParams == 0) {
            return StaticObject.NULL;
        }

        Method method;
        if (meta.java_lang_reflect_Method.isAssignableFrom(executable.getKlass())) {
            method = Method.getHostReflectiveMethodRoot(executable, meta);
        } else if (meta.java_lang_reflect_Constructor.isAssignableFrom(executable.getKlass())) {
            method = Method.getHostReflectiveConstructorRoot(executable, meta);
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw EspressoError.shouldNotReachHere();
        }

        MethodParametersAttribute methodParameters = (MethodParametersAttribute) method.getAttribute(Names.MethodParameters);

        if (methodParameters == null) {
            return StaticObject.NULL;
        }
        // Verify first.
        /*
         * If number of entries in ParametersAttribute is inconsistent with actual parameters from
         * the signature, it will be caught in guest java code.
         */
        int cpLength = method.getConstantPool().length();
        for (MethodParametersAttribute.Entry entry : methodParameters.getEntries()) {
            int nameIndex = entry.getNameIndex();
            if (nameIndex < 0 || nameIndex >= cpLength) {
                profiler.profile(0);
                throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "Constant pool index out of bounds");
            }
            if (nameIndex != 0 && method.getConstantPool().tagAt(nameIndex) != ConstantPool.Tag.UTF8) {
                profiler.profile(1);
                throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "Wrong type at constant pool index");
            }
        }

        // TODO(peterssen): Cache guest j.l.reflect.Parameter constructor.
        // Calling the constructor is just for validation, manually setting the fields would
        // be faster.
        Method parameterInit = meta.java_lang_reflect_Parameter.lookupDeclaredMethod(Names._init_, getSignatures().makeRaw(Types._void,
                        /* name */ Types.java_lang_String,
                        /* modifiers */ Types._int,
                        /* executable */ Types.java_lang_reflect_Executable,
                        /* index */ Types._int));

        // Use attribute's number of parameters.
        return meta.java_lang_reflect_Parameter.allocateReferenceArray(methodParameters.getEntries().length, new IntFunction<StaticObject>() {
            @Override
            public StaticObject apply(int index) {
                MethodParametersAttribute.Entry entry = methodParameters.getEntries()[index];
                StaticObject instance = meta.java_lang_reflect_Parameter.allocateInstance(getContext());
                // For a 0 index, give an empty name.
                StaticObject guestName;
                if (entry.getNameIndex() != 0) {
                    guestName = meta.toGuestString(method.getConstantPool().utf8At(entry.getNameIndex(), "parameter name").toString());
                } else {
                    guestName = getJavaVersion().java9OrLater() ? StaticObject.NULL : meta.toGuestString("");
                }
                parameterInit.invokeDirectSpecial(/* this */ instance,
                                /* name */ guestName,
                                /* modifiers */ entry.getAccessFlags(),
                                /* executable */ executable,
                                /* index */ index);
                return instance;
            }
        });
    }

    @VmImpl(isJni = true)
    public @JavaType(byte[].class) StaticObject JVM_GetMethodTypeAnnotations(@JavaType(java.lang.reflect.Executable.class) StaticObject guestReflectionMethod) {
        // guestReflectionMethod can be either a Method or a Constructor.
        if (InterpreterToVM.instanceOf(guestReflectionMethod, getMeta().java_lang_reflect_Method)) {
            StaticObject methodRoot = getGuestReflectiveMethodRoot(guestReflectionMethod, getMeta());
            assert methodRoot != null;
            return (StaticObject) getMeta().HIDDEN_METHOD_RUNTIME_VISIBLE_TYPE_ANNOTATIONS.getHiddenObject(methodRoot);
        } else if (InterpreterToVM.instanceOf(guestReflectionMethod, getMeta().java_lang_reflect_Constructor)) {
            StaticObject constructorRoot = getGuestReflectiveConstructorRoot(guestReflectionMethod, getMeta());
            assert constructorRoot != null;
            return (StaticObject) getMeta().HIDDEN_CONSTRUCTOR_RUNTIME_VISIBLE_TYPE_ANNOTATIONS.getHiddenObject(constructorRoot);
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw EspressoError.shouldNotReachHere();
        }
    }

    @VmImpl(isJni = true)
    public @JavaType(byte[].class) StaticObject JVM_GetFieldTypeAnnotations(@JavaType(java.lang.reflect.Field.class) StaticObject guestReflectionField) {
        assert InterpreterToVM.instanceOf(guestReflectionField, getMeta().java_lang_reflect_Field);
        StaticObject fieldRoot = getGuestReflectiveFieldRoot(guestReflectionField, getMeta());
        assert fieldRoot != null;
        return (StaticObject) getMeta().HIDDEN_FIELD_RUNTIME_VISIBLE_TYPE_ANNOTATIONS.getHiddenObject(fieldRoot);
    }

    // endregion annotations

    // region Invocation API

    @VmImpl
    @TruffleBoundary
    public int JNI_GetCreatedJavaVMs(@Pointer TruffleObject vmBufPtr, int bufLen, @Pointer TruffleObject numVMsPtr) {
        if (bufLen > 0) {
            if (getUncached().isNull(vmBufPtr)) {
                // Pointer should have been pre-null-checked.
                return JNI_ERR;
            }
            NativeUtils.writeToPointerPointer(getUncached(), vmBufPtr, getVM().getJavaVM());
            if (!getUncached().isNull(numVMsPtr)) {
                NativeUtils.writeToIntPointer(getUncached(), numVMsPtr, 1);
            }
        }
        return JNI_OK;
    }

    // endregion Invocation API

    // region threads

    @VmImpl(isJni = true)
    public @JavaType(Thread[].class) StaticObject JVM_GetAllThreads(@SuppressWarnings("unused") @JavaType(Class.class) StaticObject unused) {
        ThreadAccess threadAccess = getThreadAccess();
        StaticObject[] threads = getContext().getActiveThreads();
        int numThreads = threads.length;
        int i = 0;
        while (i < numThreads) {
            if (threadAccess.isVirtualThread(threads[i])) {
                threads[i] = threads[numThreads - 1];
                numThreads--;
                continue;
            }
            i++;
        }
        if (numThreads < threads.length) {
            threads = Arrays.copyOf(threads, numThreads);
        }
        return getMeta().getAllocator().wrapArrayAs(getMeta().java_lang_Thread.getArrayKlass(), threads);
    }

    @VmImpl(isJni = true)
    public static @JavaType(internalName = "Ljdk/internal/vm/ThreadSnapshot;") StaticObject JVM_CreateThreadSnapshot(@SuppressWarnings("unused") @JavaType(Thread.class) StaticObject thread,
                    @Inject Meta meta) {
        throw meta.throwException(meta.java_lang_UnsupportedOperationException);
    }

    // endregion threads

    // region Management

    @VmImpl
    @TruffleBoundary
    public synchronized @Pointer TruffleObject JVM_GetManagement(int version) {
        EspressoContext context = getContext();
        if (!context.getEspressoEnv().EnableManagement) {
            getLogger().severe("JVM_GetManagement: Experimental support for java.lang.management native APIs is disabled.\n" +
                            "Use '--java.EnableManagement=true' to enable experimental support for j.l.management native APIs.");
            return RawPointer.nullInstance();
        }
        assert management != null;
        return management.getManagement(version);
    }

    /**
     * Returns all values declared to the {@link EspressoOptions#VMArguments} option during context
     * creation.
     * <p>
     * In practice, this should be the list of arguments passed to the context, but depending on who
     * built it, it may be any arbitrary list of Strings.
     * <p>
     * Note that even if that's the case, it may differs slightly from the expected list of
     * arguments. The Java world expects this to be the arguments passed to the VM creation, which
     * is expected to have passed through the regular java launcher, and have been de-sugarified
     * (/ex: '-m [module]' -> '-Djdk.module.main=[module]').
     * <p>
     * In the Java-on-Truffle case, the VM arguments and the context builder options are equivalent,
     * but that is not true in the regular Espresso launcher case, or in an embedding scenario.
     */
    @VmImpl(isJni = true)
    @TruffleBoundary
    public @JavaType(String[].class) StaticObject JVM_GetVmArguments(@Inject EspressoLanguage language) {
        String[] vmArgs = getContext().getVmArguments();
        StaticObject array = getMeta().java_lang_String.allocateReferenceArray(vmArgs.length);
        for (int i = 0; i < vmArgs.length; i++) {
            getInterpreterToVM().setArrayObject(language, getMeta().toGuestString(vmArgs[i]), i, array);
        }
        return array;
    }

    // endregion Management

    // region Modules

    @VmImpl(isJni = true)
    @TruffleBoundary
    public void JVM_AddModuleExports(@JavaType(internalName = "Ljava/lang/Module;") StaticObject from_module,
                    @Pointer TruffleObject pkgName,
                    @JavaType(internalName = "Ljava/lang/Module;") StaticObject to_module,
                    @Inject SubstitutionProfiler profiler) {
        if (StaticObject.isNull(to_module)) {
            profiler.profile(6);
            throw getMeta().throwNullPointerException();
        }
        if (getUncached().isNull(pkgName)) {
            profiler.profile(0);
            throw getMeta().throwNullPointerException();
        }
        ModulesHelperVM.addModuleExports(from_module, NativeUtils.interopPointerToString(pkgName), to_module, getMeta(), profiler);
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public void JVM_AddModuleExportsToAllUnnamed(@JavaType(internalName = "Ljava/lang/Module;") StaticObject from_module, @Pointer TruffleObject pkgName,
                    @Inject SubstitutionProfiler profiler) {
        if (getUncached().isNull(pkgName)) {
            profiler.profile(0);
            throw getMeta().throwNullPointerException();
        }
        ModulesHelperVM.addModuleExportsToAllUnnamed(from_module, NativeUtils.interopPointerToString(pkgName), profiler, getMeta());
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public void JVM_AddModuleExportsToAll(@JavaType(internalName = "Ljava/lang/Module;") StaticObject from_module, @Pointer TruffleObject pkgName,
                    @Inject SubstitutionProfiler profiler) {
        if (getUncached().isNull(pkgName)) {
            profiler.profile(0);
            throw getMeta().throwNullPointerException();
        }
        ModulesHelperVM.addModuleExports(from_module, NativeUtils.interopPointerToString(pkgName), StaticObject.NULL, getMeta(), profiler);
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public void JVM_AddReadsModule(@JavaType(internalName = "Ljava/lang/Module;") StaticObject from_module, @JavaType(internalName = "Ljava/lang/Module;") StaticObject source_module,
                    @Inject SubstitutionProfiler profiler) {
        ModuleEntry fromEntry = ModulesHelperVM.extractFromModuleEntry(from_module, getMeta(), profiler);
        ModuleEntry toEntry = ModulesHelperVM.extractToModuleEntry(source_module, getMeta(), profiler);
        if (fromEntry != toEntry && fromEntry.isNamed()) {
            fromEntry.addReads(toEntry);
        }
    }

    private static final String MODULES = "modules";

    @VmImpl(isJni = true)
    @TruffleBoundary
    public void JVM_DefineModule(@JavaType(internalName = "Ljava/lang/Module;") StaticObject module,
                    boolean isOpen,
                    @JavaType(String.class) StaticObject version,
                    @JavaType(String.class) StaticObject location,
                    @Pointer TruffleObject pkgs,
                    int num_package,
                    @Inject Meta meta,
                    @Inject SubstitutionProfiler profiler) {
        if (StaticObject.isNull(module)) {
            profiler.profile(0);
            throw meta.throwNullPointerException();
        }
        if (num_package < 0) {
            profiler.profile(1);
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "num_package must be >= 0");
        }
        if (getUncached().isNull(pkgs) && num_package > 0) {
            profiler.profile(2);
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "num_packages must be 0 if packages is null");
        }
        if (!meta.java_lang_Module.isAssignableFrom(module.getKlass())) {
            profiler.profile(3);
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "module is not an instance of java.lang.Module");
        }

        StaticObject guestName = meta.java_lang_Module_name.getObject(module);
        if (StaticObject.isNull(guestName)) {
            profiler.profile(4);
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "modue name cannot be null");
        }

        String hostName = meta.toHostString(guestName);
        String hostVersion = meta.toHostString(version);
        String hostLocation = meta.toHostString(location);
        if (hostName.equals(JAVA_BASE)) {
            profiler.profile(5);
            defineJavaBaseModule(module, hostVersion, hostLocation, extractNativePackages(pkgs, num_package, profiler), profiler);
            return;
        }
        profiler.profile(6);
        defineModule(module, hostName, hostVersion, hostLocation, isOpen, extractNativePackages(pkgs, num_package, profiler), profiler);
    }

    @SuppressWarnings("try")
    public void defineModule(StaticObject module,
                    String moduleName,
                    String moduleVersion,
                    String moduleLocation,
                    boolean isOpen,
                    String[] packages,
                    SubstitutionProfiler profiler) {
        Meta meta = getMeta();
        StaticObject loader = meta.java_lang_Module_loader.getObject(module);
        if (loader != nonReflectionClassLoader(loader)) {
            profiler.profile(15);
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "Class loader is an invalid delegating class loader");
        }

        getLogger().finer(() -> "defineModule(module.loader=" + (StaticObject.isNull(loader) ? "bootstrap" : loader.getKlass().getExternalName()) + ", " + moduleName + ", " +
                        Arrays.toString(packages) + ")");

        Set<String> extraPackages = null;
        JImageExtensions jImageExtensions = getLanguage().getJImageExtensions();
        if (jImageExtensions != null) {
            extraPackages = jImageExtensions.getExtensions().get(moduleName);
            if (extraPackages != null) {
                Set<String> finalExtraPackages = extraPackages;
                getLogger().finer(() -> "extraPackages= " + finalExtraPackages);
            }
        }

        // Prepare variables
        ClassRegistry registry = getRegistries().getClassRegistry(loader);
        assert registry != null;
        PackageTable packageTable = registry.packages();
        ModuleTable moduleTable = registry.modules();
        assert moduleTable != null && packageTable != null;
        boolean loaderIsBootOrPlatform = getContext().getClassLoadingEnv().loaderIsBootOrPlatform(loader);

        ArrayList<Symbol<Name>> pkgSymbols = new ArrayList<>();
        try (EntryTable.BlockLock block = packageTable.write()) {
            for (String str : packages) {
                // Extract the package symbols. Also checks for duplicates.
                if (!loaderIsBootOrPlatform && (str.equals("java") || str.startsWith("java/"))) {
                    // Only modules defined to either the boot or platform class loader, can define
                    // a "java/" package.
                    profiler.profile(14);
                    throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException,
                                    cat("Class loader (", loader.getKlass().getType(), ") tried to define prohibited package name: ", str));
                }
                Symbol<Name> symbol = getNames().getOrCreate(str);
                if (packageTable.lookup(symbol) != null) {
                    profiler.profile(13);
                    throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException,
                                    cat("Package ", str, " is already defined."));
                }
                pkgSymbols.add(symbol);
            }
            if (extraPackages != null) {
                for (String str : extraPackages) {
                    Symbol<Name> symbol = getNames().getOrCreate(str);
                    assert packageTable.lookup(symbol) == null;
                    pkgSymbols.add(symbol);
                }
            }
            Symbol<Name> moduleSymbol = getNames().getOrCreate(moduleName);
            // Try define module
            ModuleEntry moduleEntry = moduleTable.createAndAddEntry(moduleSymbol, moduleVersion, moduleLocation, isOpen, module);
            if (moduleEntry == null) {
                // Module already defined
                profiler.profile(12);
                throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException,
                                cat("Module ", moduleName, " is already defined"));
            }
            // Register packages
            for (Symbol<Name> pkgSymbol : pkgSymbols) {
                PackageEntry pkgEntry = packageTable.createAndAddEntry(pkgSymbol, moduleEntry);
                assert pkgEntry != null; // should have been checked before
            }
            // Link guest module to its host representation
            meta.HIDDEN_MODULE_ENTRY.setHiddenObject(module, moduleEntry);
        }
        if (StaticObject.isNull(loader) && getContext().getVmProperties().bootClassPathType().isExplodedModule()) {
            profiler.profile(11);
            // If we have an exploded build, and the module is defined to the bootloader, prepend a
            // class path entry for this module.
            prependModuleClasspath(moduleName);
        }
    }

    void prependModuleClasspath(String moduleName) {
        Path path = getContext().getVmProperties().javaHome().resolve(MODULES).resolve(moduleName);
        ClasspathEntry newEntry = Classpath.createEntry(path.toString());
        if (newEntry.isDirectory()) {
            getContext().getBootClasspath().prepend(newEntry);
            // TODO: prepend path to VM properties' bootClasspath
        }
    }

    private String[] extractNativePackages(TruffleObject pkgs, int numPackages, SubstitutionProfiler profiler) {
        String[] packages = new String[numPackages];
        try {
            for (int i = 0; i < numPackages; i++) {
                String pkg = NativeUtils.interopPointerToString((TruffleObject) getUncached().execute(getPackageAt, pkgs, i));
                if (!Validation.validBinaryName(pkg)) {
                    profiler.profile(7);
                    Meta meta = getMeta();
                    throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException,
                                    cat("Invalid package name: ", pkg));
                }
                packages[i] = pkg;
            }
        } catch (UnsupportedMessageException | ArityException | UnsupportedTypeException e) {
            throw EspressoError.shouldNotReachHere();
        }
        return packages;
    }

    @SuppressWarnings("try")
    public void defineJavaBaseModule(StaticObject module, String moduleVersion, String moduleLocation, String[] packages, SubstitutionProfiler profiler) {
        Meta meta = getMeta();
        StaticObject loader = meta.java_lang_Module_loader.getObject(module);
        if (!StaticObject.isNull(loader)) {
            profiler.profile(10);
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException,
                            "Class loader must be the bootclass loader");
        }
        PackageTable pkgTable = getRegistries().getBootClassRegistry().packages();
        ModuleEntry javaBaseEntry = getRegistries().getJavaBaseModule();
        try (EntryTable.BlockLock block = pkgTable.write()) {
            if (getRegistries().javaBaseDefined()) {
                profiler.profile(9);
                throw meta.throwException(meta.java_lang_InternalError);
            }
            for (String pkg : packages) {
                Symbol<Name> pkgName = getNames().getOrCreate(pkg);
                if (pkgTable.lookup(pkgName) == null) {
                    pkgTable.createAndAddEntry(pkgName, javaBaseEntry);
                }
            }
            javaBaseEntry.setModule(module);
            javaBaseEntry.setVersionAndLocation(moduleVersion, moduleLocation);
            meta.HIDDEN_MODULE_ENTRY.setHiddenObject(module, javaBaseEntry);
            getRegistries().processFixupList(module);
        }
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public void JVM_SetBootLoaderUnnamedModule(@JavaType(internalName = "Ljava/lang/Module;") StaticObject module) {
        Meta meta = getMeta();
        if (StaticObject.isNull(module)) {
            throw meta.throwNullPointerException();
        }
        if (!meta.java_lang_Module.isAssignableFrom(module.getKlass())) {
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "module is not an instance of java.lang.module");
        }
        if (!StaticObject.isNull(meta.java_lang_Module_name.getObject(module))) {
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "boot loader unnamed module has a name");
        }
        if (!StaticObject.isNull(meta.java_lang_Module_loader.getObject(module))) {
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "Class loader must be the boot class loader");
        }
        ModuleEntry bootUnnamed = getRegistries().getBootClassRegistry().getUnnamedModule();
        bootUnnamed.setModule(module);
        meta.HIDDEN_MODULE_ENTRY.setHiddenObject(module, bootUnnamed);
    }

    // endregion Modules

    // region reference

    @VmImpl(isJni = true)
    public @JavaType(Reference.class) StaticObject JVM_GetAndClearReferencePendingList() {
        return getContext().getAndClearReferencePendingList();
    }

    @VmImpl(isJni = true)
    public void JVM_WaitForReferencePendingList() {
        getContext().waitForReferencePendingList();
    }

    @VmImpl(isJni = true)
    public boolean JVM_HasReferencePendingList() {
        return getContext().hasReferencePendingList();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @VmImpl(isJni = true)
    public boolean JVM_PhantomReferenceRefersTo(@JavaType(Reference.class) StaticObject ref, @JavaType(Object.class) StaticObject object,
                    @Inject SubstitutionProfiler profiler) {
        if (StaticObject.isNull(ref)) {
            profiler.profile(0);
            getMeta().throwNullPointerException();
        }
        EspressoReference host = (EspressoReference) getMeta().HIDDEN_HOST_REFERENCE.getHiddenObject(ref);
        if (host == null) {
            // reference was cleared
            return StaticObject.isNull(object);
        }
        assert host instanceof Reference : host;
        // Call host's refersTo. Not available in 8 or 11.
        return ((Reference<StaticObject>) host).refersTo(object);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @VmImpl(isJni = true)
    public boolean JVM_ReferenceRefersTo(@JavaType(Reference.class) StaticObject ref, @JavaType(Object.class) StaticObject object,
                    @Inject SubstitutionProfiler profiler, @Inject Meta meta, @Inject EspressoLanguage language) {
        if (StaticObject.isNull(ref)) {
            profiler.profile(0);
            getMeta().throwNullPointerException();
        }

        if (InterpreterToVM.instanceOf(ref, meta.java_lang_ref_WeakReference) //
                        || InterpreterToVM.instanceOf(ref, meta.java_lang_ref_SoftReference) //
                        || InterpreterToVM.instanceOf(ref, meta.java_lang_ref_PhantomReference) //
                        || InterpreterToVM.instanceOf(ref, meta.java_lang_ref_FinalReference)) {
            EspressoReference host = (EspressoReference) getMeta().HIDDEN_HOST_REFERENCE.getHiddenObject(ref);
            if (host == null) {
                // reference was cleared
                return StaticObject.isNull(object);
            }
            assert host instanceof Reference : host;
            // Call host's refersTo. Not available in 8 or 11.
            return ((Reference<StaticObject>) host).refersTo(object);
        } else {
            StaticObject referent = (StaticObject) meta.java_lang_ref_Reference_referent.get(ref);
            return InterpreterToVM.referenceIdentityEqual(referent, object, language);
        }
    }

    @VmImpl(isJni = true)
    public void JVM_ReferenceClear(@JavaType(Reference.class) StaticObject ref,
                    @Inject SubstitutionProfiler profiler) {
        if (StaticObject.isNull(ref)) {
            profiler.profile(0);
            getMeta().throwNullPointerException();
        }
        Target_java_lang_ref_Reference.clear(ref, getMeta());
    }

    // endregion reference

    // region archive

    @TruffleBoundary
    @VmImpl(isJni = true)
    public static void JVM_DefineArchivedModules(@JavaType(ClassLoader.class) StaticObject platformLoader,
                    @JavaType(ClassLoader.class) StaticObject systemLoader,
                    @Inject EspressoContext context) {
        CDSSupport cds = context.getCDS();
        if (cds == null || !cds.isUsingArchive()) {
            return;
        }

        ClassRegistries registries = context.getRegistries();

        ClassRegistry bootRegistry = registries.getBootClassRegistry();
        ClassRegistry platformRegistry = registries.getClassRegistry(platformLoader);
        ClassRegistry systemRegistry = registries.getClassRegistry(systemLoader);
        for (ClassRegistry registry : List.of(bootRegistry, platformRegistry, systemRegistry)) {
            cds.hydrateFromCache(registry.getUnnamedModule());
            registry.modules().collectValues(cds::hydrateFromCache);
        }

        context.getRegistries().processFixupList(registries.getJavaBaseModule().module());
    }

    @TruffleBoundary
    @VmImpl(isJni = true)
    public static void JVM_InitializeFromArchive(@JavaType(Class.class) StaticObject clazz, @Inject EspressoContext context) {
        CDSSupport cds = context.getCDS();
        if (cds == null) {
            return;
        }

        Klass klass = clazz.getMirrorKlass();
        cds.initializeFromArchive(klass);
    }

    @TruffleBoundary
    @VmImpl
    public static boolean JVM_IsDumpingClassList() {
        return false;
    }

    @TruffleBoundary
    @VmImpl
    public static boolean JVM_IsCDSDumpingEnabled(@Inject EspressoContext context) {
        CDSSupport cds = context.getCDS();
        return cds != null && cds.isDumpingStaticArchive();
    }

    @TruffleBoundary
    @VmImpl
    public static int JVM_GetCDSConfigStatus(@Inject EspressoContext context) {
        CDSSupport cds = context.getCDS();
        if (cds != null) {
            return cds.getCDSConfigStatus(context);
        }
        return 0; // nothing
    }

    @TruffleBoundary
    @VmImpl
    public static boolean JVM_IsSharingEnabled(@Inject EspressoContext context) {
        CDSSupport cds = context.getCDS();
        return cds != null && cds.isUsingArchive();
    }

    @TruffleBoundary
    @VmImpl
    public static long JVM_GetRandomSeedForDumping(@Inject EspressoContext context) {
        CDSSupport cds = context.getCDS();
        if (cds != null && cds.isDumpingStaticArchive()) {
            // HotSpot hashes internal VM data/versions here.
            // Espresso is not tied to a specific Java version, so we keep it simple.
            long seed = Objects.hashCode(EspressoLanguage.VM_VERSION);
            if (seed == 0) { // don't let this ever be zero.
                seed = 0x87654321;
            }
            CDSSupport.getLogger().fine("JVM_GetRandomSeedForDumping() = " + seed);
            return seed;
        } else {
            return 0L;
        }
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    @SuppressWarnings("unused")
    public static void JVM_RegisterLambdaProxyClassForArchiving(
                    @JavaType(Class.class) StaticObject caller,
                    @JavaType(String.class) StaticObject interfaceMethodName,
                    @JavaType(MethodType.class) StaticObject factoryType,
                    @JavaType(MethodType.class) StaticObject interfaceMethodType,
                    @JavaType(internalName = "Ljava/lang/invoke/MemberName;") StaticObject implementationMember,
                    @JavaType(MethodType.class) StaticObject dynamicMethodType,
                    @JavaType(Class.class) StaticObject lambdaProxyClass) {
        // Not supported by Espresso's CDS.
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    @SuppressWarnings("unused")
    public static @JavaType(Class.class) StaticObject JVM_LookupLambdaProxyClassFromArchive(
                    @JavaType(Class.class) StaticObject caller,
                    @JavaType(String.class) StaticObject interfaceMethodName,
                    @JavaType(MethodType.class) StaticObject factoryType,
                    @JavaType(MethodType.class) StaticObject interfaceMethodType,
                    @JavaType(internalName = "Ljava/lang/invoke/MemberName;") StaticObject implementationMember,
                    @JavaType(MethodType.class) StaticObject dynamicMethodType) {
        return StaticObject.NULL; // Not supported by Espresso's CDS.
    }

    // endregion archive

    // region VMSupport
    /**
     * Return the temporary directory that the VM uses for the attach and perf data files.
     *
     * It is important that this directory is well-known and the same for all VM instances. It
     * cannot be affected by configuration variables such as java.io.tmpdir.
     */
    @VmImpl(isJni = true)
    @TruffleBoundary
    public @JavaType(String.class) StaticObject JVM_GetTemporaryDirectory() {
        // TODO: use host VMSupport.getVMTemporaryDirectory(). Not implemented by SVM.
        // host application temporary directory
        return getMeta().toGuestString(System.getProperty("java.io.tmpdir"));
    }

    private static final long ONE_BILLION = 1_000_000_000;
    private static final long MAX_DIFF = 0x0100000000L;

    /**
     * Instant.now() uses System.currentTimeMillis() on a host Java 8. This might produce some loss
     * of precision.
     */
    @VmImpl(isJni = true)
    @SuppressWarnings("unused")
    @TruffleBoundary
    public static long JVM_GetNanoTimeAdjustment(@JavaType(Class.class) StaticObject ignored, long offset) {
        // Instant.now() uses System.currentTimeMillis() on a host Java 8. This might produce some
        // loss of precision.
        Instant now = Instant.now();
        long secs = now.getEpochSecond();
        long nanos = now.getNano();
        long diff = secs - offset;
        if (diff > MAX_DIFF || diff < -MAX_DIFF) {
            return -1;
        }
        // Test above also guards against overflow.
        return (diff * ONE_BILLION) + nanos;
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public static long JVM_MaxObjectInspectionAge() {
        // TODO: somehow use GC.maxObjectInspectionAge() (not supported by SVM);
        return 0;
    }

    // endregion VMSupport

    // region stackwalk

    private volatile StackWalk stackWalk;

    private StackWalk getStackWalk() {
        StackWalk ref = stackWalk;
        if (ref == null) {
            EspressoError.guarantee(getJavaVersion().java9OrLater(), "Stack-Walking API requires Java 9+");
            synchronized (this) {
                ref = stackWalk;
                if (ref == null) {
                    stackWalk = ref = new StackWalk();
                }
            }
        }
        return ref;
    }

    @VmImpl(isJni = true)
    @SuppressWarnings("unused")
    public static void JVM_InitStackTraceElement(@JavaType(StackTraceElement.class) StaticObject element,
                    @JavaType(internalName = "Ljava/lang/StackFrameInfo;") StaticObject info,
                    @Inject Meta meta) {
        if (StaticObject.isNull(element) || StaticObject.isNull(info)) {
            throw meta.throwNullPointerException();
        }
        Field mnameField = meta.getJavaVersion().java22OrLater()
                        ? meta.java_lang_ClassFrameInfo_classOrMemberName
                        : meta.java_lang_StackFrameInfo_memberName;
        Field clazzField = meta.getJavaVersion().java22OrLater()
                        ? meta.java_lang_invoke_ResolvedMethodName_vmholder
                        : meta.java_lang_invoke_MemberName_clazz;
        Field targetField = meta.getJavaVersion().java22OrLater()
                        ? meta.HIDDEN_VM_METHOD // ResolvedMethodName.vmMethod
                        : meta.HIDDEN_VMTARGET; // MemberName.vmTarget
        assert mnameField != null && clazzField != null && targetField != null;
        StaticObject mname = mnameField.getObject(info);
        if (StaticObject.isNull(mname)) {
            throw meta.throwExceptionWithMessage(meta.java_lang_InternalError, "uninitialized StackFrameInfo !");
        }
        StaticObject clazz = clazzField.getObject(mname);
        Method m = (Method) targetField.getHiddenObject(mname);
        if (m == null) {
            throw meta.throwExceptionWithMessage(meta.java_lang_InternalError, "uninitialized StackFrameInfo !");
        }

        int bci = meta.java_lang_StackFrameInfo_bci.getInt(info);
        fillInElement(element, new VM.EspressoStackElement(m, bci), meta);
    }

    @VmImpl(isJni = true)
    public void JVM_InitStackTraceElementArray(
                    @JavaType(StackTraceElement[].class) StaticObject elements, @JavaType(Object.class) StaticObject throwableOrBacktrace,
                    /*- Since JDK21: int depth, */
                    @Inject EspressoLanguage language,
                    @Inject Meta meta,
                    @Inject SubstitutionProfiler profiler) {
        if (StaticObject.isNull(elements) || StaticObject.isNull(throwableOrBacktrace)) {
            profiler.profile(0);
            throw meta.throwNullPointerException();
        }
        assert elements.isArray();

        StaticObject foreignWrapper = null;
        VM.StackTrace stackTrace = null;
        if (throwableOrBacktrace.isForeignObject()) {
            // foreign object wrapper passed as backtrace directly
            foreignWrapper = throwableOrBacktrace;
        } else { // check for foreign marker stack trace
            stackTrace = (VM.StackTrace) meta.HIDDEN_FRAMES.getHiddenObject(throwableOrBacktrace);
            if (stackTrace == StackTrace.FOREIGN_MARKER_STACK_TRACE) {
                foreignWrapper = meta.java_lang_Throwable_backtrace.getObject(throwableOrBacktrace);
            }
        }
        if (foreignWrapper != null) {
            AbstractTruffleException foreignException = (AbstractTruffleException) foreignWrapper.rawForeignObject(language);
            InteropLibrary interop = InteropLibrary.getUncached();
            try {
                Object exceptionStackTrace = interop.getExceptionStackTrace(foreignException);
                int stackSize = (int) interop.getArraySize(exceptionStackTrace);
                if (elements.length(language) < stackSize) {
                    profiler.profile(1);
                    throw meta.throwException(meta.java_lang_IndexOutOfBoundsException);
                }
                for (int i = 0; i < stackSize; i++) {
                    if (StaticObject.isNull(elements.get(language, i))) {
                        profiler.profile(2);
                        throw meta.throwNullPointerException();
                    }
                    fillInForeignElement(elements, language, interop, exceptionStackTrace, i);
                }
            } catch (InteropException e) {
                throw meta.throwException(meta.java_lang_IllegalArgumentException);
            }
        } else if (stackTrace != null) {
            if (elements.length(language) != stackTrace.size) {
                profiler.profile(1);
                throw meta.throwException(meta.java_lang_IndexOutOfBoundsException);
            }
            for (int i = 0; i < stackTrace.size; i++) {
                if (StaticObject.isNull(elements.get(language, i))) {
                    profiler.profile(2);
                    throw meta.throwNullPointerException();
                }
                fillInElement(elements.get(language, i), stackTrace.trace[i], meta);
            }
        }
    }

    @TruffleBoundary
    private void fillInForeignElement(StaticObject elements, EspressoLanguage language, InteropLibrary interop, Object exceptionStackTrace, int i)
                    throws UnsupportedMessageException, InvalidArrayIndexException {
        Object foreignElement = interop.readArrayElement(exceptionStackTrace, i);

        String languageId = "java";
        String fileName = "<unknown>";
        int lineNumber = EspressoStackElement.NATIVE_BCI;
        if (interop.hasSourceLocation(foreignElement)) {
            SourceSection sourceLocation = interop.getSourceLocation(foreignElement);
            fileName = sourceLocation.getSource().getName();
            if (sourceLocation.hasLines()) {
                lineNumber = sourceLocation.getStartLine();
            }
            languageId = sourceLocation.getSource().getLanguage();
        }

        String declaringClassName = languageId.equals("java") ? "" : "<" + languageId + ">";
        if (interop.hasDeclaringMetaObject(foreignElement)) {
            Object declaringMetaObject = interop.getDeclaringMetaObject(foreignElement);
            declaringClassName += interop.asString(interop.getMetaQualifiedName(declaringMetaObject));
        }

        String methodName = "<unknownForeignMethod>";
        if (interop.hasExecutableName(foreignElement)) {
            methodName = interop.asString(interop.getExecutableName(foreignElement));
        }

        ForeignStackElement foreignStackElement = new ForeignStackElement(declaringClassName, methodName, fileName, lineNumber);
        fillInElement(elements.get(language, i), foreignStackElement, getMeta());
    }

    public static void fillInElement(@JavaType(StackTraceElement.class) StaticObject ste, VM.StackElement element, Meta meta) {
        Method m = element.getMethod();
        if (m != null) {
            // espresso frame
            ObjectKlass k = m.getDeclaringKlass();
            StaticObject guestClass = k.mirror();
            StaticObject loader = k.getDefiningClassLoader();
            ModuleEntry module = k.module();

            if (meta.getJavaVersion().java9OrLater()) {
                meta.java_lang_StackTraceElement_declaringClassObject.setObject(ste, guestClass);
                // Fill in loader name
                if (!StaticObject.isNull(loader)) {
                    StaticObject loaderName = meta.java_lang_ClassLoader_name.getObject(loader);
                    if (!StaticObject.isNull(loader)) {
                        meta.java_lang_StackTraceElement_classLoaderName.setObject(ste, loaderName);
                    }
                }
                // Fill in module
                if (module.isNamed()) {
                    meta.java_lang_StackTraceElement_moduleName.setObject(ste, meta.toGuestString(module.getName()));
                    if (module.version() != null) {
                        meta.java_lang_StackTraceElement_moduleVersion.setObject(ste, meta.toGuestString(module.version()));
                    }
                }
            }
        } else { // foreign frame
            if (meta.getJavaVersion().java9OrLater()) {
                meta.java_lang_StackTraceElement_declaringClassObject.setObject(ste, meta.java_lang_Object.mirror());
            }
        }
        fillInElementBasic(ste, element, meta);
    }

    public static void fillInElementBasic(StaticObject ste, StackElement element, Meta meta) {
        // Fill in class name
        meta.java_lang_StackTraceElement_declaringClass.setObject(ste, element.getGuestDeclaringClassName(meta));
        // Fill in method name
        meta.java_lang_StackTraceElement_methodName.setObject(ste, meta.toGuestString(element.getMethodName()));

        // Fill in source information
        meta.java_lang_StackTraceElement_fileName.setObject(ste, meta.toGuestString(element.getFileName()));
        meta.java_lang_StackTraceElement_lineNumber.setInt(ste, element.getLineNumber());
    }

    private static void checkStackWalkArguments(EspressoLanguage language, int batchSize, int startIndex, @JavaType(Object[].class) StaticObject frames, Meta meta) {
        if (StaticObject.isNull(frames)) {
            throw meta.throwNullPointerException();
        }
        assert frames.isArray();
        int limit = startIndex + batchSize;
        if (frames.length(language) < limit) {
            throw meta.throwExceptionWithMessage(meta.java_lang_IllegalArgumentException, "Not enough space in buffers");
        }
    }

    @VmImpl(isJni = true)
    public @JavaType(Object.class) StaticObject JVM_CallStackWalk(
                    @JavaType(internalName = "Ljava/lang/StackStreamFactory;") StaticObject stackStream, long mode, int skipframes,
                    int batchSize, int startIndex,
                    @JavaType(Object[].class) StaticObject frames,
                    @Inject EspressoLanguage language,
                    @Inject Meta meta) {
        return JVM_CallStackWalk19(stackStream, mode, skipframes, StaticObject.NULL, StaticObject.NULL, batchSize, startIndex, frames, language, meta);
    }

    @TruffleBoundary
    public @JavaType(Object.class) StaticObject JVM_CallStackWalk19(
                    @JavaType(internalName = "Ljava/lang/StackStreamFactory;") StaticObject stackStream, long mode, int skipframes,
                    @JavaType(internalName = "Ljdk/internal/vm/ContinuationScope;") StaticObject contScope,
                    @JavaType(internalName = "Ljdk/internal/vm/Continuation;") StaticObject cont,
                    int batchSize, int startIndex,
                    @JavaType(Object[].class) StaticObject frames,
                    @Inject EspressoLanguage language,
                    @Inject Meta meta) {
        if (!StaticObject.isNull(contScope) && !StaticObject.isNull(cont)) {
            throw EspressoError.unimplemented("virtual thread support");
        }
        checkStackWalkArguments(language, batchSize, startIndex, frames, meta);
        return getStackWalk().fetchFirstBatch(stackStream, mode, skipframes, batchSize, startIndex, frames, meta);
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public int JVM_MoreStackWalk(
                    @JavaType(internalName = "Ljava/lang/StackStreamFactory;") StaticObject stackStream, long mode, long anchor,
                    int batchSize, int startIndex,
                    @JavaType(Object[].class) StaticObject frames,
                    @Inject EspressoLanguage language,
                    @Inject Meta meta) {
        checkStackWalkArguments(language, batchSize, startIndex, frames, meta);
        return getStackWalk().fetchNextBatch(stackStream, mode, anchor, batchSize, startIndex, frames, meta);
    }

    @VmImpl(isJni = true)
    @TruffleBoundary
    public static void JVM_ExpandStackFrameInfo(@JavaType(internalName = "Ljava/lang/StackFrameInfo;") StaticObject obj,
                    @Inject Meta meta) {
        StaticObject resolvedMethodName = meta.java_lang_ClassFrameInfo_classOrMemberName.getObject(obj);
        if (StaticObject.isNull(resolvedMethodName) || resolvedMethodName.getKlass() != meta.java_lang_invoke_ResolvedMethodName) {
            return;
        }
        Method m = (Method) meta.HIDDEN_VM_METHOD.getHiddenObject(resolvedMethodName);
        if (m == null) {
            return;
        }
        boolean hasName = !StaticObject.isNull(meta.java_lang_StackFrameInfo_name.getObject(obj));
        boolean hasType = !StaticObject.isNull(meta.java_lang_StackFrameInfo_type.getObject(obj));
        if (!hasName) {
            meta.java_lang_StackFrameInfo_name.setObject(obj, meta.toGuestString(m.getName()));
        }
        if (!hasType) {
            meta.java_lang_StackFrameInfo_type.setObject(obj, meta.toGuestString(m.getRawSignature()));
        }
    }

    // endregion stackwalk

    // Checkstyle: resume method name check
}
