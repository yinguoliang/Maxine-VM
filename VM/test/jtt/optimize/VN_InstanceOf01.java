/*
 * Copyright (c) 2009 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package jtt.optimize;

/*
 * Tests value numbering of instanceof operations.
 * @Harness: java
 * @Runs: 0=true; 1=true; 2=false
 */
public class VN_InstanceOf01 {
    static final Object object = new VN_InstanceOf01();

    public static boolean test(int arg) {
        if (arg == 0) {
            return foo1();
        }
        if (arg == 1) {
            return foo2();
        }
        if (arg == 2) {
            return foo3();
        }
        // do nothing
        return false;
    }

    private static boolean foo1() {
        boolean a = object instanceof VN_InstanceOf01;
        boolean b = object instanceof VN_InstanceOf01;
        return a | b;
    }

    private static boolean foo2() {
        Object obj = new VN_InstanceOf01();
        boolean a = obj instanceof VN_InstanceOf01;
        boolean b = obj instanceof VN_InstanceOf01;
        return a | b;
    }

    private static boolean foo3() {
        boolean a = null instanceof VN_InstanceOf01;
        boolean b = null instanceof VN_InstanceOf01;
        return a | b;
    }
}
