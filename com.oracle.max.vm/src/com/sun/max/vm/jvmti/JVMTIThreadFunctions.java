/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.sun.max.vm.jvmti;

import static com.sun.max.vm.intrinsics.MaxineIntrinsicIDs.*;
import static com.sun.max.vm.jvmti.JVMTIConstants.*;

import java.lang.reflect.*;
import java.util.*;

import com.sun.max.annotate.*;
import com.sun.max.memory.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.classfile.*;
import com.sun.max.vm.compiler.target.*;
import com.sun.max.vm.jni.*;
import com.sun.max.vm.jvmti.JVMTIUtil.TypedData;
import com.sun.max.vm.reference.*;
import com.sun.max.vm.run.java.*;
import com.sun.max.vm.runtime.VmOperation;
import com.sun.max.vm.stack.*;
import com.sun.max.vm.thread.*;

/**
 * Support for the JVMTI functions related to {@link Thread}.
 */
class JVMTIThreadFunctions {
    private static ClassActor vmThreadClassActor;
    private static ClassActor jniFunctionsClassActor;
    private static ClassActor vmThreadMapClassActor;
    private static ClassActor methodClassActor;
    private static ClassActor jvmtiClassActor;
    private static ClassActor javaRunSchemeClassActor;

    private static ClassActor vmThreadClassActor() {
        if (vmThreadClassActor == null) {
            vmThreadClassActor = ClassActor.fromJava(VmThread.class);
        }
        return vmThreadClassActor;
    }

    private static ClassActor vmThreadMapClassActor() {
        if (vmThreadMapClassActor == null) {
            vmThreadMapClassActor = ClassActor.fromJava(VmThreadMap.class);
        }
        return vmThreadMapClassActor;
    }

    private static ClassActor jniFunctionsClassActor() {
        if (jniFunctionsClassActor == null) {
            jniFunctionsClassActor = ClassActor.fromJava(JniFunctions.class);
        }
        return jniFunctionsClassActor;
    }

    private static ClassActor methodClassActor() {
        if (methodClassActor == null) {
            methodClassActor = ClassActor.fromJava(Method.class);
        }
        return methodClassActor;
    }

    private static ClassActor jvmtiClassActor() {
        if (jvmtiClassActor == null) {
            jvmtiClassActor = ClassActor.fromJava(JVMTI.class);
        }
        return jvmtiClassActor;
    }

    private static ClassActor javaRunSchemeClassActor() {
        if (javaRunSchemeClassActor == null) {
            javaRunSchemeClassActor = ClassActor.fromJava(JavaRunScheme.class);
        }
        return javaRunSchemeClassActor;
    }

    static int getAllThreads(Pointer threadsCountPtr, Pointer threadsPtr) {
        final Thread[] threads = VmThreadMap.getThreads(true);
        threadsCountPtr.setInt(threads.length);
        Pointer threadCArray = Memory.allocate(Size.fromInt(threads.length * Word.size()));
        if (threadCArray.isZero()) {
            return JVMTI_ERROR_OUT_OF_MEMORY;
        }
        threadsPtr.setWord(threadCArray);
        for (int i = 0; i < threads.length; i++) {
            Thread thread = threads[i];
            threadCArray.setWord(i, JniHandles.createLocalHandle(thread));
        }
        return JVMTI_ERROR_NONE;
    }

    static int getThreadState(Thread thread, Pointer threadStatePtr) {
        threadStatePtr.setInt(getThreadState(VmThread.fromJava(thread)));
        return JVMTI_ERROR_NONE;
    }

