/*
 * Copyright 2014 The Closure Compiler Authors.
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
import com.google.common.base.Splitter;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.Es6Module.ImportEntry;
import com.google.javascript.jscomp.Es6Module.ModuleNamePair;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.IR;

import java.util.List;
import java.util.Set;
import java.util.Iterator;

/**
 * Appends suffix to all global variable names defined in this module and
 * rewrites imported variables into it's original name.
 */
public final class Es6ModuleRewrite extends AbstractPostOrderCallback {

  static final DiagnosticType MODULE_NAMESPACE_ASSIGNMENT =
      DiagnosticType.error(
          "JSC_ES6_MODULE_NAMESPACE_OBJECT_ASSIGNEMNT",
          "All properties on module namespace exotic object are immutable.");

  static final DiagnosticType MODULE_NAMESPACE_NON_GETPROP =
      DiagnosticType.error(
          "JSC_ES6_MODULE_NAMESPACE_OBJECT_NON_GETPROP",
          "Using module namespace exotic object without property get is not supported.");

  static final DiagnosticType IMPORTED_BINDING_ASSIGNMENT =
      DiagnosticType.error(
          "JSC_ES6_IMPORTED_BINDING_ASSIGNMENT",
          "Imported bindings are immutable.");

  static final DiagnosticType LHS_OF_GOOG_REQUIRE_MUST_BE_CONST =
      DiagnosticType.error(
          "JSC_LHS_OF_GOOG_REQUIRE_MUST_BE_CONST",
          "The left side of a goog.require() must use ''const'' (not ''let'' or ''var'')");

  static final DiagnosticType USELESS_USE_STRICT_DIRECTIVE =
      DiagnosticType.warning(
          "JSC_USELESS_USE_STRICT_DIRECTIVE",
          "'use strict' is unnecessary in goog.module files.");

  private static final ImmutableSet<String> USE_STRICT_ONLY = ImmutableSet.of("use strict");

  private final AbstractCompiler compiler;
  private final Es6ModuleRegistry moduleRegistry;
  private final Es6Module module;

  /**
   * Creates a new Es6ModuleRewrite instance which can be used to rewrite
   * ES6 modules to a concatenable form.
   *
   * @param compiler The compiler
   * @param moduleRegistry Module registry that holds all ES6 modules derived
   *   from all compiler inputs.
   * @param module Processing module.
   */
  public Es6ModuleRewrite(AbstractCompiler compiler, Es6ModuleRegistry moduleRegistry, Es6Module module) {
    this.compiler = compiler;
    this.moduleRegistry = moduleRegistry;
    this.module = module;
  }

  public void processFile(Node root) {
    Preconditions.checkArgument(root.isScript() &&
        compiler.getInput(root.getInputId()) == module.getInput());
    NodeTraversal.traverseEs6(compiler, root, this);
    // We unconditionally call reportCodeChange() because
    // the tree is always modified in visitScript().
    compiler.reportCodeChange();
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    JSDocInfo info = n.getJSDocInfo();
    if (info != null) {
      for (Node typeNode : info.getTypeNodes()) {
        fixTypeNode(t, typeNode);
      }
    }

    if (n.isThis()) {
      visitThis(t, n, parent);
    } else if (n.isName()) {
      visitName(t, n, parent);
    } else if (n.isGetProp()) {
      visitGetProp(t, n, parent);
    } else if (n.isScript()) {
      visitScript(t, n);
    }
  }

  /**
   * Rewrites global {@code this} into {@code undefined}.
   * Global `this` value in Module Environment Record is always `undefined`.
   *
   * @see "http://www.ecma-international.org/ecma-262/6.0/#sec-module-environment-records-getthisbinding"
   */
  private void visitThis(NodeTraversal t, Node n, Node parent) {
    if(t.getScope().isGlobal()) {
      // Note: We don't have to care about assignment to `this` here,
      // because the parser should already prohibited that.
      parent.replaceChild(n, IR.name("undefined").srcref(n));
    }
  }

