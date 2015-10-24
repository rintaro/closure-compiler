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
import com.google.javascript.rhino.Node;

import java.util.List;
import java.util.Collection;
import java.util.Set;
import java.util.Map;
import java.util.LinkedList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.HashMap;


/**
 * Represents subset of ES6 Source Text Module Record.
 *
 * see: http://www.ecma-international.org/ecma-262/6.0/index.html#sec-source-text-module-records
 */
class Es6Module {

  private Es6ModuleRegistry registry;
  private CompilerInput input;
  private List<Node> requestedModules;
  private Map<String, ImportEntry> importEntryMap;
  private List<ExportEntry> localExportEntries;
  private List<ExportEntry> indirectExportEntries;
  private List<ExportEntry> starExportEntries;

  private Namespace namespace;
  private Map<String, ModuleNamePair> resolvedExportMap;

  public Es6Module(
      Es6ModuleRegistry registry,
      CompilerInput input,
      List<Node> requestedModules,
      Map<String, ImportEntry> importEntryMap,
      List<ExportEntry> localExportEntries,
      List<ExportEntry> indirectExportEntries,
      List<ExportEntry> starExportEntries) {

    this.registry = registry;
    this.input = input;
    this.requestedModules = requestedModules;
    this.importEntryMap = importEntryMap;
    this.localExportEntries = localExportEntries;
    this.indirectExportEntries = indirectExportEntries;
    this.starExportEntries = starExportEntries;

    this.namespace = null;
    this.resolvedExportMap = new HashMap<>();
  }

  public CompilerInput getInput() {
    return input;
  }

  public List<Node> getRequestedModules() {
    return requestedModules;
  }

  public boolean hasExports() {
    return !localExportEntries.isEmpty() ||
      !indirectExportEntries.isEmpty() ||
      !starExportEntries.isEmpty();
  }

  public Collection<ImportEntry> getImportEntries() {
    return importEntryMap.values();
  }

  public List<ExportEntry> getLocalExportEntries() {
    return localExportEntries;
  }

  public List<ExportEntry> getIndirectExportEntries() {
    return indirectExportEntries;
  }

  public List<ExportEntry> getStarExportEntries() {
    return starExportEntries;
  }

  public ImportEntry getImportEntry(String localName) {
    return importEntryMap.get(localName);
  }

  /**
   * Laizly create and returns "module namespace exotic object" of
   * this module. This object does not have "default" key.
   */
  public Namespace getNamespace() {
    if(namespace == null) {
      namespace = new Namespace(this, getExportedNames());
    }
    return namespace;
  }

  /**
   * Returns set of all names this module exports.
   */
  public Set<String> getExportedNames() {
    return getExportedNames(new HashSet<Es6Module>());
  }

  /**
   * GetExportedNames( exportStarSet ) Concrete Method implementation.
   *
   * http://www.ecma-international.org/ecma-262/6.0/#sec-getexportednames
   */
  private Set<String> getExportedNames(Set<Es6Module> exportStarSet) {

    if (exportStarSet.contains(this)) {
      return new HashSet<String>();
    }
    exportStarSet.add(this);

    Set<String> exportedNames = new LinkedHashSet<>();
    for(ExportEntry e : localExportEntries) {
      exportedNames.add(e.getExportName());
    }
    for(ExportEntry e : indirectExportEntries) {
      exportedNames.add(e.getExportName());
    }
    for(ExportEntry e : starExportEntries) {
      Es6Module requestedModule = registry.resolveImportedModule(this, e.getModuleRequest());
      if (requestedModule == null) {
        return null;
      }

      Set<String> starNames = requestedModule.getExportedNames(exportStarSet);
      for(String n : starNames) {
        if("default" != n) {
          exportedNames.add(n);
        }
      }
    }
    return exportedNames;
  }

  /**
   * Cached exportName resolution.
   */
  public ModuleNamePair resolveExport(String exportName) {
    if (!resolvedExportMap.containsKey(exportName)) {
      // If resolution not cached yet. Call implementation and cache the result.
      // Note that the result may be null.
      ModuleNamePair resolution = resolveExport(exportName, new HashSet<ModuleNamePair>(), new HashSet<Es6Module>());
      if(resolution == ModuleNamePair.AMBIGUOUS) {
        // we don't have to propagate AMBIGUOUS resolution to outside.
        resolution = null;
      }
      resolvedExportMap.put(exportName, resolution);
      return resolution;
    }
    return resolvedExportMap.get(exportName);
  }

