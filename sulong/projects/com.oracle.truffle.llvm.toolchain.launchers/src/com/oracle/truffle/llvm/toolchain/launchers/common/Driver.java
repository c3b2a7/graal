/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.toolchain.launchers.common;

import org.graalvm.home.HomeFinder;
import org.graalvm.home.Version;

import com.oracle.truffle.llvm.toolchain.launchers.AbstractToolchainWrapper;
import com.oracle.truffle.llvm.toolchain.launchers.AbstractToolchainWrapper.ToolchainWrapperConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Driver {

    protected final String exe;
    protected final boolean isBundledTool;

    public Driver(String exe, boolean inLLVMDir) {
        this.exe = inLLVMDir ? getLLVMExecutable(exe).toString() : exe;
        this.isBundledTool = inLLVMDir;
    }

    public Driver(String exe) {
        this(exe, true);
    }

    public enum OS {

        WINDOWS,
        DARWIN,
        LINUX;

        private static OS findCurrent() {
            final String name = System.getProperty("os.name");
            if (name.equals("Linux")) {
                return LINUX;
            }
            if (name.equals("Mac OS X") || name.equals("Darwin")) {
                return DARWIN;
            }
            if (name.startsWith("Windows")) {
                return WINDOWS;
            }
            throw new IllegalArgumentException("unknown OS: " + name);
        }

        private static final class Lazy {
            private static final OS current = findCurrent();
        }

        public static OS getCurrent() {
            return Lazy.current;
        }
    }

    public enum Arch {

        X86_64,
        AARCH_64;

        private static Arch findCurrent() {
            final String name = System.getProperty("os.arch");
            if (name.equals("amd64")) {
                return X86_64;
            }
            if (name.equals("x86_64")) {
                return X86_64;
            }
            if (name.equals("aarch64")) {
                return AARCH_64;
            }
            throw new IllegalArgumentException("unknown architecture: " + name);
        }

        private static final class Lazy {
            private static final Arch current = findCurrent();
        }

        public static Arch getCurrent() {
            return Lazy.current;
        }
    }

    public static Path getLLVMBinDir() {
        // allow manually overriding custom LLVM path
        final String property = System.getProperty("llvm.bin.dir");
        if (property != null) {
            return Paths.get(property);
        }

        // look up LLVM path relative to the toolchain wrapper
        ToolchainWrapperConfig config = AbstractToolchainWrapper.getConfig();
        if (config != null) {
            return config.llvmPath().resolve("bin");
        }

        // TODO (GR-18389): Set only for old-style standalones
        Path toolchainHome = HomeFinder.getInstance().getLanguageHomes().get("llvm-toolchain");
        if (toolchainHome != null) {
            return toolchainHome.resolve("bin");
        }

        // TODO: Set only for old-style monolithic GraalVM builds
        return HomeFinder.getInstance().getHomeFolder().resolve("lib").resolve("llvm").resolve("bin");
    }

    public static Path getSulongHome() {
        ToolchainWrapperConfig config = AbstractToolchainWrapper.getConfig();
        if (config != null) {
            // the toolchain root is <sulong_home>/native
            return config.toolchainPath().getParent();
        }

        // TODO (GR-18389): Set only for old-style standalones
        final Path sulongHome = HomeFinder.getInstance().getLanguageHomes().get("llvm");
        if (sulongHome != null) {
            return sulongHome;
        }

        throw new IllegalStateException("Could not find the llvm home");
    }

    private static String getVersion() {
        Version version = Version.getCurrent();
        if (version.isSnapshot()) {
            return "Development Build";
        } else {
            return version.toString();
        }
    }

    public void runDriver(List<String> sulongArgs, List<String> userArgs, boolean verbose, boolean help, boolean earlyExit) {
        runDriverExit(sulongArgs, userArgs, verbose, help, earlyExit);
    }

    public final void runDriverExit(List<String> sulongArgs, List<String> userArgs, boolean verbose, boolean help, boolean earlyExit) {
        try {
            System.exit(runDriverReturn(sulongArgs, userArgs, verbose, help, earlyExit));
        } catch (IOException e) {
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Exception: " + e);
            System.exit(1);
        }
    }

    public final int runDriverReturn(List<String> sulongArgs, List<String> userArgs, boolean verbose, boolean help, boolean earlyExit) throws Exception {
        ArrayList<String> toolArgs = new ArrayList<>(sulongArgs.size() + userArgs.size());
        // add custom sulong flags
        toolArgs.addAll(sulongArgs);
        // add user flags
        toolArgs.addAll(userArgs);
        if (earlyExit) {
            printInfos(verbose, help, earlyExit, toolArgs);
            return 0;
        }
        ProcessBuilder pb = new ProcessBuilder(toolArgs);
        if (verbose) {
            // do no filter input/output streams if in verbose mode
            setupRedirectsDefault(pb);
        } else {
            setupRedirects(pb);
        }
        Process p = null;
        try {
            // start process
            p = pb.start();
            if (!verbose) {
                // process IO (if not in verbose mode)
                processIO(p.getInputStream(), p.getOutputStream(), p.getErrorStream());
            }
            // wait for process termination
            p.waitFor();
            printInfos(verbose, help, earlyExit, toolArgs);
            // set exit code
            int exitCode = p.exitValue();
            if (verbose) {
                System.err.println("exit code: " + exitCode);
            }
            return exitCode;
        } catch (IOException ioe) {
            // can only occur on ProcessBuilder#start, no destroying necessary
            if (isBundledTool) {
                printMissingToolMessage(Optional.ofNullable(new File(this.exe).toPath().getParent()).map(Path::getParent).map(Path::toString).orElse("<invalid>"));
            }
            throw ioe;
        } catch (Exception e) {
            if (p != null) {
                p.destroyForcibly();
            }
            throw e;
        }
    }

    /**
     * @param inputStream
     * @param outputStream
     * @param errorStream
     */
    protected void processIO(InputStream inputStream, OutputStream outputStream, InputStream errorStream) {
    }

    protected ProcessBuilder setupRedirects(ProcessBuilder pb) {
        return setupRedirectsDefault(pb);
    }

    private static ProcessBuilder setupRedirectsDefault(ProcessBuilder pb) {
        return pb.inheritIO();
    }

    public static void printMissingToolMessage(String llvmRoot) {
        System.err.println("Tool execution failed. Are you sure the toolchain is available at " + llvmRoot);
        System.err.println();
        System.err.println("More infos: https://www.graalvm.org/docs/reference-manual/languages/llvm/");
    }

    private void printInfos(boolean verbose, boolean help, boolean earlyExit, ArrayList<String> toolArgs) {
        if (help) {
            System.out.println("##################################################");
            System.out.println("This it the the GraalVM wrapper script for " + getTool());
            System.out.println();
            System.out.println("Its purpose is to make it easy to compile native projects to be used with");
            System.out.println("GraalVM's LLVM IR engine (bin/lli).");
            System.out.println("More infos: https://www.graalvm.org/docs/reference-manual/languages/llvm/");
            System.out.println("Wrapped executable: " + exe);
            System.out.println("GraalVM version: " + getVersion());
        }
        if (verbose) {
            System.err.println("GraalVM wrapper script for " + getTool());
            System.err.println("GraalVM version: " + getVersion());
            System.err.println("running: " + String.join(" ", toolArgs));
        }
        if (help) {
            if (!earlyExit) {
                System.out.println();
                System.out.println("The following is the output of the wrapped tool:");
            }
            System.out.println("##################################################");
        }
    }

    private Path getTool() {
        return Paths.get(exe).getFileName();
    }

    public static Path getLLVMExecutable(String tool) {
        return getLLVMBinDir().resolve(tool);
    }
}