    static int getThreadState(VmThread vmThread) {
        // TODO Maxine does not keep any state beyond Thread.State so the detailed JVMTI states are unavailable
        int state = 0;
        if (vmThread != null) {
            switch (vmThread.state()) {
                case TERMINATED:
                    state = JVMTI_THREAD_STATE_TERMINATED;
                    break;
                case NEW:
                    state = JVMTI_THREAD_STATE_ALIVE;
                    break;
                case RUNNABLE:
                    state = JVMTI_THREAD_STATE_ALIVE | JVMTI_THREAD_STATE_RUNNABLE;
                    break;
                case BLOCKED:
                    state = JVMTI_THREAD_STATE_ALIVE | JVMTI_THREAD_STATE_BLOCKED_ON_MONITOR_ENTER;
                    break;
                case WAITING:
                    state = JVMTI_THREAD_STATE_ALIVE | JVMTI_THREAD_STATE_WAITING | JVMTI_THREAD_STATE_WAITING_INDEFINITELY;
                    break;
                case TIMED_WAITING:
                    state = JVMTI_THREAD_STATE_ALIVE | JVMTI_THREAD_STATE_WAITING | JVMTI_THREAD_STATE_WAITING_WITH_TIMEOUT;
            }
        }
        return state;
    }

    static int getThreadInfo(Thread thread, Pointer threadInfoPtr) {
        if (thread == null) {
            thread = VmThread.current().javaThread();
        }
        setJVMTIThreadInfo(threadInfoPtr, CString.utf8FromJava(thread.getName()), thread.getPriority(), thread.isDaemon(),
                        JniHandles.createLocalHandle(thread.getThreadGroup()),
                        JniHandles.createLocalHandle(thread.getContextClassLoader()));
        return JVMTI_ERROR_NONE;
    }

    /**
     * Type too complex to handle in Java, delegate to native code.
     */
    @C_FUNCTION
    private static native void setJVMTIThreadInfo(Pointer threadInfoPtr, Pointer name, int priority, boolean isDaemon, JniHandle threadGroup, JniHandle contextClassLoader);

    // Stack trace handling

    // Single thread stack trace

    static int getStackTrace(Thread thread, int startDepth, int maxFrameCount, Pointer frameBuffer, Pointer countPtr) {
        VmThread vmThread = thread == null ? VmThread.current() : VmThread.fromJava(thread);
        FrameBufferStackTraceVisitor stackTraceVisitor = new FrameBufferStackTraceVisitor(vmThread, startDepth, maxFrameCount, frameBuffer);
        SingleThreadStackTraceVmOperation vmOperation = new SingleThreadStackTraceVmOperation(vmThread, stackTraceVisitor);
        vmOperation.submit();
        countPtr.setInt(stackTraceVisitor.frameBufferIndex);
        return JVMTI_ERROR_NONE;
    }

    private static class StackElement {
        ClassMethodActor classMethodActor;
        int bci;
        Pointer fp;

        StackElement(ClassMethodActor classMethodActor, int bci, Pointer fp) {
            this.classMethodActor = classMethodActor;
            this.fp = fp;
            this.bci = bci;
        }

    }

    /**
     * A stack visitor that analyses the stack, by which we mean
     * ignoring the frames that are on the stack because of the
     * way {@link VMOperation} brings a thread to a safepoint, and also ignores
     * "implementation" frames, i.e., VM frames, reflection stubs.
     *
     * The nature of the mechanisms for freezing threads in {@link VMOperation}
     * and entering native code, means that there are always VM frames on the
     * stack that we do not want to include. In addition because JVMTI is
     * implemented in Java, any calls from agents in response to JVMTI events
     * and callbacks will also have stacks containing VM frames. Plus the
     * base of every thread stack has VM frames from the thread startup.
     *
     * The stack walker visits the stack top down, but an accurate picture
     * requires a bottom up analysis. So in a first top down pass we build a list
     * of {@link StackElement} which is an approximation, then re-analyse it
     * bottom up. The resulting list is then easy to use to answer all the
     * JVMTI query variants. The process isn't allocation free.
     *
     * In the initial scan downwards all VM frames are dropped until a non-VM frame
     * is seen, then all frames are kept (except reflection stubs).
     */
    private static class FindAppFramesStackTraceVisitor extends SourceFrameVisitor {
        boolean seenNonVMFrame;
        LinkedList<StackElement> stackElements = new LinkedList<StackElement>();

