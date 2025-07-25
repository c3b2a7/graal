/*
 * Copyright (c) 2012, 2023, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.loop.phases;

import static jdk.graal.compiler.core.common.GraalOptions.MaximumDesiredSize;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.Equivalence;

import jdk.graal.compiler.core.common.RetryableBailoutException;
import jdk.graal.compiler.core.common.calc.CanonicalCondition;
import jdk.graal.compiler.core.common.type.IntegerStamp;
import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Graph.Mark;
import jdk.graal.compiler.graph.Graph.NodeEventScope;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.Position;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.AbstractEndNode;
import jdk.graal.compiler.nodes.AbstractMergeNode;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.ControlSplitNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedGuardNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.GraphState.StageFlag;
import jdk.graal.compiler.nodes.GuardPhiNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.LoopBeginNode;
import jdk.graal.compiler.nodes.LoopExitNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.PhiNode;
import jdk.graal.compiler.nodes.ProfileData.BranchProbabilityData;
import jdk.graal.compiler.nodes.ProxyNode;
import jdk.graal.compiler.nodes.SafepointNode;
import jdk.graal.compiler.nodes.StateSplit;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValueProxyNode;
import jdk.graal.compiler.nodes.VirtualState.NodePositionClosure;
import jdk.graal.compiler.nodes.calc.AddNode;
import jdk.graal.compiler.nodes.calc.CompareNode;
import jdk.graal.compiler.nodes.calc.ConditionalNode;
import jdk.graal.compiler.nodes.cfg.ControlFlowGraph;
import jdk.graal.compiler.nodes.extended.OpaqueNode;
import jdk.graal.compiler.nodes.extended.SwitchNode;
import jdk.graal.compiler.nodes.loop.CountedLoopInfo;
import jdk.graal.compiler.nodes.loop.DefaultLoopPolicies;
import jdk.graal.compiler.nodes.loop.InductionVariable.Direction;
import jdk.graal.compiler.nodes.loop.Loop;
import jdk.graal.compiler.nodes.loop.LoopFragment;
import jdk.graal.compiler.nodes.loop.LoopFragmentInside;
import jdk.graal.compiler.nodes.loop.LoopFragmentWhole;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.nodes.spi.Simplifiable;
import jdk.graal.compiler.nodes.spi.SimplifierTool;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.nodes.util.IntegerHelper;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.graal.compiler.phases.common.CanonicalizerPhase;
import jdk.graal.compiler.phases.common.util.EconomicSetNodeEventListener;
import jdk.graal.compiler.phases.common.util.LoopUtility;

public abstract class LoopTransformations {

    private LoopTransformations() {
        // does not need to be instantiated
    }

    public static LoopFragmentInside peel(Loop loop) {
        loop.detectCounted();
        double frequencyBefore = loop.localLoopFrequency();
        AbstractBeginNode mainExit = null;
        if (loop.isCounted()) {
            mainExit = loop.counted().getCountedExit();
        } else if (loop.loopBegin().loopExits().count() == 1) {
            mainExit = loop.loopBegin().loopExits().first();
            if (!(mainExit.predecessor() instanceof IfNode)) {
                mainExit = null;
            }
        }
        LoopFragmentInside inside = loop.inside().duplicate();
        inside.insertBefore(loop);
        loop.loopBegin().incrementPeelings();
        loop.loopBegin().graph().getOptimizationLog().withProperty("peelings", loop.loopBegin().peelings()).report(LoopTransformations.class, "LoopPeeling", loop.loopBegin());
        if (mainExit != null) {
            adaptCountedLoopExitProbability(mainExit, frequencyBefore - 1D);
        }
        return inside;
    }

    @SuppressWarnings("try")
    public static void fullUnroll(Loop loop, CoreProviders context, CanonicalizerPhase canonicalizer) {
        // assert loop.isCounted(); //TODO (gd) strengthen : counted with known trip count
        LoopBeginNode loopBegin = loop.loopBegin();
        StructuredGraph graph = loopBegin.graph();
        int initialNodeCount = graph.getNodeCount();
        SimplifierTool defaultSimplifier = GraphUtil.getDefaultSimplifier(context, canonicalizer.getCanonicalizeReads(), graph.getAssumptions(), graph.getOptions());
        /*
         * IMPORTANT: Canonicalizations inside the body of the remaining loop can introduce new
         * control flow that is not automatically picked up by the control flow graph computation of
         * the original LoopEx data structure, thus we disable simplification and manually simplify
         * conditions in the peeled iteration to simplify the exit path.
         */
        CanonicalizerPhase c = canonicalizer.copyWithoutSimplification();
        EconomicSetNodeEventListener l = new EconomicSetNodeEventListener();
        int peelings = 0;
        try (NodeEventScope ev = graph.trackNodeEvents(l)) {
            while (!loopBegin.isDeleted()) {
                Mark newNodes = graph.getMark();
                /*
                 * Mark is not enough for the canonicalization of the floating nodes in the unrolled
                 * code since pre-existing constants are not new nodes. Therefore, we canonicalize
                 * (without simplification) all floating nodes changed during peeling but only
                 * simplify new (in the peeled iteration) ones.
                 */
                EconomicSetNodeEventListener peeledListener = new EconomicSetNodeEventListener();
                try (NodeEventScope peeledScope = graph.trackNodeEvents(peeledListener)) {
                    LoopTransformations.peel(loop);
                }
                c.applyIncremental(graph, context, peeledListener.getNodes());
                loop.invalidateFragmentsAndIVs();
                for (Node n : graph.getNewNodes(newNodes)) {
                    if (n.isAlive() && (n instanceof IfNode || n instanceof SwitchNode || n instanceof FixedGuardNode || n instanceof BeginNode)) {
                        Simplifiable s = (Simplifiable) n;
                        s.simplify(defaultSimplifier);
                        graph.getOptimizationLog().report(LoopTransformations.class, "LoopFullUnrollCfgSimplification", n);
                    }
                }
                if (graph.getNodeCount() > initialNodeCount + MaximumDesiredSize.getValue(graph.getOptions()) * 2 ||
                                peelings > DefaultLoopPolicies.Options.FullUnrollMaxIterations.getValue(graph.getOptions())) {
                    throw new RetryableBailoutException("FullUnroll : Graph seems to grow out of proportion");
                }
                peelings++;
            }
        }
        // Canonicalize with the original canonicalizer to capture all simplifications
        canonicalizer.applyIncremental(graph, context, l.getNodes());
        loop.loopBegin().graph().getOptimizationLog().report(LoopTransformations.class, "LoopFullUnroll", loop.loopBegin());
    }

    public static void unswitch(Loop loop, List<ControlSplitNode> controlSplitNodeSet, boolean isTrivialUnswitch) {
        final ControlSplitNode firstNode = controlSplitNodeSet.iterator().next();
        final StructuredGraph graph = firstNode.graph();

        graph.getDebug().dump(DebugContext.VERBOSE_LEVEL, graph, "Before unswitching %s", controlSplitNodeSet);

        LoopFragmentWhole originalLoop = loop.whole();

        if (!isTrivialUnswitch) {
            loop.loopBegin().incrementUnswitches();
        }

        // create new control split out of loop
        ControlSplitNode newControlSplit = (ControlSplitNode) firstNode.copyWithInputs();
        originalLoop.entryPoint().replaceAtPredecessor(newControlSplit);

        /*
         * The code below assumes that all of the control split nodes have the same successor
         * structure, which should have been enforced by findUnswitchable.
         */
        Iterator<Position> successors = firstNode.successorPositions().iterator();
        assert successors.hasNext();
        // original loop is used as first successor
        Position firstPosition = successors.next();
        AbstractBeginNode originalLoopBegin = BeginNode.begin(originalLoop.entryPoint());
        firstPosition.set(newControlSplit, originalLoopBegin);
        originalLoopBegin.setNodeSourcePosition(firstPosition.get(firstNode).getNodeSourcePosition());

        while (successors.hasNext()) {
            Position position = successors.next();
            // create a new loop duplicate and connect it.
            LoopFragmentWhole duplicateLoop = originalLoop.duplicate();
            AbstractBeginNode newBegin = BeginNode.begin(duplicateLoop.entryPoint());
            newBegin.setNodeSourcePosition(position.get(firstNode).getNodeSourcePosition());
            position.set(newControlSplit, newBegin);

            // For each cloned ControlSplitNode, simplify the proper path
            for (ControlSplitNode controlSplitNode : controlSplitNodeSet) {
                ControlSplitNode duplicatedControlSplit = duplicateLoop.getDuplicatedNode(controlSplitNode);
                if (duplicatedControlSplit.isAlive()) {
                    AbstractBeginNode survivingSuccessor = (AbstractBeginNode) position.get(duplicatedControlSplit);
                    survivingSuccessor.replaceAtUsages(newBegin, InputType.Guard);
                    graph.removeSplitPropagate(duplicatedControlSplit, survivingSuccessor);
                }
            }
        }
        // original loop is simplified last to avoid deleting controlSplitNode too early
        for (ControlSplitNode controlSplitNode : controlSplitNodeSet) {
            if (controlSplitNode.isAlive()) {
                AbstractBeginNode survivingSuccessor = (AbstractBeginNode) firstPosition.get(controlSplitNode);
                survivingSuccessor.replaceAtUsages(originalLoopBegin, InputType.Guard);
                graph.removeSplitPropagate(controlSplitNode, survivingSuccessor);
            }
        }

        // TODO (gd) probabilities need some amount of fixup.. (probably also in other transforms)
        loop.loopBegin().graph().getOptimizationLog().withProperty("unswitches", loop.loopBegin().unswitches()).report(LoopTransformations.class, "LoopUnswitching", loop.loopBegin());
    }

    public static void partialUnroll(Loop loop, EconomicMap<LoopBeginNode, OpaqueNode> opaqueUnrolledStrides) {
        assert loop.loopBegin().isMainLoop();
        adaptCountedLoopExitProbability(loop.counted().getCountedExit(), loop.localLoopFrequency() / 2D);
        LoopFragmentInside newSegment = loop.inside().duplicate();
        newSegment.insertWithinAfter(loop, opaqueUnrolledStrides);
        loop.loopBegin().graph().getOptimizationLog().withProperty("unrollFactor", loop.loopBegin().getUnrollFactor()).report(LoopTransformations.class, "LoopPartialUnroll", loop.loopBegin());
    }

    /**
     * Create unique framestates for the loop exits of this loop: unique states ensure that virtual
     * instance nodes of this framestate are not shared with other framestates.
     *
     * Loop exit states and virtual object state inputs: The loop exit state can have a (transitive)
     * virtual object state input that is shared with other states outside the loop. Without a
     * dedicated state (with virtual object state inputs) for the loop exit state we can no longer
     * answer the question what is inside the loop (which virtual object state) and which is outside
     * given that we create new loop exits after existing ones. Thus, we create a dedicated state
     * for the exit that can later be duplicated cleanly.
     */
    public static void ensureExitsHaveUniqueStates(Loop loop) {
        if (loop.loopBegin().graph().getGuardsStage().areFrameStatesAtDeopts()) {
            return;
        }
        for (LoopExitNode lex : loop.loopBegin().loopExits()) {
            FrameState oldState = lex.stateAfter();
            lex.setStateAfter(lex.stateAfter().duplicateWithVirtualState());
            if (oldState.hasNoUsages()) {
                GraphUtil.killWithUnusedFloatingInputs(oldState);
            }
        }
        loop.invalidateFragmentsAndIVs();
    }

    // This function splits candidate loops into pre, main and post loops,
    // dividing the iteration space to facilitate the majority of iterations
    // being executed in a main loop, which will have RCE implemented upon it.
    // The initial loop form is constrained to single entry/exit, but can have
    // flow. The translation looks like:
    //
    //  @formatter:off
    //
    //       (Simple Loop entry)                   (Pre Loop Entry)
    //                |                                  |
    //         (LoopBeginNode)                    (LoopBeginNode)
    //                |                                  |
    //       (Loop Control Test)<------   ==>  (Loop control Test)<------
    //         /               \       \         /               \       \
    //    (Loop Exit)      (Loop Body) |    (Loop Exit)      (Loop Body) |
    //        |                |       |        |                |       |
    // (continue code)     (Loop End)  |  if (M < length)*   (Loop End)  |
    //                         \       /       /      \           \      /
    //                          ----->        /       |            ----->
    //                                       /  if ( ... )*
    //                                      /     /       \
    //                                     /     /         \
    //                                    /     /           \
    //                                   |     /     (Main Loop Entry)
    //                                   |    |             |
    //                                   |    |      (LoopBeginNode)
    //                                   |    |             |
    //                                   |    |     (Loop Control Test)<------
    //                                   |    |      /               \        \
    //                                   |    |  (Loop Exit)      (Loop Body) |
    //                                    \   \      |                |       |
    //                                     \   \     |            (Loop End)  |
    //                                      \   \    |                \       /
    //                                       \   \   |                 ------>
    //                                        \   \  |
    //                                      (Main Loop Merge)*
    //                                               |
    //                                      (Post Loop Entry)
    //                                               |
    //                                        (LoopBeginNode)
    //                                               |
    //                                       (Loop Control Test)<-----
    //                                        /               \       \
    //                                    (Loop Exit)     (Loop Body) |
    //                                        |               |       |
    //                                 (continue code)    (Loop End)  |
    //                                                         \      /
    //                                                          ----->
    //
    // Key: "*" = optional.
    // @formatter:on
    //
    // The value "M" is the maximal value of the loop trip for the original
    // loop. The value of "length" is applicable to the number of arrays found
    // in the loop but is reduced if some or all of the arrays are known to be
    // the same length as "M". The maximum number of tests can be equal to the
    // number of arrays in the loop, where multiple instances of an array are
    // subsumed into a single test for that arrays length.
    //
    // If the optional main loop entry tests are absent, the Pre Loop exit
    // connects to the Main loops entry and there is no merge hanging off the
    // main loops exit to converge flow from said tests. All split use data
    // flow is mitigated through phi(s) in the main merge if present and
    // passed through the main and post loop phi(s) from the originating pre
    // loop with final phi(s) and data flow patched to the "continue code".
    // The pre loop is constrained to one iteration for now and will likely
    // be updated to produce vector alignment if applicable.
    public static PreMainPostResult insertPrePostLoops(Loop loop) {
        assert loop.loopBegin().loopExits().isEmpty() || loop.loopBegin().graph().isAfterStage(StageFlag.VALUE_PROXY_REMOVAL) ||
                        loop.counted().getCountedExit() instanceof LoopExitNode : "Can only unroll loops, if they have exits, if the counted exit is a regular loop exit " + loop;
        StructuredGraph graph = loop.loopBegin().graph();

        // prepare clean exit states
        ensureExitsHaveUniqueStates(loop);

        graph.getDebug().log("LoopTransformations.insertPrePostLoops %s", loop);

        LoopFragmentWhole preLoop = loop.whole();
        CountedLoopInfo preCounted = loop.counted();
        LoopBeginNode preLoopBegin = loop.loopBegin();
        /*
         * When transforming counted loops with multiple loop exits the counted exit is the one that
         * is interesting for the pre-main-post transformation since it is the regular, non-early,
         * exit.
         */
        final AbstractBeginNode preLoopExitNode = preCounted.getCountedExit();

        assert preLoop.nodes().contains(preLoopBegin);
        assert preLoop.nodes().contains(preLoopExitNode);

        /*
         * Duplicate the original loop two times, each duplication will create a merge for the loop
         * exits of the original loop and the duplication one.
         */
        LoopFragmentWhole mainLoop = preLoop.duplicate();
        LoopBeginNode mainLoopBegin = mainLoop.getDuplicatedNode(preLoopBegin);
        AbstractBeginNode mainLoopExitNode = mainLoop.getDuplicatedNode(preLoopExitNode);
        EndNode mainEndNode = getBlockEndAfterLoopExit(mainLoopExitNode);
        AbstractMergeNode mainMergeNode = mainEndNode.merge();
        graph.getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, graph, "After  duplication of main loop %s", mainLoop);

        LoopFragmentWhole postLoop = preLoop.duplicate();
        LoopBeginNode postLoopBegin = postLoop.getDuplicatedNode(preLoopBegin);
        AbstractBeginNode postLoopExitNode = postLoop.getDuplicatedNode(preLoopExitNode);
        EndNode postEndNode = getBlockEndAfterLoopExit(postLoopExitNode);
        AbstractMergeNode postMergeNode = postEndNode.merge();
        graph.getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, graph, "After post loop duplication");

        preLoopBegin.setPreLoop();
        mainLoopBegin.setMainLoop();
        postLoopBegin.setPostLoop();

        if (graph.isBeforeStage(StageFlag.VALUE_PROXY_REMOVAL)) {
            // clear state to avoid problems with usages on the merge
            cleanupAndDeleteState(mainMergeNode);
            cleanupPostDominatingValues(mainLoopBegin, mainMergeNode, postEndNode);
            removeStateAndPhis(postMergeNode);
            /*
             * Fix the framestates for the pre loop exit node and the main loop exit node.
             *
             * The only exit that actually really exits the original loop is the loop exit of the
             * post-loop. All other paths have to fully go through pre->main->post loops. We can
             * never go from pre/main loop directly to the code after the loop, we always have to go
             * through the original loop header, thus we need to fix the correct state on the
             * pre/main loop exit.
             *
             * However, depending on the shape of the loop this is either
             *
             * for head counted loops: the loop header state with the values fixed
             *
             * for tail counted loops: the last state inside the body of the loop dominating the
             * tail check (This is different since tail counted loops have protection control flow
             * meaning it is possible to go pre -> after post, pre->main->after post, pre -> post ->
             * after post. For the protected main and post loops it is enough to deopt to the last
             * body state and the interpreter can then re-execute any failing counter check).
             *
             * For both scenarios we proxy the necessary nodes.
             */
            createExitState(preLoopBegin, (LoopExitNode) preLoopExitNode, loop.counted().isInverted(), preLoop);
            createExitState(mainLoopBegin, (LoopExitNode) mainLoopExitNode, loop.counted().isInverted(), mainLoop);
        }

        assert graph.isAfterStage(StageFlag.VALUE_PROXY_REMOVAL) || preLoopExitNode instanceof LoopExitNode : "Unrolling with proxies requires actual loop exit nodes as counted exits";
        rewirePreToMainPhis(preLoopBegin, mainLoop, preLoop, graph.isBeforeStage(StageFlag.VALUE_PROXY_REMOVAL) ? (LoopExitNode) preLoopExitNode : null, loop.counted().isInverted());

        AbstractEndNode postEntryNode = postLoopBegin.forwardEnd();
        // Exits have been merged, find the continuation below the merge
        FixedNode continuationNode = mainMergeNode.next();

        // In the case of no Bounds tests, we just flow right into the main loop
        AbstractBeginNode mainLandingNode = BeginNode.begin(postEntryNode);
        mainLoopExitNode.setNext(mainLandingNode);
        preLoopExitNode.setNext(mainLoopBegin.forwardEnd());

        // Add and update any phi edges as per merge usage as needed and update usages
        assert graph.isAfterStage(StageFlag.VALUE_PROXY_REMOVAL) ||
                        mainLoopExitNode instanceof LoopExitNode : "Unrolling with proxies requires actual loop exit nodes as counted exits";
        processPreLoopPhis(loop, graph.isBeforeStage(StageFlag.VALUE_PROXY_REMOVAL) ? (LoopExitNode) mainLoopExitNode : null, mainLoop, postLoop);
        graph.getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, graph, "After processing pre loop phis");

        continuationNode.predecessor().clearSuccessors();
        postLoopExitNode.setNext(continuationNode);
        cleanupMerge(postMergeNode, postLoopExitNode);
        cleanupMerge(mainMergeNode, mainLandingNode);

        // Change the preLoop to execute one iteration for now
        if (graph.isBeforeStage(StageFlag.VALUE_PROXY_REMOVAL)) {
            /*
             * The pre-loop exit's condition's induction variable start node might be already
             * re-written to be a phi of merged loop exits from a previous pre-main-post creation,
             * thus use an updated loop info.
             */
            loop.resetCounted();
            loop.detectCounted();
            updatePreLoopLimit(loop.counted());
        } else {
            updatePreLoopLimit(preCounted);
        }

        graph.getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, graph, "After updating preloop limit");

        double originalFrequency = loop.localLoopFrequency();
        preLoopBegin.setLoopOrigFrequency(originalFrequency);
        mainLoopBegin.setLoopOrigFrequency(originalFrequency);
        postLoopBegin.setLoopOrigFrequency(originalFrequency);

        assert preLoopExitNode.predecessor() instanceof IfNode : Assertions.errorMessage(preLoopExitNode);
        assert mainLoopExitNode.predecessor() instanceof IfNode : Assertions.errorMessage(mainLoopExitNode);
        assert postLoopExitNode.predecessor() instanceof IfNode : Assertions.errorMessage(postLoopExitNode);

        /*
         * The bodies of pre and post loops are assumed to be executed just once. As the local loop
         * frequency is calculated from the loop exit probabilities, it has to be taken into account
         * how often the exit check is performed. If the loop is inverted, i.e., tail-counted, the
         * exit will be taken at the end of the first body execution. Thus, the frequency of the
         * exit check is 1. If the loop is head-counted, the exit check will be performed twice,
         * which is reflected by a frequency of 2. This results in the correct relative frequency
         * being propagated into the loop body.
         */
        final int prePostFrequency = loop.counted().isInverted() ? 1 : 2;
        adaptCountedLoopExitProbability(preLoopExitNode, prePostFrequency);
        adaptCountedLoopExitProbability(postLoopExitNode, prePostFrequency);

        if (graph.isAfterStage(StageFlag.VALUE_PROXY_REMOVAL)) {
            // The pre and post loops don't require safepoints at all
            for (SafepointNode safepoint : preLoop.nodes().filter(SafepointNode.class)) {
                graph.removeFixed(safepoint);
            }
            for (SafepointNode safepoint : postLoop.nodes().filter(SafepointNode.class)) {
                graph.removeFixed(safepoint);
            }
        }
        graph.getOptimizationLog().report(LoopTransformations.class, "PreMainPostInsertion", loop.loopBegin());

        return new PreMainPostResult(preLoopBegin, mainLoopBegin, postLoopBegin, preLoop, mainLoop, postLoop);
    }

    /**
     * Inject a split probability for the (counted) loop check that will result in a loop frequency
     * of 1 (in case this is the only loop exit). This implies that the loop body is expected to be
     * never entered.
     */
    private static void setSingleVisitedLoopFrequencySplitProbability(AbstractBeginNode lex) {
        IfNode ifNode = ((IfNode) lex.predecessor());
        boolean trueSucc = ifNode.trueSuccessor() == lex;
        ifNode.setTrueSuccessorProbability(BranchProbabilityData.injected(0.01, trueSucc));
    }

    /**
     * Inject a new branch probability for the condition dominating the given loop exit path. This
     * probability is based on the local frequency of the exit check. This calculation will act as
     * if the given loop exit is the only exit of the loop.
     */
    public static void adaptCountedLoopExitProbability(AbstractBeginNode lex, double newExitCheckFrequency) {
        invalidateCFGFrequencies(lex.graph().getLastCFG());
        double probability = 1.0D - 1.0D / newExitCheckFrequency;
        if (probability <= 0D) {
            setSingleVisitedLoopFrequencySplitProbability(lex);
            return;
        }
        IfNode ifNode = ((IfNode) lex.predecessor());
        boolean trueSucc = ifNode.trueSuccessor() == lex;
        ifNode.setTrueSuccessorProbability(BranchProbabilityData.injected(probability, trueSucc));
    }

    private static void invalidateCFGFrequencies(ControlFlowGraph cfg) {
        if (cfg != null) {
            cfg.invalidateFrequencies();
        }
    }

    public static class PreMainPostResult {
        private final LoopBeginNode preLoop;
        private final LoopBeginNode mainLoop;
        private final LoopBeginNode postLoop;

        private final LoopFragment preLoopFragment;
        private final LoopFragment mainLoopFragment;
        private final LoopFragment postLoopFragment;

        public PreMainPostResult(LoopBeginNode preLoop, LoopBeginNode mainLoop, LoopBeginNode postLoop, LoopFragment preLoopFragment, LoopFragment mainLoopFragment, LoopFragment postLoopFragment) {
            this.preLoop = preLoop;
            this.mainLoop = mainLoop;
            this.postLoop = postLoop;
            this.preLoopFragment = preLoopFragment;
            this.mainLoopFragment = mainLoopFragment;
            this.postLoopFragment = postLoopFragment;
        }

        public LoopFragment getPreLoopFragment() {
            return preLoopFragment;
        }

        public LoopFragment getMainLoopFragment() {
            return mainLoopFragment;
        }

        public LoopFragment getPostLoopFragment() {
            return postLoopFragment;
        }

        public LoopBeginNode getMainLoop() {
            return mainLoop;
        }

        public LoopBeginNode getPostLoop() {
            return postLoop;
        }

        public LoopBeginNode getPreLoop() {
            return preLoop;
        }
    }

    private static void cleanupPostDominatingValues(LoopBeginNode mainLoopBegin, AbstractMergeNode mainMergeNode, AbstractEndNode postEndNode) {
        /*
         * duplicating with loop proxies will create phis for all proxies on the newly introduced
         * merges, however after introducing the pre-main-post scheme all original usages outside of
         * the loop will go through the post loop, so we rewrite the new phis created and replace
         * all phis created on the merges after with the value proxies of the final(post) loop
         */
        for (LoopExitNode exit : mainLoopBegin.loopExits()) {
            for (ProxyNode proxy : exit.proxies()) {
                for (Node usage : proxy.usages().snapshot()) {
                    if (usage instanceof PhiNode && ((PhiNode) usage).merge() == mainMergeNode) {
                        assert usage instanceof PhiNode : Assertions.errorMessage(usage);
                        // replace with the post loop proxy
                        PhiNode pUsage = (PhiNode) usage;
                        // get the other input phi at pre loop end
                        Node v = pUsage.valueAt(0);
                        assert v instanceof PhiNode : Assertions.errorMessage(v);
                        PhiNode vP = (PhiNode) v;
                        usage.replaceAtUsages(vP.valueAt(postEndNode));
                        usage.safeDelete();
                    }
                }
            }
        }
        mainLoopBegin.graph().getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, mainLoopBegin.graph(), "After fixing post dominating proxy usages");
    }

    private static void rewirePreToMainPhis(LoopBeginNode preLoopBegin, LoopFragment mainLoop, LoopFragment preLoop, LoopExitNode preLoopCountedExit, boolean inverted) {
        // Update the main loop phi initialization to carry from the pre loop, use a snapshot
        // because guard prox nodes can reference the loop begin and change usage lists
        for (PhiNode prePhiNode : preLoopBegin.phis().snapshot()) {
            PhiNode mainPhiNode = mainLoop.getDuplicatedNode(prePhiNode);
            rewirePhi(prePhiNode, mainPhiNode, preLoopCountedExit, preLoop, inverted);
        }
        preLoopBegin.graph().getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, preLoopBegin.graph(), "After updating value flow from pre loop phi to main loop phi");
    }

    private static void cleanupAndDeleteState(StateSplit statesplit) {
        FrameState fs = statesplit.stateAfter();
        statesplit.setStateAfter(null);
        GraphUtil.killWithUnusedFloatingInputs(fs);
    }

    private static void removeStateAndPhis(AbstractMergeNode merge) {
        cleanupAndDeleteState(merge);
        for (PhiNode phi : merge.phis().snapshot()) {
            phi.safeDelete();
        }
        merge.graph().getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, merge.graph(), "After deleting unused phis");
    }

    private static void createExitState(LoopBeginNode begin, LoopExitNode lex, boolean inverted, LoopFragment loop) {
        FrameState stateToUse;
        if (inverted) {
            stateToUse = GraphUtil.findLastFrameState((FixedNode) lex.predecessor()).duplicateWithVirtualState();
        } else {
            stateToUse = begin.stateAfter().duplicateWithVirtualState();
        }
        stateToUse.applyToNonVirtual(new NodePositionClosure<>() {
            @Override
            public void apply(Node from, Position p) {
                final ValueNode toProxy = (ValueNode) p.get(from);
                if (toProxy instanceof VirtualObjectNode) {
                    /*
                     * VirtualObjectNodes: though they are leaf nodes they are considered to be
                     * inside a loop for duplication purposes of loop optimizations. However, we do
                     * not need/must proxy them: see LoopFragement::computeNodes for details.
                     */
                    return;
                }
                Node replacement;
                // we are reasoning about a framestate here, it can only ever have
                // InputType.Value inputs.
                if (loop.contains(toProxy)) {
                    replacement = lex.graph().addOrUnique(new ValueProxyNode(toProxy, lex));
                } else {
                    replacement = toProxy;
                }
                p.set(from, replacement);
            }
        });
        lex.setStateAfter(stateToUse);
        begin.graph().getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, begin.graph(), "After proxy-ing phis for exit state");
    }

    /**
     * Cleanup the merge and remove the predecessors too.
     */
    private static void cleanupMerge(AbstractMergeNode mergeNode, AbstractBeginNode landingNode) {
        for (EndNode end : mergeNode.cfgPredecessors().snapshot()) {
            mergeNode.removeEnd(end);
            end.safeDelete();
        }
        mergeNode.getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, mergeNode.graph(), "After cleaning up merge %s", mergeNode);
        mergeNode.prepareDelete(landingNode);
        mergeNode.safeDelete();
    }

    private static void rewirePhi(PhiNode currentPhi, PhiNode outGoingPhi, LoopExitNode exitToProxy, LoopFragment loopToProxy, boolean inverted) {
        if (currentPhi.graph().isBeforeStage(StageFlag.VALUE_PROXY_REMOVAL)) {
            ValueNode set = null;
            ValueNode toProxy = inverted ? currentPhi.singleBackValueOrThis() : currentPhi;
            set = toProxy;
            if (toProxy == null) {
                GraalError.guarantee(currentPhi instanceof GuardPhiNode, "Only guard phi nodes can have null inputs %s", currentPhi);
            } else if (loopToProxy.contains(toProxy)) {
                set = LoopFragmentInside.patchProxyAtPhi(currentPhi, exitToProxy, toProxy);
                assert set != null;
            }
            outGoingPhi.setValueAt(0, set);
        } else {
            outGoingPhi.setValueAt(0, currentPhi);
        }
    }

    private static void processPreLoopPhis(Loop preLoop, LoopExitNode mainLoopCountedExit, LoopFragmentWhole mainLoop, LoopFragmentWhole postLoop) {
        /*
         * Re-route values from the main loop to the post loop
         */
        LoopBeginNode preLoopBegin = preLoop.loopBegin();
        StructuredGraph graph = preLoopBegin.graph();
        for (PhiNode prePhiNode : preLoopBegin.phis().snapshot()) {
            PhiNode postPhiNode = postLoop.getDuplicatedNode(prePhiNode);
            PhiNode mainPhiNode = mainLoop.getDuplicatedNode(prePhiNode);

            rewirePhi(mainPhiNode, postPhiNode, mainLoopCountedExit, mainLoop, preLoop.counted().isInverted());

            /*
             * Update all usages of the pre phi node below the original loop with the post phi
             * nodes, these are already properly proxied if we have loop proxies
             */
            if (graph.isAfterStage(StageFlag.VALUE_PROXY_REMOVAL)) {
                for (Node usage : prePhiNode.usages().snapshot()) {
                    if (usage == mainPhiNode) {
                        continue;
                    }
                    if (preLoop.isOutsideLoop(usage)) {
                        usage.replaceFirstInput(prePhiNode, postPhiNode);
                    }
                }
                for (Node node : preLoop.inside().nodes()) {
                    for (Node externalUsage : node.usages().snapshot()) {
                        if (preLoop.isOutsideLoop(externalUsage)) {
                            Node postUsage = postLoop.getDuplicatedNode(node);
                            assert postUsage != null;
                            externalUsage.replaceFirstInput(node, postUsage);
                        }
                    }
                }
            }
        }
    }

    /**
     * Find the end of the block following the LoopExit.
     */
    private static EndNode getBlockEndAfterLoopExit(AbstractBeginNode exit) {
        FixedNode node = exit.next();
        // Find the last node after the exit blocks starts
        return getBlockEnd(node);
    }

    private static EndNode getBlockEnd(FixedNode node) {
        FixedNode curNode = node;
        while (curNode instanceof FixedWithNextNode) {
            curNode = ((FixedWithNextNode) curNode).next();
        }
        return (EndNode) curNode;
    }

    private static void updatePreLoopLimit(CountedLoopInfo preCounted) {
        // Update the pre loops limit test
        // Make new limit one iteration
        ValueNode newLimit = AddNode.add(preCounted.getBodyIVStart(), preCounted.getLimitCheckedIV().strideNode(), NodeView.DEFAULT);

        // Fetch the variable we are not replacing and configure the one we are
        ValueNode ub = preCounted.getLimit();
        IntegerHelper helper = preCounted.getCounterIntegerHelper();
        LogicNode entryCheck;
        if (preCounted.getDirection() == Direction.Up) {
            entryCheck = helper.createCompareNode(newLimit, ub, NodeView.DEFAULT);
        } else {
            entryCheck = helper.createCompareNode(ub, newLimit, NodeView.DEFAULT);
        }
        newLimit = ConditionalNode.create(entryCheck, newLimit, ub, NodeView.DEFAULT);
        // Re-wire the condition with the new limit
        CompareNode compareNode = (CompareNode) preCounted.getLimitTest().condition();
        compareNode.replaceFirstInput(ub, compareNode.graph().addOrUniqueWithInputs(newLimit));
    }

    /**
     * Find all unswichable control split nodes in the given loop. When multiple control split nodes
     * have the same invariant condition, group them together.
     *
     * @param loop search control split nodes in this loop.
     * @return the unswitchable control split nodes grouped by condition meaning that every control
     *         split node within the same inner list share the same condition (the key for the map).
     */
    public static EconomicMap<ValueNode, List<ControlSplitNode>> findUnswitchable(Loop loop) {
        EconomicMap<ValueNode, List<ControlSplitNode>> controls = EconomicMap.create(Equivalence.IDENTITY);
        for (IfNode ifNode : loop.whole().nodes().filter(IfNode.class)) {
            if (loop.isOutsideLoop(ifNode.condition())) {
                ValueNode invariantValue = ifNode.condition();
                List<ControlSplitNode> ifs = controls.get(invariantValue);
                if (ifs == null) {
                    ifs = new ArrayList<>();
                    controls.put(invariantValue, ifs);
                }
                ifs.add(ifNode);
            }
        }
        for (SwitchNode switchNode : loop.whole().nodes().filter(SwitchNode.class)) {
            if (switchNode.successors().count() > 1 && loop.isOutsideLoop(switchNode.value())) {
                ValueNode invariantValue = switchNode.value();
                List<ControlSplitNode> switchs = controls.get(invariantValue);
                if (switchs == null) {
                    switchs = new ArrayList<>();
                    switchs.add(switchNode);
                    controls.put(invariantValue, switchs);
                } else {
                    // The list is not empty because we always add a node when we create it and
                    // switch cannot match on boolean so we don't have to check before the cast.
                    if (((SwitchNode) switchs.get(0)).structureEquals(switchNode)) {
                        // Only collect switches which test the same values in the same order
                        switchs.add(switchNode);
                    }
                }
            }
        }

        return controls;
    }

    /**
     * Check for multiple usages of the loop condition. Partial unrolling will modify the condition
     * in place. Any other usages of the condition would therefore compute a different condition
     * than before. A shared loop condition indicates that the graph isn't properly optimized, so
     * don't bother with partial unrolling, especially if it would break things.
     */
    public static boolean countedLoopExitConditionHasMultipleUsages(Loop loop) {
        LogicNode condition = loop.counted().getLimitTest().condition();
        return condition.hasMoreThanOneUsage();
    }

    public static boolean strideAdditionOverflows(Loop loop) {
        final int bits = ((IntegerStamp) loop.counted().getLimitCheckedIV().valueNode().stamp(NodeView.DEFAULT)).getBits();
        long stride = loop.counted().getLimitCheckedIV().constantStride();
        try {
            LoopUtility.addExact(bits, stride, stride);
            return false;
        } catch (ArithmeticException ae) {
            return true;
        }
    }

    public static boolean isUnrollableLoop(Loop loop) {
        if (LoopUtility.excludeLoopFromOptimizer(loop)) {
            return false;
        }
        if (!loop.isCounted() || !loop.counted().getLimitCheckedIV().isConstantStride() || !loop.getCFGLoop().getChildren().isEmpty() || loop.loopBegin().loopEnds().count() != 1 ||
                        loop.loopBegin().loopExits().count() > 1 || loop.counted().isInverted()) {
            // loops without exits can be unrolled, inverted loops cannot be unrolled without
            // protecting their first iteration
            return false;
        }
        assert loop.counted().getDirection() != null;
        LoopBeginNode loopBegin = loop.loopBegin();
        LogicNode condition = loop.counted().getLimitTest().condition();
        if (!(condition instanceof CompareNode)) {
            return false;
        }
        if (((CompareNode) condition).condition() == CanonicalCondition.EQ) {
            condition.getDebug().log(DebugContext.VERBOSE_LEVEL, "isUnrollableLoop %s condition unsupported %s ", loopBegin, ((CompareNode) condition).condition());
            return false;
        }
        if (countedLoopExitConditionHasMultipleUsages(loop)) {
            return false;
        }
        if (strideAdditionOverflows(loop)) {
            condition.getDebug().log(DebugContext.VERBOSE_LEVEL, "isUnrollableLoop %s doubling the stride overflows %d", loopBegin, loop.counted().getLimitCheckedIV().constantStride());
            return false;
        }
        if (!loop.canDuplicateLoop()) {
            return false;
        }
        if (loopBegin.isMainLoop() || loopBegin.isSimpleLoop()) {
            // Flow-less loops to partial unroll for now. 3 blocks corresponds to an if that either
            // exits or continues the loop. There might be fixed and floating work within the loop
            // as well.
            if (loop.getCFGLoop().getBlocks().size() < 3) {
                return true;
            }
            condition.getDebug().log(DebugContext.VERBOSE_LEVEL, "isUnrollableLoop %s too large to unroll %s ", loopBegin, loop.getCFGLoop().getBlocks().size());
        }
        return false;
    }
}
