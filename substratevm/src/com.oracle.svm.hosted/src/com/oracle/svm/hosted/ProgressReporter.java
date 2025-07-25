/*
 * Copyright (c) 2021, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.svm.hosted;

import static com.oracle.svm.hosted.ProgressReporterJsonHelper.UNAVAILABLE_METRIC;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.impl.ImageSingletonsSupport;

import com.oracle.graal.pointsto.meta.AnalysisElement;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.graal.pointsto.reports.ReportUtils;
import com.oracle.graal.pointsto.util.Timer;
import com.oracle.graal.pointsto.util.TimerCollection;
import com.oracle.svm.common.option.CommonOptionParser;
import com.oracle.svm.core.BuildArtifacts;
import com.oracle.svm.core.BuildArtifacts.ArtifactType;
import com.oracle.svm.core.SubstrateGCOptions;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.VM;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.hub.ClassForNameSupport;
import com.oracle.svm.core.jdk.Resources;
import com.oracle.svm.core.option.AccumulatingLocatableMultiOptionValue;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.option.HostedOptionValues;
import com.oracle.svm.core.option.OptionMigrationMessage;
import com.oracle.svm.core.option.OptionOrigin;
import com.oracle.svm.core.option.OptionUtils;
import com.oracle.svm.core.option.RuntimeOptionKey;
import com.oracle.svm.core.option.SubstrateOptionsParser;
import com.oracle.svm.core.traits.BuiltinTraits;
import com.oracle.svm.core.traits.SingletonLayeredInstallationKind;
import com.oracle.svm.core.traits.SingletonTraits;
import com.oracle.svm.core.util.TimeUtils;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.ProgressReporterFeature.UserRecommendation;
import com.oracle.svm.hosted.ProgressReporterJsonHelper.AnalysisResults;
import com.oracle.svm.hosted.ProgressReporterJsonHelper.GeneralInfo;
import com.oracle.svm.hosted.ProgressReporterJsonHelper.ImageDetailKey;
import com.oracle.svm.hosted.ProgressReporterJsonHelper.JsonMetric;
import com.oracle.svm.hosted.ProgressReporterJsonHelper.ResourceUsageKey;
import com.oracle.svm.hosted.c.codegen.CCompilerInvoker;
import com.oracle.svm.hosted.image.AbstractImage.NativeImageKind;
import com.oracle.svm.hosted.image.NativeImageDebugInfoStripFeature;
import com.oracle.svm.hosted.reflect.ReflectionHostedSupport;
import com.oracle.svm.hosted.util.CPUType;
import com.oracle.svm.hosted.util.DiagnosticUtils;
import com.oracle.svm.hosted.util.VMErrorReporter;
import com.oracle.svm.util.ImageBuildStatistics;
import com.sun.management.OperatingSystemMXBean;

import jdk.graal.compiler.options.OptionDescriptor;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionStability;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.util.json.JsonWriter;

@SingletonTraits(access = BuiltinTraits.BuildtimeAccessOnly.class, layeredCallbacks = BuiltinTraits.NoLayeredCallbacks.class, layeredInstallationKind = SingletonLayeredInstallationKind.Independent.class)
public class ProgressReporter {
    private static final int CHARACTERS_PER_LINE;
    private static final String HEADLINE_SEPARATOR;
    private static final String LINE_SEPARATOR;
    private static final int MAX_NUM_BREAKDOWN = 10;
    public static final String DOCS_BASE_URL = "https://github.com/oracle/graal/blob/master/docs/reference-manual/native-image/BuildOutput.md";
    private static final double EXCESSIVE_GC_MIN_THRESHOLD_MILLIS = TimeUtils.secondsToMillis(15);
    private static final double EXCESSIVE_GC_RATIO = 0.5;

    private final NativeImageSystemIOWrappers builderIO;

    public final ProgressReporterJsonHelper jsonHelper;
    private final DirectPrinter linePrinter = new DirectPrinter();
    private final StringBuilder buildOutputLog = new StringBuilder();
    private final StagePrinter<?> stagePrinter;
    private final ColorStrategy colorStrategy;
    private final LinkStrategy linkStrategy;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private long lastGCCheckTimeNanos = System.nanoTime();
    private GCStats lastGCStats = GCStats.getCurrent();
    private long numRuntimeCompiledMethods = -1;
    private int numJNIClasses = -1;
    private int numJNIFields = -1;
    private int numJNIMethods = -1;
    private int numForeignDowncalls = -1;
    private int numForeignUpcalls = -1;
    private Timer debugInfoTimer;
    private boolean creationStageEndCompleted = false;

    /**
     * Build stages displayed as part of the Native Image build output. Changing this enum may
     * require updating the doc entries for each stage in the BuildOutput.md.
     */
    private enum BuildStage {
        INITIALIZING("Initializing"),
        ANALYSIS("Performing analysis", true, false),
        UNIVERSE("Building universe"),
        PARSING("Parsing methods", true, true),
        INLINING("Inlining methods", true, false),
        COMPILING("Compiling methods", true, true),
        LAYING_OUT("Laying out methods", true, true),
        CREATING("Creating image", true, true);

        private static final int NUM_STAGES = values().length;

        private final String message;
        private final boolean hasProgressBar;
        private final boolean hasPeriodicProgress;

        BuildStage(String message) {
            this(message, false, false);
        }

        BuildStage(String message, boolean hasProgressBar, boolean hasPeriodicProgress) {
            this.message = message;
            this.hasProgressBar = hasProgressBar;
            this.hasPeriodicProgress = hasPeriodicProgress;
        }
    }

    static {
        CHARACTERS_PER_LINE = SubstrateUtil.isNonInteractiveTerminal() ? ProgressReporterCHelper.MAX_CHARACTERS_PER_LINE : ProgressReporterCHelper.getTerminalWindowColumnsClamped();
        HEADLINE_SEPARATOR = Utils.stringFilledWith(CHARACTERS_PER_LINE, "=");
        LINE_SEPARATOR = Utils.stringFilledWith(CHARACTERS_PER_LINE, "-");
    }

    public static ProgressReporter singleton() {
        return ImageSingletons.lookup(ProgressReporter.class);
    }

    public ProgressReporter(OptionValues options) {
        if (SubstrateOptions.BuildOutputSilent.getValue(options)) {
            builderIO = NativeImageSystemIOWrappers.disabled();
        } else {
            builderIO = NativeImageSystemIOWrappers.singleton();
        }
        jsonHelper = new ProgressReporterJsonHelper();

        boolean enableColors = SubstrateOptions.hasColorsEnabled(options);
        colorStrategy = enableColors ? new ColorfulStrategy() : new ColorlessStrategy();
        stagePrinter = SubstrateOptions.BuildOutputProgress.getValue(options) ? new CharacterwiseStagePrinter() : new LinewiseStagePrinter();
        linkStrategy = SubstrateOptions.BuildOutputLinks.getValue(options) ? new LinkyStrategy() : new LinklessStrategy();
    }

    public void setNumRuntimeCompiledMethods(int value) {
        numRuntimeCompiledMethods = value;
    }

    public void setJNIInfo(int numClasses, int numFields, int numMethods) {
        numJNIClasses = numClasses;
        numJNIFields = numFields;
        numJNIMethods = numMethods;
    }

    public void setForeignFunctionsInfo(int numDowncallStubs, int numUpcallStubs) {
        this.numForeignDowncalls = numDowncallStubs;
        this.numForeignUpcalls = numUpcallStubs;
    }

    public void printStart(String imageName, NativeImageKind imageKind) {
        l().printHeadlineSeparator();
        String outputFilename = imageKind.getOutputFilename(imageName);
        recordJsonMetric(GeneralInfo.NAME, outputFilename);
        String imageKindName = imageKind.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        l().blueBold().link("GraalVM Native Image", "https://www.graalvm.org/native-image/").reset()
                        .a(": Generating '").bold().a(outputFilename).reset().a("' (").doclink(imageKindName, "#glossary-imagekind").a(")...").println();
        l().printHeadlineSeparator();
        if (!linkStrategy.isTerminalSupported()) {
            l().a("For detailed information and explanations on the build output, visit:").println();
            l().a(DOCS_BASE_URL).println();
            l().printLineSeparator();
        }
        stagePrinter.start(BuildStage.INITIALIZING);
    }

    public void printUnsuccessfulInitializeEnd() {
        if (stagePrinter.activeBuildStage != null) {
            stagePrinter.end(0);
        }
    }

    public void printInitializeEnd(List<Feature> features, ImageClassLoader classLoader) {
        stagePrinter.end(getTimer(TimerCollection.Registry.CLASSLIST).getTotalTime() + getTimer(TimerCollection.Registry.SETUP).getTotalTime());
        VM vm = ImageSingletons.lookup(VM.class);
        recordJsonMetric(GeneralInfo.JAVA_VERSION, vm.version);
        recordJsonMetric(GeneralInfo.VENDOR_VERSION, vm.vendorVersion);
        recordJsonMetric(GeneralInfo.GRAALVM_VERSION, vm.vendorVersion); // deprecated
        l().a(" ").doclink("Java version", "#glossary-java-info").a(": ").a(vm.version).a(", ").doclink("vendor version", "#glossary-java-info").a(": ").a(vm.vendorVersion).println();
        String optimizationLevel = SubstrateOptions.Optimize.getValue();
        recordJsonMetric(GeneralInfo.GRAAL_COMPILER_OPTIMIZATION_LEVEL, optimizationLevel);
        String march = CPUType.getSelectedOrDefaultMArch();
        recordJsonMetric(GeneralInfo.GRAAL_COMPILER_MARCH, march);
        DirectPrinter graalLine = l().a(" ").doclink("Graal compiler", "#glossary-graal-compiler").a(": optimization level: %s, target machine: %s", optimizationLevel, march);
        ImageSingletons.lookup(ProgressReporterFeature.class).appendGraalSuffix(graalLine);
        graalLine.println();
        String cCompilerShort = null;
        if (ImageSingletons.contains(CCompilerInvoker.class)) {
            cCompilerShort = ImageSingletons.lookup(CCompilerInvoker.class).compilerInfo.getShortDescription();
            l().a(" ").doclink("C compiler", "#glossary-ccompiler").a(": ").a(cCompilerShort).println();
        }
        recordJsonMetric(GeneralInfo.CC, cCompilerShort);
        String gcName = Heap.getHeap().getGC().getName();
        recordJsonMetric(GeneralInfo.GC, gcName);
        long maxHeapSize = SubstrateGCOptions.MaxHeapSize.getValue();
        String maxHeapValue = maxHeapSize == 0 ? Heap.getHeap().getGC().getDefaultMaxHeapSize() : ByteFormattingUtil.bytesToHuman(maxHeapSize);
        l().a(" ").doclink("Garbage collector", "#glossary-gc").a(": ").a(gcName).a(" (").doclink("max heap size", "#glossary-gc-max-heap-size").a(": ").a(maxHeapValue).a(")").println();

        printFeatures(features);
        printExperimentalOptions(classLoader);
        printResourceInfo();
    }

    private void printFeatures(List<Feature> features) {
        int numFeatures = features.size();
        if (numFeatures > 0) {
            l().a(" ").a(numFeatures).a(" ").doclink("user-specific feature(s)", "#glossary-user-specific-features").a(":").println();
            features.sort(Comparator.comparing(a -> a.getClass().getName()));
            for (Feature feature : features) {
                printFeature(l(), feature);
            }
        }
    }

    private static void printFeature(DirectPrinter printer, Feature feature) {
        printer.a(" - ");
        String name = feature.getClass().getName();
        String url = feature.getURL();
        if (url != null) {
            printer.link(name, url);
        } else {
            printer.a(name);
        }
        String description = feature.getDescription();
        if (description != null) {
            printer.a(": ").a(description);
        }
        printer.println();
    }

    record ExperimentalOptionDetails(String migrationMessage, String alternatives, String origins) {
        public String toSuffix() {
            if (migrationMessage.isEmpty() && alternatives.isEmpty() && origins.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (!migrationMessage.isEmpty()) {
                sb.append(": ").append(migrationMessage);
            }
            if (!alternatives.isEmpty() || !origins.isEmpty()) {
                sb.append(" (");
                if (!alternatives.isEmpty()) {
                    sb.append("alternative API option(s): ").append(alternatives);
                }
                if (!origins.isEmpty()) {
                    if (!alternatives.isEmpty()) {
                        sb.append("; ");
                    }
                    sb.append("origin(s): ").append(origins);
                }
                sb.append(")");
            }
            return sb.toString();
        }
    }

    private void printExperimentalOptions(ImageClassLoader classLoader) {
        /*
         * Step 1: scan all builder arguments and collect relevant options.
         */
        Map<String, OptionOrigin> experimentalBuilderOptionsAndOrigins = new HashMap<>();
        for (String arg : DiagnosticUtils.getBuilderArguments(classLoader)) {
            if (!arg.startsWith(CommonOptionParser.HOSTED_OPTION_PREFIX)) {
                continue;
            }
            String[] optionParts = arg.split("=", 2)[0].split("@", 2);
            OptionOrigin optionOrigin = optionParts.length == 2 ? OptionOrigin.from(optionParts[1], false) : null;
            if (optionOrigin == null || !isStableOrInternalOrigin(optionOrigin)) {
                String prefixedOptionName = optionParts[0];
                experimentalBuilderOptionsAndOrigins.put(prefixedOptionName, optionOrigin);
            }
        }
        if (experimentalBuilderOptionsAndOrigins.isEmpty()) {
            return;
        }
        /*
         * Step 2: scan HostedOptionValues and collect migrationMessage, alternatives, and origins.
         */
        Map<String, ExperimentalOptionDetails> experimentalOptions = new HashMap<>();
        var hostedOptionValues = HostedOptionValues.singleton().getMap();
        for (OptionKey<?> option : hostedOptionValues.getKeys()) {
            if (option instanceof RuntimeOptionKey || option == SubstrateOptions.UnlockExperimentalVMOptions || option.getDescriptor().getStability() != OptionStability.EXPERIMENTAL) {
                continue;
            }
            OptionDescriptor descriptor = option.getDescriptor();
            Object optionValue = option.getValueOrDefault(hostedOptionValues);
            String emptyOrBooleanValue = "";
            if (descriptor.getOptionValueType() == Boolean.class) {
                emptyOrBooleanValue = Boolean.parseBoolean(optionValue.toString()) ? "+" : "-";
            }
            String prefixedOptionName = CommonOptionParser.HOSTED_OPTION_PREFIX + emptyOrBooleanValue + option.getName();
            if (!experimentalBuilderOptionsAndOrigins.containsKey(prefixedOptionName)) {
                /* Only check builder arguments, ignore options that were set as part of others. */
                continue;
            }
            String origins;
            String migrationMessage = OptionUtils.getAnnotationsByType(descriptor, OptionMigrationMessage.class).stream().map(OptionMigrationMessage::value).collect(Collectors.joining(". "));
            String alternatives = "";

            if (optionValue instanceof AccumulatingLocatableMultiOptionValue<?> lmov) {
                if (lmov.getValuesWithOrigins().allMatch(o -> o.origin().isStable())) {
                    continue;
                } else {
                    origins = lmov.getValuesWithOrigins().filter(p -> !isStableOrInternalOrigin(p.origin())).map(p -> p.origin().toString()).distinct().collect(Collectors.joining(", "));
                    alternatives = lmov.getValuesWithOrigins().map(p -> SubstrateOptionsParser.commandArgument(option, p.value().toString()))
                                    .filter(c -> !c.startsWith(CommonOptionParser.HOSTED_OPTION_PREFIX))
                                    .collect(Collectors.joining(", "));
                }
            } else {
                OptionOrigin origin = experimentalBuilderOptionsAndOrigins.get(prefixedOptionName);
                if (origin == null && option instanceof HostedOptionKey<?> hok) {
                    origin = hok.getLastOrigin();
                }
                if (origin == null /* unknown */ || isStableOrInternalOrigin(origin)) {
                    continue;
                }
                origins = origin.toString();
                String optionValueString;
                if (descriptor.getOptionValueType() == Boolean.class) {
                    assert !emptyOrBooleanValue.isEmpty();
                    optionValueString = emptyOrBooleanValue;
                } else {
                    optionValueString = String.valueOf(optionValue);
                }
                String command = SubstrateOptionsParser.commandArgument(option, optionValueString);
                if (!command.startsWith(CommonOptionParser.HOSTED_OPTION_PREFIX)) {
                    alternatives = command;
                }
            }
            experimentalOptions.put(prefixedOptionName, new ExperimentalOptionDetails(migrationMessage, alternatives, origins));
        }
        /*
         * Step 3: print list of experimental options (if any).
         */
        if (experimentalOptions.isEmpty()) {
            return;
        }
        l().printLineSeparator();
        l().yellowBold().a(" ").a(experimentalOptions.size()).a(" ").doclink("experimental option(s)", "#glossary-experimental-options").a(" unlocked").reset().a(":").println();
        for (var optionAndDetails : experimentalOptions.entrySet()) {
            l().a(" - '%s'%s", optionAndDetails.getKey(), optionAndDetails.getValue().toSuffix()).println();
        }
    }

    private static boolean isStableOrInternalOrigin(OptionOrigin origin) {
        return origin.isStable() || origin.isInternal();
    }

    private void printResourceInfo() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        recordJsonMetric(ResourceUsageKey.GC_MAX_HEAP, maxMemory);
        long totalMemorySize = getOperatingSystemMXBean().getTotalMemorySize();
        recordJsonMetric(ResourceUsageKey.MEMORY_TOTAL, totalMemorySize);

        List<String> inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        List<String> maxRAMPercentageValues = inputArguments.stream().filter(arg -> arg.startsWith("-XX:MaxRAMPercentage=") || arg.startsWith("-XX:MaximumHeapSizePercent=")).toList();
        String memoryUsageReason = "unknown";
        if (maxRAMPercentageValues.size() == 1) { // The driver sets one of these options once
            memoryUsageReason = System.getProperty(SubstrateOptions.BUILD_MEMORY_USAGE_REASON_TEXT_PROPERTY, "unknown");
        } else if (maxRAMPercentageValues.size() > 1) {
            memoryUsageReason = "set via '%s'".formatted(maxRAMPercentageValues.getLast());
        }
        String xmxValueOrNull = inputArguments.stream().filter(arg -> arg.startsWith("-Xmx")).reduce((first, second) -> second).orElse(null);
        if (xmxValueOrNull != null) { // -Xmx takes precedence over -XX:MaxRAMPercentage
            memoryUsageReason = "set via '%s'".formatted(xmxValueOrNull);
        }

        int maxNumberOfThreads = NativeImageOptions.getActualNumberOfThreads();
        recordJsonMetric(ResourceUsageKey.PARALLELISM, maxNumberOfThreads);
        int availableProcessors = runtime.availableProcessors();
        recordJsonMetric(ResourceUsageKey.CPU_CORES_TOTAL, availableProcessors);
        String maxNumberOfThreadsSuffix = "determined at start";
        if (NativeImageOptions.NumberOfThreads.hasBeenSet()) {
            maxNumberOfThreadsSuffix = "set via '%s'".formatted(SubstrateOptionsParser.commandArgument(NativeImageOptions.NumberOfThreads, Integer.toString(maxNumberOfThreads)));
        }

        l().printLineSeparator();
        l().yellowBold().doclink("Build resources", "#glossary-build-resources").a(":").reset().println();
        l().a(" - %s of memory (%.1f%% of system memory, %s)", ByteFormattingUtil.bytesToHuman(maxMemory), Utils.toPercentage(maxMemory, totalMemorySize), memoryUsageReason).println();
        l().a(" - %s thread(s) (%.1f%% of %s available processor(s), %s)",
                        maxNumberOfThreads, Utils.toPercentage(maxNumberOfThreads, availableProcessors), availableProcessors, maxNumberOfThreadsSuffix).println();
    }

    public ReporterClosable printAnalysis(AnalysisUniverse universe, Collection<String> libraries) {
        return print(TimerCollection.Registry.ANALYSIS, BuildStage.ANALYSIS, () -> printAnalysisStatistics(universe, libraries));
    }

    private ReporterClosable print(TimerCollection.Registry registry, BuildStage buildStage) {
        return print(registry, buildStage, null);
    }

    private ReporterClosable print(TimerCollection.Registry registry, BuildStage buildStage, Runnable extraPrint) {
        Timer timer = getTimer(registry);
        timer.start();
        stagePrinter.start(buildStage);
        return new ReporterClosable() {
            @Override
            public void closeAction() {
                timer.stop();
                stagePrinter.end(timer);
                if (extraPrint != null) {
                    extraPrint.run();
                }
            }
        };
    }

    private void printAnalysisStatistics(AnalysisUniverse universe, Collection<String> libraries) {
        String typesFieldsMethodFormat = "%,9d types, %,7d fields, and %,7d methods ";
        long reachableTypes = reportedReachableTypes(universe).size();
        long totalTypes = universe.getTypes().size();
        recordJsonMetric(AnalysisResults.DEPRECATED_TYPES_TOTAL, totalTypes);
        recordJsonMetric(AnalysisResults.TYPES_REACHABLE, reachableTypes);
        Collection<AnalysisField> fields = universe.getFields();
        long reachableFields = reportedReachableFields(universe).size();
        int totalFields = fields.size();
        recordJsonMetric(AnalysisResults.DEPRECATED_FIELD_TOTAL, totalFields);
        recordJsonMetric(AnalysisResults.FIELD_REACHABLE, reachableFields);
        Collection<AnalysisMethod> methods = universe.getMethods();
        long reachableMethods = reportedReachableMethods(universe).size();
        int totalMethods = methods.size();
        recordJsonMetric(AnalysisResults.DEPRECATED_METHOD_TOTAL, totalMethods);
        recordJsonMetric(AnalysisResults.METHOD_REACHABLE, reachableMethods);
        l().a(typesFieldsMethodFormat, reachableTypes, reachableFields, reachableMethods)
                        .doclink("found reachable", "#glossary-reachability").println();
        int reflectClassesCount = ClassForNameSupport.currentLayer().count();
        ReflectionHostedSupport rs = ImageSingletons.lookup(ReflectionHostedSupport.class);
        int reflectFieldsCount = rs.getReflectionFieldsCount();
        int reflectMethodsCount = rs.getReflectionMethodsCount();
        recordJsonMetric(AnalysisResults.METHOD_REFLECT, reflectMethodsCount);
        recordJsonMetric(AnalysisResults.TYPES_REFLECT, reflectClassesCount);
        recordJsonMetric(AnalysisResults.FIELD_REFLECT, reflectFieldsCount);
        l().a(typesFieldsMethodFormat, reflectClassesCount, reflectFieldsCount, reflectMethodsCount)
                        .doclink("registered for reflection", "#glossary-reflection-registrations").println();
        recordJsonMetric(AnalysisResults.METHOD_JNI, (numJNIMethods >= 0 ? numJNIMethods : UNAVAILABLE_METRIC));
        recordJsonMetric(AnalysisResults.TYPES_JNI, (numJNIClasses >= 0 ? numJNIClasses : UNAVAILABLE_METRIC));
        recordJsonMetric(AnalysisResults.FIELD_JNI, (numJNIFields >= 0 ? numJNIFields : UNAVAILABLE_METRIC));
        if (numJNIClasses >= 0) {
            l().a(typesFieldsMethodFormat, numJNIClasses, numJNIFields, numJNIMethods)
                            .doclink("registered for JNI access", "#glossary-jni-access-registrations").println();
        }
        String stubsFormat = "%,9d downcalls and %,d upcalls ";
        recordJsonMetric(AnalysisResults.FOREIGN_DOWNCALLS, (numForeignDowncalls >= 0 ? numForeignDowncalls : UNAVAILABLE_METRIC));
        recordJsonMetric(AnalysisResults.FOREIGN_UPCALLS, (numForeignUpcalls >= 0 ? numForeignUpcalls : UNAVAILABLE_METRIC));
        if (numForeignDowncalls >= 0 || numForeignUpcalls >= 0) {
            l().a(stubsFormat, numForeignDowncalls, numForeignUpcalls)
                            .doclink("registered for foreign access", "#glossary-foreign-downcall-and-upcall-registrations").println();
        }
        int numLibraries = libraries.size();
        if (numLibraries > 0) {
            TreeSet<String> sortedLibraries = new TreeSet<>(libraries);
            l().a("%,9d native %s: ", numLibraries, numLibraries == 1 ? "library" : "libraries").a(String.join(", ", sortedLibraries)).println();
        }
        if (numRuntimeCompiledMethods >= 0) {
            recordJsonMetric(ImageDetailKey.RUNTIME_COMPILED_METHODS_COUNT, numRuntimeCompiledMethods);
            l().a("%,9d ", numRuntimeCompiledMethods).doclink("runtime compiled methods", "#glossary-runtime-methods")
                            .a(" (%.1f%% of all reachable methods)", Utils.toPercentage(numRuntimeCompiledMethods, reachableMethods), reachableMethods).println();
        }
    }

    public static List<AnalysisType> reportedReachableTypes(AnalysisUniverse universe) {
        return reportedElements(universe, universe.getTypes(), AnalysisType::isReachable, t -> !t.isInBaseLayer());
    }

    public static List<AnalysisField> reportedReachableFields(AnalysisUniverse universe) {
        return reportedElements(universe, universe.getFields(), AnalysisField::isAccessed, f -> !f.isInBaseLayer());
    }

    public static List<AnalysisMethod> reportedReachableMethods(AnalysisUniverse universe) {
        return reportedElements(universe, universe.getMethods(), AnalysisMethod::isReachable, m -> !m.isInBaseLayer());
    }

    private static <T extends AnalysisElement> List<T> reportedElements(AnalysisUniverse universe, Collection<T> elements, Predicate<T> elementsFilter, Predicate<T> baseLayerFilter) {
        Stream<T> reachableElements = elements.stream().filter(elementsFilter);
        return universe.hostVM().useBaseLayer() ? reachableElements.filter(baseLayerFilter).toList() : reachableElements.toList();
    }

    public ReporterClosable printUniverse() {
        return print(TimerCollection.Registry.UNIVERSE, BuildStage.UNIVERSE);
    }

    public ReporterClosable printParsing() {
        return print(TimerCollection.Registry.PARSE, BuildStage.PARSING);
    }

    public ReporterClosable printInlining() {
        return print(TimerCollection.Registry.INLINE, BuildStage.INLINING);
    }

    public ReporterClosable printCompiling() {
        return print(TimerCollection.Registry.COMPILE, BuildStage.COMPILING);
    }

    public ReporterClosable printLayouting() {
        return print(TimerCollection.Registry.LAYOUT, BuildStage.LAYING_OUT);
    }

    // TODO: merge printCreationStart and printCreationEnd at some point (GR-35721).
    public void printCreationStart() {
        stagePrinter.start(BuildStage.CREATING);
    }

    public void setDebugInfoTimer(Timer timer) {
        this.debugInfoTimer = timer;
    }

    public void printCreationEnd(int imageFileSize, int heapObjectCount, long imageHeapSize, int codeAreaSize, int numCompilations, int debugInfoSize, int imageDiskFileSize) {
        recordJsonMetric(ImageDetailKey.IMAGE_HEAP_OBJECT_COUNT, heapObjectCount);
        Timer imageTimer = getTimer(TimerCollection.Registry.IMAGE);
        Timer writeTimer = getTimer(TimerCollection.Registry.WRITE);
        Timer archiveTimer = getTimer(TimerCollection.Registry.ARCHIVE_LAYER);
        stagePrinter.end(imageTimer.getTotalTime() + writeTimer.getTotalTime() + archiveTimer.getTotalTime());
        creationStageEndCompleted = true;
        String format = "%9s (%5.2f%%) for ";
        l().a(format, ByteFormattingUtil.bytesToHuman(codeAreaSize), Utils.toPercentage(codeAreaSize, imageFileSize))
                        .doclink("code area", "#glossary-code-area").a(":%,10d compilation units", numCompilations).println();
        int numResources = Resources.currentLayer().count();
        recordJsonMetric(ImageDetailKey.IMAGE_HEAP_RESOURCE_COUNT, numResources);
        l().a(format, ByteFormattingUtil.bytesToHuman(imageHeapSize), Utils.toPercentage(imageHeapSize, imageFileSize))
                        .doclink("image heap", "#glossary-image-heap").a(":%,9d objects and %,d resources", heapObjectCount, numResources).println();
        long otherBytes = imageFileSize - codeAreaSize - imageHeapSize;
        if (debugInfoSize > 0) {
            recordJsonMetric(ImageDetailKey.DEBUG_INFO_SIZE, debugInfoSize); // Optional metric
            DirectPrinter l = l().a(format, ByteFormattingUtil.bytesToHuman(debugInfoSize), Utils.toPercentage(debugInfoSize, imageFileSize))

                            .doclink("debug info", "#glossary-debug-info");
            if (debugInfoTimer != null) {
                l.a(" generated in %.1fs", Utils.millisToSeconds(debugInfoTimer.getTotalTime()));
            }
            l.println();
            if (!(ImageSingletons.contains(NativeImageDebugInfoStripFeature.class) && ImageSingletons.lookup(NativeImageDebugInfoStripFeature.class).hasStrippedSuccessfully())) {
                // Only subtract if debug info is embedded in file (not stripped).
                otherBytes -= debugInfoSize;
            }
        }
        assert otherBytes >= 0 : "Other bytes should never be negative: " + otherBytes;
        recordJsonMetric(ImageDetailKey.IMAGE_HEAP_SIZE, imageHeapSize);
        recordJsonMetric(ImageDetailKey.TOTAL_SIZE, imageFileSize);
        recordJsonMetric(ImageDetailKey.CODE_AREA_SIZE, codeAreaSize);
        recordJsonMetric(ImageDetailKey.NUM_COMP_UNITS, numCompilations);
        l().a(format, ByteFormattingUtil.bytesToHuman(otherBytes), Utils.toPercentage(otherBytes, imageFileSize))
                        .doclink("other data", "#glossary-other-data").println();
        l().a("%9s in total image size", ByteFormattingUtil.bytesToHuman(imageFileSize));
        if (imageDiskFileSize >= 0) {
            l().a(", %s in total file size", ByteFormattingUtil.bytesToHuman(imageDiskFileSize));
        }
        l().println();
        printBreakdowns();
        ImageSingletons.lookup(ProgressReporterFeature.class).afterBreakdowns();
        printRecommendations();
    }

    public void ensureCreationStageEndCompleted() {
        if (!creationStageEndCompleted) {
            println();
        }
    }

    private void printBreakdowns() {
        if (!SubstrateOptions.BuildOutputBreakdowns.getValue()) {
            return;
        }
        l().printLineSeparator();
        Map<String, Long> codeBreakdown = CodeBreakdownProvider.getAndClear();
        Iterator<Entry<String, Long>> packagesBySize = codeBreakdown.entrySet().stream()
                        .sorted(Entry.comparingByValue(Comparator.reverseOrder())).iterator();

        HeapBreakdownProvider heapBreakdown = HeapBreakdownProvider.singleton();
        Iterator<HeapBreakdownProvider.HeapBreakdownEntry> typesBySizeInHeap = heapBreakdown.getSortedBreakdownEntries().iterator();

        final TwoColumnPrinter p = new TwoColumnPrinter();
        p.l().yellowBold().a(String.format("Top %d ", MAX_NUM_BREAKDOWN)).doclink("origins", "#glossary-code-area-origins").a(" of code area:")
                        .jumpToMiddle()
                        .a(String.format("Top %d object types in image heap:", MAX_NUM_BREAKDOWN)).reset().flushln();

        long printedCodeBytes = 0;
        long printedHeapBytes = 0;
        long printedCodeItems = 0;
        long printedHeapItems = 0;
        for (int i = 0; i < MAX_NUM_BREAKDOWN; i++) {
            String codeSizePart = "";
            if (packagesBySize.hasNext()) {
                Entry<String, Long> e = packagesBySize.next();
                String className = Utils.truncateClassOrPackageName(e.getKey());
                codeSizePart = String.format("%9s %s", ByteFormattingUtil.bytesToHuman(e.getValue()), className);
                printedCodeBytes += e.getValue();
                printedCodeItems++;
            }

            String heapSizePart = "";
            if (typesBySizeInHeap.hasNext()) {
                HeapBreakdownProvider.HeapBreakdownEntry e = typesBySizeInHeap.next();
                String className = e.label.renderToString(linkStrategy);
                // Do not truncate special breakdown items, they can contain links.
                if (e.label instanceof HeapBreakdownProvider.SimpleHeapObjectKindName) {
                    className = Utils.truncateClassOrPackageName(className);
                }
                long byteSize = e.byteSize;
                heapSizePart = String.format("%9s %s", ByteFormattingUtil.bytesToHuman(byteSize), className);
                printedHeapBytes += byteSize;
                printedHeapItems++;
            }
            if (codeSizePart.isEmpty() && heapSizePart.isEmpty()) {
                break;
            }
            p.l().a(codeSizePart).jumpToMiddle().a(heapSizePart).flushln();
        }

        int numCodeItems = codeBreakdown.size();
        int numHeapItems = heapBreakdown.getSortedBreakdownEntries().size();
        long totalCodeBytes = codeBreakdown.values().stream().mapToLong(Long::longValue).sum();

        p.l().a(String.format("%9s for %s more packages", ByteFormattingUtil.bytesToHuman(totalCodeBytes - printedCodeBytes), numCodeItems - printedCodeItems))
                        .jumpToMiddle()
                        .a(String.format("%9s for %s more object types", ByteFormattingUtil.bytesToHuman(heapBreakdown.getTotalHeapSize() - printedHeapBytes), numHeapItems - printedHeapItems))
                        .flushln();
    }

    private void printRecommendations() {
        if (!SubstrateOptions.BuildOutputRecommendations.getValue()) {
            return;
        }
        List<UserRecommendation> recommendations = ImageSingletons.lookup(ProgressReporterFeature.class).getRecommendations();
        List<UserRecommendation> topApplicableRecommendations = recommendations.stream().filter(r -> r.isApplicable().get()).limit(5).toList();
        if (topApplicableRecommendations.isEmpty()) {
            return;
        }
        l().printLineSeparator();
        l().yellowBold().a("Recommendations:").reset().println();
        for (UserRecommendation r : topApplicableRecommendations) {
            String alignment = Utils.stringFilledWith(Math.max(1, 5 - r.id().length()), " ");
            l().a(" ").doclink(r.id(), "#recommendation-" + r.id().toLowerCase(Locale.ROOT)).a(":").a(alignment).a(r.description()).println();
        }
    }

    public void printEpilog(Optional<String> optionalImageName, Optional<NativeImageGenerator> optionalGenerator, ImageClassLoader classLoader, NativeImageGeneratorRunner.BuildOutcome buildOutcome,
                    Optional<Throwable> optionalUnhandledThrowable, OptionValues parsedHostedOptions) {
        executor.shutdown();

        boolean singletonSupportAvailable = ImageSingletonsSupport.isInstalled() && ImageSingletons.contains(BuildArtifacts.class) && ImageSingletons.contains(TimerCollection.class);
        if (optionalUnhandledThrowable.isPresent()) {
            Path errorReportPath = NativeImageOptions.getErrorFilePath(parsedHostedOptions);
            Optional<FeatureHandler> featureHandler = optionalGenerator.map(nativeImageGenerator -> nativeImageGenerator.featureHandler);
            ReportUtils.report("GraalVM Native Image Error Report", errorReportPath,
                            p -> VMErrorReporter.generateErrorReport(p, buildOutputLog, classLoader, featureHandler, optionalUnhandledThrowable.get()),
                            false);
            if (!singletonSupportAvailable) {
                printErrorMessage(optionalUnhandledThrowable, parsedHostedOptions);
                return;
            }
            BuildArtifacts.singleton().add(ArtifactType.BUILD_INFO, errorReportPath);
        }

        if (!singletonSupportAvailable || optionalImageName.isEmpty() || optionalGenerator.isEmpty()) {
            printErrorMessage(optionalUnhandledThrowable, parsedHostedOptions);
            return;
        }
        String imageName = optionalImageName.get();
        NativeImageGenerator generator = optionalGenerator.get();

        l().printLineSeparator();
        printResourceStatistics();

        double totalSeconds = Utils.millisToSeconds(getTimer(TimerCollection.Registry.TOTAL).getTotalTime());
        recordJsonMetric(ResourceUsageKey.TOTAL_SECS, totalSeconds);

        createAdditionalArtifacts(imageName, generator, buildOutcome.successful(), parsedHostedOptions);
        printArtifacts(BuildArtifacts.singleton());

        l().printHeadlineSeparator();

        String timeStats;
        if (totalSeconds < 60) {
            timeStats = String.format("%.1fs", totalSeconds);
        } else {
            timeStats = String.format("%dm %ds", (int) totalSeconds / 60, (int) totalSeconds % 60);
        }
        l().a(outcomePrefix(buildOutcome)).a(" generating '").bold().a(imageName).reset().a("' ")
                        .a(buildOutcome.successful() ? "in" : "after").a(" ").a(timeStats).a(".").println();

        printErrorMessage(optionalUnhandledThrowable, parsedHostedOptions);
    }

    private static String outcomePrefix(NativeImageGeneratorRunner.BuildOutcome buildOutcome) {
        return switch (buildOutcome) {
            case SUCCESSFUL -> "Finished";
            case FAILED -> "Failed";
            case STOPPED -> "Stopped";
        };
    }

    private void printErrorMessage(Optional<Throwable> optionalUnhandledThrowable, OptionValues parsedHostedOptions) {
        if (optionalUnhandledThrowable.isEmpty()) {
            return;
        }
        Throwable unhandledThrowable = optionalUnhandledThrowable.get();
        l().println();
        l().redBold().a("The build process encountered an unexpected error:").reset().println();
        if (NativeImageOptions.ReportExceptionStackTraces.getValue(parsedHostedOptions)) {
            l().dim().println();
            unhandledThrowable.printStackTrace(builderIO.getOut());
            l().reset().println();
        } else {
            l().println();
            l().dim().a("> %s", unhandledThrowable).reset().println();
            l().println();
            l().a("Please inspect the generated error report at:").println();
            l().link(NativeImageOptions.getErrorFilePath(parsedHostedOptions)).println();
            l().println();
            l().a("If you are unable to resolve this problem, please file an issue with the error report at:").println();
            var supportUrl = VM.getSupportUrl();
            l().link(supportUrl, supportUrl).println();
        }
    }

    private void createAdditionalArtifacts(String imageName, NativeImageGenerator generator, boolean wasSuccessfulBuild, OptionValues parsedHostedOptions) {
        BuildArtifacts artifacts = BuildArtifacts.singleton();
        if (wasSuccessfulBuild) {
            createAdditionalArtifactsOnSuccess(artifacts, generator, parsedHostedOptions);
        }
        BuildArtifactsExporter.run(imageName, artifacts);
    }

    private void createAdditionalArtifactsOnSuccess(BuildArtifacts artifacts, NativeImageGenerator generator, OptionValues parsedHostedOptions) {
        Optional<Path> buildOutputJSONFile = SubstrateOptions.BuildOutputJSONFile.getValue(parsedHostedOptions).lastValue();
        buildOutputJSONFile.ifPresent(path -> artifacts.add(ArtifactType.BUILD_INFO, reportBuildOutput(path)));
        if (generator.getBigbang() != null && ImageBuildStatistics.Options.CollectImageBuildStatistics.getValue(parsedHostedOptions)) {
            artifacts.add(ArtifactType.BUILD_INFO, reportImageBuildStatistics());
        }
        ImageSingletons.lookup(ProgressReporterFeature.class).createAdditionalArtifactsOnSuccess(artifacts);
    }

    private void printArtifacts(BuildArtifacts artifacts) {
        if (artifacts.isEmpty()) {
            return;
        }
        l().printLineSeparator();
        l().yellowBold().doclink("Build artifacts", "#glossary-build-artifacts").a(":").reset().println();
        // Use TreeMap to sort paths alphabetically.
        Map<Path, List<String>> pathToTypes = new TreeMap<>();
        artifacts.forEach((artifactType, paths) -> {
            for (Path path : paths) {
                pathToTypes.computeIfAbsent(path, p -> new ArrayList<>()).add(artifactType.name().toLowerCase(Locale.ROOT));
            }
        });
        pathToTypes.forEach((path, typeNames) -> l().a(" ").link(path).dim().a(" (").a(String.join(", ", typeNames)).a(")").reset().println());
    }

    private Path reportBuildOutput(Path jsonOutputFile) {
        String description = "image statistics in json";
        return ReportUtils.report(description, jsonOutputFile.toAbsolutePath(), out -> {
            try {
                jsonHelper.print(new JsonWriter(out));
            } catch (IOException e) {
                throw VMError.shouldNotReachHere("Failed to create " + jsonOutputFile, e);
            }
        }, false);
    }

    private static Path reportImageBuildStatistics() {
        Consumer<PrintWriter> statsReporter = ImageSingletons.lookup(ImageBuildStatistics.class).getReporter();
        Path reportsPath = NativeImageGenerator.generatedFiles(HostedOptionValues.singleton()).resolve("reports");
        return ReportUtils.report("image build statistics", reportsPath.resolve("image_build_statistics.json"), statsReporter, false);
    }

    private void printResourceStatistics() {
        double totalProcessTimeSeconds = Utils.millisToSeconds(ManagementFactory.getRuntimeMXBean().getUptime());
        GCStats gcStats = GCStats.getCurrent();
        double gcSeconds = Utils.millisToSeconds(gcStats.totalTimeMillis);
        recordJsonMetric(ResourceUsageKey.GC_COUNT, gcStats.totalCount);
        recordJsonMetric(ResourceUsageKey.GC_SECS, gcSeconds);
        CenteredTextPrinter p = centered();
        p.a("%.1fs (%.1f%% of total time) in %d ", gcSeconds, gcSeconds / totalProcessTimeSeconds * 100, gcStats.totalCount)
                        .doclink("GCs", "#glossary-garbage-collections");
        long peakRSS = ProgressReporterCHelper.getPeakRSS();
        if (peakRSS >= 0) {
            p.a(" | ").doclink("Peak RSS", "#glossary-peak-rss").a(": ").a(ByteFormattingUtil.bytesToHuman(peakRSS));
        }
        recordJsonMetric(ResourceUsageKey.PEAK_RSS, (peakRSS >= 0 ? peakRSS : UNAVAILABLE_METRIC));
        long processCPUTime = getOperatingSystemMXBean().getProcessCpuTime();
        double cpuLoad = UNAVAILABLE_METRIC;
        if (processCPUTime > 0) {
            cpuLoad = Utils.nanosToSeconds(processCPUTime) / totalProcessTimeSeconds;
            p.a(" | ").doclink("CPU load", "#glossary-cpu-load").a(": ").a("%.2f", cpuLoad);
        }
        recordJsonMetric(ResourceUsageKey.CPU_LOAD, cpuLoad);
        p.flushln();
    }

    private void checkForExcessiveGarbageCollection() {
        long nowNanos = System.nanoTime();
        long timeDeltaMillis = TimeUtils.millisSinceNanos(nowNanos, lastGCCheckTimeNanos);
        lastGCCheckTimeNanos = nowNanos;
        GCStats currentGCStats = GCStats.getCurrent();
        long gcTimeDeltaMillis = currentGCStats.totalTimeMillis - lastGCStats.totalTimeMillis;
        double ratio = gcTimeDeltaMillis / (double) timeDeltaMillis;
        if (gcTimeDeltaMillis > EXCESSIVE_GC_MIN_THRESHOLD_MILLIS && ratio > EXCESSIVE_GC_RATIO) {
            l().redBold().a("GC warning").reset()
                            .a(": %.1fs spent in %d GCs during the last stage, taking up %.2f%% of the time.",
                                            Utils.millisToSeconds(gcTimeDeltaMillis), currentGCStats.totalCount - lastGCStats.totalCount, ratio * 100)
                            .println();
            l().a("            Please ensure more than %s of memory is available for Native Image", ByteFormattingUtil.bytesToHuman(ProgressReporterCHelper.getPeakRSS())).println();
            l().a("            to reduce GC overhead and improve image build time.").println();
        }
        lastGCStats = currentGCStats;
    }

    public Object getJsonMetricValue(JsonMetric metric) {
        if (jsonHelper == null) {
            return null;
        }
        return metric.getValue(jsonHelper);
    }

    public boolean containsJsonMetricValue(JsonMetric metric) {
        if (jsonHelper == null) {
            return false;
        }
        return metric.containsValue(jsonHelper);
    }

    public void recordJsonMetric(JsonMetric metric, Object value) {
        if (jsonHelper != null) {
            metric.record(jsonHelper, value);
        }
    }

    /*
     * HELPERS
     */

    private static Timer getTimer(TimerCollection.Registry type) {
        return TimerCollection.singleton().get(type);
    }

    private static OperatingSystemMXBean getOperatingSystemMXBean() {
        return (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    private static final class Utils {
        private static final double MILLIS_TO_SECONDS = 1000d;
        private static final double NANOS_TO_SECONDS = 1000d * 1000d * 1000d;

        private static double millisToSeconds(double millis) {
            return millis / MILLIS_TO_SECONDS;
        }

        private static double nanosToSeconds(double nanos) {
            return nanos / NANOS_TO_SECONDS;
        }

        private static String getUsedMemory() {
            return ByteFormattingUtil.bytesToHumanGB(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        }

        private static String stringFilledWith(int size, String fill) {
            return new String(new char[size]).replace("\0", fill);
        }

        private static double toPercentage(long part, long total) {
            return part / (double) total * 100;
        }

        private static String truncateClassOrPackageName(String classOrPackageName) {
            int classNameLength = classOrPackageName.length();
            int maxLength = CHARACTERS_PER_LINE / 2 - 10;
            if (classNameLength <= maxLength) {
                return classOrPackageName;
            }
            StringBuilder sb = new StringBuilder();
            int currentDot = -1;
            while (true) {
                int nextDot = classOrPackageName.indexOf('.', currentDot + 1);
                if (nextDot < 0) { // Not more dots, handle the rest and return.
                    String rest = classOrPackageName.substring(currentDot + 1);
                    int sbLength = sb.length();
                    int restLength = rest.length();
                    if (sbLength + restLength <= maxLength) {
                        sb.append(rest);
                    } else {
                        int remainingSpaceDivBy2 = (maxLength - sbLength) / 2;
                        sb.append(rest, 0, remainingSpaceDivBy2 - 1).append("~").append(rest, restLength - remainingSpaceDivBy2, restLength);
                    }
                    break;
                }
                sb.append(classOrPackageName.charAt(currentDot + 1)).append('.');
                if (sb.length() + (classNameLength - nextDot) <= maxLength) {
                    // Rest fits maxLength, append and return.
                    sb.append(classOrPackageName.substring(nextDot + 1));
                    break;
                }
                currentDot = nextDot;
            }
            return sb.toString();
        }
    }

    private record GCStats(long totalCount, long totalTimeMillis) {
        private static GCStats getCurrent() {
            long totalCount = 0;
            long totalTime = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                long collectionCount = bean.getCollectionCount();
                if (collectionCount > 0) {
                    totalCount += collectionCount;
                }
                long collectionTime = bean.getCollectionTime();
                if (collectionTime > 0) {
                    totalTime += collectionTime;
                }
            }
            return new GCStats(totalCount, totalTime);
        }
    }

    public abstract static class ReporterClosable implements AutoCloseable {
        @Override
        public void close() {
            closeAction();
        }

        abstract void closeAction();
    }

    /*
     * CORE PRINTING
     */

    private void print(char text) {
        builderIO.getOut().print(text);
        buildOutputLog.append(text);
    }

    private void print(String text) {
        builderIO.getOut().print(text);
        buildOutputLog.append(text);
    }

    private void println() {
        builderIO.getOut().println();
        buildOutputLog.append(System.lineSeparator());
    }

    /*
     * PRINTERS
     */

    public abstract class AbstractPrinter<T extends AbstractPrinter<T>> {
        abstract T getThis();

        public abstract T a(String text);

        public final T a(String text, Object... args) {
            return a(String.format(text, args));
        }

        public final T a(int i) {
            return a(String.valueOf(i));
        }

        public final T a(long i) {
            return a(String.valueOf(i));
        }

        public final T bold() {
            colorStrategy.bold(this);
            return getThis();
        }

        public final T blue() {
            colorStrategy.blue(this);
            return getThis();
        }

        public final T blueBold() {
            colorStrategy.blueBold(this);
            return getThis();
        }

        public final T red() {
            colorStrategy.red(this);
            return getThis();
        }

        public final T redBold() {
            colorStrategy.redBold(this);
            return getThis();
        }

        public final T yellowBold() {
            colorStrategy.yellowBold(this);
            return getThis();
        }

        public final T dim() {
            colorStrategy.dim(this);
            return getThis();
        }

        public final T reset() {
            colorStrategy.reset(this);
            return getThis();
        }

        public final T link(String text, String url) {
            linkStrategy.link(this, text, url);
            return getThis();
        }

        public final T link(Path path) {
            return link(path, false);
        }

        public final T link(Path path, boolean filenameOnly) {
            linkStrategy.link(this, path, filenameOnly);
            return getThis();
        }

        public final T doclink(String text, String htmlAnchor) {
            linkStrategy.doclink(this, text, htmlAnchor);
            return getThis();
        }
    }

    /**
     * Start printing a new line.
     */
    public DirectPrinter l() {
        return linePrinter;
    }

    public CenteredTextPrinter centered() {
        return new CenteredTextPrinter();
    }

    public final class DirectPrinter extends AbstractPrinter<DirectPrinter> {
        @Override
        DirectPrinter getThis() {
            return this;
        }

        @Override
        public DirectPrinter a(String text) {
            print(text);
            return this;
        }

        public void println() {
            ProgressReporter.this.println();
        }

        void printHeadlineSeparator() {
            dim().a(HEADLINE_SEPARATOR).reset().println();
        }

        public void printLineSeparator() {
            dim().a(LINE_SEPARATOR).reset().println();
        }
    }

    public abstract class LinePrinter<T extends LinePrinter<T>> extends AbstractPrinter<T> {
        protected final List<String> lineParts = new ArrayList<>();

        @Override
        public T a(String value) {
            lineParts.add(value);
            return getThis();
        }

        T l() {
            assert lineParts.isEmpty();
            return getThis();
        }

        final int getCurrentTextLength() {
            int textLength = 0;
            for (String text : lineParts) {
                if (!text.startsWith(ANSI.ESCAPE)) { // Ignore ANSI escape sequences.
                    textLength += text.length();
                }
            }
            return textLength;
        }

        final void printLineParts() {
            /*
             * This uses a copy of the array to avoid any ConcurrentModificationExceptions in case
             * progress is still being printed.
             */
            for (String part : lineParts.toArray(new String[0])) {
                print(part);
            }
        }

        void flushln() {
            printLineParts();
            lineParts.clear();
            println();
        }
    }

    public void reportStageProgress() {
        stagePrinter.reportProgress();
    }

    public void beforeNextStdioWrite() {
        stagePrinter.beforeNextStdioWrite();
    }

    abstract class StagePrinter<T extends StagePrinter<T>> extends LinePrinter<T> {
        private static final int PROGRESS_BAR_START = 30;
        private BuildStage activeBuildStage = null;

        private ScheduledFuture<?> periodicPrintingTask;
        private boolean isCancelled;

        void start(BuildStage stage) {
            assert activeBuildStage == null;
            activeBuildStage = stage;
            appendStageStart();
            if (activeBuildStage.hasProgressBar) {
                a(progressBarStartPadding()).dim().a("[");
            }
            if (activeBuildStage.hasPeriodicProgress) {
                startPeriodicProgress();
            }
        }

        private void startPeriodicProgress() {
            isCancelled = false;
            periodicPrintingTask = executor.scheduleAtFixedRate(new Runnable() {
                int countdown;
                int numPrints;

                @Override
                public void run() {
                    if (isCancelled) {
                        return;
                    }
                    if (--countdown < 0) {
                        reportProgress();
                        countdown = ++numPrints > 2 ? numPrints * 2 : numPrints;
                    }
                }
            }, 0, 1, TimeUnit.SECONDS);
        }

        private void appendStageStart() {
            blue().a(String.format("[%s/%s] ", 1 + activeBuildStage.ordinal(), BuildStage.NUM_STAGES)).reset()
                            .blueBold().doclink(activeBuildStage.message, "#stage-" + activeBuildStage.name().toLowerCase(Locale.ROOT)).a("...").reset();
        }

        final String progressBarStartPadding() {
            return Utils.stringFilledWith(PROGRESS_BAR_START - getCurrentTextLength(), " ");
        }

        void reportProgress() {
            a("*");
        }

        final void end(Timer timer) {
            end(timer.getTotalTime());
        }

        void end(double totalTime) {
            if (activeBuildStage.hasPeriodicProgress) {
                isCancelled = true;
                periodicPrintingTask.cancel(false);
            }
            if (activeBuildStage.hasProgressBar) {
                a("]").reset();
            }

            String suffix = String.format("(%.1fs @ %s)", Utils.millisToSeconds(totalTime), Utils.getUsedMemory());
            int textLength = getCurrentTextLength();
            // TODO: `assert textLength > 0;` should be used here but tests do not start stages
            // properly (GR-35721)
            String padding = Utils.stringFilledWith(Math.max(0, CHARACTERS_PER_LINE - textLength - suffix.length()), " ");
            a(padding).dim().a(suffix).reset().flushln();

            activeBuildStage = null;

            boolean optionsAvailable = ImageSingletonsSupport.isInstalled() && ImageSingletons.contains(HostedOptionValues.class);
            if (optionsAvailable && SubstrateOptions.BuildOutputGCWarnings.getValue()) {
                checkForExcessiveGarbageCollection();
            }
        }

        abstract void beforeNextStdioWrite();
    }

    /**
     * A {@link StagePrinter} that prints full lines to stdout at the end of each stage. This
     * printer should be used when interactive progress bars are not desired, e.g., when logging is
     * enabled or in dumb terminals.
     */
    final class LinewiseStagePrinter extends StagePrinter<LinewiseStagePrinter> {
        @Override
        LinewiseStagePrinter getThis() {
            return this;
        }

        @Override
        void beforeNextStdioWrite() {
            throw VMError.shouldNotReachHere("LinewiseStagePrinter not allowed to set builderIO.listenForNextStdioWrite");
        }
    }

    /**
     * A {@link StagePrinter} that produces interactive progress bars on the command line. It should
     * only be used in rich terminals with cursor and ANSI support. It is also the only component
     * that interacts with {@link NativeImageSystemIOWrappers#progressReporter}.
     */
    final class CharacterwiseStagePrinter extends StagePrinter<CharacterwiseStagePrinter> {
        @Override
        CharacterwiseStagePrinter getThis() {
            return this;
        }

        /**
         * Print directly and only append to keep track of the current line in case it needs to be
         * re-printed.
         */
        @Override
        public CharacterwiseStagePrinter a(String value) {
            print(value);
            return super.a(value);
        }

        @Override
        void start(BuildStage stage) {
            super.start(stage);
            builderIO.progressReporter = ProgressReporter.this;
        }

        @Override
        void reportProgress() {
            reprintLineIfNecessary();
            // Ensure builderIO is not listening for the next stdio write when printing progress
            // characters to stdout.
            builderIO.progressReporter = null;
            super.reportProgress();
            // Now that progress has been printed and has not been stopped, make sure builderIO
            // listens for the next stdio write again.
            builderIO.progressReporter = ProgressReporter.this;
        }

        @Override
        void end(double totalTime) {
            reprintLineIfNecessary();
            builderIO.progressReporter = null;
            super.end(totalTime);
        }

        void reprintLineIfNecessary() {
            if (builderIO.progressReporter == null) {
                printLineParts();
            }
        }

        @Override
        void flushln() {
            // No need to print lineParts because they are only needed for re-printing.
            lineParts.clear();
            println();
        }

        @Override
        void beforeNextStdioWrite() {
            colorStrategy.reset(); // Ensure color is reset.
            // Clear the current line.
            print('\r');
            int textLength = getCurrentTextLength();
            assert textLength > 0 : "linePrinter expected to hold current line content";
            for (int i = 0; i <= textLength; i++) {
                print(' ');
            }
            print('\r');
        }
    }

    final class TwoColumnPrinter extends LinePrinter<TwoColumnPrinter> {
        @Override
        TwoColumnPrinter getThis() {
            return this;
        }

        @Override
        public TwoColumnPrinter a(String value) {
            super.a(value);
            return this;
        }

        TwoColumnPrinter jumpToMiddle() {
            int remaining = (CHARACTERS_PER_LINE / 2) - getCurrentTextLength();
            assert remaining >= 0 : "Column text too wide";
            a(Utils.stringFilledWith(remaining, " "));
            assert getCurrentTextLength() == CHARACTERS_PER_LINE / 2;
            return this;
        }
    }

    public final class CenteredTextPrinter extends LinePrinter<CenteredTextPrinter> {
        @Override
        CenteredTextPrinter getThis() {
            return this;
        }

        @Override
        public void flushln() {
            String padding = Utils.stringFilledWith((Math.max(0, CHARACTERS_PER_LINE - getCurrentTextLength())) / 2, " ");
            print(padding);
            super.flushln();
        }
    }

    @SuppressWarnings("unused")
    private interface ColorStrategy {
        default void bold(AbstractPrinter<?> printer) {
        }

        default void blue(AbstractPrinter<?> printer) {
        }

        default void blueBold(AbstractPrinter<?> printer) {
        }

        default void magentaBold(AbstractPrinter<?> printer) {
        }

        default void red(AbstractPrinter<?> printer) {
        }

        default void redBold(AbstractPrinter<?> printer) {
        }

        default void yellowBold(AbstractPrinter<?> printer) {
        }

        default void dim(AbstractPrinter<?> printer) {
        }

        default void reset(AbstractPrinter<?> printer) {
        }

        default void reset() {
        }
    }

    static final class ColorlessStrategy implements ColorStrategy {
    }

    final class ColorfulStrategy implements ColorStrategy {
        @Override
        public void bold(AbstractPrinter<?> printer) {
            printer.a(ANSI.BOLD);
        }

        @Override
        public void blue(AbstractPrinter<?> printer) {
            printer.a(ANSI.BLUE);
        }

        @Override
        public void blueBold(AbstractPrinter<?> printer) {
            printer.a(ANSI.BLUE_BOLD);
        }

        @Override
        public void magentaBold(AbstractPrinter<?> printer) {
            printer.a(ANSI.MAGENTA_BOLD);
        }

        @Override
        public void red(AbstractPrinter<?> printer) {
            printer.a(ANSI.RED);
        }

        @Override
        public void redBold(AbstractPrinter<?> printer) {
            printer.a(ANSI.RED_BOLD);
        }

        @Override
        public void yellowBold(AbstractPrinter<?> printer) {
            printer.a(ANSI.YELLOW_BOLD);
        }

        @Override
        public void dim(AbstractPrinter<?> printer) {
            printer.a(ANSI.DIM);
        }

        @Override
        public void reset(AbstractPrinter<?> printer) {
            printer.a(ANSI.RESET);
        }

        @Override
        public void reset() {
            print(ANSI.RESET);
        }
    }

    public interface LinkStrategy {
        default boolean isTerminalSupported() {
            return false;
        }

        void link(AbstractPrinter<?> printer, String text, String url);

        String asDocLink(String text, String htmlAnchor);

        default void link(AbstractPrinter<?> printer, Path path, boolean filenameOnly) {
            Path normalized = path.normalize();
            String name;
            if (filenameOnly) {
                Path filename = normalized.getFileName();
                if (filename != null) {
                    name = filename.toString();
                } else {
                    throw VMError.shouldNotReachHere("filename should never be null, illegal path: " + path);
                }
            } else {
                name = normalized.toString();
            }
            link(printer, name, normalized.toUri().toString());
        }

        default void doclink(AbstractPrinter<?> printer, String text, String htmlAnchor) {
            link(printer, text, DOCS_BASE_URL + htmlAnchor);
        }
    }

    static final class LinklessStrategy implements LinkStrategy {
        @Override
        public void link(AbstractPrinter<?> printer, String text, String url) {
            printer.a(text);
        }

        @Override
        public String asDocLink(String text, String htmlAnchor) {
            return text;
        }
    }

    static final class LinkyStrategy implements LinkStrategy {
        @Override
        public boolean isTerminalSupported() {
            return true;
        }

        /**
         * Adding link part individually for {@link LinePrinter#getCurrentTextLength()}.
         */
        @Override
        public void link(AbstractPrinter<?> printer, String text, String url) {
            printer.a(ANSI.LINK_START + url).a(ANSI.LINK_TEXT).a(text).a(ANSI.LINK_END);
        }

        @Override
        public String asDocLink(String text, String htmlAnchor) {
            return String.format(ANSI.LINK_FORMAT, DOCS_BASE_URL + htmlAnchor, text);
        }
    }

    public static class ANSI {
        static final String ESCAPE = "\033";
        static final String RESET = ESCAPE + "[0m";
        static final String BOLD = ESCAPE + "[1m";
        static final String DIM = ESCAPE + "[2m";
        static final String STRIP_COLORS = "\033\\[[;\\d]*m";

        static final String LINK_START = ESCAPE + "]8;;";
        static final String LINK_TEXT = ESCAPE + "\\";
        static final String LINK_END = LINK_START + LINK_TEXT;
        static final String LINK_FORMAT = LINK_START + "%s" + LINK_TEXT + "%s" + LINK_END;
        static final String STRIP_LINKS = "\033]8;;https://\\S+\033\\\\([^\033]*)\033]8;;\033\\\\";

        static final String RED = ESCAPE + "[0;31m";
        static final String BLUE = ESCAPE + "[0;34m";

        static final String RED_BOLD = ESCAPE + "[1;31m";
        static final String YELLOW_BOLD = ESCAPE + "[1;33m";
        static final String BLUE_BOLD = ESCAPE + "[1;34m";
        static final String MAGENTA_BOLD = ESCAPE + "[1;35m";

        /* Strip all ANSI codes emitted by this class. */
        public static String strip(String string) {
            return string.replaceAll(STRIP_COLORS, "").replaceAll(STRIP_LINKS, "$1");
        }
    }
}