        @Override
        public boolean visitSourceFrame(ClassMethodActor methodActor, int bci, boolean trapped, long frameId) {
            // "trapped" indicates the frame in the safepoint trap handler.
            // In other stack visitors in the VM this causes a reset but,
            // in this context, it is subsumed by the check for VM frames.
            ClassMethodActor classMethodActor = methodActor.original();
            // check for first non-VM frame
            if (seenNonVMFrame) {
                add(classMethodActor, bci);
            } else {
                if (!JVMTIClassFunctions.isVmClass(classMethodActor.holder())) {
                    seenNonVMFrame = true;
                    add(classMethodActor, bci);
                }
            }
            return true;
        }

        StackElement getStackElement(int depth) {
            return stackElements.get((stackElements.size() - 1) - depth);
        }

        private void add(ClassMethodActor classMethodActor, int bci) {
            if (!classMethodActor.holder().isReflectionStub()) {
                stackElements.addFirst(new StackElement(classMethodActor, bci, currentCursor.fp()));
            }
        }

        @Override
        public void walk(StackFrameWalker walker, Pointer ip, Pointer sp, Pointer fp) {
            super.walk(walker, ip, sp, fp);
            /*
             * Analysing the stack to remove the VM frames depends on knowledge
             * of the way that threads start up. The normal case is VmThread.run
             * calls VmThread.executeRunnable which calls Thread.run.
             * The unusual case is an attached native thread which starts with
             * JNIFunctions.CallStaticVoidMethodA. A special case is the
             * main thread which has a Method.invoke, since it is is called from
             * JavaRunScheme, unless it has returned but the VM hasn't terminated, in which
             * case it is in VmThreadMap.joinAllNonDaemons. This analysis gets the
             * correct base frame. We then scan upwards. If we hit another VM frame we throw everything
             * away after that. Reason being that the initial down scan may have hit a platform
             * class method used by the VM, but couldn't know there might be another VM class below,
             * so decided it was an app frame.
             *
             * TODO handle user-defined classloader callbacks, which have VM frames sandwiched
             * between app frames.
             */

            if (stackElements.size() == 0) {
                // some threads, e.g., SignalDispatcher, have only VM frames, so nothing to analyse.
                return;
            }

            int startIndex = -1;
            StackElement base = stackElements.getFirst();
            ClassActor classActor = base.classMethodActor.holder();
            if (classActor == vmThreadClassActor()) {
                base = stackElements.get(1);
                classActor = base.classMethodActor.holder();
                if (classActor == vmThreadClassActor) {
                    if (stackElements.get(2).classMethodActor.holder() == javaRunSchemeClassActor()) {
                        startIndex = 5;
                    } else {
                        startIndex = 2;
                    }
                } else if (classActor == vmThreadMapClassActor()) {
                    // main returned
                    stackElements = new LinkedList<StackElement>();
                    return;
                } else if (classActor == methodClassActor()) {
                    startIndex = 3;
                } else if (classActor == jvmtiClassActor()) {
                    startIndex = 2;
                }
            } else if (classActor == jniFunctionsClassActor()) {
                startIndex = 3;
            }

            if (startIndex < 0) {
                assert false : "unexpected thread stack layout";
            }

            for (int i = 0; i < startIndex; i++) {
                stackElements.remove();
            }
            int endIndex = 0;
            ListIterator<StackElement> iter = stackElements.listIterator();
            while (iter.hasNext()) {
                StackElement e = iter.next();
                classActor = e.classMethodActor.holder();
                if (JVMTIClassFunctions.isVmClass(classActor)) {
                    break;
                }
                endIndex++;
            }
            int lastIndex = stackElements.size();
            for (int i = endIndex; i < lastIndex; i++) {
                stackElements.remove();
            }
        }

    }

