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

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.javascript.rhino.Node;
import com.google.javascript.jscomp.Es6Module.ExportEntry;
import com.google.javascript.jscomp.Es6Module.ImportEntry;
import com.google.javascript.jscomp.Es6Module.ModuleNamePair;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;

/**
 * Holds all ES6Module objects in this compilation.
 * And emulate host wide semantics in module processing.
 */
class Es6ModuleRegistry {


  static final DiagnosticType DUPLICATED_EXPORT_NAMES = DiagnosticType.error(
      "JSC_ES6_DUPLICATED_EXPORT_NAMES",
      "Duplicated export name: {0}");

  static final DiagnosticType RESOLVE_EXPORT_FAILURE = DiagnosticType.error(
      "JSC_ES6_RESOLVE_EXPORT_FAILURE",
      "Failed to resolve exported name \"{1}\" in module \"{0}\"");

  public static String MODULE_NAME_PREFIX = "module$";

  // For error reporting.
  private AbstractCompiler compiler;
  private ES6ModuleLoader loader;
  private BiMap<String, Es6Module> moduleMap = HashBiMap.create();

  public Es6ModuleRegistry(AbstractCompiler compiler, ES6ModuleLoader loader) {
    this.compiler = compiler;
    this.loader = loader;
  }

  public void addModule(CompilerInput input,
      List<Node> requestedModules,
      List<ImportEntry> importEntries,
      List<ExportEntry> exportEntries) {

    URI loadAddress = loader.normalizeInputAddress(input);
    String moduleName = ES6ModuleLoader.toModuleName(loadAddress);

    Preconditions.checkState(!moduleMap.containsKey(moduleName));

    Map<String, ImportEntry> importEntryMap = new LinkedHashMap<>();
    Set<String> exportedNames = new HashSet<>();
    List<ExportEntry> indirectExportEntries = new ArrayList<>();
    List<ExportEntry> localExportEntries = new ArrayList<>();
    List<ExportEntry> starExportEntries = new ArrayList<>();

    for (ImportEntry ie : importEntries) {
      importEntryMap.put(ie.getLocalName(), ie);
    }

    for (ExportEntry ee : exportEntries) {
      String exportName = ee.getExportName();
      // Error if the ExportedNames of ExportDeclaration
      // contains any duplicate entries
      // http://www.ecma-international.org/ecma-262/6.0/#sec-module-semantics-static-semantics-early-errors
      if (exportName != null && !exportedNames.add(exportName)) {
        compiler.report(JSError.make(ee.getExportNameNode(),
              DUPLICATED_EXPORT_NAMES, exportName));
      }
      if (ee.getModuleRequestNode() == null) {
        ImportEntry ie = importEntryMap.get(ee.getLocalName());
        if (ie == null) {
          // local export
          //
          //    var a = 12; export {a};
          //    export function foo() {}
          // etc.
          localExportEntries.add(ee);
        } else {
          // re-export of imported bindings
          //
          //   import a from "mod"; export {a};
          //   import {b} from "mod"; export {b};
          //   import * as ns from "mod"; export {ns};
          //
          // Note: According to the specification, "re-export of an
          // imported module namespace object" would be added to localExportEntries
          // But since we statically resolve module namespace object in
          // Es6ModuleRewrite, we put them into indirectExportEntries here.
          indirectExportEntries.add(
              new ExportEntry(
                  ee.getExportNameNode(),
                  ie.getModuleRequestNode(),
                  ie.getImportNameNode()));
        }
      } else {
        if(ee.getImportName() == null) {
          //   export * from "mod";
          starExportEntries.add(ee);
        } else {
          //   export {a, b as c} from "mod";
          indirectExportEntries.add(ee);
        }
      }
    }

    loader.addInput(input);
    moduleMap.put(moduleName,
        new Es6Module(this, input, requestedModules, importEntryMap,
            localExportEntries, indirectExportEntries, starExportEntries));
  }

