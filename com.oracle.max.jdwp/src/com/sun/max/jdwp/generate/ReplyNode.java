/*
 * Copyright (c) 2007, 2011, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.max.jdwp.generate;

import java.io.*;
import java.util.*;

/**
 * @author JDK7: jdk/make/tools/src/build/tools/jdwpgen
 */
class ReplyNode extends AbstractTypeListNode {

    String cmdName;

    @Override
    void set(String kind, List<Node> components, int lineno) {
        super.set(kind, components, lineno);
        components.add(0, new NameNode(kind));
    }

    @Override
    void constrain(Context ctx) {
        super.constrain(ctx.replyReadingSubcontext());
        final CommandNode cmd = (CommandNode) parent;
        cmdName = cmd.name();
    }

    @Override
    void genJava(PrintWriter writer, int depth) {
        genJavaPreDef(writer, depth);
        super.genJava(writer, depth);
        writer.println();

        indent(writer, depth);
        writer.println("public static class Reply implements OutgoingData {");

        indent(writer, depth + 1);
        writer.println("public byte getCommandId() { return COMMAND; }");
        indent(writer, depth + 1);
        writer.println("public byte getCommandSetId() { return COMMAND_SET; }");
        genJavaReadingClassBody(writer, depth + 1, "Reply");
        indent(writer, depth);
        writer.write("}");
        writer.println();
    }
}