    /**
     * Visitor for copying portions of a stack to an agent provided buffer.
     * We do this as a visitor because it is used in single/multiple thread variants.
     */
    private static class FrameBufferStackTraceVisitor extends FindAppFramesStackTraceVisitor {
        int startDepth;       // first frame to record; > 0 => from top, < 0 from bottom
        int maxCount;         // max number of frames to record
        int frameBufferIndex; // in range 0 .. maxCount - 1
        Pointer frameBuffer;  // C struct for storing info
        VmThread vmThread;    // thread associated with this stack

        FrameBufferStackTraceVisitor(VmThread vmThread, int startDepth, int maxCount, Pointer frameBuffer) {
            this.startDepth = startDepth;
            this.maxCount = maxCount;
            this.frameBuffer = frameBuffer;
            this.vmThread = vmThread;
        }

        @Override
        public void walk(StackFrameWalker walker, Pointer ip, Pointer sp, Pointer fp) {
            super.walk(walker, ip, sp, fp);
            if (startDepth < 0) {
                startDepth = stackElements.size() + startDepth;
            }
            for (int i = 0; i < stackElements.size(); i++) {
                if (i >= startDepth) {
                    if (store(getStackElement(i))) {
                        break;
                    }
                }
            }
        }

        private boolean store(StackElement se) {
            setJVMTIFrameInfo(frameBuffer, frameBufferIndex, MethodID.fromMethodActor(se.classMethodActor), se.bci);
            frameBufferIndex++;
            if (frameBufferIndex >= maxCount) {
                return true;
            }
            return false;
        }
    }

    private static class SingleThreadStackTraceVmOperation extends VmOperation {
        FindAppFramesStackTraceVisitor stackTraceVisitor;

        /**
         * Create a {@link VmOperation} that runs the given {@link BaseStackTraceVisitor} on the given thread.
         * @param vmThread
         * @param stackTraceVisitor
         */
        SingleThreadStackTraceVmOperation(VmThread vmThread, FindAppFramesStackTraceVisitor stackTraceVisitor) {
            super("JVMTISingleStackTrace", vmThread, Mode.Safepoint);
            this.stackTraceVisitor = stackTraceVisitor;
        }

        @Override
        public void doThread(VmThread vmThread, Pointer ip, Pointer sp, Pointer fp) {
            stackTraceVisitor.walk(new VmStackFrameWalker(vmThread.tla()), ip, sp, fp);
        }
    }

    // Stack traces for all threads

    static int getAllStackTraces(int maxFrameCount, Pointer stackInfoPtrPtr, Pointer threadCountPtr) {
        return getThreadListStackTraces(VmThreadMap.getThreads(false), maxFrameCount, stackInfoPtrPtr, threadCountPtr);
    }

