/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.graal.nodes;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.FrameAccess;
import com.oracle.svm.core.graal.code.CGlobalDataInfo;
import com.oracle.svm.core.graal.code.SubstrateNodeLIRBuilder;

import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeCycles;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodeinfo.NodeSize;
import jdk.graal.compiler.nodes.calc.FloatingNode;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;

/**
 * Directly load the address of {@code CGlobalData} memory.
 *
 * Only for use in AOT-compiled code because it uses relocations.
 */
@Platforms(Platform.HOSTED_ONLY.class)
@NodeInfo(cycles = NodeCycles.CYCLES_1, size = NodeSize.SIZE_1, sizeRationale = "same as LoadAddressNode")
public final class CGlobalDataLoadAddressNode extends FloatingNode implements LIRLowerable {
    public static final NodeClass<CGlobalDataLoadAddressNode> TYPE = NodeClass.create(CGlobalDataLoadAddressNode.class);

    private final CGlobalDataInfo dataInfo;

    public CGlobalDataLoadAddressNode(CGlobalDataInfo dataInfo) {
        super(TYPE, FrameAccess.getWordStamp());
        assert dataInfo != null;
        this.dataInfo = dataInfo;
    }

    public CGlobalDataInfo getDataInfo() {
        return dataInfo;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        ((SubstrateNodeLIRBuilder) gen).emitCGlobalDataLoadAddress(this);
    }
}
