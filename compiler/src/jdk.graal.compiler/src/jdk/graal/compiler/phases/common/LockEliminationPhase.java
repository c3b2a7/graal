/*
 * Copyright (c) 2011, 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.phases.common;

import java.util.Optional;

import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.GraphState;
import jdk.graal.compiler.nodes.GraphState.StageFlag;
import jdk.graal.compiler.nodes.GuardNode;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.ProxyNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.cfg.ControlFlowGraph;
import jdk.graal.compiler.nodes.cfg.HIRBlock;
import jdk.graal.compiler.nodes.extended.AnchoringNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.extended.OSRMonitorEnterNode;
import jdk.graal.compiler.nodes.java.AccessMonitorNode;
import jdk.graal.compiler.nodes.java.MonitorEnterNode;
import jdk.graal.compiler.nodes.java.MonitorExitNode;
import jdk.graal.compiler.nodes.java.MonitorIdNode;
import jdk.graal.compiler.nodes.memory.MemoryAnchorNode;
import jdk.graal.compiler.nodes.memory.MemoryKill;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.ValueProxy;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.phases.Phase;

public class LockEliminationPhase extends Phase {

    @Override
    public Optional<NotApplicable> notApplicableTo(GraphState graphState) {
        return NotApplicable.when(graphState.isAfterStage(StageFlag.FLOATING_READS) && graphState.isBeforeStage(StageFlag.FIXED_READS),
                        "This phase must not be applied while reads are floating");
    }

    @Override
    protected void run(StructuredGraph graph) {
        ControlFlowGraph cfg = ControlFlowGraph.newBuilder(graph).connectBlocks(true).computeLoops(true).computeDominators(true).computeFrequency(true).build();
        for (MonitorExitNode monitorExitNode : graph.getNodes(MonitorExitNode.TYPE)) {
            FixedNode next = monitorExitNode.next();
            if ((next instanceof MonitorEnterNode)) {
                // should never happen, osr monitor enters are always direct successors of the graph
                // start
                GraalError.guarantee(!(next instanceof OSRMonitorEnterNode), "OSRMonitorEnterNode can't be seen here: %s", next);
                AccessMonitorNode monitorEnterNode = (AccessMonitorNode) next;
                if (isCompatibleLock(monitorEnterNode, monitorExitNode, true, cfg)) {
                    /*
                     * We've coarsened the lock so use the same monitor id for the whole region,
                     * otherwise the monitor operations appear to be unrelated.
                     */
                    MonitorIdNode enterId = monitorEnterNode.getMonitorId();
                    MonitorIdNode exitId = monitorExitNode.getMonitorId();
                    if (enterId != exitId) {
                        enterId.replaceAndDelete(exitId);
                    }
                    GraphUtil.removeFixedWithUnusedInputs(monitorEnterNode);
                    GraphUtil.removeFixedWithUnusedInputs(monitorExitNode);
                    graph.getOptimizationLog().report(getClass(), "LockCoarsening", monitorEnterNode);
                }
            }
        }
    }

    /**
     * Check that the paired monitor operations operate on the same object at the same lock depth.
     * Additionally, ensure that any {@link PiNode} in between respect a dominance relation between
     * a and b. This is necessary to ensure any monitor rewiring respects a proper schedule.
     *
     * @param a The first {@link AccessMonitorNode}
     * @param b The first {@link AccessMonitorNode}
     * @param aDominatesB if {@code true} determine if a must dominate b (including any guarded
     *            {@link PiNode} in between to determine if a and b are compatible, else if
     *            {@code false} determine if b must dominate a
     *
     */
    public static boolean isCompatibleLock(AccessMonitorNode a, AccessMonitorNode b, boolean aDominatesB, ControlFlowGraph cfg) {
        /*
         * It is not always the case that sequential monitor operations on the same object have the
         * same lock depth: Escape analysis can have removed a lock operation that was in between,
         * leading to a mismatch in lock depth.
         */
        ValueNode objectA = GraphUtil.unproxify(a.object());
        ValueNode objectB = GraphUtil.unproxify(b.object());
        if (objectA == objectB && a.getMonitorId().getLockDepth() == b.getMonitorId().getLockDepth() &&
                        a.getMonitorId().isMultipleEntry() == b.getMonitorId().isMultipleEntry()) {
            /*
             * If the monitor operations operate on the same unproxified object, ensure any pi nodes
             * in the proxy chain are safe to re-order when moving monitor operations.
             */
            HIRBlock lowestBlockA = cfg != null ? lowestGuardedInputBlock(b, cfg) : null;
            HIRBlock lowestBlockB = null;
            /*
             * If the object nodes are the same and there is no object or data guard for one of the
             * monitor operations it can only mean that one of them did not have to skip any pi
             * nodes while the other did. We are safe then because the object node is the same (by
             * identity) and it has to dominate both monitor operations.
             */
            if (lowestBlockA != null) {
                lowestBlockB = lowestGuardedInputBlock(b, cfg);
            }
            if (lowestBlockA == null || lowestBlockB == null) {
                return true;
            }
            if (aDominatesB) {
                return lowestBlockA.dominates(lowestBlockB);
            } else {
                return lowestBlockB.dominates(lowestBlockA);
            }
        }
        return false;
    }

    /**
     * Get the lowest (by dominance relation) {@link HIRBlock} for the (potentially hidden behind
     * {@link ProxyNode}s) inputs of the {@link AccessMonitorNode}.
     */
    public static HIRBlock lowestGuardedInputBlock(AccessMonitorNode monitorNode, ControlFlowGraph cfg) {
        return lowestGuardedInputBlock(unproxifyHighestGuard(monitorNode.object()), unproxifyHighestGuard(monitorNode.getObjectData()), cfg);
    }

    public static HIRBlock lowestGuardedInputBlock(GuardingNode g1, GuardingNode g2, ControlFlowGraph cfg) {
        HIRBlock b1 = getGuardingBlock(g1, cfg);
        HIRBlock b2 = getGuardingBlock(g2, cfg);
        if (b1 == null) {
            return b2;
        }
        if (b2 == null) {
            return b1;
        }
        if (b1.dominates(b2)) {
            return b2;
        }
        return b1;
    }

    /**
     * Get the basic block of the {@link GuardingNode}. Handles fixed and floating guarded nodes.
     */
    public static HIRBlock getGuardingBlock(GuardingNode g1, ControlFlowGraph cfg) {
        HIRBlock b1 = null;
        if (g1 != null) {
            if (g1 instanceof FixedNode) {
                b1 = cfg.blockFor((Node) g1);
            } else if (g1 instanceof GuardNode) {
                AnchoringNode a = ((GuardNode) g1).getAnchor();
                if (a instanceof FixedNode) {
                    b1 = cfg.blockFor((FixedNode) a);
                }
            }
        }
        return b1;
    }

    /**
     * Get the highest (by input traversal) {@link GuardingNode} attached to any {@link ProxyNode}
     * visited in {@link GraphUtil#unproxify(ValueNode)}.
     */
    public static GuardingNode unproxifyHighestGuard(ValueNode value) {
        if (value != null) {
            ValueNode result = value;
            GuardingNode highestGuard = null;
            while (result instanceof ValueProxy) {
                GuardingNode curGuard = ((ValueProxy) result).getGuard();
                if (curGuard != null) {
                    highestGuard = curGuard;
                }
                result = ((ValueProxy) result).getOriginalNode();
            }
            return highestGuard;
        } else {
            return null;
        }
    }

    public static void removeMonitorAccess(AccessMonitorNode access) {
        GraalError.guarantee(!(access instanceof OSRMonitorEnterNode), "Must not remove OSR monitor enters");
        if (access.usages().isNotEmpty()) {
            boolean replaced = false;
            for (FixedNode pred : GraphUtil.predecessorIterable((FixedNode) access.predecessor())) {
                if (MemoryKill.isSingleMemoryKill(pred)) {
                    SingleMemoryKill single = (SingleMemoryKill) pred;
                    if (single.getKilledLocationIdentity().isAny()) {
                        // We do not need a memory anchor in this case.
                        access.replaceAtUsages(pred);
                        replaced = true;
                        break;
                    } else {
                        break;
                    }
                } else if (MemoryKill.isMultiMemoryKill(pred)) {
                    break;
                }
            }
            if (!replaced) {
                MemoryAnchorNode anchor = access.graph().add(new MemoryAnchorNode());
                anchor.setNodeSourcePosition(access.getNodeSourcePosition());
                access.replaceAtUsages(anchor);
                access.graph().addBeforeFixed(access, anchor);
            }
        }
        GraalError.guarantee(access.hasNoUsages(), "Node must not have usages %s", access);
        GraphUtil.removeFixedWithUnusedInputs(access);
    }

}
