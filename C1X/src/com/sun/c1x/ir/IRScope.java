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
package com.sun.c1x.ir;

import java.lang.reflect.*;

import com.sun.c1x.*;
import com.sun.c1x.util.*;
import com.sun.c1x.value.*;
import com.sun.cri.ci.*;
import com.sun.cri.ri.*;

/**
 * The {@code IRScope} class represents an inlining context in the compilation
 * of a method.
 *
 * @author Ben L. Titzer
 */
public class IRScope {

    public final IRScope caller;
    public final RiMethod method;
    public final int level;
    final int callerBCI;
    CiCodePos callerCodeSite;

    FrameState callerState;
    int numberOfLocks;

    int lockStackSize;

    BitMap storesInLoops;

    public IRScope(IRScope caller, int callerBCI, RiMethod method, int osrBCI) {
        this.caller = caller;
        this.callerBCI = callerBCI;
        this.method = method;
        this.level = caller == null ? 0 : 1 + caller.level;
    }

    /**
     * Sets the minimum number of locks that are necessary for this context.
     * @param size the number of locks required
     */
    public void setMinimumNumberOfLocks(int size) {
        if (size > numberOfLocks) {
            numberOfLocks = size;
        }
    }

    /**
     * Gets the number of locks in this IR scope.
     * @return the number of locks
     */
    public final int numberOfLocks() {
        return numberOfLocks;
    }

    /**
     * Gets the bytecode index of the callsite that called this method.
     * @return the call site's bytecode index
     */
    public final int callerBCI() {
        return callerBCI;
    }

    /**
     * Gets the value stack at the caller of this scope.
     * @return the value stack at the point of this call
     */
    public final FrameState callerState() {
        return callerState;
    }

    /**
     * Returns whether this IR scope is the top scope (i.e. has no caller).
     * @return {@code true} if this inlining scope has no parent
     */
    public final boolean isTopScope() {
        return caller == null;
    }

    /**
     * Gets the phi bitmap for this IR scope. The phi bitmap stores
     * whether a phi instruction is required for each local variable.
     * @return the phi bitmap for this IR scope
     */
    public final BitMap getStoresInLoops() {
        return storesInLoops;
    }

    /**
     * Sets the caller state for this IRScope.
     * @param callerState the new caller state
     */
    public final void setCallerState(FrameState callerState) {
        this.callerState = callerState;
    }

    public final void setStoresInLoops(BitMap storesInLoops) {
        this.storesInLoops = storesInLoops;
    }

    @Override
    public String toString() {
        if (caller == null) {
            return "root-scope: " + method;
        } else {
            return "inlined-scope: " + method + " [caller bci: " + callerBCI + "]";
        }
    }

    /**
     * Computes the size of the lock stack and saves it in a field of this scope.
     */
    public final void computeLockStackSize() {
        if (!C1XOptions.OptInlineExcept) {
            lockStackSize = 0;
            return;
        }
        // (ds) This calculation seems bogus to me. It's computing the stack depth of the closest caller
        // that has no exception handlers. If I understand how this value is used, I think the correct
        // thing to compute is the stack depth of the closest inlined call site not covered by an
        // exception handler.
        IRScope curScope = this;
        // Synchronized methods are implemented with a synthesized exception handler
        while (curScope != null && (curScope.method.exceptionHandlers().length > 0 || Modifier.isSynchronized(curScope.method.accessFlags()))) {
            curScope = curScope.caller;
        }
        lockStackSize = curScope == null ? 0 : curScope.callerState() == null ? 0 : curScope.callerState().stackSize();
    }

    /**
     * Gets the lock stack size. The method {@link #computeLockStackSize()} has to be called for this value to be valid.
     * @return the lock stack size.
     */
    public int lockStackSize() {
        assert lockStackSize >= 0;
        return lockStackSize;
    }

    public CiCodePos callerCodeSite() {
        if (caller != null && callerCodeSite == null) {
            callerCodeSite = caller.toCodeSite(callerBCI);
        }
        return callerCodeSite;
    }

    public CiCodePos toCodeSite(int bci) {
        return new CiCodePos(callerCodeSite(), method, bci);
    }
}
