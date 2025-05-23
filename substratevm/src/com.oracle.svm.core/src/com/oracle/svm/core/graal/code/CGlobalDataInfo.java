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
package com.oracle.svm.core.graal.code;

import java.util.function.Supplier;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platform.HOSTED_ONLY;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.BuildPhaseProvider.AfterHeapLayout;
import com.oracle.svm.core.BuildPhaseProvider.AfterHostedUniverse;
import com.oracle.svm.core.c.BoxedRelocatedPointer;
import com.oracle.svm.core.c.CGlobalDataImpl;
import com.oracle.svm.core.heap.UnknownPrimitiveField;
import com.oracle.svm.core.util.VMError;

public final class CGlobalDataInfo {
    /**
     * Image heap object storing the base address of CGlobalData memory using a relocation. Before
     * the image heap is set up, CGlobalData must be accessed via relocations in the code instead.
     */
    public static final BoxedRelocatedPointer CGLOBALDATA_RUNTIME_BASE_ADDRESS = new BoxedRelocatedPointer(CGlobalDataBasePointer.INSTANCE);

    private final CGlobalDataImpl<?> data;
    private final boolean isSymbolReference;

    @UnknownPrimitiveField(availability = AfterHostedUniverse.class) private boolean isGlobalSymbol;
    @UnknownPrimitiveField(availability = AfterHostedUniverse.class) private boolean isHiddenSymbol;
    @UnknownPrimitiveField(availability = AfterHeapLayout.class) private int offset = -1;
    @UnknownPrimitiveField(availability = AfterHeapLayout.class) private int size = -1;

    /** Cache until writing the image in case the {@link Supplier} is costly or has side-effects. */
    @Platforms(HOSTED_ONLY.class) private byte[] bytes;

    @Platforms(Platform.HOSTED_ONLY.class)
    public CGlobalDataInfo(CGlobalDataImpl<?> data) {
        assert data != null;
        this.data = data;
        this.isSymbolReference = (data.bytesSupplier == null && data.sizeSupplier == null);
        assert !this.isSymbolReference || data.symbolName != null;
    }

    public CGlobalDataImpl<?> getData() {
        return data;
    }

    @SuppressWarnings("hiding")
    public void assignOffset(int offset) {
        assert this.offset == -1 : "already initialized";
        assert offset >= 0;
        this.offset = offset;
    }

    @SuppressWarnings("hiding")
    public void assignSize(int size) {
        assert this.size == -1 : "already initialized";
        assert bytes == null || bytes.length == size;
        assert size >= 0;
        this.size = size;
    }

    @SuppressWarnings("hiding")
    public void assignBytes(byte[] bytes) {
        assert this.bytes == null : "already initialized";
        assert size == -1 || size == bytes.length;
        this.bytes = bytes;
    }

    public int getOffset() {
        VMError.guarantee(offset >= 0, "Offset has not been initialized");
        return offset;
    }

    public int getSize() {
        VMError.guarantee(size >= 0, "size has not been initialized");
        return size;
    }

    public void makeGlobalSymbol() {
        VMError.guarantee(!isSymbolReference && data.symbolName != null, "Cannot change the local/global status of a symbol reference");
        isGlobalSymbol = true;
    }

    public boolean isGlobalSymbol() {
        return isGlobalSymbol;
    }

    public void makeHiddenSymbol() {
        isHiddenSymbol = true;
    }

    public boolean isHiddenSymbol() {
        return isHiddenSymbol;
    }

    public boolean isSymbolReference() {
        return isSymbolReference;
    }

    public byte[] getBytes() {
        return bytes;
    }

    @Override
    public String toString() {
        return data.toString();
    }
}