    static int getThreadListStackTraces(int threadCount, Pointer threadList, int maxFrameCount, Pointer stackInfoPtrPtr) {
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            try {
                Thread thread = (Thread) threadList.getWord(i).asJniHandle().unhand();
                threads[i] = thread;
            } catch (ClassCastException ex) {
                return JVMTI_ERROR_INVALID_THREAD;
            }
        }
        return getThreadListStackTraces(threads, maxFrameCount, stackInfoPtrPtr, Pointer.zero());
    }

    private static int getThreadListStackTraces(Thread[] threads, int maxFrameCount, Pointer stackInfoPtrPtr, Pointer threadCountPtr) {
        int threadCount = threads.length;
        // Have to preallocate all the memory in one contiguous chunk
        Pointer stackInfoArrayPtr = Memory.allocate(Size.fromInt(threadCount * (stackInfoSize() + maxFrameCount * FRAME_INFO_STRUCT_SIZE)));
        if (stackInfoArrayPtr.isZero()) {
            return JVMTI_ERROR_OUT_OF_MEMORY;
        }
        Pointer frameBuffersBasePtr = stackInfoArrayPtr.plus(threadCount * stackInfoSize());
        FrameBufferStackTraceVisitor[] stackTraceVisitors = new FrameBufferStackTraceVisitor[threadCount];
        for (int i = 0; i < threadCount; i++) {
            stackTraceVisitors[i] = new FrameBufferStackTraceVisitor(VmThread.fromJava(threads[i]), 0, maxFrameCount,
                            frameBuffersBasePtr.plus(i * maxFrameCount * FRAME_INFO_STRUCT_SIZE));
        }

        MultipleThreadStackTraceVmOperation vmOperation = new MultipleThreadStackTraceVmOperation(threads, stackTraceVisitors);
        vmOperation.submit();
        for (int i = 0; i < threadCount; i++) {
            FrameBufferStackTraceVisitor sv = stackTraceVisitors[i];
            setJVMTIStackInfo(stackInfoArrayPtr, i, JniHandles.createLocalHandle(sv.vmThread.javaThread()),
                            getThreadState(sv.vmThread), sv.frameBuffer, sv.frameBufferIndex);
        }
        stackInfoPtrPtr.setWord(stackInfoArrayPtr);
        if (!threadCountPtr.isZero()) {
            threadCountPtr.setInt(threadCount);
        }
        return JVMTI_ERROR_NONE;
    }

    private static class MultipleThreadStackTraceVmOperation extends VmOperation {
        FindAppFramesStackTraceVisitor[] stackTraceVisitors;
        Thread[] threads;

        MultipleThreadStackTraceVmOperation(Thread[] threads, FindAppFramesStackTraceVisitor[] stackTraceVisitors) {
            super("JVMTIMultipleStackTrace", null, Mode.Safepoint);
            this.threads = threads;
            this.stackTraceVisitors = stackTraceVisitors;
        }

        @Override
        protected boolean operateOnThread(VmThread vmThread) {
            for (int i = 0; i < threads.length; i++) {
                if (VmThread.fromJava(threads[i]) == vmThread) {
                    return true;
                }
            }
            return false;
        }

        private FindAppFramesStackTraceVisitor getStackTraceVisitor(VmThread vmThread) {
            for (int i = 0; i < threads.length; i++) {
                if (VmThread.fromJava(threads[i]) == vmThread) {
                    return stackTraceVisitors[i];
                }
            }
            assert false;
            return null;
        }

        @Override
        public void doThread(VmThread vmThread, Pointer ip, Pointer sp, Pointer fp) {
            getStackTraceVisitor(vmThread).walk(new VmStackFrameWalker(vmThread.tla()), ip, sp, fp);
        }

    }

    private static final int FRAME_INFO_STRUCT_SIZE = Word.size() * 2;

    private static int stackInfoSize;

    private static int stackInfoSize() {
        if (stackInfoSize == 0) {
            stackInfoSize = getJVMTIStackInfoSize();
        }
        return stackInfoSize;
    }

    @C_FUNCTION
    private static native int getJVMTIStackInfoSize();

    @C_FUNCTION
    private static native void setJVMTIFrameInfo(Pointer frameInfo, int index, Word methodID, long location);

    @C_FUNCTION
    private static native void setJVMTIStackInfo(Pointer stackInfo, int index, Word threadHandle,
                    int state, Pointer frameBuffer, int frameount);

    // Frame operations

    static int getFrameCount(Thread thread, Pointer countPtr) {
        VmThread vmThread = thread == null ? VmThread.current() : VmThread.fromJava(thread);
        if (vmThread == null || vmThread.state() == Thread.State.TERMINATED) {
            return JVMTI_ERROR_THREAD_NOT_ALIVE;
        }
        SingleThreadStackTraceVmOperation op = new SingleThreadStackTraceVmOperation(vmThread, new FindAppFramesStackTraceVisitor());
        op.submit();
        countPtr.setInt(op.stackTraceVisitor.stackElements.size());
        return JVMTI_ERROR_NONE;
    }

    static int getFrameLocation(Thread thread, int depth, Pointer methodPtr, Pointer locationPtr) {
        VmThread vmThread = thread == null ? VmThread.current() : VmThread.fromJava(thread);
        if (vmThread == null || vmThread.state() == Thread.State.TERMINATED) {
            return JVMTI_ERROR_THREAD_NOT_ALIVE;
        }
        if (depth < 0) {
            return JVMTI_ERROR_ILLEGAL_ARGUMENT;
        }
        FindAppFramesStackTraceVisitor stackVisitor = new FindAppFramesStackTraceVisitor();
        SingleThreadStackTraceVmOperation op = new SingleThreadStackTraceVmOperation(vmThread, stackVisitor);
        op.submit();
        if (stackVisitor.stackElements.size() < depth) {
            return JVMTI_ERROR_NO_MORE_FRAMES;
        }
        methodPtr.setWord(MethodID.fromMethodActor(stackVisitor.getStackElement(depth).classMethodActor));
        locationPtr.setLong(stackVisitor.getStackElement(depth).bci);
        return JVMTI_ERROR_NONE;
    }

    private static int getOrSetLocalValue(Thread thread, int depth, int slot, Pointer valuePtr, TypedData typedData) {
        if (thread == null) {
            thread = VmThread.current().javaThread();
        }
        FindAppFramesStackTraceVisitor stackTraceVisitor = new FindAppFramesStackTraceVisitor();
        SingleThreadStackTraceVmOperation op = new SingleThreadStackTraceVmOperation(VmThread.fromJava(thread), stackTraceVisitor);
        op.submit();
        if (depth < stackTraceVisitor.stackElements.size()) {
            boolean isSet = valuePtr.isZero();
            StackElement stackElement = stackTraceVisitor.getStackElement(depth);
            ClassMethodActor classMethodActor = stackElement.classMethodActor;
            LocalVariableTable.Entry[] entries = classMethodActor.codeAttribute().localVariableTable().entries();
            if (entries.length == 0) {
                return JVMTI_ERROR_INVALID_SLOT;
            } else {
                for (int i = 0; i < entries.length; i++) {
                    LocalVariableTable.Entry entry = entries[i];
                    if (entry.slot() == slot) {
                        String slotType = classMethodActor.holder().constantPool().utf8At(entry.descriptorIndex(), "local variable type").toString();
                        if (slotType.charAt(0) == typedData.tag) {
                            TargetMethod targetMethod = classMethodActor.currentTargetMethod();
                            if (!targetMethod.isBaseline()) {
                                return JVMTI_ERROR_INVALID_SLOT; // TODO
                            }
                            int offset = targetMethod.frameLayout().localVariableOffset(slot);
                            Pointer varPtr = stackElement.fp;
                            switch (typedData.tag) {
                                case 'L':
                                    if (isSet) {
                                        varPtr.writeReference(offset, Reference.fromJava(typedData.objectValue));
                                    } else {
                                        valuePtr.setWord(JniHandles.createLocalHandle(varPtr.readReference(offset).toJava()));
                                    }
                                    break;

                                case 'F':
                                    if (isSet) {
                                        varPtr.writeFloat(offset, typedData.floatValue);
                                    } else {
                                        valuePtr.setFloat(varPtr.readFloat(offset));
                                    }
                                    break;

                                case 'D':
                                    if (isSet) {
                                        varPtr.writeDouble(offset, typedData.doubleValue);
                                    } else {
                                        valuePtr.setDouble(varPtr.readDouble(offset));
                                    }
                                    break;

                                default:
                                    if (isSet) {
                                        varPtr.writeWord(offset, typedData.wordValue);
                                    } else {
                                        valuePtr.setWord(varPtr.readWord(offset));
                                    }
                            }
                        }
                    }
                }
            }
        } else {
            return JVMTI_ERROR_NO_MORE_FRAMES;
        }
        return JVMTI_ERROR_NONE;
    }

    static int getLocalValue(Thread thread, int depth, int slot, Pointer valuePtr, char type) {
        return getOrSetLocalValue(thread, depth, slot, valuePtr, new TypedData(type));
    }

    static int setLocalValue(Thread thread, int depth, int slot, TypedData typedData) {
        return getOrSetLocalValue(thread, depth, slot, Pointer.zero(), typedData);
    }

    static int setLocalInt(Thread thread, int depth, int slot, int value) {
        return setLocalValue(thread, depth, slot, new TypedData(TypedData.DATA_INT, value));
    }

    static int setLocalLong(Thread thread, int depth, int slot, long value) {
        return setLocalValue(thread, depth, slot, new TypedData(TypedData.DATA_LONG, value));
    }

    static int setLocalFloat(Thread thread, int depth, int slot, float value) {
        return setLocalValue(thread, depth, slot, new TypedData(TypedData.DATA_FLOAT, value));
    }

    static int setLocalDouble(Thread thread, int depth, int slot, double value) {
        return setLocalValue(thread, depth, slot, new TypedData(TypedData.DATA_DOUBLE, value));
    }

    static int setLocalObject(Thread thread, int depth, int slot, Object value) {
        return setLocalValue(thread, depth, slot, new TypedData(TypedData.DATA_OBJECT, value));
    }

    // Thread suspend/resume/stop/interrupt

    static int suspendThread(Thread thread) {
        if (!thread.isAlive()) {
            return JVMTI_ERROR_THREAD_NOT_ALIVE;
        }
        new VmOperation.SuspendThreadSet(VmThread.fromJava(thread)).submit();
        return JVMTI_ERROR_NONE;
    }

    private static int suspendOrResumeThreadList(int requestCount, Pointer requestList, Pointer results, boolean isSuspend) {
        if (requestCount < 0) {
            return JVMTI_ERROR_ILLEGAL_ARGUMENT;
        }
        Set<VmThread> set = new HashSet<VmThread>();
        for (int i = 0; i < requestCount; i++) {
            try {
                Thread thread = (Thread) requestList.getWord(i).asJniHandle().unhand();
                if (!thread.isAlive()) {
                    results.setInt(i, JVMTI_ERROR_THREAD_NOT_ALIVE);
                } else {
                    set.add(VmThread.fromJava(thread));
                    results.setInt(i, JVMTI_ERROR_NONE);
                }
            } catch (ClassCastException ex) {
                results.setInt(i, JVMTI_ERROR_INVALID_THREAD);
            }
        }
        if (isSuspend) {
            new VmOperation.SuspendThreadSet(set).submit();
        } else {
            new VmOperation.ResumeThreadSet(set).submit();
        }
        return JVMTI_ERROR_NONE;

    }

    static int suspendThreadList(int requestCount, Pointer requestList, Pointer results) {
        return suspendOrResumeThreadList(requestCount, requestList, results, true);
    }

    static int resumeThread(Thread thread) {
        if (!thread.isAlive()) {
            return JVMTI_ERROR_THREAD_NOT_ALIVE;
        }
        new VmOperation.ResumeThreadSet(VmThread.fromJava(thread)).submit();
        return JVMTI_ERROR_NONE;
    }

    static int resumeThreadList(int requestCount, Pointer requestList, Pointer results) {
        return suspendOrResumeThreadList(requestCount, requestList, results, false);
    }

    static int interruptThread(Thread thread) {
        if (!thread.isAlive()) {
            return JVMTI_ERROR_THREAD_NOT_ALIVE;
        }
        VmThread.fromJava(thread).interrupt0();
        return JVMTI_ERROR_NONE;
    }

    // ThreadGroup functions

    static int getTopThreadGroups(Pointer countPtr, Pointer groupsPtrPtr) {
        countPtr.setInt(1);
        Pointer groupsPtr = Memory.allocate(Size.fromInt(Word.size()));
        groupsPtr.setWord(JniHandles.createLocalHandle(VmThread.systemThreadGroup));
        groupsPtrPtr.setWord(groupsPtr);
        return JVMTI_ERROR_NONE;
    }

    static int getThreadGroupInfo(ThreadGroup threadGroup, Pointer infoPtr) {
        setThreadGroupInfo(infoPtr, JniHandles.createLocalHandle(threadGroup.getParent()),
                        CString.utf8FromJava(threadGroup.getName()), threadGroup.getMaxPriority(),
                        threadGroup.isDaemon());
        return JVMTI_ERROR_NONE;
    }

    @C_FUNCTION
    private static native void setThreadGroupInfo(Pointer threadGroupInfoPtr, Word parent,
                    Pointer name, int maxPriority, boolean isDaemon);


    static int getThreadGroupChildren(ThreadGroup threadGroup, Pointer threadCountPtr, Pointer threadsPtrPtr, Pointer groupCountPtr, Pointer groupsPtrPtr) {
        // We reach directly into the ThreadGroup class state to avoid security checks and clumsy iterators.
        ThreadGroupProxy proxy = ThreadGroupProxy.asThreadGroupProxy(threadGroup);
        Thread[] threads = proxy.threads;
        ThreadGroup[] threadGroups = proxy.groups;
        // Holding the lock means no changes to the ThreadGroup, however threads may die at any time.
        synchronized (threadGroup) {
            int nGroups = 0;
            int nThreads = 0;
            for (int i = 0; i < proxy.ngroups; i++) {
                if (!threadGroups[i].isDestroyed()) {
                    nGroups++;
                }
            }
            for (int i = 0; i < proxy.nthreads; i++) {
                if (threads[i].isAlive()) {
                    nThreads++;
                }
            }
            Pointer threadsPtr = Memory.allocate(Size.fromInt(nThreads * Word.size()));
            if (threadsPtr.isZero()) {
                return JVMTI_ERROR_OUT_OF_MEMORY;
            }
            Pointer threadGroupsPtr = Memory.allocate(Size.fromInt(nGroups * Word.size()));
            if (threadGroupsPtr.isZero()) {
                Memory.deallocate(threadsPtr);
                return JVMTI_ERROR_OUT_OF_MEMORY;
            }
            // we recompute the live thread count
            int liveThreads = 0;
            for (int i = 0; i < nThreads; i++) {
                if (threads[i].isAlive()) {
                    threadsPtr.setWord(i, JniHandles.createLocalHandle(threads[i]));
                    liveThreads++;
                }
            }
            for (int i = 0; i < nGroups; i++) {
                if (!threadGroups[i].isDestroyed()) {
                    threadGroupsPtr.setWord(i, JniHandles.createLocalHandle(threadGroups[i]));
                }
            }
            threadCountPtr.setInt(liveThreads);
            groupCountPtr.setInt(nGroups);
            threadsPtrPtr.setWord(threadsPtr);
            groupsPtrPtr.setWord(threadGroupsPtr);

            return JVMTI_ERROR_NONE;
        }
    }

    static class ThreadGroupProxy {
        @INTRINSIC(UNSAFE_CAST) public static native ThreadGroupProxy asThreadGroupProxy(Object object);

        @ALIAS(declaringClass = ThreadGroup.class)
        private Thread[] threads;
        @ALIAS(declaringClass = ThreadGroup.class)
        private ThreadGroup[] groups;
        @ALIAS(declaringClass = ThreadGroup.class)
        private int nthreads;
        @ALIAS(declaringClass = ThreadGroup.class)
        private int ngroups;

    }

}