/*
 * Copyright (c) 2018, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.graal.aarch64;

import static com.oracle.svm.core.graal.aarch64.SubstrateAArch64RegisterConfig.fp;
import static com.oracle.svm.core.graal.code.SubstrateBackend.SubstrateMarkId.PROLOGUE_DECD_RSP;
import static com.oracle.svm.core.graal.code.SubstrateBackend.SubstrateMarkId.PROLOGUE_END;
import static com.oracle.svm.core.util.VMError.shouldNotReachHere;
import static com.oracle.svm.core.util.VMError.unsupportedFeature;
import static jdk.graal.compiler.core.common.GraalOptions.ZapStackOnMethodEntry;
import static jdk.graal.compiler.lir.LIRInstruction.OperandFlag.REG;
import static jdk.graal.compiler.lir.LIRValueUtil.asConstantValue;
import static jdk.graal.compiler.lir.LIRValueUtil.differentRegisters;
import static jdk.vm.ci.aarch64.AArch64.lr;
import static jdk.vm.ci.aarch64.AArch64.sp;
import static jdk.vm.ci.code.ValueUtil.asRegister;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.BiConsumer;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.FrameAccess;
import com.oracle.svm.core.ReservedRegisters;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.aarch64.SubstrateAArch64MacroAssembler;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.deopt.DeoptimizationRuntime;
import com.oracle.svm.core.deopt.DeoptimizationSupport;
import com.oracle.svm.core.deopt.Deoptimizer;
import com.oracle.svm.core.graal.code.AssignedLocation;
import com.oracle.svm.core.graal.code.PatchConsumerFactory;
import com.oracle.svm.core.graal.code.SharedCompilationResult;
import com.oracle.svm.core.graal.code.SubstrateBackend;
import com.oracle.svm.core.graal.code.SubstrateCallingConvention;
import com.oracle.svm.core.graal.code.SubstrateCallingConventionKind;
import com.oracle.svm.core.graal.code.SubstrateCallingConventionType;
import com.oracle.svm.core.graal.code.SubstrateCompiledCode;
import com.oracle.svm.core.graal.code.SubstrateDataBuilder;
import com.oracle.svm.core.graal.code.SubstrateDebugInfoBuilder;
import com.oracle.svm.core.graal.code.SubstrateLIRGenerator;
import com.oracle.svm.core.graal.code.SubstrateNodeLIRBuilder;
import com.oracle.svm.core.graal.lir.VerificationMarkerOp;
import com.oracle.svm.core.graal.meta.KnownOffsets;
import com.oracle.svm.core.graal.meta.SharedConstantReflectionProvider;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallLinkage;
import com.oracle.svm.core.graal.meta.SubstrateRegisterConfig;
import com.oracle.svm.core.graal.nodes.CGlobalDataLoadAddressNode;
import com.oracle.svm.core.graal.nodes.ComputedIndirectCallTargetNode;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.heap.SubstrateReferenceMapBuilder;
import com.oracle.svm.core.imagelayer.ImageLayerBuildingSupport;
import com.oracle.svm.core.interpreter.InterpreterSupport;
import com.oracle.svm.core.meta.CompressedNullConstant;
import com.oracle.svm.core.meta.SharedField;
import com.oracle.svm.core.meta.SharedMethod;
import com.oracle.svm.core.meta.SubstrateMethodPointerConstant;
import com.oracle.svm.core.meta.SubstrateObjectConstant;
import com.oracle.svm.core.nodes.SafepointCheckNode;
import com.oracle.svm.core.pltgot.PLTGOTConfiguration;
import com.oracle.svm.core.thread.VMThreads.StatusSupport;
import com.oracle.svm.core.util.VMError;

import jdk.graal.compiler.asm.BranchTargetOutOfBoundsException;
import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.asm.aarch64.AArch64Address.AddressingMode;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.PrefetchMode;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.ShiftType;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler.ScratchRegister;
import jdk.graal.compiler.code.CompilationResult;
import jdk.graal.compiler.core.aarch64.AArch64AddressLoweringByUse;
import jdk.graal.compiler.core.aarch64.AArch64ArithmeticLIRGenerator;
import jdk.graal.compiler.core.aarch64.AArch64LIRGenerator;
import jdk.graal.compiler.core.aarch64.AArch64LIRKindTool;
import jdk.graal.compiler.core.aarch64.AArch64MoveFactory;
import jdk.graal.compiler.core.aarch64.AArch64NodeLIRBuilder;
import jdk.graal.compiler.core.aarch64.AArch64NodeMatchRules;
import jdk.graal.compiler.core.common.CompilationIdentifier;
import jdk.graal.compiler.core.common.CompressEncoding;
import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.common.alloc.RegisterAllocationConfig;
import jdk.graal.compiler.core.common.calc.Condition;
import jdk.graal.compiler.core.common.memory.MemoryExtendKind;
import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.core.common.spi.LIRKindTool;
import jdk.graal.compiler.core.common.type.CompressibleConstant;
import jdk.graal.compiler.core.gen.DebugInfoBuilder;
import jdk.graal.compiler.core.gen.LIRGenerationProvider;
import jdk.graal.compiler.core.gen.NodeLIRBuilder;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.lir.ConstantValue;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.LIRFrameState;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.LIRInstructionClass;
import jdk.graal.compiler.lir.LabelRef;
import jdk.graal.compiler.lir.Opcode;
import jdk.graal.compiler.lir.StandardOp.BlockEndOp;
import jdk.graal.compiler.lir.StandardOp.LoadConstantOp;
import jdk.graal.compiler.lir.Variable;
import jdk.graal.compiler.lir.aarch64.AArch64AddressValue;
import jdk.graal.compiler.lir.aarch64.AArch64BreakpointOp;
import jdk.graal.compiler.lir.aarch64.AArch64Call;
import jdk.graal.compiler.lir.aarch64.AArch64ControlFlow;
import jdk.graal.compiler.lir.aarch64.AArch64FrameMap;
import jdk.graal.compiler.lir.aarch64.AArch64FrameMapBuilder;
import jdk.graal.compiler.lir.aarch64.AArch64LIRInstruction;
import jdk.graal.compiler.lir.aarch64.AArch64Move;
import jdk.graal.compiler.lir.aarch64.AArch64Move.PointerCompressionOp;
import jdk.graal.compiler.lir.aarch64.AArch64PrefetchOp;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.graal.compiler.lir.asm.CompilationResultBuilderFactory;
import jdk.graal.compiler.lir.asm.DataBuilder;
import jdk.graal.compiler.lir.asm.EntryPointDecorator;
import jdk.graal.compiler.lir.asm.FrameContext;
import jdk.graal.compiler.lir.framemap.FrameMap;
import jdk.graal.compiler.lir.framemap.FrameMapBuilder;
import jdk.graal.compiler.lir.framemap.FrameMapBuilderTool;
import jdk.graal.compiler.lir.framemap.ReferenceMapBuilder;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.gen.LIRGeneratorTool;
import jdk.graal.compiler.lir.gen.MoveFactory;
import jdk.graal.compiler.nodes.BreakpointNode;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.DirectCallTargetNode;
import jdk.graal.compiler.nodes.IndirectCallTargetNode;
import jdk.graal.compiler.nodes.InvokeWithExceptionNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.LoweredCallTargetNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ParameterNode;
import jdk.graal.compiler.nodes.SafepointNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.nodes.spi.NodeValueMap;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.common.AddressLoweringByUsePhase;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.vector.lir.aarch64.AArch64SimdLIRKindTool;
import jdk.graal.compiler.vector.lir.aarch64.AArch64VectorArithmeticLIRGenerator;
import jdk.graal.compiler.vector.lir.aarch64.AArch64VectorMoveFactory;
import jdk.graal.compiler.vector.lir.aarch64.AArch64VectorNodeMatchRules;
import jdk.graal.compiler.vector.nodes.simd.SimdConstant;
import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.CodeUtil;
import jdk.vm.ci.code.CompilationRequest;
import jdk.vm.ci.code.CompiledCode;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.ValueUtil;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Value;

public class SubstrateAArch64Backend extends SubstrateBackend implements LIRGenerationProvider {

    protected static CompressEncoding getCompressEncoding() {
        return ImageSingletons.lookup(CompressEncoding.class);
    }

    public SubstrateAArch64Backend(Providers providers) {
        super(providers);
    }

    @Opcode("CALL_DIRECT")
    public static class SubstrateAArch64DirectCallOp extends AArch64Call.DirectCallOp {
        public static final LIRInstructionClass<SubstrateAArch64DirectCallOp> TYPE = LIRInstructionClass.create(SubstrateAArch64DirectCallOp.class);

        private final int newThreadStatus;
        @Use({REG, OperandFlag.ILLEGAL}) private Value javaFrameAnchor;

        private final boolean destroysCallerSavedRegisters;
        @Temp({REG, OperandFlag.ILLEGAL}) private Value exceptionTemp;
        /*
         * Make it explicit that this operation overrides the link register. This is important to
         * know when the callTarget uses the StubCallingConvention.
         */
        @Temp({REG}) private Value linkReg;

        public SubstrateAArch64DirectCallOp(ResolvedJavaMethod callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState state,
                        Value javaFrameAnchor, int newThreadStatus, boolean destroysCallerSavedRegisters, Value exceptionTemp) {
            super(TYPE, callTarget, result, parameters, temps, state);
            this.javaFrameAnchor = javaFrameAnchor;
            this.newThreadStatus = newThreadStatus;
            this.destroysCallerSavedRegisters = destroysCallerSavedRegisters;
            this.exceptionTemp = exceptionTemp;
            this.linkReg = lr.asValue(LIRKind.value(AArch64Kind.QWORD));
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            maybeTransitionToNative(crb, masm, javaFrameAnchor, state, newThreadStatus);
            // All direct calls assume that they are within +-128 MB
            AArch64Call.directCall(crb, masm, callTarget, null, state);
        }

        @Override
        public boolean destroysCallerSavedRegisters() {
            return destroysCallerSavedRegisters;
        }
    }

    @Opcode("CALL_INDIRECT")
    public static class SubstrateAArch64IndirectCallOp extends AArch64Call.IndirectCallOp {
        public static final LIRInstructionClass<SubstrateAArch64IndirectCallOp> TYPE = LIRInstructionClass.create(SubstrateAArch64IndirectCallOp.class);
        private final int newThreadStatus;
        @Use({REG, OperandFlag.ILLEGAL}) private Value javaFrameAnchor;

        private final boolean destroysCallerSavedRegisters;
        @Temp({REG, OperandFlag.ILLEGAL}) private Value exceptionTemp;
        /*
         * Make it explicit that this operation overrides the link register. This is important to
         * know when the callTarget uses the StubCallingConvention.
         */
        @Temp({REG}) private Value linkReg;
        private final BiConsumer<CompilationResultBuilder, Integer> offsetRecorder;

        @Def({REG}) private Value[] multipleResults;

        public SubstrateAArch64IndirectCallOp(ResolvedJavaMethod callTarget, Value result, Value[] parameters, Value[] temps, Value targetAddress,
                        LIRFrameState state, Value javaFrameAnchor, int newThreadStatus, boolean destroysCallerSavedRegisters, Value exceptionTemp,
                        BiConsumer<CompilationResultBuilder, Integer> offsetRecorder, Value[] multipleResults) {
            super(TYPE, callTarget, result, parameters, temps, targetAddress, state);
            this.javaFrameAnchor = javaFrameAnchor;
            this.newThreadStatus = newThreadStatus;
            this.destroysCallerSavedRegisters = destroysCallerSavedRegisters;
            this.exceptionTemp = exceptionTemp;
            this.linkReg = lr.asValue(LIRKind.value(AArch64Kind.QWORD));
            this.offsetRecorder = offsetRecorder;
            this.multipleResults = multipleResults;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            maybeTransitionToNative(crb, masm, javaFrameAnchor, state, newThreadStatus);
            Register target = asRegister(targetAddress);
            int offset = AArch64Call.indirectCall(crb, masm, target, callTarget, state);
            if (offsetRecorder != null) {
                offsetRecorder.accept(crb, offset);
            }
        }

        @Override
        public boolean destroysCallerSavedRegisters() {
            return destroysCallerSavedRegisters;
        }
    }

    @Opcode("CALL_COMPUTED_INDIRECT")
    public static class SubstrateAArch64ComputedIndirectCallOp extends AArch64Call.MethodCallOp {
        public static final LIRInstructionClass<SubstrateAArch64ComputedIndirectCallOp> TYPE = LIRInstructionClass.create(SubstrateAArch64ComputedIndirectCallOp.class);

        // addressBase is killed during code generation
        @Use({REG}) private Value addressBase;
        @Temp({REG}) private Value addressBaseTemp;

        @Temp({REG, OperandFlag.ILLEGAL}) private Value exceptionTemp;

        private final ComputedIndirectCallTargetNode.Computation[] addressComputation;
        private final LIRKindTool lirKindTool;
        private final ConstantReflectionProvider constantReflection;

        public SubstrateAArch64ComputedIndirectCallOp(ResolvedJavaMethod callTarget, Value result, Value[] parameters, Value[] temps,
                        Value addressBase, ComputedIndirectCallTargetNode.Computation[] addressComputation,
                        LIRFrameState state, Value exceptionTemp, LIRKindTool lirKindTool, ConstantReflectionProvider constantReflection) {
            super(TYPE, callTarget, result, parameters, temps, state);
            this.addressBase = this.addressBaseTemp = addressBase;
            this.exceptionTemp = exceptionTemp;
            this.addressComputation = addressComputation;
            this.lirKindTool = lirKindTool;
            this.constantReflection = constantReflection;
            assert differentRegisters(parameters, temps, addressBase);
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            VMError.guarantee(SubstrateOptions.SpawnIsolates.getValue(), "Memory access without isolates is not implemented");
            try (ScratchRegister sc1 = masm.getScratchRegister(); ScratchRegister sc2 = masm.getScratchRegister()) {
                Register immediateScratch = sc1.getRegister();
                Register addressScratch = sc2.getRegister();

                int compressionShift = ReferenceAccess.singleton().getCompressionShift();
                Register computeRegister = asRegister(addressBase);
                int addressBitSize = addressBase.getPlatformKind().getSizeInBytes() * Byte.SIZE;
                boolean nextMemoryAccessNeedsDecompress = false;

                for (var computation : addressComputation) {
                    /*
                     * Both supported computations are field loads. The difference is the memory
                     * address (which either reads from the current computed value, or from a newly
                     * provided constant object).
                     */
                    SharedField field;
                    AArch64Address memoryAddress;
                    Label done = null;

                    if (computation instanceof ComputedIndirectCallTargetNode.FieldLoad) {
                        field = (SharedField) ((ComputedIndirectCallTargetNode.FieldLoad) computation).getField();
                        addressBitSize = getFieldSize(field);
                        if (nextMemoryAccessNeedsDecompress) {
                            /*
                             * Manual implementation of the only compressed reference scheme that is
                             * currently in use: references are relative to the heap base register,
                             * with an optional shift.
                             */
                            masm.add(64, addressScratch, ReservedRegisters.singleton().getHeapBaseRegister(), computeRegister, ShiftType.LSL, compressionShift);
                            memoryAddress = masm.makeAddress(addressBitSize, addressScratch, field.getOffset(), immediateScratch);
                        } else {
                            memoryAddress = masm.makeAddress(addressBitSize, computeRegister, field.getOffset(), immediateScratch);
                        }

                    } else if (computation instanceof ComputedIndirectCallTargetNode.FieldLoadIfZero) {
                        done = new Label();
                        VMError.guarantee(!nextMemoryAccessNeedsDecompress, "Comparison with compressed null value not implemented");
                        masm.cbnz(addressBitSize, computeRegister, done);

                        JavaConstant object = ((ComputedIndirectCallTargetNode.FieldLoadIfZero) computation).getObject();
                        field = (SharedField) ((ComputedIndirectCallTargetNode.FieldLoadIfZero) computation).getField();
                        addressBitSize = getFieldSize(field);

                        /*
                         * The mechanism for loading a field from a constant object is dependent on
                         * whether we are at build-time or during execution. At build-time, the heap
                         * layout isn't known yet so that value has to be patched in later. At
                         * execution time the heap offset can be directly queried.
                         */
                        if (SubstrateUtil.HOSTED) {
                            crb.recordInlineDataInCode(object);
                            int referenceSize = ConfigurationValues.getObjectLayout().getReferenceSize();
                            if (referenceSize == 4) {
                                masm.mov(addressScratch, 0xDEADDEAD, true);
                            } else {
                                masm.mov(addressScratch, 0xDEADDEADDEADDEADL, true);
                            }
                            masm.add(64, addressScratch, ReservedRegisters.singleton().getHeapBaseRegister(), addressScratch, ShiftType.LSL, compressionShift);
                            memoryAddress = masm.makeAddress(addressBitSize, addressScratch, field.getOffset(), immediateScratch);
                        } else {
                            memoryAddress = masm.makeAddress(addressBitSize, ReservedRegisters.singleton().getHeapBaseRegister(), field.getOffset() +
                                            ((SharedConstantReflectionProvider) constantReflection).getImageHeapOffset(object), immediateScratch);
                        }

                    } else {
                        throw VMError.shouldNotReachHere("Computation is not supported yet: " + computation.getClass().getTypeName());
                    }

                    nextMemoryAccessNeedsDecompress = field.getStorageKind() == JavaKind.Object;

                    masm.ldr(addressBitSize, computeRegister, memoryAddress);

                    if (done != null) {
                        masm.bind(done);
                    }
                }

                VMError.guarantee(!nextMemoryAccessNeedsDecompress, "Final computed call target address is not a primitive value");
                AArch64Call.indirectCall(crb, masm, computeRegister, callTarget, state);
            }
        }

        private int getFieldSize(SharedField field) {
            switch (field.getStorageKind()) {
                case Int:
                    return Integer.SIZE;
                case Long:
                    return Long.SIZE;
                case Object:
                    return lirKindTool.getNarrowOopKind().getPlatformKind().getSizeInBytes() * Byte.SIZE;
                default:
                    throw VMError.shouldNotReachHere("Kind is not supported yet: " + field.getStorageKind());
            }
        }
    }

    static void maybeTransitionToNative(CompilationResultBuilder crb, AArch64MacroAssembler masm, Value javaFrameAnchor, LIRFrameState state, int newThreadStatus) {
        if (ValueUtil.isIllegal(javaFrameAnchor)) {
            /* Not a call that needs to set up a JavaFrameAnchor. */
            assert newThreadStatus == StatusSupport.STATUS_ILLEGAL;
            return;
        }
        assert StatusSupport.isValidStatus(newThreadStatus);

        Register anchor = asRegister(javaFrameAnchor);

        /*
         * We record the instruction to load the current instruction pointer as a Call infopoint, so
         * that the same metadata is emitted in the machine code as for a normal call instruction.
         * The adr loads the offset 8 relative to the begin of the adr instruction, which is the
         * same as for a blr instruction. So the usual AArch64 specific semantics that all the
         * metadata is registered for the end of the instruction just works.
         */
        int startPos = masm.position();
        KnownOffsets knownOffsets = KnownOffsets.singleton();
        try (ScratchRegister scratch = masm.getScratchRegister()) {
            Register tempRegister = scratch.getRegister();
            // Save PC
            masm.adr(tempRegister, 4); // Read PC + 4
            crb.recordIndirectCall(startPos, masm.position(), null, state);
            masm.str(64, tempRegister,
                            AArch64Address.createImmediateAddress(64, AArch64Address.AddressingMode.IMMEDIATE_UNSIGNED_SCALED, anchor, knownOffsets.getJavaFrameAnchorLastIPOffset()));
            // Save SP
            masm.mov(64, tempRegister, sp);
            masm.str(64, tempRegister,
                            AArch64Address.createImmediateAddress(64, AArch64Address.AddressingMode.IMMEDIATE_UNSIGNED_SCALED, anchor, knownOffsets.getJavaFrameAnchorLastSPOffset()));
        }

        /*
         * Change VMThread status from Java to Native. Note a "store release" is needed for this
         * update to ensure VMThread status is only updated once all prior stores are also
         * observable.
         */
        try (ScratchRegister scratch1 = masm.getScratchRegister(); ScratchRegister scratch2 = masm.getScratchRegister()) {
            Register statusValueRegister = scratch1.getRegister();
            Register statusAddressRegister = scratch2.getRegister();
            masm.mov(statusValueRegister, newThreadStatus);
            masm.loadAlignedAddress(32, statusAddressRegister, ReservedRegisters.singleton().getThreadRegister(), knownOffsets.getVMThreadStatusOffset());
            masm.stlr(32, statusValueRegister, statusAddressRegister);
        }
    }

    /**
     * Marks a point that is unreachable because a previous instruction never returns.
     */
    @Opcode("DEAD_END")
    public static class DeadEndOp extends LIRInstruction implements BlockEndOp {
        public static final LIRInstructionClass<DeadEndOp> TYPE = LIRInstructionClass.create(DeadEndOp.class);

        public DeadEndOp() {
            super(TYPE);
        }

        @Override
        public void emitCode(CompilationResultBuilder crb) {
            if (SubstrateUtil.assertionsEnabled()) {
                ((AArch64MacroAssembler) crb.asm).brk(AArch64MacroAssembler.AArch64ExceptionCode.BREAKPOINT);
            }
        }
    }

    protected static final class SubstrateLIRGenerationResult extends LIRGenerationResult {

        private final SharedMethod method;

        public SubstrateLIRGenerationResult(CompilationIdentifier compilationId, LIR lir, FrameMapBuilder frameMapBuilder, RegisterAllocationConfig registerAllocationConfig,
                        CallingConvention callingConvention, SharedMethod method) {
            super(compilationId, lir, frameMapBuilder, registerAllocationConfig, callingConvention);
            this.method = method;

            if (method.hasCalleeSavedRegisters()) {
                AArch64CalleeSavedRegisters calleeSavedRegisters = AArch64CalleeSavedRegisters.singleton();
                FrameMap frameMap = ((FrameMapBuilderTool) frameMapBuilder).getFrameMap();
                int registerSaveAreaSizeInBytes = calleeSavedRegisters.getSaveAreaSize();
                StackSlot calleeSaveArea = frameMap.allocateStackMemory(registerSaveAreaSizeInBytes, frameMap.getTarget().wordSize);

                /*
                 * The offset of the callee save area must be fixed early during image generation.
                 * It is accessed when compiling methods that have a call with callee-saved calling
                 * convention. Here we verify that offset computed earlier is the same as the offset
                 * actually reserved.
                 */
                calleeSavedRegisters.verifySaveAreaOffsetInFrame(calleeSaveArea.getRawOffset());
            }

            if (method.canDeoptimize() || method.isDeoptTarget()) {
                ((FrameMapBuilderTool) frameMapBuilder).getFrameMap().reserveOutgoing(16);
            }
        }

        public SharedMethod getMethod() {
            return method;
        }
    }

    protected class SubstrateAArch64LIRGenerator extends AArch64LIRGenerator implements SubstrateLIRGenerator {

        public SubstrateAArch64LIRGenerator(LIRKindTool lirKindTool, AArch64ArithmeticLIRGenerator arithmeticLIRGen, MoveFactory moveFactory, Providers providers, LIRGenerationResult lirGenRes) {
            super(lirKindTool, arithmeticLIRGen, null, moveFactory, providers, lirGenRes);
        }

        @Override
        public SubstrateLIRGenerationResult getResult() {
            return (SubstrateLIRGenerationResult) super.getResult();
        }

        @Override
        public SubstrateRegisterConfig getRegisterConfig() {
            return (SubstrateRegisterConfig) super.getRegisterConfig();
        }

        protected boolean getDestroysCallerSavedRegisters(ResolvedJavaMethod targetMethod) {
            if (getResult().getMethod().isDeoptTarget()) {
                /*
                 * The Deoptimizer cannot restore register values, so in a deoptimization target
                 * method all registers must always be caller saved. It is of course inefficient to
                 * caller-save all registers and then invoke a method that callee-saves all
                 * registers again. But deoptimization entry point methods cannot be optimized
                 * aggressively anyway.
                 */
                return true;
            }
            return targetMethod == null || !((SharedMethod) targetMethod).hasCalleeSavedRegisters();
        }

        @Override
        protected Value emitIndirectForeignCallAddress(ForeignCallLinkage linkage) {
            if (!shouldEmitOnlyIndirectCalls()) {
                return null;
            }

            SubstrateForeignCallLinkage callTarget = (SubstrateForeignCallLinkage) linkage;
            SharedMethod targetMethod = (SharedMethod) callTarget.getMethod();

            LIRKind wordKind = getLIRKindTool().getWordKind();
            Value codeOffsetInImage = emitConstant(wordKind, JavaConstant.forLong(targetMethod.getImageCodeOffset()));
            Value codeInfo = emitJavaConstant(SubstrateObjectConstant.forObject(targetMethod.getImageCodeInfo()));
            int size = wordKind.getPlatformKind().getSizeInBytes() * Byte.SIZE;
            int codeStartFieldOffset = KnownOffsets.singleton().getImageCodeInfoCodeStartOffset();
            Value codeStartField = AArch64AddressValue.makeAddress(wordKind, size, asAllocatable(codeInfo), codeStartFieldOffset);
            Value codeStart = getArithmetic().emitLoad(wordKind, codeStartField, null, MemoryOrderMode.PLAIN, MemoryExtendKind.DEFAULT);
            return getArithmetic().emitAdd(codeStart, codeOffsetInImage, false);
        }

        @Override
        protected void emitForeignCallOp(ForeignCallLinkage linkage, Value targetAddress, Value result, Value[] arguments, Value[] temps, LIRFrameState info) {
            SubstrateForeignCallLinkage callTarget = (SubstrateForeignCallLinkage) linkage;
            SharedMethod targetMethod = (SharedMethod) callTarget.getMethod();
            Value exceptionTemp = getExceptionTemp(info != null && info.exceptionEdge != null);

            if (shouldEmitOnlyIndirectCalls()) {
                RegisterValue targetRegister = AArch64.lr.asValue(FrameAccess.getWordStamp().getLIRKind(getLIRKindTool()));
                emitMove(targetRegister, targetAddress);
                Value[] multipleResults = Value.NO_VALUES;
                append(new SubstrateAArch64IndirectCallOp(targetMethod, result, arguments, temps, targetRegister, info, Value.ILLEGAL, StatusSupport.STATUS_ILLEGAL,
                                getDestroysCallerSavedRegisters(targetMethod), exceptionTemp, null, multipleResults));
            } else {
                assert targetAddress == null;
                append(new SubstrateAArch64DirectCallOp(targetMethod, result, arguments, temps, info, Value.ILLEGAL, StatusSupport.STATUS_ILLEGAL,
                                getDestroysCallerSavedRegisters(targetMethod), exceptionTemp));
            }
        }

        /**
         * For invokes that have an exception handler, the register used for the incoming exception
         * is destroyed at the call site even when registers are caller saved. The normal object
         * return register is used in {@link NodeLIRBuilder#emitReadExceptionObject} also for the
         * exception.
         */
        private Value getExceptionTemp(boolean hasExceptionEdge) {
            if (hasExceptionEdge) {
                return getRegisterConfig().getReturnRegister(JavaKind.Object).asValue();
            } else {
                return Value.ILLEGAL;
            }
        }

        @Override
        public void emitUnwind(Value operand) {
            throw shouldNotReachHere("handled by lowering");
        }

        @Override
        public void emitDeoptimize(Value actionAndReason, Value failedSpeculation, LIRFrameState state) {
            if (!SubstrateUtil.HOSTED && DeoptimizationSupport.enabled()) {
                ForeignCallLinkage linkage = getForeignCalls().lookupForeignCall(DeoptimizationRuntime.DEOPTIMIZE);
                emitForeignCall(linkage, state, actionAndReason, failedSpeculation);
                append(new DeadEndOp());
            } else {
                throw shouldNotReachHere("Substrate VM does not use deoptimization");
            }
        }

        @Override
        public void emitVerificationMarker(Object marker) {
            append(new VerificationMarkerOp(marker));
        }

        @Override
        public void emitInstructionSynchronizationBarrier() {
            append(new AArch64InstructionSynchronizationBarrierOp());
        }

        @Override
        public void emitFarReturn(AllocatableValue result, Value stackPointer, Value ip, boolean fromMethodWithCalleeSavedRegisters) {
            append(new AArch64FarReturnOp(asAllocatable(result), asAllocatable(stackPointer), asAllocatable(ip), fromMethodWithCalleeSavedRegisters));
        }

        @Override
        public void emitDeadEnd() {
            append(new DeadEndOp());
        }

        @Override
        public void emitPrefetchAllocate(Value address) {
            append(new AArch64PrefetchOp(asAddressValue(address, AArch64Address.ANY_SIZE), PrefetchMode.PSTL1KEEP));
        }

        @Override
        public Value emitCompress(Value pointer, CompressEncoding encoding, boolean isNonNull) {
            Variable result = newVariable(getLIRKindTool().getNarrowOopKind());
            boolean nonNull = useLinearPointerCompression() || isNonNull;
            append(new AArch64Move.CompressPointerOp(result, asAllocatable(pointer), ReservedRegisters.singleton().getHeapBaseRegister().asValue(), encoding, nonNull, getLIRKindTool()));
            return result;
        }

        @Override
        public Value emitUncompress(Value pointer, CompressEncoding encoding, boolean isNonNull) {
            assert pointer.getValueKind(LIRKind.class).getPlatformKind() == getLIRKindTool().getNarrowOopKind().getPlatformKind();
            Variable result = newVariable(getLIRKindTool().getObjectKind());
            boolean nonNull = useLinearPointerCompression() || isNonNull;
            append(new AArch64Move.UncompressPointerOp(result, asAllocatable(pointer), ReservedRegisters.singleton().getHeapBaseRegister().asValue(), encoding, nonNull, getLIRKindTool()));
            return result;
        }

        @Override
        public void emitConvertNullToZero(AllocatableValue result, AllocatableValue value) {
            if (useLinearPointerCompression()) {
                append(new AArch64Move.ConvertNullToZeroOp(result, value));
            } else {
                emitMove(result, value);
            }
        }

        @Override
        public void emitConvertZeroToNull(AllocatableValue result, Value value) {
            if (useLinearPointerCompression()) {
                append(new AArch64Move.ConvertZeroToNullOp(result, (AllocatableValue) value));
            } else {
                emitMove(result, value);
            }
        }

        @Override
        public void emitReturn(JavaKind kind, Value input) {
            AllocatableValue operand = Value.ILLEGAL;
            if (input != null) {
                operand = resultOperandFor(kind, input.getValueKind());
                emitMove(operand, input);
            }
            append(new AArch64ControlFlow.ReturnOp(operand));
        }

        @Override
        public int getArrayLengthOffset() {
            return ConfigurationValues.getObjectLayout().getArrayLengthOffset();
        }

        @Override
        public boolean isReservedRegister(Register r) {
            return ReservedRegisters.singleton().isReservedRegister(r);
        }

        @Override
        protected int getVMPageSize() {
            return SubstrateOptions.getPageSize();
        }

        @Override
        public void emitExitMethodAddressResolution(Value ip) {
            PLTGOTConfiguration configuration = PLTGOTConfiguration.singleton();
            RegisterValue exitThroughRegisterValue = configuration.getExitMethodAddressResolutionRegister(getRegisterConfig()).asValue(ip.getValueKind());
            emitMove(exitThroughRegisterValue, ip);
            append(configuration.createExitMethodAddressResolutionOp(exitThroughRegisterValue));
        }
    }

    public class SubstrateAArch64NodeLIRBuilder extends AArch64NodeLIRBuilder implements SubstrateNodeLIRBuilder {
        public SubstrateAArch64NodeLIRBuilder(StructuredGraph graph, LIRGeneratorTool gen, AArch64NodeMatchRules nodeMatchRules) {
            super(graph, gen, nodeMatchRules);
        }

        @Override
        public void visitSafepointNode(SafepointNode node) {
            throw shouldNotReachHere("handled by lowering");
        }

        @Override
        public void visitBreakpointNode(BreakpointNode node) {
            JavaType[] sig = new JavaType[node.arguments().size()];
            for (int i = 0; i < sig.length; i++) {
                sig[i] = node.arguments().get(i).stamp(NodeView.DEFAULT).javaType(gen.getMetaAccess());
            }

            CallingConvention convention = gen.getRegisterConfig().getCallingConvention(SubstrateCallingConventionKind.Java.toType(true), null, sig, gen);
            append(new AArch64BreakpointOp(visitInvokeArguments(convention, node.arguments())));
        }

        @Override
        protected DebugInfoBuilder createDebugInfoBuilder(StructuredGraph graph, NodeValueMap nodeValueMap) {
            return new SubstrateDebugInfoBuilder(graph, gen.getProviders().getMetaAccessExtensionProvider(), nodeValueMap);
        }

        @Override
        public Value[] visitInvokeArguments(CallingConvention invokeCc, Collection<ValueNode> arguments) {
            Value[] values = super.visitInvokeArguments(invokeCc, arguments);
            SubstrateCallingConventionType type = (SubstrateCallingConventionType) ((SubstrateCallingConvention) invokeCc).getType();

            if (type.usesReturnBuffer()) {
                /*
                 * We save the return buffer so that it can be accessed after the call.
                 */
                assert values.length > 0;
                Value returnBuffer = values[0];
                Variable saved = gen.newVariable(returnBuffer.getValueKind());
                gen.append(gen.getSpillMoveFactory().createMove(saved, returnBuffer));
                values[0] = saved;
            }

            return values;
        }

        private boolean getDestroysCallerSavedRegisters(ResolvedJavaMethod targetMethod) {
            return ((SubstrateAArch64LIRGenerator) gen).getDestroysCallerSavedRegisters(targetMethod);
        }

        @Override
        protected void prologSetParameterNodes(StructuredGraph graph, Value[] params) {
            SubstrateCallingConvention convention = (SubstrateCallingConvention) gen.getResult().getCallingConvention();
            for (ParameterNode param : graph.getNodes(ParameterNode.TYPE)) {
                Value inputValue = params[param.index()];
                Value paramValue = gen.emitMove(inputValue);

                /*
                 * In the native ABI some parameters are not extended to the equivalent Java stack
                 * kinds.
                 */
                if (inputValue.getPlatformKind().getSizeInBytes() < Integer.BYTES) {
                    SubstrateCallingConventionType type = (SubstrateCallingConventionType) convention.getType();
                    assert !type.outgoing && type.nativeABI();
                    JavaKind kind = convention.getArgumentStorageKinds()[param.index()];
                    JavaKind stackKind = kind.getStackKind();
                    if (kind.isUnsigned()) {
                        paramValue = gen.getArithmetic().emitZeroExtend(paramValue, kind.getBitCount(), stackKind.getBitCount());
                    } else {
                        paramValue = gen.getArithmetic().emitSignExtend(paramValue, kind.getBitCount(), stackKind.getBitCount());
                    }
                }

                assert paramValue.getValueKind().equals(gen.getLIRKind(param.stamp(NodeView.DEFAULT)));

                setResult(param, paramValue);
            }
        }

        /**
         * For invokes that have an exception handler, the register used for the incoming exception
         * is destroyed at the call site even when registers are caller saved. The normal object
         * return register is used in {@link NodeLIRBuilder#emitReadExceptionObject} also for the
         * exception.
         */
        private Value getExceptionTemp(CallTargetNode callTarget) {
            return ((SubstrateAArch64LIRGenerator) gen).getExceptionTemp(callTarget.invoke() instanceof InvokeWithExceptionNode);
        }

        public BiConsumer<CompilationResultBuilder, Integer> getOffsetRecorder(@SuppressWarnings("unused") IndirectCallTargetNode callTargetNode) {
            return null;
        }

        private static AllocatableValue asReturnedValue(AssignedLocation assignedLocation) {
            assert assignedLocation.assignsToRegister();
            Register.RegisterCategory category = assignedLocation.register().getRegisterCategory();
            LIRKind kind;
            if (category.equals(AArch64.CPU)) {
                kind = LIRKind.value(AArch64Kind.QWORD);
            } else if (category.equals(AArch64.SIMD)) {
                kind = LIRKind.value(AArch64Kind.V128_QWORD);
            } else {
                throw unsupportedFeature("Register category " + category + " should not be used for returns spanning multiple registers.");
            }
            return assignedLocation.register().asValue(kind);
        }

        @Override
        protected void emitInvoke(LoweredCallTargetNode callTarget, Value[] parameters, LIRFrameState callState, Value result) {
            var cc = (SubstrateCallingConventionType) callTarget.callType();
            verifyCallTarget(callTarget);
            if (callTarget instanceof ComputedIndirectCallTargetNode) {
                assert !cc.customABI();
                emitComputedIndirectCall((ComputedIndirectCallTargetNode) callTarget, result, parameters, AllocatableValue.NONE, callState);
            } else {
                super.emitInvoke(callTarget, parameters, callState, result);
            }
            if (cc.usesReturnBuffer()) {
                /*
                 * The buffer argument was saved in visitInvokeArguments, so that the value was not
                 * killed by the call.
                 */
                Value returnBuffer = parameters[0];
                long offset = 0;
                for (AssignedLocation ret : cc.returnSaving) {
                    Value saveLocation = gen.getArithmetic().emitAdd(returnBuffer, gen.emitJavaConstant(JavaConstant.forLong(offset)), false);
                    AllocatableValue returnedValue = asReturnedValue(ret);
                    gen.getArithmetic().emitStore(returnedValue.getValueKind(), saveLocation, returnedValue, callState, MemoryOrderMode.PLAIN);
                    offset += returnedValue.getPlatformKind().getSizeInBytes();
                }
            }
        }

        @Override
        protected void emitDirectCall(DirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
            ResolvedJavaMethod targetMethod = callTarget.targetMethod();
            append(new SubstrateAArch64DirectCallOp(targetMethod, result, parameters, temps, callState, setupJavaFrameAnchor(callTarget),
                            getNewThreadStatus(callTarget), getDestroysCallerSavedRegisters(targetMethod), getExceptionTemp(callTarget)));
        }

        @Override
        protected void emitIndirectCall(IndirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
            // The register allocator cannot handle variables at call sites, need a fixed register.
            Register targetRegister = AArch64.lr;
            AllocatableValue targetAddress = targetRegister.asValue(FrameAccess.getWordStamp().getLIRKind(getLIRGeneratorTool().getLIRKindTool()));
            gen.emitMove(targetAddress, operand(callTarget.computedAddress()));
            ResolvedJavaMethod targetMethod = callTarget.targetMethod();
            SubstrateCallingConventionType cc = (SubstrateCallingConventionType) callTarget.callType();

            Value[] multipleResults = new Value[0];
            if (cc.customABI() && cc.usesReturnBuffer()) {
                multipleResults = Arrays.stream(cc.returnSaving)
                                .map(SubstrateAArch64NodeLIRBuilder::asReturnedValue)
                                .toList().toArray(new Value[0]);
            }

            append(new SubstrateAArch64IndirectCallOp(targetMethod, result, parameters, temps, targetAddress, callState, setupJavaFrameAnchor(callTarget),
                            getNewThreadStatus(callTarget), getDestroysCallerSavedRegisters(targetMethod), getExceptionTemp(callTarget), getOffsetRecorder(callTarget), multipleResults));
        }

        protected void emitComputedIndirectCall(ComputedIndirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
            assert !((SubstrateCallingConventionType) callTarget.callType()).nativeABI();
            // The register allocator cannot handle variables at call sites, need a fixed register.
            AllocatableValue addressBase = AArch64.lr.asValue(callTarget.getAddressBase().stamp(NodeView.DEFAULT).getLIRKind(getLIRGeneratorTool().getLIRKindTool()));
            gen.emitMove(addressBase, operand(callTarget.getAddressBase()));
            ResolvedJavaMethod targetMethod = callTarget.targetMethod();
            append(new SubstrateAArch64ComputedIndirectCallOp(targetMethod, result, parameters, temps, addressBase, callTarget.getAddressComputation(),
                            callState, getExceptionTemp(callTarget), gen.getLIRKindTool(), getConstantReflection()));
        }

        private AllocatableValue setupJavaFrameAnchor(CallTargetNode callTarget) {
            if (!hasJavaFrameAnchor(callTarget)) {
                return Value.ILLEGAL;
            }
            /* Register allocator cannot handle variables at call sites, need a fixed register. */
            Register frameAnchorRegister = AArch64.r13;
            AllocatableValue frameAnchor = frameAnchorRegister.asValue(FrameAccess.getWordStamp().getLIRKind(getLIRGeneratorTool().getLIRKindTool()));
            gen.emitMove(frameAnchor, operand(getJavaFrameAnchor(callTarget)));
            return frameAnchor;
        }

        @Override
        public void emitBranch(LogicNode node, LabelRef trueSuccessor, LabelRef falseSuccessor, double trueSuccessorProbability) {
            if (node instanceof SafepointCheckNode) {
                AArch64SafepointCheckOp op = new AArch64SafepointCheckOp();
                append(op);
                append(new AArch64ControlFlow.BranchOp(op.getConditionFlag(), trueSuccessor, falseSuccessor, trueSuccessorProbability));
            } else {
                super.emitBranch(node, trueSuccessor, falseSuccessor, trueSuccessorProbability);
            }
        }

        @Override
        public void emitCGlobalDataLoadAddress(CGlobalDataLoadAddressNode node) {
            Variable result = gen.newVariable(gen.getLIRKindTool().getWordKind());
            append(new AArch64CGlobalDataLoadAddressOp(node.getDataInfo(), result));
            setResult(node, result);
        }

        @Override
        public Variable emitReadReturnAddress() {
            return getLIRGeneratorTool().emitMove(StackSlot.get(getLIRGeneratorTool().getLIRKind(FrameAccess.getWordStamp()), -FrameAccess.returnAddressSize(), true));
        }

        @Override
        public ForeignCallLinkage lookupGraalStub(ValueNode valueNode, ForeignCallDescriptor foreignCallDescriptor) {
            SharedMethod method = (SharedMethod) valueNode.graph().method();
            if (method != null && method.isForeignCallTarget()) {
                // Emit assembly for snippet stubs
                return null;
            }
            // Assume the SVM ForeignCallSignature are identical to the Graal ones.
            return gen.getForeignCalls().lookupForeignCall(foreignCallDescriptor);
        }
    }

    public static class SubstrateAArch64FrameContext implements FrameContext {

        protected final SharedMethod method;

        protected SubstrateAArch64FrameContext(SharedMethod method) {
            this.method = method;
        }

        @Override
        public void enter(CompilationResultBuilder crb) {
            FrameMap frameMap = crb.frameMap;
            final int frameSize = frameMap.frameSize();
            final int totalFrameSize = frameMap.totalFrameSize();
            assert frameSize + 2 * crb.target.arch.getWordSize() == totalFrameSize : "totalFramesize should be frameSize + 2 words";
            AArch64MacroAssembler masm = (AArch64MacroAssembler) crb.asm;

            crb.blockComment("[method prologue]");
            makeFrame(crb, masm, totalFrameSize, frameSize);
            crb.recordMark(PROLOGUE_DECD_RSP);

            if (ZapStackOnMethodEntry.getValue(crb.getOptions())) {
                try (ScratchRegister sc = masm.getScratchRegister()) {
                    Register scratch = sc.getRegister();
                    int longSize = Long.BYTES;
                    masm.mov(64, scratch, sp);
                    AArch64Address address = AArch64Address.createImmediateAddress(64, AArch64Address.AddressingMode.IMMEDIATE_POST_INDEXED, scratch, longSize);
                    try (ScratchRegister sc2 = masm.getScratchRegister()) {
                        Register value = sc2.getRegister();
                        masm.mov(value, 0xBADDECAFFC0FFEEL);
                        for (int i = 0; i < frameSize; i += longSize) {
                            masm.str(64, value, address);
                        }
                    }

                }
            }

            if (method.hasCalleeSavedRegisters()) {
                VMError.guarantee(!method.isDeoptTarget(), "Deoptimization runtime cannot fill the callee saved registers");
                AArch64CalleeSavedRegisters.singleton().emitSave(masm, totalFrameSize);
            }
            crb.recordMark(PROLOGUE_END);
        }

        protected void makeFrame(CompilationResultBuilder crb, AArch64MacroAssembler masm, int totalFrameSize, int frameSize) {
            boolean preserveFramePointer = ((SubstrateAArch64RegisterConfig) crb.frameMap.getRegisterConfig()).shouldPreserveFramePointer();
            // based on HotSpot's macroAssembler_aarch64.cpp MacroAssembler::build_frame
            try (ScratchRegister sc = masm.getScratchRegister()) {
                Register scratch = sc.getRegister();
                assert totalFrameSize > 0;
                AArch64Address.AddressingMode addressingMode = AArch64Address.AddressingMode.IMMEDIATE_PAIR_SIGNED_SCALED;
                if (AArch64Address.isValidImmediateAddress(64, addressingMode, frameSize)) {
                    masm.sub(64, sp, sp, totalFrameSize);
                    masm.stp(64, fp, lr, AArch64Address.createImmediateAddress(64, addressingMode, sp, frameSize));
                    if (preserveFramePointer) {
                        masm.add(64, fp, sp, frameSize);
                    }
                } else {
                    // frameRecordSize = 2 * wordSize (space for fp & lr)
                    int frameRecordSize = totalFrameSize - frameSize;
                    masm.stp(64, fp, lr, AArch64Address.createImmediateAddress(64, AArch64Address.AddressingMode.IMMEDIATE_PAIR_PRE_INDEXED, sp, -frameRecordSize));
                    if (preserveFramePointer) {
                        masm.mov(64, fp, sp);
                    }
                    masm.sub(64, sp, sp, frameSize, scratch);
                }
            }
        }

        @Override
        public void leave(CompilationResultBuilder crb) {
            FrameMap frameMap = crb.frameMap;
            final int frameSize = frameMap.frameSize();
            final int totalFrameSize = frameMap.totalFrameSize();
            assert frameSize + 2 * crb.target.arch.getWordSize() == totalFrameSize : "totalFramesize should be frameSize + 2 words";
            AArch64MacroAssembler masm = (AArch64MacroAssembler) crb.asm;

            crb.blockComment("[method epilogue]");
            crb.recordMark(SubstrateMarkId.EPILOGUE_START);
            if (method.hasCalleeSavedRegisters()) {
                JavaKind returnKind = method.getSignature().getReturnKind();
                Register returnRegister = null;
                if (returnKind != JavaKind.Void) {
                    returnRegister = frameMap.getRegisterConfig().getReturnRegister(returnKind);
                }
                AArch64CalleeSavedRegisters.singleton().emitRestore(masm, totalFrameSize, returnRegister);
            }

            // based on HotSpot's macroAssembler_aarch64.cpp MacroAssembler::remove_frame
            try (ScratchRegister sc = masm.getScratchRegister()) {
                Register scratch = sc.getRegister();
                assert totalFrameSize > 0;
                AArch64Address.AddressingMode addressingMode = AArch64Address.AddressingMode.IMMEDIATE_PAIR_SIGNED_SCALED;
                if (AArch64Address.isValidImmediateAddress(64, addressingMode, frameSize)) {
                    masm.ldp(64, fp, lr, AArch64Address.createImmediateAddress(64, addressingMode, sp, frameSize));
                    masm.add(64, sp, sp, totalFrameSize);
                } else {
                    // frameRecordSize = 2 * wordSize (space for fp & lr)
                    int frameRecordSize = totalFrameSize - frameSize;
                    masm.add(64, sp, sp, totalFrameSize - frameRecordSize, scratch);
                    masm.ldp(64, fp, lr, AArch64Address.createImmediateAddress(64, AArch64Address.AddressingMode.IMMEDIATE_PAIR_POST_INDEXED, sp, frameRecordSize));
                }
            }

            crb.recordMark(SubstrateMarkId.EPILOGUE_INCD_RSP);
        }

        @Override
        public void returned(CompilationResultBuilder crb) {
            crb.recordMark(SubstrateMarkId.EPILOGUE_END);
        }

    }

    /**
     * Generates the prologue of a {@link com.oracle.svm.core.deopt.Deoptimizer.StubType#EntryStub}
     * method.
     */
    protected static class DeoptEntryStubContext extends SubstrateAArch64FrameContext {
        protected final CallingConvention callingConvention;

        protected DeoptEntryStubContext(SharedMethod method, CallingConvention callingConvention) {
            super(method);
            this.callingConvention = callingConvention;
        }

        @Override
        public void enter(CompilationResultBuilder crb) {
            AArch64MacroAssembler masm = (AArch64MacroAssembler) crb.asm;
            RegisterConfig registerConfig = crb.frameMap.getRegisterConfig();
            Register frameRegister = registerConfig.getFrameRegister();
            Register gpReturnReg = registerConfig.getReturnRegister(JavaKind.Object);
            Register fpReturnReg = registerConfig.getReturnRegister(JavaKind.Double);

            /* Create the frame. */
            super.enter(crb);

            /*
             * Synthesize the parameters for the deopt stub. This needs to be done after enter() to
             * avoid overwriting register values that it might save to the stack.
             */

            /* Pass the general purpose and floating point registers to the deopt stub. */
            Register secondParameter = ValueUtil.asRegister(callingConvention.getArgument(1));
            masm.mov(64, secondParameter, gpReturnReg);

            Register thirdParameter = ValueUtil.asRegister(callingConvention.getArgument(2));
            masm.fmov(64, thirdParameter, fpReturnReg);

            /*
             * Pass the address of the frame to deoptimize as first argument. Do this last, because
             * the first argument register may overlap with the object return register.
             */
            Register firstParameter = ValueUtil.asRegister(callingConvention.getArgument(0));
            masm.add(64, firstParameter, frameRegister, crb.frameMap.totalFrameSize());
        }
    }

    /**
     * Generates the epilogue of a {@link com.oracle.svm.core.deopt.Deoptimizer.StubType#ExitStub}
     * method.
     */
    protected static class DeoptExitStubContext extends SubstrateAArch64FrameContext {
        protected final CallingConvention callingConvention;

        protected DeoptExitStubContext(SharedMethod method, CallingConvention callingConvention) {
            super(method);
            this.callingConvention = callingConvention;
        }

        /**
         * The deoptimization function starts with the stack pointer set to newSp. The stub enter
         * function first reserves space for the framepointer and the return address. It then pushes
         * the floating point register to the stack. The rewrite stub overwrites the frame pointers
         * and return address values, as well as context above it on the stack.
         * <p>
         * When exiting the stack, the function must first pop the floating point return value into
         * its register. Then it needs to read out the frame pointer and return address values
         * again, because this were loaded with the incorrect values (where frameSp is).
         *
         * <pre>
         *        ------------------------ <- newSp
         * + 0x00 | x29 (framepointer)   |
         * + 0x08 | x30 (return address) |
         *        ------------------------ <- bottomSp
         * + 0x10 | padding              | (stub stack data)
         * + 0x18 | fp return            |
         *        ------------------------ <- frameSp
         * + 0x20 | wrong x29            |
         * + 0x28 | wrong x30            |
         * + ...  | ... remaining        |
         * </pre>
         */

        @Override
        public void enter(CompilationResultBuilder crb) {
            AArch64MacroAssembler masm = (AArch64MacroAssembler) crb.asm;

            Register firstParameter = ValueUtil.asRegister(callingConvention.getArgument(0));

            /*
             * The new stack pointer is passed in as the first method parameter. Sets SP to newSp.
             */
            masm.mov(64, sp, firstParameter);

            /*
             * Save the floating point return value register. Sets SP to frameSp.
             */
            Register thirdParameter = ValueUtil.asRegister(callingConvention.getArgument(2));
            masm.str(64, thirdParameter, AArch64Address.createImmediateAddress(64, AddressingMode.IMMEDIATE_PRE_INDEXED, sp, -32));

            super.enter(crb);
        }

        @Override
        public void leave(CompilationResultBuilder crb) {
            AArch64MacroAssembler masm = (AArch64MacroAssembler) crb.asm;
            RegisterConfig registerConfig = crb.frameMap.getRegisterConfig();
            Register fpReturnReg = registerConfig.getReturnRegister(JavaKind.Double);

            /*
             * This restores the stack pointer to sp (+0x20) and loads the incorrect x29 and x30
             * registers
             */
            super.leave(crb);

            /*
             * Restore the floating point return value register. Sets SP to bottomSp (+0x10).
             */
            masm.fldr(64, fpReturnReg, AArch64Address.createImmediateAddress(64, AddressingMode.IMMEDIATE_POST_INDEXED, sp, 16));

            /* Reread the fp and lr registers from the overwritten. Sets SP to newSp (+0). */
            masm.ldp(64, fp, lr, AArch64Address.createImmediateAddress(64, AddressingMode.IMMEDIATE_PAIR_POST_INDEXED, sp, 16));
        }
    }

    static class SubstrateReferenceMapBuilderFactory implements FrameMap.ReferenceMapBuilderFactory {
        @Override
        public ReferenceMapBuilder newReferenceMapBuilder(int totalFrameSize) {
            return new SubstrateReferenceMapBuilder(totalFrameSize);
        }
    }

    protected static class SubstrateAArch64MoveFactory extends AArch64MoveFactory {

        private final SharedMethod method;
        private final LIRKindTool lirKindTool;

        protected SubstrateAArch64MoveFactory(SharedMethod method, LIRKindTool lirKindTool) {
            super();
            this.method = method;
            this.lirKindTool = lirKindTool;
        }

        @Override
        public boolean allowConstantToStackMove(Constant constant) {
            if (constant instanceof SubstrateObjectConstant && method.isDeoptTarget()) {
                return false;
            }
            return super.allowConstantToStackMove(constant);
        }

        private static JavaConstant getZeroConstant(AllocatableValue dst) {
            int size = dst.getPlatformKind().getSizeInBytes() * Byte.SIZE;
            switch (size) {
                case 32:
                    return JavaConstant.INT_0;
                case 64:
                    return JavaConstant.LONG_0;
                default:
                    throw VMError.shouldNotReachHereUnexpectedInput(size); // ExcludeFromJacocoGeneratedReport
            }
        }

        public AArch64LIRInstruction createLoadMethodPointerConstant(AllocatableValue dst, SubstrateMethodPointerConstant constant) {
            if (ImageLayerBuildingSupport.buildingExtensionLayer()) {
                if (constant.pointer().getMethod() instanceof SharedMethod sharedMethod && sharedMethod.forceIndirectCall()) {
                    // GR-53498 AArch64 layered image support
                    throw VMError.unimplemented("AArch64 does not currently support layered images.");
                }
            }

            return new AArch64LoadMethodPointerConstantOp(dst, constant);
        }

        @Override
        public AArch64LIRInstruction createLoad(AllocatableValue dst, Constant src) {
            if (CompressedNullConstant.COMPRESSED_NULL.equals(src)) {
                return super.createLoad(dst, getZeroConstant(dst));
            } else if (src instanceof CompressibleConstant constant) {
                return loadObjectConstant(dst, constant);
            } else if (src instanceof SubstrateMethodPointerConstant constant) {
                return createLoadMethodPointerConstant(dst, constant);
            }
            return super.createLoad(dst, src);
        }

        @Override
        public LIRInstruction createStackLoad(AllocatableValue dst, Constant src) {
            if (CompressedNullConstant.COMPRESSED_NULL.equals(src)) {
                return super.createStackLoad(dst, getZeroConstant(dst));
            } else if (src instanceof CompressibleConstant constant) {
                return loadObjectConstant(dst, constant);
            } else if (src instanceof SubstrateMethodPointerConstant constant) {
                return createLoadMethodPointerConstant(dst, constant);
            }
            return super.createStackLoad(dst, src);
        }

        protected AArch64LIRInstruction loadObjectConstant(AllocatableValue dst, CompressibleConstant constant) {
            if (ReferenceAccess.singleton().haveCompressedReferences()) {
                RegisterValue heapBase = ReservedRegisters.singleton().getHeapBaseRegister().asValue();
                return new LoadCompressedObjectConstantOp(dst, constant, heapBase, getCompressEncoding(), lirKindTool);
            }
            return new AArch64Move.LoadInlineConstant(constant, dst);
        }
    }

    /*
     * The constant denotes the result produced by this node. Thus if the constant is compressed,
     * the result must be compressed and vice versa. Both compressed and uncompressed constants can
     * be loaded by compiled code.
     *
     * Method getConstant() could uncompress the constant value from the node input. That would
     * require a few indirections and an allocation of an uncompressed constant. The allocation
     * could be eliminated if we stored uncompressed ConstantValue as input. But as this method
     * looks performance-critical, it is still faster to memorize the original constant in the node.
     */
    public static final class LoadCompressedObjectConstantOp extends PointerCompressionOp implements LoadConstantOp {
        public static final LIRInstructionClass<LoadCompressedObjectConstantOp> TYPE = LIRInstructionClass.create(LoadCompressedObjectConstantOp.class);

        static Constant asCompressed(CompressibleConstant constant) {
            // We only want compressed references in code
            return constant.isCompressed() ? constant : constant.compress();
        }

        private final CompressibleConstant constant;

        public LoadCompressedObjectConstantOp(AllocatableValue result, CompressibleConstant constant, AllocatableValue baseRegister, CompressEncoding encoding, LIRKindTool lirKindTool) {
            super(TYPE, result, new ConstantValue(lirKindTool.getNarrowOopKind(), asCompressed(constant)), baseRegister, encoding, true, lirKindTool);
            this.constant = constant;
        }

        @Override
        public Constant getConstant() {
            return constant;
        }

        @Override
        public void emitCode(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            /*
             * WARNING: must NOT have side effects. Preserve the flags register!
             */
            Register resultReg = getResultRegister();
            int referenceSize = ConfigurationValues.getObjectLayout().getReferenceSize();
            Constant inputConstant = asConstantValue(getInput()).getConstant();
            if (masm.inlineObjects()) {
                crb.recordInlineDataInCode(inputConstant);
                if (referenceSize == 4) {
                    masm.mov(resultReg, 0xDEADDEAD, true);
                } else {
                    masm.mov(resultReg, 0xDEADDEADDEADDEADL, true);
                }
            } else {
                crb.recordDataReferenceInCode(inputConstant, referenceSize);
                int srcSize = referenceSize * Byte.SIZE;
                masm.adrpLdr(srcSize, resultReg, resultReg);
            }
            if (!constant.isCompressed()) { // the result is expected to be uncompressed
                Register baseReg = getBaseRegister();
                assert !baseReg.equals(Register.None) || getShift() != 0 : "no compression in place";
                masm.add(64, resultReg, baseReg, resultReg, ShiftType.LSL, getShift());
            }
        }

        @Override
        public boolean canRematerializeToStack() {
            /* This operation MUST have a register as its destination. */
            return false;
        }
    }

    public FrameMapBuilder newFrameMapBuilder(RegisterConfig registerConfig) {
        RegisterConfig registerConfigNonNull = registerConfig == null ? getCodeCache().getRegisterConfig() : registerConfig;
        return new AArch64FrameMapBuilder(newFrameMap(registerConfigNonNull), getCodeCache(), registerConfigNonNull);
    }

    public FrameMap newFrameMap(RegisterConfig registerConfig) {
        return new AArch64FrameMap(getProviders().getCodeCache(), registerConfig, new SubstrateReferenceMapBuilderFactory());
    }

    @Override
    public CompilationResultBuilder newCompilationResultBuilder(LIRGenerationResult lirGenResult, FrameMap frameMap, CompilationResult compilationResult, CompilationResultBuilderFactory factory,
                    EntryPointDecorator entryPointDecorator) {
        AArch64MacroAssembler masm = new SubstrateAArch64MacroAssembler(getTarget());
        PatchConsumerFactory patchConsumerFactory;
        if (SubstrateUtil.HOSTED) {
            patchConsumerFactory = PatchConsumerFactory.HostedPatchConsumerFactory.factory();
        } else {
            patchConsumerFactory = PatchConsumerFactory.NativePatchConsumerFactory.factory();
        }
        masm.setCodePatchingAnnotationConsumer(patchConsumerFactory.newConsumer(compilationResult));
        SharedMethod method = ((SubstrateLIRGenerationResult) lirGenResult).getMethod();
        Deoptimizer.StubType stubType = method.getDeoptStubType();
        DataBuilder dataBuilder = new SubstrateDataBuilder();
        CallingConvention callingConvention = lirGenResult.getCallingConvention();
        FrameContext frameContext = createFrameContext(method, stubType, callingConvention);
        LIR lir = lirGenResult.getLIR();
        OptionValues options = lir.getOptions();
        DebugContext debug = lir.getDebug();
        Register uncompressedNullRegister = useLinearPointerCompression() ? ReservedRegisters.singleton().getHeapBaseRegister() : Register.None;
        CompilationResultBuilder crb = factory.createBuilder(getProviders(), lirGenResult.getFrameMap(), masm, dataBuilder, frameContext, options, debug, compilationResult,
                        uncompressedNullRegister, lir);
        crb.setTotalFrameSize(lirGenResult.getFrameMap().totalFrameSize());
        if (SubstrateUtil.HOSTED) {
            var sharedCompilationResult = (SharedCompilationResult) compilationResult;
            sharedCompilationResult.setCodeAlignment(SubstrateOptions.codeAlignment(options));
        }
        return crb;
    }

    protected FrameContext createFrameContext(SharedMethod method, Deoptimizer.StubType stubType, CallingConvention callingConvention) {
        if (stubType == Deoptimizer.StubType.EntryStub) {
            return new DeoptEntryStubContext(method, callingConvention);
        } else if (stubType == Deoptimizer.StubType.ExitStub) {
            return new DeoptExitStubContext(method, callingConvention);
        } else if (stubType == Deoptimizer.StubType.InterpreterEnterStub) {
            assert InterpreterSupport.isEnabled();
            return new AArch64InterpreterStubs.InterpreterEnterStubContext(method);
        } else if (stubType == Deoptimizer.StubType.InterpreterLeaveStub) {
            assert InterpreterSupport.isEnabled();
            return new AArch64InterpreterStubs.InterpreterLeaveStubContext(method);
        }

        return new SubstrateAArch64FrameContext(method);
    }

    protected static boolean isVectorizationTarget() {
        return ((AArch64) ConfigurationValues.getTarget().arch).getFeatures().contains(AArch64.CPUFeature.ASIMD);
    }

    protected AArch64ArithmeticLIRGenerator createArithmeticLIRGen(AllocatableValue nullRegisterValue) {
        if (isVectorizationTarget()) {
            return new AArch64VectorArithmeticLIRGenerator(nullRegisterValue);
        } else {
            return new AArch64ArithmeticLIRGenerator(nullRegisterValue);
        }
    }

    protected AArch64MoveFactory createMoveFactory(LIRGenerationResult lirGenRes) {
        SharedMethod method = ((SubstrateLIRGenerationResult) lirGenRes).getMethod();
        AArch64MoveFactory factory = new SubstrateAArch64MoveFactory(method, createLirKindTool());
        if (isVectorizationTarget()) {
            factory = new AArch64VectorMoveFactory(factory, new MoveFactory.BackupSlotProvider(lirGenRes.getFrameMapBuilder()));
        }
        return factory;
    }

    protected static class SubstrateAArch64LIRKindTool extends AArch64LIRKindTool implements AArch64SimdLIRKindTool {
        @Override
        public LIRKind getNarrowOopKind() {
            return LIRKind.compressedReference(AArch64Kind.QWORD);
        }

        @Override
        public LIRKind getNarrowPointerKind() {
            throw VMError.shouldNotReachHereAtRuntime(); // ExcludeFromJacocoGeneratedReport
        }
    }

    protected AArch64LIRKindTool createLirKindTool() {
        return new SubstrateAArch64LIRKindTool();
    }

    protected class SubstrateAArch64VectorLIRGenerator extends SubstrateAArch64LIRGenerator {
        public SubstrateAArch64VectorLIRGenerator(LIRKindTool lirKindTool, AArch64ArithmeticLIRGenerator arithmeticLIRGen, MoveFactory moveFactory, Providers providers,
                        LIRGenerationResult lirGenRes) {
            super(lirKindTool, arithmeticLIRGen, moveFactory, providers, lirGenRes);
        }

        @Override
        public Variable emitIntegerTestMove(Value left, Value right, Value trueValue, Value falseValue) {
            AArch64VectorArithmeticLIRGenerator vectorGen = (AArch64VectorArithmeticLIRGenerator) arithmeticLIRGen;
            Variable vectorResult = vectorGen.emitVectorIntegerTestMove(left, right, trueValue, falseValue);
            if (vectorResult != null) {
                return vectorResult;
            }
            return super.emitIntegerTestMove(left, right, trueValue, falseValue);
        }

        @Override
        public Variable emitConditionalMove(PlatformKind cmpKind, Value left, Value right, Condition cond, boolean unorderedIsTrue, Value trueValue, Value falseValue) {
            AArch64VectorArithmeticLIRGenerator vectorGen = (AArch64VectorArithmeticLIRGenerator) arithmeticLIRGen;
            Variable vectorResult = vectorGen.emitVectorConditionalMove(cmpKind, left, right, cond, unorderedIsTrue, trueValue, falseValue);
            if (vectorResult != null) {
                return vectorResult;
            }
            return super.emitConditionalMove(cmpKind, left, right, cond, unorderedIsTrue, trueValue, falseValue);
        }

        @Override
        public Value emitConstant(LIRKind kind, Constant constant) {
            int length = kind.getPlatformKind().getVectorLength();
            if (length == 1) {
                return super.emitConstant(kind, constant);
            } else if (constant instanceof SimdConstant) {
                assert ((SimdConstant) constant).getVectorLength() == length;
                return super.emitConstant(kind, constant);
            } else {
                return super.emitConstant(kind, SimdConstant.broadcast(constant, length));
            }
        }

        @Override
        public Variable emitReverseBytes(Value input) {
            AArch64VectorArithmeticLIRGenerator vectorGen = (AArch64VectorArithmeticLIRGenerator) arithmeticLIRGen;
            Variable vectorResult = vectorGen.emitVectorByteSwap(input);
            if (vectorResult != null) {
                return vectorResult;
            }
            return super.emitReverseBytes(input);
        }

    }

    @Override
    public LIRGeneratorTool newLIRGenerator(LIRGenerationResult lirGenRes) {
        RegisterValue nullRegisterValue = useLinearPointerCompression() ? ReservedRegisters.singleton().getHeapBaseRegister().asValue(LIRKind.unknownReference(AArch64Kind.QWORD)) : null;
        AArch64ArithmeticLIRGenerator arithmeticLIRGen = createArithmeticLIRGen(nullRegisterValue);
        AArch64MoveFactory moveFactory = createMoveFactory(lirGenRes);
        if (isVectorizationTarget()) {
            return new SubstrateAArch64VectorLIRGenerator(createLirKindTool(), arithmeticLIRGen, moveFactory, getProviders(), lirGenRes);
        } else {
            return new SubstrateAArch64LIRGenerator(createLirKindTool(), arithmeticLIRGen, moveFactory, getProviders(), lirGenRes);
        }
    }

    protected AArch64NodeMatchRules createMatchRules(LIRGeneratorTool lirGen) {
        if (isVectorizationTarget()) {
            return new AArch64VectorNodeMatchRules(lirGen);
        } else {
            return new AArch64NodeMatchRules(lirGen);
        }
    }

    @Override
    public NodeLIRBuilderTool newNodeLIRBuilder(StructuredGraph graph, LIRGeneratorTool lirGen) {
        AArch64NodeMatchRules nodeMatchRules = createMatchRules(lirGen);
        return new SubstrateAArch64NodeLIRBuilder(graph, lirGen, nodeMatchRules);
    }

    protected static boolean useLinearPointerCompression() {
        return SubstrateOptions.SpawnIsolates.getValue();
    }

    @Override
    public RegisterAllocationConfig newRegisterAllocationConfig(RegisterConfig registerConfig, String[] allocationRestrictedTo, Object stub) {
        RegisterConfig registerConfigNonNull = registerConfig == null ? getCodeCache().getRegisterConfig() : registerConfig;
        return new RegisterAllocationConfig(registerConfigNonNull, allocationRestrictedTo);
    }

    @Override
    public CompilationResult createJNITrampolineMethod(ResolvedJavaMethod method, CompilationIdentifier identifier,
                    RegisterValue threadArg, int threadIsolateOffset, RegisterValue methodIdArg, int methodObjEntryPointOffset) {

        CompilationResult result = new CompilationResult(identifier);
        AArch64MacroAssembler asm = new SubstrateAArch64MacroAssembler(getTarget());
        try (ScratchRegister scratch = asm.getScratchRegister()) {
            Register scratchRegister = scratch.getRegister();
            asm.ldr(64, scratchRegister, AArch64Address.createImmediateAddress(64, AArch64Address.AddressingMode.IMMEDIATE_UNSIGNED_SCALED, threadArg.getRegister(), threadIsolateOffset));
            /*
             * Load the isolate pointer from the JNIEnv argument (same as the isolate thread). The
             * isolate pointer is equivalent to the heap base address (which would normally be
             * provided via Isolate.getHeapBase which is a no-op), which we then use to access the
             * method object and read the entry point.
             */
            asm.add(64, scratchRegister, scratchRegister, methodIdArg.getRegister());
            asm.ldr(64, scratchRegister, AArch64Address.createImmediateAddress(64, AArch64Address.AddressingMode.IMMEDIATE_UNSIGNED_SCALED, scratchRegister, methodObjEntryPointOffset));
            asm.jmp(scratchRegister);
        }
        result.recordMark(asm.position(), PROLOGUE_DECD_RSP);
        result.recordMark(asm.position(), PROLOGUE_END);
        byte[] instructions = asm.close(true);
        result.setTargetCode(instructions, instructions.length);
        result.setTotalFrameSize(getTarget().stackAlignment); // not really, but 0 not allowed
        return result;
    }

    @Override
    protected CompiledCode createCompiledCode(ResolvedJavaMethod method, CompilationRequest compilationRequest, CompilationResult compilationResult, boolean isDefault, OptionValues options) {
        return new SubstrateCompiledCode(compilationResult);
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, ResolvedJavaMethod installedCodeOwner, EntryPointDecorator entryPointDecorator) {
        try {
            crb.buildLabelOffsets();
            crb.emitLIR();
            finalizeCode(crb);
        } catch (BranchTargetOutOfBoundsException e) {
            // A branch estimation was wrong, now retry with conservative label ranges, this
            // should always work
            resetForEmittingCode(crb);
            crb.resetForEmittingCode();
            crb.setConservativeLabelRanges();
            crb.emitLIR();
            finalizeCode(crb);
        }
    }

    @SuppressWarnings("unused")
    protected void finalizeCode(CompilationResultBuilder crb) {

    }

    @SuppressWarnings("unused")
    protected void resetForEmittingCode(CompilationResultBuilder crb) {

    }

    @Override
    public LIRGenerationResult newLIRGenerationResult(CompilationIdentifier compilationId, LIR lir, RegisterAllocationConfig registerAllocationConfig, StructuredGraph graph, Object stub) {
        SharedMethod method = (SharedMethod) graph.method();
        SubstrateCallingConventionKind ccKind = method.getCallingConventionKind();
        SubstrateCallingConventionType ccType = ccKind.isCustom() ? method.getCustomCallingConventionType() : ccKind.toType(false);
        CallingConvention callingConvention = CodeUtil.getCallingConvention(getCodeCache(), ccType, method, this);
        LIRGenerationResult lirGenerationResult = new SubstrateLIRGenerationResult(compilationId, lir, newFrameMapBuilder(registerAllocationConfig.getRegisterConfig()), registerAllocationConfig,
                        callingConvention, method);

        FrameMap frameMap = ((FrameMapBuilderTool) lirGenerationResult.getFrameMapBuilder()).getFrameMap();
        Deoptimizer.StubType stubType = method.getDeoptStubType();
        if (stubType == Deoptimizer.StubType.InterpreterEnterStub) {
            assert InterpreterSupport.isEnabled();
            frameMap.reserveOutgoing(AArch64InterpreterStubs.additionalFrameSizeEnterStub());
        } else if (stubType == Deoptimizer.StubType.InterpreterLeaveStub) {
            assert InterpreterSupport.isEnabled();
            frameMap.reserveOutgoing(AArch64InterpreterStubs.additionalFrameSizeLeaveStub());
        }
        return lirGenerationResult;
    }

    @Override
    public BasePhase<CoreProviders> newAddressLoweringPhase(CodeCacheProvider codeCache) {
        return new AddressLoweringByUsePhase(new AArch64AddressLoweringByUse(createLirKindTool(), false));
    }
}