  private String toGlobalName(ModuleNamePair binding) {
    return toGlobalName(binding.module, binding.name);
  }

  private String toGlobalName(Es6Module module, String name) {
    return name + "$$" + moduleRegistry.getModuleName(module);
  }

  /**
   * Visit NAME node and..
   *
   * <ul>
   *   <li>rename variables declared in this file so that we can safely
   *   concatenate inputs.</li>
   *   <li>resolve imported name and replace them with its original name.</li>
   *   <li>emits an error when detecting assignment to imported binding.</li>
   * </ul>
   *
   * @see "http://www.ecma-international.org/ecma-262/6.0/#sec-createimportbinding"
   */
  private void visitName(NodeTraversal t, Node n, Node parent) {
    String name = n.getString();

    Var var = t.getScope().getVar(name);
    if (var != null && var.isGlobal()) {
      // Add module name suffix to global variables to avoid polluting the global namespace.
      // "foo" -> "foo$$moduleName"
      n.setString(toGlobalName(module, name));
      n.putProp(Node.ORIGINALNAME_PROP, name);
      return;
    } else if (var == null) {
      ImportEntry in = module.getImportEntry(name);
      if(in != null) {
        // Replace imporeted bindings with original bindings.
        if (NodeUtil.isAssignmentTarget(n)) {
          // All imported bindings are immutable.
          t.report(n, IMPORTED_BINDING_ASSIGNMENT);
          return;
        }
        Es6Module importedModule = moduleRegistry.resolveImportedModule(module, in.getModuleRequest());
        Preconditions.checkState(importedModule != null);
        ModuleNamePair binding = in.getImportName() == null
          //   import * as ns from "mod"
          ? new ModuleNamePair(importedModule, null)
          //   import {a} from "mod"
          //   import a from "mod"
          : importedModule.resolveExport(in.getImportName());

        Preconditions.checkState(binding != null);

        rewriteImportedBinding(t, n, parent, binding);
      }
    }
  }

  /**
   * Statically resolve and collapse property access on module namespace
   * exotic object into its original binding.
   *
   * <code>
   * // mod3.js
   * var foo = 42;
   * export {foo};
   *
   * // mod2.js
   * import * as ns3 from "mod3";
   * export {ns3};
   *
   * // mod1.js
   * import * as ns2 from "mod2";
   * export {ns2};
   *
   * // app.js
   * import * as ns1 from "mod1";
   *
   * ns1.ns2.ns3.foo            // input
   *
   * -> module$mod1.ns2.ns3.foo // rewritten in `visitName`
   * -> module$mod2.ns3.foo     // iteration1
   * -> module$mod3.foo         // iteration2
   * -> foo$$module$mod3        // iteration3
   * </code>
   *
   * Also, emits an error when detects assignment to properties.
   *
   * @see "http://www.ecma-international.org/ecma-262/6.0/#sec-module-namespace-exotic-objects-set-p-v-receiver"
   * @see "http://www.ecma-international.org/ecma-262/6.0/#sec-module-namespace-exotic-objects-isextensible"
   */
  private void visitGetProp(NodeTraversal t, Node n, Node parent) {
    Node target = n.getFirstChild();
    if (target.isName() &&
        target.getString().startsWith(Es6ModuleRegistry.MODULE_NAME_PREFIX)) {
      if (NodeUtil.isAssignmentTarget(n)) {
        // Module namespace exotic object is not extensible.
        // All properties on module namespace exotic object are immutable.
        t.report(n, MODULE_NAMESPACE_ASSIGNMENT);
        return;
      }

      Es6Module.Namespace ns = moduleRegistry.getModuleNamespace(target.getString());
      String propertyName = target.getNext().getString();
      ModuleNamePair binding = ns.get(propertyName);
      if(binding == null) {
        t.report(target.getNext(), Es6ModuleRegistry.RESOLVE_EXPORT_FAILURE,
            moduleRegistry.getModuleName(ns.getModule()), propertyName);
        return;
      }
      rewriteImportedBinding(t, n, parent, binding);
    }
  }

