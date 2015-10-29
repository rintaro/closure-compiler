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

import static com.google.javascript.jscomp.Es6SyntacticScopeCreator.DEFAULT_BIND_NAME;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableList;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.Es6Module.ImportEntry;
import com.google.javascript.jscomp.Es6Module.ExportEntry;
import com.google.javascript.jscomp.Es6Module.ModuleNamePair;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
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
public final class Es6RewriteModule implements HotSwapCompilerPass, NodeTraversal.Callback {

  static final DiagnosticType EXPORTED_BINDING_NOT_DECLARED =
      DiagnosticType.error(
          "JSC_ES6_EXPORTED_BINDING_NOT_DECLARED",
          "Exporting local name \"{0}\" is not declared.");

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

  // Module scope that we are currently processing
  private Es6Module module;
  // Module namespace scope that we are currently processing
  private Scope moduleScope;

  /**
   * Creates a new Es6RewriteModule instance which can be used to rewrite
   * ES6 modules to a concatenable form.
   *
   * @param compiler The compiler
   */
  public Es6RewriteModule(AbstractCompiler compiler) {
    this.compiler = compiler;
    this.moduleRegistry = compiler.getEs6ModuleRegistry();
    this.module = null;
  }

  @Override
  public void process(Node externs, Node root) {
    // Each module is its own scope, prevent building a global scope,
    // so we can use the scope for the file.
    // TODO: Same as ClosureRewriteModule. we need a concept of a module scope.
    for (Node c = root.getFirstChild(); c != null; c = c.getNext()) {
      Preconditions.checkState(c.isScript());
      hotSwapScript(c, null);
    }
  }

  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    NodeTraversal.traverseEs6(compiler, scriptRoot, this);
  }

  @Override
  public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
    if (n.isScript()) {

      // Do nothing if empty.
      if (!n.hasChildren()) {
        return false;
      }

      CompilerInput input = t.getInput();
      module = moduleRegistry.getModule(input);
      if (module == null) {
        return false;
      }
      moduleScope = t.getScope();

      // Need to rewriteRequires before renaming variables.
      rewriteRequires(n);
    }

    if (parent != null) {
      switch (parent.getType()) {
        // Since we will remove these nodes from the tree,
        // We don't have to rename NAMEs in these nodes.
        case Token.IMPORT:
        case Token.EXPORT_SPECS:
          return false;
      }
    }

    return true;
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    JSDocInfo info = n.getJSDocInfo();
    if (info != null) {
      for (Node typeNode : info.getTypeNodes()) {
        fixTypeNode(t, typeNode);
      }
    }

    switch (n.getType()) {
      case Token.THIS: visitThis(t, n, parent); break;
      case Token.NAME: visitName(t, n, parent); break;
      case Token.GETPROP: visitGetProp(t, n, parent); break;
      case Token.IMPORT: visitImport(t, n, parent); break;
      case Token.EXPORT: visitExport(t, n, parent); break;
      case Token.SCRIPT: visitScript(t, n); break;
    }
  }

  /**
   * Rewrite ExportDeclaration into normal declaration if needed.
   * And remove ExportDeclaration from the tree.
   *
   *   "export default 'someExpression' into "$default$$modueName = 'someExpression'"
   *   "export default function()" into "var $default$$modueName = function() {}"
   *   "export default function name()" into "function name$$moduleName() {}"
   *   "export function name()" into "function name$$moduleName() {}"
   *   "export var x, y" into "var x$$moduleName, y$$moduleName"
   */
  private void visitExport(NodeTraversal t, Node n, Node parent) {
    Preconditions.checkState(parent.isScript());
    if (n.hasOneChild()) {
      Node child = n.getFirstChild();
      if (child.getType() == Token.EXPORT_SPECS) {
        // Check the exisitence of exported local names.
        //   export {a, b as c}
        for (Node exportSpec : child.children()) {
          Node name = exportSpec.getFirstChild();
          if (!moduleScope.isDeclared(name.getString(), false)) {
            // Error if any element of the ExportedBinding of this
            // ExportSpecifier is not declared.
            t.report(name, EXPORTED_BINDING_NOT_DECLARED, name.getString());
          }
        }
      } else {
        // Make export declaration having local declarations into 
        // normal declarations.
        Node localName = null;
        if (child.isFunction() || child.isClass()) {
          localName = child.getFirstChild();
        }

        Node decl = child.detachFromParent();
        if ((localName == null || localName.isEmpty() || localName.getString().isEmpty())
            && n.getBooleanProp(Node.EXPORT_DEFAULT)) {
          //  export default function() {}
          //  export default class {}
          //  export default AssingnmentExpression
          Node name = IR.name(toGlobalName(module, DEFAULT_BIND_NAME));
          decl = IR.var(name, child);
          decl.useSourceInfoIfMissingFromForTree(n);
        } else {
          //  export default function funcName() {}
          //  export default class ClassName {}
          //  export function funcName() ()
          //  export class Foo() {}
          //  export var x,y,z
        }
        decl.setJSDocInfo(n.getJSDocInfo());
        parent.addChildBefore(decl, n);
      }
    }
    parent.removeChild(n);
  }

  /**
   * Remove all ImportDeclarations.
   */
  private void visitImport(NodeTraversal t, Node n, Node parent) {
    // since we already getScope() we can safely remove this declaration here.
    parent.removeChild(n);
  }

  /**
   * Rewrites global {@code this} into {@code undefined}.
   * Global {@code this} value in Module Environment Record is always `undefined`.
   *
   * @see "http://www.ecma-international.org/ecma-262/6.0/#sec-module-environment-records-getthisbinding"
   */
  private void visitThis(NodeTraversal t, Node n, Node parent) {
    if (t.getScope() == moduleScope) {
      // Note: We don't have to care about assignment to `this` here,
      // because the parser should already prohibited that.
      parent.replaceChild(n, IR.name("undefined").srcref(n));
    }
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
    ModuleNamePair binding = resolveModuleBinding(t, n.getString());
    if (binding != null) {
      if (binding.module != module && NodeUtil.isAssignmentTarget(n)) {
        // Imported bindings are immutable.
        t.report(n, IMPORTED_BINDING_ASSIGNMENT);
        return;
      }
      rewriteWithBinding(t, n, parent, binding, null);
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
      if (binding == null) {
        t.report(target.getNext(), Es6ModuleRegistry.RESOLVE_EXPORT_FAILURE,
            moduleRegistry.getModuleName(ns.getModule()), propertyName);
        return;
      }

      String origName = (String) target.getProp(Node.ORIGINALNAME_PROP);
      if (origName == null) {
        origName = target.getString();
      }
      // Call on module namespace object properties can be considered as FREE_CALL.
      if (parent.isCall() && parent.getFirstChild() == n) {
        parent.putBooleanProp(Node.FREE_CALL, true);
      }

      rewriteWithBinding(t, n, parent, binding, origName + "." + propertyName);
    }
  }

  private String toGlobalName(ModuleNamePair binding) {
    return toGlobalName(binding.module, binding.name);
  }

  private String toGlobalName(Es6Module module, String name) {
    return name + "$$" + moduleRegistry.getModuleName(module);
  }

  /**
   * Resolve NAME in current module scope.
   */
  private ModuleNamePair resolveModuleBinding(NodeTraversal t, String name) {
    Var var = t.getScope().getVar(name);
    if (var == null || var.getScope() != moduleScope) {
      // this name is not declared in moduleScope.
      return null;
    }

    ImportEntry in = module.getImportEntry(name);
    if (in == null) {
      // module local binding.
      return new ModuleNamePair(module, name);
    } else {
      // imported binding.
      Es6Module importedModule = moduleRegistry.resolveImportedModule(module, in.getModuleRequest());
      Preconditions.checkState(importedModule != null);
      return in.getImportName() == null
          //   import * as ns from "mod"
          ? new ModuleNamePair(importedModule, null)
          //   import {a} from "mod"
          //   import a from "mod"
          : importedModule.resolveExport(in.getImportName());
    }
  }

  /**
   * Replace resolved binding with its orignal binding.
   */
  private void rewriteWithBinding(NodeTraversal t, Node n, Node parent,
      ModuleNamePair binding, String originalName) {

    String newName;
    if (binding.name == null) {
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

    if (n.isName()) {
      n.setString(newName);
      if (originalName != null) {
        n.putProp(Node.ORIGINALNAME_PROP, originalName);
      }
    } else {
      Node ref = NodeUtil.newName(compiler, newName, n);
      if (originalName != null) {
        ref.putProp(Node.ORIGINALNAME_PROP, originalName);
      }
      parent.replaceChild(n, ref);
    }
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

    ModuleNamePair binding;
    List<String> rest = ImmutableList.of();

    if (ES6ModuleLoader.isRelativeIdentifier(name)) {
      //   @type {./foo/bar.baz/qux.quux.Foo}
      int lastSlash = name.lastIndexOf('/');
      int endIndex = name.indexOf('.', lastSlash);

      String required = name.substring(0, endIndex);
      Es6Module mod = moduleRegistry.resolveImportedModule(module, required);
      if (mod == null) {
        // Since JSDocs are not processed in Es6ModuleRegistry#instantiateAllModules(),
        // this module may be unresolvable.
        t.report(n, ES6ModuleLoader.LOAD_ERROR, required);
        return;
      }
      binding = new ModuleNamePair(mod, null);
      if (endIndex >= 0) {
        rest = Splitter.on('.').splitToList(name.substring(endIndex + 1));
      }
    } else {
      List<String> splitted = Splitter.on('.').splitToList(name);
      String baseName = splitted.get(0);
      rest = splitted.subList(1, splitted.size());

      binding = resolveModuleBinding(t, baseName);
      if (binding == null) {
        // Not file global nor imported.
        return;
      }
    }

    // Resolve and collapse imported module namespace object property path.
    if (binding.name == null) {
      Iterator<String> path = rest.iterator();
      do {
        if (!path.hasNext()) {
          t.report(n, MODULE_NAMESPACE_NON_GETPROP);
          return;
        }
        String propertyName = path.next();
        ModuleNamePair resolved = binding.module.getNamespace().get(propertyName);
        if (resolved == null) {
          t.report(n, Es6ModuleRegistry.RESOLVE_EXPORT_FAILURE,
              moduleRegistry.getModuleName(binding.module), propertyName);
          return;
        }
        binding = resolved;
      } while (binding.name == null);

      rest = ImmutableList.copyOf(path);
    }

    Preconditions.checkState(binding.name != null);
    String newName = toGlobalName(binding);
    if (!rest.isEmpty()) {
      newName += "." + Joiner.on('.').join(rest);
    }
    n.setString(newName);
    n.putProp(Node.ORIGINALNAME_PROP, name);
  }

  /**
   * Misc SCRIPT root processings.
   */
  private void visitScript(NodeTraversal t, Node n) {

    // Do nothing if empty.
    // If it's empty, all nodes were removed from the tree. Eg.
    //
    //   import * as ns from 'mod';
    //   export {ns};
    if (!n.hasChildren()) {
      return;
    }

    checkStrictModeDirective(t, n);

    JSDocInfoBuilder jsDocInfo = n.getJSDocInfo() == null
        ? new JSDocInfoBuilder(false)
        : JSDocInfoBuilder.copyFrom(n.getJSDocInfo());
    if (!jsDocInfo.isPopulatedWithFileOverview()) {
      jsDocInfo.recordFileOverview("");
    }
    // Don't check provides and requires, since most of them are auto-generated.
    jsDocInfo.recordSuppressions(ImmutableSet.of("missingProvide", "missingRequire"));
    n.setJSDocInfo(jsDocInfo.build());

    compiler.reportCodeChange();
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
