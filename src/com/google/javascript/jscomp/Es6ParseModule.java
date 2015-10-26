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
 * Collect ES6 ExportEntry and ImportEntry from module source file tree.
 * Also rewrites ExportDeclaration into normal declarations, and remove
 * ImportDeclaration from the tree.
 *
 * @see "http://www.ecma-international.org/ecma-262/6.0/index.html#sec-source-text-module-records"
 */
public final class Es6ParseModule extends AbstractShallowCallback {

  private static final String DEFAULT_VAR_NAME = "$jscompDefaultExport";
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
   * Collect ImportEntry and remove ImportDeclaration from the tree.
   */
  private void visitImport(NodeTraversal t, Node importDecl, Node parent) {

    Node defaultImport = importDecl.getChildAtIndex(0);
    Node otherImport = importDecl.getChildAtIndex(1);
    Node moduleRequest = importDecl.getLastChild();

    moduleRequests.add(moduleRequest);

    if (!defaultImport.isEmpty()) {
      // import foo from "mod"
      addImportEntry(moduleRequest, IR.name(DEFAULT_EXPORT_NAME).srcref(importDecl),
          defaultImport);
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
            addImportEntry(moduleRequest, importName, localName);
          }
          break;
        case Token.IMPORT_STAR:
          // import * as foo from "mod"
          addImportEntry(moduleRequest, null, otherImport);
          break;
        default:
          // TODO: Should we report unexpected token?
          break;
      }
    }

    parent.removeChild(importDecl);
    compiler.reportCodeChange();
  }

  /**
   * Collect ExportEntry and rewrites ExportDeclaration into normal declarations.
   */
  private void visitExport(NodeTraversal t, Node export, Node parent) {
    if (export.getBooleanProp(Node.EXPORT_DEFAULT)) {
      // export default

      // If the thing being exported is a class or function that has a name,
      // extract it from the export statement, so that it can be referenced
      // from within the module.
      //
      //   export default class X {} -> class X {};
      //   export default function X() {} -> function X() {};
      //
      // Otherwise, create a local variable for it and export that.
      //
      //   export default 'someExpression'
      //     ->
      //   var $jscompDefaultExport = 'someExpression';
 
      Node child = export.getFirstChild();
      Node name = null;

      if (child.isFunction()) {
        name = NodeUtil.getFunctionNameNode(child);
      } else if (child.isClass()) {
        name = NodeUtil.getClassNameNode(child);
      }

      Node decl = null;
      if (name != null) {
        //   export default class ClassName { }
        decl = child.cloneTree();
        decl.setJSDocInfo(export.getJSDocInfo());
      } else {
        //   export default class { }
        name = IR.name(DEFAULT_VAR_NAME).srcref(export);
        decl = IR.var(name , export.removeFirstChild());
        decl.useSourceInfoIfMissingFromForTree(export);
      }
      parent.addChildBefore(decl, export);
      addExportEntry(IR.name(DEFAULT_EXPORT_NAME).srcref(export), null, name.cloneNode());
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
      Scope scope = t.getScope();
      for (Node exportSpec : export.getFirstChild().children()) {
        Node origName = exportSpec.getFirstChild();
        Node exportName = exportSpec.getChildCount() == 2
            ? exportSpec.getLastChild()
            : origName;

        // Note: The existence of localName declaration will be checked later
        // in Es6ModuleRewrite#visitScript. We cannot check that here because
        // the `origName` may be a imported name.
 
        addExportEntry(exportName, moduleRequest, origName);
      }
      if(moduleRequest != null) {
        moduleRequests.add(moduleRequest);
      }
    } else {
      //   export var Foo, Bar, Baz;
      //   export function foo() {}
      // etc.
      Node declaration = export.getFirstChild();
      for (int i = 0; i < declaration.getChildCount(); i++) {
        Node localName = declaration.getChildAtIndex(i);
        if (!localName.isName()) {
          break;
        }
        // Break out on "B" in "class A extends B"
        if (declaration.isClass() && i > 0) {
          break;
        }

        // If `localName` is already declared, it would be an error
        // caught in VariableReferenceCheck.

        localName = localName.cloneNode();
        // Clone because this name may be rewritten by setString()
        // in Es6ModuleRewrite.
        addExportEntry(localName, null, localName);
      }

      // Extract declaration from the export statement.
      //
      //   export var Foo;
      //   -> var Foo;
      declaration.setJSDocInfo(export.getJSDocInfo());
      export.setJSDocInfo(null);
      parent.addChildBefore(declaration.detachFromParent(), export);
    }
    parent.removeChild(export);
    compiler.reportCodeChange();
  }

  private void addImportEntry(Node moduleRequest, Node importName, Node localName) {
    importEntries.add(new ImportEntry(moduleRequest, importName, localName));
  }

  private void addExportEntry(Node exportName, Node moduleRequest, Node origName) {
    exportEntries.add(new ExportEntry(exportName, moduleRequest, origName));
  }
}