  /**
   * Replace resolved bindings with its orignal bindings.
   */
  private void rewriteImportedBinding(
      NodeTraversal t, Node n, Node parent, ModuleNamePair binding) {

    String newName;
    if(binding.name == null) {
      // If resolved binding is another module namespace object,
      // replace the node with "moduleName". That will eventually be
      // rewritten again in visitGetProp()

      if (!parent.isGetProp()) {
        // We currently support only property access on module exotic objecs.
        t.report(n, MODULE_NAMESPACE_NON_GETPROP);
        return;
      }
      newName = moduleRegistry.getModuleName(binding.module);
    } else {
      newName = toGlobalName(binding);
    }

    parent.replaceChild(n, IR.name(newName).srcref(n));
  }

  /**
   * fix JSDoc type nodes as the same manner.
   * 
   * Also, we support special syntax in JSDoc types.
   * If the type name looks like relative path, then we treat
   * "string before first period after last slash" as a ModuleSpecifier
   * 
   * <code>
   * @type {./path/to.the/module.NAME}
   *        ^^^^^^^^^^^^^^^^^^^^ moduleSpecifier
   * </code>
   *
   * Note that this syntax doesn't affect the module depenencies graph.
   */
  private void fixTypeNode(NodeTraversal t, Node n) {
    for (Node child = n.getFirstChild(); child != null;
        child = child.getNext()) {
      fixTypeNode(t, child);
    }

    if (!n.isString()) {
      return;
    }

    String name = n.getString();
    String newName = null;

    Es6Module.Namespace namespace = null;
    String rest = "";

    if(ES6ModuleLoader.isRelativeIdentifier(name)) {
      //   @type {./foo/bar.baz/qux.quux.Foo}
      int lastSlash = name.lastIndexOf('/');
      int endIndex = name.indexOf('.', lastSlash);
      if(endIndex == -1) {
        t.report(n, MODULE_NAMESPACE_NON_GETPROP);
        return;
      }

      String required = name.substring(0, endIndex);
      Es6Module mod = moduleRegistry.resolveImportedModule(module, required);
      if (mod == null) {
        // Since JSDocs are not processed in Es6ModuleRegistry#instantiateAllModules(),
        // this module may be unresolvable.
        t.report(n, ES6ModuleLoader.LOAD_ERROR, required);
        return;
      }
      namespace = mod.getNamespace();
      rest = name.substring(endIndex + 1);
    } else {
      List<String> splitted = Splitter.on('.').limit(2).splitToList(name);
      String baseName = splitted.get(0);
      if(splitted.size() == 2) {
        rest = splitted.get(1);
      }

      Var var = t.getScope().getVar(baseName);
      if (var != null && var.isGlobal()) {
        //   @type {fileLocalVar.Foo}
        newName = toGlobalName(module, baseName);
      } else {
        //   @type {importedName.foo.Bar}
        //   @type {importedNamespace.foo.Bar}
        ImportEntry in = module.getImportEntry(baseName);
        if (in != null) {
          Es6Module importedModule = moduleRegistry.resolveImportedModule(module, in.getModuleRequest());
          Preconditions.checkState(importedModule != null);
          if (in.getImportName() == null) {
            namespace = importedModule.getNamespace();
          } else {
            ModuleNamePair binding = importedModule.resolveExport(in.getImportName());
            Preconditions.checkState(binding != null && binding.name != null);
            newName = toGlobalName(binding);
          }
        } else {
          // Not file global nor imported.
          return;
        }
      }
    }

    // Resolve and collapse imported module namespace object property path.
    if (namespace != null) {
      Iterator<String> path = Splitter.on('.').split(rest).iterator();

      while (namespace != null) {
        if (!path.hasNext()) {
          t.report(n, MODULE_NAMESPACE_NON_GETPROP);
          return;
        }
        String propertyName = path.next();
        ModuleNamePair binding = namespace.get(propertyName);
        if(binding == null) {
          t.report(n, Es6ModuleRegistry.RESOLVE_EXPORT_FAILURE,
              moduleRegistry.getModuleName(namespace.getModule()), propertyName);
          return;
        }
        if(binding.name == null) {
          namespace = binding.module.getNamespace();
        } else {
          newName = toGlobalName(binding);
          rest = Joiner.on(".").join(path);
          namespace = null;
        }
      }
    }

    Preconditions.checkState(newName != null);
    if(!rest.isEmpty()) {
      newName += "." + rest;
    }
    n.setString(newName);
    n.putProp(Node.ORIGINALNAME_PROP, name);
  }