  /**
   * Rough impletation of ModuleDeclarationInstantiation() in ES6 specification.
   *
   * @see "http://www.ecma-international.org/ecma-262/6.0/#sec-moduledeclarationinstantiation"
   */
  public void instantiateAllModules() {

    Set<String> nonModules = new HashSet<>(moduleMap.keySet());

    for (String moduleName : moduleMap.keySet()) {
      Es6Module module = moduleMap.get(moduleName);
      List<Node> requestedModules = module.getRequestedModules();

      // If this imports or export something, this is a ES6 module.
      if(!requestedModules.isEmpty() || module.hasExports()) {
        nonModules.remove(moduleName);
      }

      Set<String> failedModules = new HashSet<>();

      // Ensure all required module are resolvable.
      for (Node required : requestedModules) {
        Es6Module requiredModule = resolveImportedModule(module, required.getString());
        if (requiredModule == null) {
          compiler.report(JSError.make(required, ES6ModuleLoader.LOAD_ERROR, required.getString()));
          failedModules.add(required.getString());
          continue;
        }
        String requiredModuleName = getModuleName(requiredModule);

        // If required from any module, that is a ES6 module.
        nonModules.remove(requiredModuleName);
        module.getInput().addRequire(requiredModuleName);
      }

      // Ensure all named exports from module are resolvable.
      for (ExportEntry e : module.getIndirectExportEntries()) {

        // skip is this entry has failed module request.
        String required = e.getModuleRequest();
        if(required != null && failedModules.contains(required)) {
          continue;
        }

        if (e.getExportName() != null && module.resolveExport(e.getExportName()) == null) {
          compiler.report(JSError.make(e.getExportNameNode(), RESOLVE_EXPORT_FAILURE,
                moduleName, e.getExportName()));
        }
      }

      // Ensure all imported bindings are resolvable.
      for (ImportEntry in : module.getImportEntries()) {

        // skip this entry has failed module request.
        if (failedModules.contains(in.getModuleRequest())) {
          continue;
        }

        // ignore namespace import here.
        String importName = in.getImportName();
        if (importName == null) {
          continue;
        }

        Es6Module requiredModule = resolveImportedModule(module, in.getModuleRequest());
        if (requiredModule != null && requiredModule.resolveExport(importName) == null) {
          compiler.report(JSError.make(in.getImportNameNode(), RESOLVE_EXPORT_FAILURE,
                getModuleName(requiredModule), importName));
        }
      }
    }

    // Here, `nonModules` is a set of "modules" they don't export anything,
    // don't import anything, and not imported from other modules.
    // We treat them as non modules (i.e. Scripts).
    moduleMap.keySet().removeAll(nonModules);

    // Declare provide on all modules.
    for (String moduleName : moduleMap.keySet()) {
      // This is a ES6 module.
      Es6Module module = moduleMap.get(moduleName);
      module.getInput().addProvide(moduleName);
    }
  }

  /**
   * Returns all ES6Module record objects this registry contains.
   */
  public Set<Es6Module> getModules() {
    return moduleMap.values();
  }

  /**
   * Returns canonical module name for specified ES6Module object.
   */
  public String getModuleName(Es6Module module) {
    return moduleMap.inverse().get(module);
  }

  /**
   * HostResolveImportedModule (referencingModule, specifier )
   * implementation.
   *
   * @see "http://www.ecma-international.org/ecma-262/6.0/#sec-hostresolveimportedmodule"
   */
  public Es6Module resolveImportedModule(Es6Module module, String specifier) {
    URI loadAddress = loader.locateEs6Module(specifier, module.getInput());
    if(loadAddress == null) {
      return null;
    }
    String moduleName = ES6ModuleLoader.toModuleName(loadAddress);
    return moduleMap.get(moduleName);
  }

  public Es6Module getModule(CompilerInput input) {
    URI loadAddress = loader.normalizeInputAddress(input);
    if(loadAddress == null) {
      return null;
    }
    String moduleName = ES6ModuleLoader.toModuleName(loadAddress);
    return moduleMap.get(moduleName);
  }

  /**
   * Substitute implementation of GetModuleNamespace( module )
   *
   * http://www.ecma-international.org/ecma-262/6.0/#sec-getmodulenamespace
   */
  public Es6Module.Namespace getModuleNamespace(String moduleName) {
    return getModuleNamespace(moduleMap.get(moduleName));
  }

  public Es6Module.Namespace getModuleNamespace(Es6Module module) {
    if(module != null) {
      return module.getNamespace();
    }
    return null;
  }
}
