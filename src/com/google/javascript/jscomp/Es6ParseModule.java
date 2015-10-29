/*
 * Copyright 2015 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import static com.google.javascript.jscomp.Es6SyntacticScopeCreator.DEFAULT_BIND_NAME;

import com.google.common.base.Preconditions;
import com.google.javascript.jscomp.NodeTraversal.AbstractShallowCallback;
import com.google.javascript.jscomp.Es6Module.ImportEntry;
import com.google.javascript.jscomp.Es6Module.ExportEntry;
import com.google.javascript.jscomp.ProcessCommonJSModules.FindGoogProvideOrGoogModule;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

import java.util.Set;
import java.util.List;
import java.util.LinkedList;
import java.util.LinkedHashSet;

/**
 * Parse ES6 module related ModuleItems from module source file tree and collect dependency
 * information.
 *
 * Note: This process never rewrites anything.
 *
 * @see "http://www.ecma-international.org/ecma-262/6.0/index.html#sec-source-text-module-records"
 */
public final class Es6ParseModule extends AbstractShallowCallback {

  static final DiagnosticType DUPLICATED_IMPORTED_BOUND_NAMES = DiagnosticType.error(
      "JSC_ES6_DUPLICATED_IMPORTED_BOUND_NAMES",
      "Duplicated imported bound name: {0}");

  private static final String DEFAULT_EXPORT_NAME = "default";
  private final AbstractCompiler compiler;

  public List<Node> moduleRequests;
  public List<ImportEntry> importEntries;
  public List<ExportEntry> exportEntries;

  /**
   * Creates a new Es6ParseModule instance
   *
   * @param compiler The compiler
   */
  public Es6ParseModule(AbstractCompiler compiler) {
    this.compiler = compiler;
    moduleRequests = new LinkedList<>();
    exportEntries = new LinkedList<>();
    importEntries = new LinkedList<>();
  }

  /**
   * Process tree of single source input and collect ModuleRequests,
   * ExportEntry and ImportEntry.
   *
   * @see "http://www.ecma-international.org/ecma-262/6.0/#sec-parsemodule"
   */
  public void processFile(Node root) {
    Preconditions.checkArgument(root.isScript(),
        "Es6ParseModule supports only one invocation per CompilerInput node");

    FindGoogProvideOrGoogModule finder = new FindGoogProvideOrGoogModule();
    NodeTraversal.traverseEs6(compiler, root, finder);
    if (finder.isFound()) {
      return;
    }

    NodeTraversal.traverseEs6(compiler, root, this);
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    if (n.isExport()) {
      visitExport(t, n, parent);
    } else if (n.isImport()) {
      visitImport(t, n, parent);
    }
  }

  /**
   * Collect ImportEntry. And check the duplicated local names.
   */
  private void visitImport(NodeTraversal t, Node importDecl, Node parent) {

    Node defaultImport = importDecl.getChildAtIndex(0);
    Node otherImport = importDecl.getChildAtIndex(1);
    Node moduleRequest = importDecl.getLastChild();

    moduleRequests.add(moduleRequest);

    if (!defaultImport.isEmpty()) {
      // import foo from "mod"
      checkImportedName(t, defaultImport);
      addImportEntry(moduleRequest, IR.name(DEFAULT_EXPORT_NAME).srcref(importDecl),
          defaultImport.cloneNode());
    }

    if (!otherImport.isEmpty()) {
      switch (otherImport.getType()) {
        case Token.IMPORT_SPECS:
          // import {a as foo, b} from "mod"
          for (Node grandChild : otherImport.children()) {
            Node importName = grandChild.getFirstChild();
            Node localName = grandChild.getChildCount() == 2
                ? grandChild.getLastChild()
                : importName;
            checkImportedName(t, localName);
            addImportEntry(moduleRequest, importName.cloneNode(), localName.cloneNode());
          }
          break;
        case Token.IMPORT_STAR:
          // import * as foo from "mod"
          checkImportedName(t, otherImport);
          addImportEntry(moduleRequest, null, otherImport.cloneNode());
          break;
        default:
          // TODO: Should we report unexpected token?
          break;
      }
    }
  }

  private void checkImportedName(NodeTraversal t, Node nameNode) {
    Var var = t.getScope().getVar(nameNode.getString());
    // Must be declared in Es6SytaticScopeCreator.
    Preconditions.checkState(var != null);
    if (var.getNameNode() != nameNode) {
      t.report(nameNode, DUPLICATED_IMPORTED_BOUND_NAMES, nameNode.getString());
    }
  }

  /**
   * Collect ExportEntry. And check the existence of exporting variable.
   */
  private void visitExport(NodeTraversal t, Node export, Node parent) {
    if (export.getBooleanProp(Node.EXPORT_DEFAULT)) {
      // export default
      Node child = export.getFirstChild();
      Node localName = null;

      if (child.isFunction() || child.isClass()) {
        //  export default function functionName() {}
        //  export default class ClassName {}
        localName = child.getFirstChild().cloneNode();
      }
      if (localName == null || localName.isEmpty()) {
        //  export default function() {}
        //  export default class {}
        //  export default AssingnmentExpression
        localName = IR.name(DEFAULT_BIND_NAME).srcref(export);
      }
      addExportEntry(IR.name(DEFAULT_EXPORT_NAME).srcref(export), null, localName);
    } else if (export.getBooleanProp(Node.EXPORT_ALL_FROM)) {
      //   export * from 'moduleIdentifier';
      Node moduleRequest = export.getLastChild();
      addExportEntry(null, moduleRequest, null);
      moduleRequests.add(moduleRequest);
    } else if (export.getFirstChild().getType() == Token.EXPORT_SPECS) {
      //   export {x, y as z};
      //   export {x, y as z} from 'moduleIdentifier';
      Node moduleRequest = export.getChildCount() == 2
          ? export.getLastChild()
          : null;
      for (Node exportSpec : export.getFirstChild().children()) {
        Node localName = exportSpec.getFirstChild().cloneNode();
        Node exportName = exportSpec.getChildCount() == 2
            ? exportSpec.getLastChild().cloneNode()
            : localName;
        addExportEntry(exportName, moduleRequest, localName);
      }
      if (moduleRequest != null) {
        moduleRequests.add(moduleRequest);
      }
    } else {
      Node child = export.getFirstChild();
      if (child.isFunction() || child.isClass()) {
        //   export function foo() {}
        //   export class Foo {}
        Node localName = child.getFirstChild().cloneNode();
        addExportEntry(localName, null, localName);
      } else {
        //    export var x, y, z;
        for (Node name : child.children()) {
          if (!name.isName()) {
            break;
          }
          name = name.cloneNode();
          addExportEntry(name, null, name);
        }
      }
    }
  }

  private void addImportEntry(Node moduleRequest, Node importName, Node localName) {
    importEntries.add(new ImportEntry(moduleRequest, importName, localName));
  }

  private void addExportEntry(Node exportName, Node moduleRequest, Node origName) {
    exportEntries.add(new ExportEntry(exportName, moduleRequest, origName));
  }
}
