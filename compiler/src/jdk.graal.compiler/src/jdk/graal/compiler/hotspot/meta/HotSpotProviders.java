/*
 * Copyright (c) 2013, 2021, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot.meta;

import jdk.graal.compiler.api.replacements.SnippetReflectionProvider;
import jdk.graal.compiler.core.common.spi.ConstantFieldProvider;
import jdk.graal.compiler.core.common.spi.MetaAccessExtensionProvider;
import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.graal.compiler.hotspot.replaycomp.ReplayCompilationSupport;
import jdk.graal.compiler.hotspot.word.HotSpotWordTypes;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import jdk.graal.compiler.nodes.spi.IdentityHashCodeProvider;
import jdk.graal.compiler.nodes.spi.LoopsDataProvider;
import jdk.graal.compiler.nodes.spi.LoweringProvider;
import jdk.graal.compiler.nodes.spi.PlatformConfigurationProvider;
import jdk.graal.compiler.nodes.spi.Replacements;
import jdk.graal.compiler.nodes.spi.StampProvider;
import jdk.graal.compiler.phases.tiers.SuitesProvider;
import jdk.graal.compiler.phases.util.Providers;
import jdk.vm.ci.hotspot.HotSpotCodeCacheProvider;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.MetaAccessProvider;

/**
 * Extends {@link Providers} to include a number of extra capabilities used by the HotSpot parts of
 * the compiler.
 */
public class HotSpotProviders extends Providers {

    private SuitesProvider suites;
    private final HotSpotRegistersProvider registers;
    private final GraalHotSpotVMConfig config;

    /**
     * The interface for recording and replaying compilations or {@code null} if disabled.
     */
    private final ReplayCompilationSupport replayCompilationSupport;

    public HotSpotProviders(MetaAccessProvider metaAccess,
                    HotSpotCodeCacheProvider codeCache,
                    ConstantReflectionProvider constantReflection,
                    ConstantFieldProvider constantField,
                    HotSpotHostForeignCallsProvider foreignCalls,
                    LoweringProvider lowerer,
                    Replacements replacements,
                    SuitesProvider suites,
                    HotSpotRegistersProvider registers,
                    SnippetReflectionProvider snippetReflection,
                    HotSpotWordTypes wordTypes,
                    StampProvider stampProvider,
                    PlatformConfigurationProvider platformConfigurationProvider,
                    MetaAccessExtensionProvider metaAccessExtensionProvider,
                    LoopsDataProvider loopsDataProvider,
                    GraalHotSpotVMConfig config,
                    IdentityHashCodeProvider identityHashCodeProvider,
                    ReplayCompilationSupport replayCompilationSupport) {
        super(metaAccess, codeCache, constantReflection, constantField, foreignCalls, lowerer, replacements, stampProvider, platformConfigurationProvider, metaAccessExtensionProvider,
                        snippetReflection, wordTypes, loopsDataProvider, identityHashCodeProvider);
        this.suites = suites;
        this.registers = registers;
        this.config = config;
        this.replayCompilationSupport = replayCompilationSupport;
    }

    @Override
    public HotSpotCodeCacheProvider getCodeCache() {
        return (HotSpotCodeCacheProvider) super.getCodeCache();
    }

    @Override
    public HotSpotHostForeignCallsProvider getForeignCalls() {
        return (HotSpotHostForeignCallsProvider) super.getForeignCalls();
    }

    public SuitesProvider getSuites() {
        return suites;
    }

    public HotSpotRegistersProvider getRegisters() {
        return registers;
    }

    public Plugins getGraphBuilderPlugins() {
        return replacements.getGraphBuilderPlugins();
    }

    @Override
    public HotSpotWordTypes getWordTypes() {
        return (HotSpotWordTypes) super.getWordTypes();
    }

    public GraalHotSpotVMConfig getConfig() {
        return config;
    }

    @Override
    public HotSpotPlatformConfigurationProvider getPlatformConfigurationProvider() {
        return (HotSpotPlatformConfigurationProvider) platformConfigurationProvider;
    }

    @Override
    public HotSpotProviders copyWith(ConstantReflectionProvider substitution) {
        return new HotSpotProviders(getMetaAccess(), getCodeCache(), substitution, getConstantFieldProvider(), getForeignCalls(), getLowerer(), getReplacements(), getSuites(),
                        getRegisters(), getSnippetReflection(), getWordTypes(), getStampProvider(), getPlatformConfigurationProvider(), getMetaAccessExtensionProvider(),
                        getLoopsDataProvider(),
                        config, getIdentityHashCodeProvider(), getReplayCompilationSupport());
    }

    @Override
    public HotSpotProviders copyWith(ConstantFieldProvider substitution) {
        return new HotSpotProviders(getMetaAccess(), getCodeCache(), getConstantReflection(), substitution, getForeignCalls(), getLowerer(), getReplacements(),
                        getSuites(),
                        getRegisters(), getSnippetReflection(), getWordTypes(), getStampProvider(), getPlatformConfigurationProvider(), getMetaAccessExtensionProvider(),
                        getLoopsDataProvider(),
                        config, getIdentityHashCodeProvider(), getReplayCompilationSupport());
    }

    @Override
    public HotSpotProviders copyWith(Replacements substitution) {
        return new HotSpotProviders(getMetaAccess(), getCodeCache(), getConstantReflection(), getConstantFieldProvider(), getForeignCalls(), getLowerer(), substitution,
                        getSuites(), getRegisters(), getSnippetReflection(), getWordTypes(), getStampProvider(), getPlatformConfigurationProvider(),
                        getMetaAccessExtensionProvider(),
                        getLoopsDataProvider(), config, getIdentityHashCodeProvider(), getReplayCompilationSupport());
    }

    public HotSpotProviders copyWith() {
        return new HotSpotProviders(getMetaAccess(), getCodeCache(), getConstantReflection(), getConstantFieldProvider(), getForeignCalls(), getLowerer(), getReplacements(),
                        getSuites(), getRegisters(), getSnippetReflection(), getWordTypes(), getStampProvider(), getPlatformConfigurationProvider(), getMetaAccessExtensionProvider(),
                        getLoopsDataProvider(),
                        config, getIdentityHashCodeProvider(), getReplayCompilationSupport());
    }

    public void setSuites(HotSpotSuitesProvider suites) {
        this.suites = suites;
    }

    /**
     * Returns the interface for recording and replaying compilations or {@code null} if disabled.
     */
    public ReplayCompilationSupport getReplayCompilationSupport() {
        return replayCompilationSupport;
    }
}
