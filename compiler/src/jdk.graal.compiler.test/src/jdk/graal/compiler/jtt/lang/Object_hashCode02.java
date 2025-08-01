/*
 * Copyright (c) 2007, 2018, Oracle and/or its affiliates. All rights reserved.
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
/*
 */
package jdk.graal.compiler.jtt.lang;

import java.time.DayOfWeek;
import java.util.HashMap;

import org.junit.Test;

import jdk.graal.compiler.jtt.JTTTest;

public final class Object_hashCode02 extends JTTTest {

    public static final Object obj1 = new Object();
    public static final Object obj2 = DayOfWeek.FRIDAY;
    public static final Object obj3 = new HashMap<>(); // STABLE ITERATION ORDER: empty

    public static int test(int a) {
        if (a == 1) {
            return obj1.hashCode();
        }
        if (a == 2) {
            return obj2.hashCode();
        }
        return obj3.hashCode();
    }

    @Test
    public void run0() throws Throwable {
        runTest("test", 1);
    }

    @Test
    public void run1() throws Throwable {
        runTest("test", 2);
    }

    @Test
    public void run2() throws Throwable {
        runTest("test", 3);
    }
}