  /**
   * ResolveExport(exportName, resolveSet, exportStarSet) Concrete Method
   * implementation.
   *
   * see: http://www.ecma-international.org/ecma-262/6.0/#sec-resolveexport
   */
  private ModuleNamePair resolveExport(String exportName,
    Set<ModuleNamePair> resolveSet, Set<Es6Module> exportStarSet) {


    ModuleNamePair resolve = new ModuleNamePair(this, exportName);
    if(resolveSet.contains(resolve)) {
      // this is a circular import request.
      return null;
    }
    resolveSet.add(resolve);

    for(ExportEntry e : localExportEntries) {
      if(exportName.equals(e.getExportName())) {
        // module provides the direct binding for this export.
        return new ModuleNamePair(this, e.getLocalName());
      }
    }

    for(ExportEntry e : indirectExportEntries) {
      if(exportName.equals(e.getExportName())) {
        Es6Module importedModule = registry.resolveImportedModule(this, e.getModuleRequest());
        if(importedModule == null) {
          return null;
        }
        if(e.getImportName() == null) {
          // module re-exports another module namespace object.
          return new ModuleNamePair(importedModule, null);
        }
        // module imports a specific binding for this export.
        ModuleNamePair indirectResolution =
            importedModule.resolveExport(e.getImportName(), resolveSet, exportStarSet);
        if(indirectResolution != null) {
          return indirectResolution;
        }
      }
    }

    if("default".equals(exportName)) {
      // A default export was not explicitly defined by this module.
      // NOTE A `default` export cannot be provided by an `export *`.
      return null;
    }

    if(exportStarSet.contains(this)) {
      return null;
    }
    exportStarSet.add(this);

    ModuleNamePair starResolution = null;
    for(ExportEntry e : starExportEntries) {
      Es6Module importedModule = registry.resolveImportedModule(this, e.getModuleRequest());
      if(importedModule == null) {
        return null;
      }
      ModuleNamePair resolution = importedModule.resolveExport(exportName, resolveSet, exportStarSet);
      if (ModuleNamePair.AMBIGUOUS == resolution) {
        return resolution;
      }
      if (resolution != null) {
        if (starResolution == null) {
          starResolution = resolution;
        } else if (!resolution.equals(starResolution)) {
          // there is more than one * import that includes the requested name.
          return ModuleNamePair.AMBIGUOUS;
        }
      }
    }
    return starResolution;
  }

  /**
   * Represents "module namespace exotic object"
   */
  public static class Namespace {
    Es6Module module;
    Set<String> exportedNames;

    public Namespace(Es6Module module, Set<String> exportedNames) {
      this.module = module;
      this.exportedNames = exportedNames;
    }

    public Es6Module getModule() {
      return module;
    }

    public ModuleNamePair get(String name) {
      if (exportedNames.contains(name)) {
        return module.resolveExport(name);
      }
      return null;
    }
  }

  /**
   * Represents ES6 ImportEntry Record.
   *
   * see: http://www.ecma-international.org/ecma-262/6.0/index.html#table-39
   */
  public static class ImportEntry {
    // ModuleSpecifier of ImportDeclaration
    private final Node moduleRequest;
    // name of export entries exported by moduleRequest.
    // `null` indicates namespace object.
    private final Node importName;
    // name for access the imported value from importing module.
    // `null` indicates no imported names. Eg: import from "mod"
    private final Node localName;

    public ImportEntry(Node moduleRequest, Node importName, Node localName) {
      Preconditions.checkArgument(moduleRequest != null);
      this.moduleRequest = moduleRequest;
      this.importName = importName;
      this.localName = localName;
    }

    public String getModuleRequest() {
      return moduleRequest.getString();
    }

    public String getImportName() {
      return importName == null ? null : importName.getString();
    }

    public String getLocalName() {
      return localName == null ? null : localName.getString();
    }

    public Node getModuleRequestNode() {
      return this.moduleRequest;
    }

    public Node getImportNameNode() {
      return this.importName;
    }

    public Node getLocalNameNode() {
      return this.localName;
    }

  }

  /**
   * Represents ES6 ExportEntry Record.
   *
   * see: http://www.ecma-international.org/ecma-262/6.0/index.html#table-41
   */
  public static class ExportEntry {
    // name of this export entry
    private final Node exportName;
    // ModuleSpecifier of ExportDeclaration, null for local export.
    private final Node moduleRequest;
    // importName if moduleRequest is not null, localName otherwise.
    // `null` indicates re-export of module namespace object. Eg. export * from "mod"
    private final Node origName;

    public ExportEntry(Node exportName, Node moduleRequest, Node origName) {
      if(moduleRequest == null) {
        Preconditions.checkArgument(origName != null && exportName != null);
      }
      this.exportName = exportName;
      this.moduleRequest = moduleRequest;
      this.origName = origName;
    }

    public String getExportName() {
      return exportName == null ? null : exportName.getString();
    }

    public String getModuleRequest() {
      return moduleRequest == null ? null : moduleRequest.getString();
    }

    public String getImportName() {
      return moduleRequest == null ? null : origName == null
          ? null : origName.getString();
    }

    public String getLocalName() {
      return moduleRequest != null ? null : origName.getString();
    }

    public Node getModuleRequestNode() {
      return this.moduleRequest;
    }

    public Node getExportNameNode() {
      return this.exportName;
    }

    public Node getLocalNameNode() {
      return moduleRequest != null ? null : this.origName;
    }

    public Node getImportNameNode() {
      return moduleRequest == null ? null : this.origName;
    }
  }

  /**
   * Represents ES6 module.ResolveExport result record, or resolveSet item record.
   *
   * result record: {[[module]]: Module Record, [[bindingName]]: String}
   * resolveSet item: {[[module]]: Module Record, [[exportName]]: String}
   */
  public static class ModuleNamePair {

    private static final ModuleNamePair AMBIGUOUS = new ModuleNamePair();

    // Module that holds this binding name.
    public final Es6Module module;
    // Binding name. 'null' indicates module namespace object.
    public final String name;


    public ModuleNamePair(Es6Module module, String name) {
      Preconditions.checkArgument(module != null);
      this.module = module;
      this.name = name;
    }

    /// Only for AMBIGUOUS
    private ModuleNamePair() {
      this.module = null;
      this.name = null;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof ModuleNamePair)) {
        return false;
      }
      ModuleNamePair  _other = (ModuleNamePair) other;
      return module == _other.module &&
        Objects.equal(name, _other.name);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(module, name);
    }
  }
}