  /**
   * Misc SCRIPT root processings.
   */
  private void visitScript(NodeTraversal t, Node n) {
    checkStrictModeDirective(t, n);
    rewriteRequires(n);

    JSDocInfoBuilder jsDocInfo = n.getJSDocInfo() == null
        ? new JSDocInfoBuilder(false)
        : JSDocInfoBuilder.copyFrom(n.getJSDocInfo());
    if (!jsDocInfo.isPopulatedWithFileOverview()) {
      jsDocInfo.recordFileOverview("");
    }
    // Don't check provides and requires, since most of them are auto-generated.
    jsDocInfo.recordSuppressions(ImmutableSet.of("missingProvide", "missingRequire"));
    n.setJSDocInfo(jsDocInfo.build());
  }

  private static void checkStrictModeDirective(NodeTraversal t, Node n) {
    Preconditions.checkState(n.isScript());
    Set<String> directives = n.getDirectives();
    if (directives != null && directives.contains("use strict")) {
      t.report(n, USELESS_USE_STRICT_DIRECTIVE);
    } else {
      if (directives == null) {
        n.setDirectives(USE_STRICT_ONLY);
      } else {
        ImmutableSet.Builder<String> builder = new ImmutableSet.Builder<String>().add("use strict");
        builder.addAll(directives);
        n.setDirectives(builder.build());
      }
    }
  }

  private void rewriteRequires(Node script) {
    NodeTraversal.traverseEs6(
        compiler,
        script,
        new NodeTraversal.AbstractShallowCallback() {
          @Override
          public void visit(NodeTraversal t, Node n, Node parent) {
            if (n.isCall()
                && n.getFirstChild().matchesQualifiedName("goog.require")
                && NodeUtil.isNameDeclaration(parent.getParent())) {
              visitRequire(n, parent);
            }
          }

          /**
           * Rewrites
           *   const foo = goog.require('bar.foo');
           * to
           *   goog.require('bar.foo');
           *   const foo = bar.foo;
           */
          private void visitRequire(Node requireCall, Node parent) {
            String namespace = requireCall.getLastChild().getString();
            if (!parent.getParent().isConst()) {
              compiler.report(JSError.make(parent.getParent(), LHS_OF_GOOG_REQUIRE_MUST_BE_CONST));
            }

            // If the LHS is a destructuring pattern with the "shorthand" syntax,
            // desugar it because otherwise the renaming will not be done correctly.
            //   const {x} = goog.require('y')
            // becomes
            //   const {x: x} = goog.require('y');
            if (parent.isObjectPattern()) {
              for (Node key = parent.getFirstChild(); key != null; key = key.getNext()) {
                if (!key.hasChildren()) {
                  key.addChildToBack(IR.name(key.getString()).useSourceInfoFrom(key));
                }
              }
            }

            Node replacement = NodeUtil.newQName(compiler, namespace).srcrefTree(requireCall);
            parent.replaceChild(requireCall, replacement);
            Node varNode = parent.getParent();
            varNode.getParent().addChildBefore(
                IR.exprResult(requireCall).srcrefTree(requireCall),
                varNode);
          }
        });
  }
}
