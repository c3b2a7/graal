/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.test.polyglot;

import static com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import static com.oracle.truffle.api.TruffleLanguage.Registration;
import static com.oracle.truffle.api.test.common.AbstractExecutableTestLanguage.evalTestLanguage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.Assert;
import org.junit.Test;

import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.test.common.AbstractExecutableTestLanguage;
import com.oracle.truffle.api.test.common.TestUtils;

public class GR43906Test {
    private static final String CHAR = "\u200b";
    private static final byte[] UTF_8 = CHAR.getBytes(StandardCharsets.UTF_8);
    public static final int CHUNK_SIZE = 10_000;
    private static final byte[] UTF_8_CHUNK = CHAR.repeat(CHUNK_SIZE).getBytes(StandardCharsets.UTF_8);
    private static final int COUNT = 1_000_000;

    @Registration
    static class LargeStringTestLanguage extends AbstractExecutableTestLanguage {

        @TruffleBoundary
        @Override
        protected Object execute(RootNode node, Env env, Object[] contextArguments, Object[] frameArguments) throws Exception {
            return CHAR.repeat(COUNT);
        }
    }

    private static final class DummyOutputStream extends OutputStream {
        private int i = 0;
        private int length = 0;

        @Override
        public void write(int b) {
            if (length >= COUNT) {
                Assert.assertEquals(System.getProperty("line.separator").charAt(i++), b);
            } else {
                Assert.assertEquals(UTF_8[i++], b);
                if (i == UTF_8.length) {
                    i = 0;
                    length++;
                }
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            int j = 0;
            while (j < len && i != 0) {
                write(b[off + j++]);
            }
            while (j + UTF_8_CHUNK.length < len) {
                Assert.assertTrue(Arrays.equals(b, j + off, j + off + UTF_8_CHUNK.length, UTF_8_CHUNK, 0, UTF_8_CHUNK.length));
                length += CHUNK_SIZE;
                j += UTF_8_CHUNK.length;
            }
            while (j < len) {
                write(b[off + j++]);
            }
        }
    }

    @Test
    public void testPrintLargeString() throws IOException {
        DummyOutputStream out = new DummyOutputStream();
        try (Context context = Context.newBuilder().out(out).build()) {
            evalTestLanguage(context, LargeStringTestLanguage.class, Source.newBuilder(TestUtils.getDefaultLanguageId(LargeStringTestLanguage.class), "", "").interactive(true).build());
        }
        Assert.assertEquals(COUNT, out.length);
    }
}
