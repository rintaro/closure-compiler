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

import static com.google.javascript.jscomp.Es6RewriteModule.LHS_OF_GOOG_REQUIRE_MUST_BE_CONST;

import com.google.common.collect.ImmutableList;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;
import com.google.javascript.rhino.Node;

import java.util.List;
import java.util.ArrayList;

/**
 * Unit tests for {@link ProcessEs6Modules}
 */

public final class Es6RewriteModuleTest extends CompilerTestCase {

  static final String FILE_OVERVIEW = LINE_JOINER.join(
      "/** @fileoverview",
      " * @suppress {missingProvide|missingRequire}",
      " */");

  public Es6RewriteModuleTest() {
    compareJsDoc = true;
  }

  @Override
  public void setUp() {
    // ECMASCRIPT5 to trigger module processing after parsing.
    setLanguage(LanguageMode.ECMASCRIPT6, LanguageMode.ECMASCRIPT5);
    enableAstValidation(true);
    runTypeCheckAfterProcessing = true;
  }

  @Override
  protected CompilerOptions getOptions() {
    CompilerOptions options = super.getOptions();
    // ECMASCRIPT5 to Trigger module processing after parsing.
    options.setLanguageOut(LanguageMode.ECMASCRIPT5);
    return options;
  }

  @Override
  public CompilerPass getProcessor(Compiler compiler) {
    return new Es6RewriteModule(compiler);
  }

  @Override
  protected int getNumRepetitions() {
    return 1;
  }

  static void testModules(CompilerTestCase test, String input, String expected) {
    // Shared with ProcessCommonJSModulesTest.
    String fileName = test.getFilename() + ".js";
    ImmutableList<SourceFile> inputs =
        ImmutableList.of(SourceFile.fromCode("other.js", ""), SourceFile.fromCode(fileName, input));
    ImmutableList<SourceFile> expecteds =
        ImmutableList.of(
            SourceFile.fromCode("other.js", ""),
            SourceFile.fromCode(fileName, FILE_OVERVIEW + expected));
    test.test(inputs, expecteds);
  }

  static void testModules(
      CompilerTestCase test, ImmutableList<SourceFile> inputs, String expected) {
    ImmutableList<SourceFile> expecteds =
        ImmutableList.of(
            SourceFile.fromCode("other.js", ""),
            SourceFile.fromCode(test.getFilename() + ".js", expected));
    test.test(inputs, expecteds);
  }

  void testModules(String input, String expected) {
    testModules(this, input, expected);
  }

  private static void testModules(CompilerTestCase test, String input, DiagnosticType error) {
    String fileName = test.getFilename() + ".js";
    ImmutableList<SourceFile> inputs =
        ImmutableList.of(SourceFile.fromCode("other.js", ""), SourceFile.fromCode(fileName, input));
    test.test(inputs, null, error);
  }

  private void testModules(String input, DiagnosticType error) {
    testModules(this, input, error);
  }

  private SourceFile source(String filename, String... lines) {
    return SourceFile.fromCode(filename, LINE_JOINER.join(lines));
  }

  public void testExport() {
    test(
        "export var a = 1, b = 2;",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "var a$$module$testcode = 1, b$$module$testcode = 2;"));

    test(
        "export var a; export var b;",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "var a$$module$testcode; var b$$module$testcode;"));

    test(
        "export function f() {};",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "function f$$module$testcode() {}"));

    test(
        "export function f() {}; function g() { f(); }",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "function f$$module$testcode() {}",
            "function g$$module$testcode() { f$$module$testcode(); }"));

    test(
        LINE_JOINER.join(
            "export function MyClass() {};",
            "MyClass.prototype.foo = function() {};"),
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "function MyClass$$module$testcode() {}",
            "MyClass$$module$testcode.prototype.foo = function() {};"));

    test(
        LINE_JOINER.join(
            "var f = 1; var b = 2;",
            "export {f as foo, b as bar};"),
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "var f$$module$testcode = 1;",
            "var b$$module$testcode = 2;"));

    test(
        "var f = 1; export {f as default};",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "var f$$module$testcode = 1;"));

    test(
        "export {name}; var name;",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "var name$$module$testcode;"));
  }

  public void testExportWithJsDoc() {
    test(
        "/** @constructor */ export function F() { return '';}",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "/** @constructor */",
            "function F$$module$testcode() { return ''; }"));

    test(
        "/** @return {string} */ export function f() { return '';}",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "/** @return {string} */",
            "function f$$module$testcode() { return ''; }"));

    test(
        "/** @return {string} */ export var f = function() { return '';}",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "/** @return {string} */",
            "var f$$module$testcode = function() { return ''; }"));

    test(
        "/** @type {number} */ export var x = 3, y, z = 2",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "/** @type {number} */",
            "var x$$module$testcode = 3, y$$module$testcode, z$$module$testcode = 2;"));
  }

  public void testExportDefault() {
    test(
        LINE_JOINER.join(
            "export default function f(){};",
            "var x = f();"),
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "function f$$module$testcode() {}",
            "var x$$module$testcode = f$$module$testcode();"));

    test(
        LINE_JOINER.join(
            "export default class Foo {};",
            "var x = new Foo;"),
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "class Foo$$module$testcode {}",
            "var x$$module$testcode = new Foo$$module$testcode;"));
  }

  public void testExportDefault_anonymous() {
    test(
        "export default 'someString';",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "var $jscompDefaultExport$$module$testcode = 'someString';"));

    test(
        "var x = 5; export default x;",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "var x$$module$testcode = 5;",
            "var $jscompDefaultExport$$module$testcode = x$$module$testcode;"));

    test(
        "export default class {};",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "var $jscompDefaultExport$$module$testcode = class {};"));

    test(
        "export default function() {}",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "var $jscompDefaultExport$$module$testcode = function() {}"));
  }

  public void testExport_missing() {
    testError(
        "export {foo}",
        Es6RewriteModule.EXPORTED_BINDING_NOT_DECLARED);
    testError(
        "var foo; export {bar as foo};",
        Es6RewriteModule.EXPORTED_BINDING_NOT_DECLARED);
  }

  public void testExportDuplicate() {
    testError(
        "var x, y; export {x as z, y as z};",
        Es6ModuleRegistry.DUPLICATED_EXPORT_NAMES);

    testError(
        "var x, y; export {x as z}; export {y as z};",
        Es6ModuleRegistry.DUPLICATED_EXPORT_NAMES);

    testError(
        "var x; export {x as default}; export default 1;",
        Es6ModuleRegistry.DUPLICATED_EXPORT_NAMES);
  }

  public void testImport() {
    SourceFile other =
        source("other.js",
            "export var name;",
            "export default function use() {}");
    SourceFile otherExpected =
        source("other.js",
            FILE_OVERVIEW,
            "var name$$module$other;",
            "function use$$module$other() {}");

    test(
        ImmutableList.of(
            other,
            source("main.js",
                "import {name} from 'other';",
                "use(name);")),
        ImmutableList.of(
            otherExpected,
            source("main.js",
                FILE_OVERVIEW,
                "use(name$$module$other);")));

    test(
        ImmutableList.of(
            other,
            source("main.js",
                "import use from 'other';",
                "use(name);")),
        ImmutableList.of(
            otherExpected,
            source("main.js",
                FILE_OVERVIEW,
                "use$$module$other(name);")));

    test(
        ImmutableList.of(
            other,
            source("main.js",
                "import use, {name} from 'other';",
                "use(name);")),
        ImmutableList.of(
            otherExpected,
            source("main.js",
                FILE_OVERVIEW,
                "use$$module$other(name$$module$other);")));

    test(
        ImmutableList.of(
            other,
            source("main.js",
                "import use, {name} from 'other';",
                "use(name);")),
        ImmutableList.of(
            otherExpected,
            source("main.js",
                FILE_OVERVIEW,
                "use$$module$other(name$$module$other);")));

    test(
        ImmutableList.of(
            other,
            source("main.js",
                "import * as ns from 'other';",
                "use(name);",
                "ns.default(ns.name);")),
        ImmutableList.of(
            otherExpected,
            source("main.js",
                FILE_OVERVIEW,
                "use(name);",
                "use$$module$other(name$$module$other);")));
  }

  public void testImportDuplicate() {
    SourceFile other = source("other.js", "export default 1; export var x");

    test(
        ImmutableList.of(
            other,
            source("main.js",
                "import z from 'other';",
                "import {x as z} from 'other';")),
        null,
        Es6ParseModule.DUPLICATED_IMPORTED_BOUND_NAMES);

    test(
        ImmutableList.of(
            other,
            source("main.js",
                "import {default as z} from 'other';",
                "import {x as z} from 'other';")),
        null,
        Es6ParseModule.DUPLICATED_IMPORTED_BOUND_NAMES);

    test(
        ImmutableList.of(
            other,
            source("main.js",
                "import {x as z, default as z} from 'other';")),
        null,
        Es6ParseModule.DUPLICATED_IMPORTED_BOUND_NAMES);

    test(
        ImmutableList.of(
            other,
            source("main.js",
                "import z, {x as z} from 'other';")),
        null,
        Es6ParseModule.DUPLICATED_IMPORTED_BOUND_NAMES);
  }

  public void testImportAndExport() {
    test(
        ImmutableList.of(
            source("mod2.js",
                "export var a;"),
            source("mod1.js",
                "import {a as b} from 'mod2';",
                "use(b)",
                "export {b as c};"),
            source("main.js",
                "import {c as d} from 'mod1';",
                "use(d);")),
        ImmutableList.of(
            source("mod2.js",
                FILE_OVERVIEW,
                "var a$$module$mod2;"),
            source("mod1.js",
                FILE_OVERVIEW,
                "use(a$$module$mod2);"),
            source("main.js",
                FILE_OVERVIEW,
                "use(a$$module$mod2);")));

    test(
        ImmutableList.of(
            source("mod2.js",
                "export var a;"),
            source("mod1.js",
                "import {a as b} from 'mod2';",
                "export {b as c};"),
            source("main.js",
                "import {c as d} from 'mod1';",
                "use(d);")),
        ImmutableList.of(
            source("mod2.js",
                FILE_OVERVIEW,
                "var a$$module$mod2;"),
            source("mod1.js",
                ""),
            source("main.js",
                FILE_OVERVIEW,
                "use(a$$module$mod2);")));
  }

  public void testIndirectExport() {
    test(
        ImmutableList.of(
            source("mod2.js",
                "export var a;"),
            source("mod1.js",
                "export {a as b} from 'mod2';"),
            source("main.js",
                "import {b as c} from 'mod1';",
                "use(c);")),
        ImmutableList.of(
            source("mod2.js",
                FILE_OVERVIEW,
                "var a$$module$mod2;"),
            source("mod1.js",
                ""),
            source("main.js",
                FILE_OVERVIEW,
                "use(a$$module$mod2);")));
  }

  public void testStarExport() {
    test(
        ImmutableList.of(
            source("mod2.js",
                "export var a;"),
            source("mod1.js",
                "export * from 'mod2';"),
            source("main.js",
                "import {a} from 'mod1';",
                "use(a);")),
        ImmutableList.of(
            source("mod2.js",
                FILE_OVERVIEW,
                "var a$$module$mod2;"),
            source("mod1.js",
                ""),
            source("main.js",
                FILE_OVERVIEW,
                "use(a$$module$mod2);")));
  }

  public void testIndirectExportNamespace() {
    test(
        ImmutableList.of(
            source("mod2.js",
                "export var a;"),
            source("mod1.js",
                "import * as mod2 from 'mod2';",
                "export {mod2};"),
            source("main.js",
                "import {mod2} from 'mod1';",
                "use(mod2.a);")),
        ImmutableList.of(
            source("mod2.js",
                FILE_OVERVIEW,
                "var a$$module$mod2;"),
            source("mod1.js",
                ""),
            source("main.js",
                FILE_OVERVIEW,
                "use(a$$module$mod2);")));

    test(
        ImmutableList.of(
            source("mod2.js",
                "export var a;"),
            source("mod1.js",
                "import * as mod2 from 'mod2';",
                "export {mod2}"),
            source("main.js",
                "import * as mod1 from 'mod1';",
                "use(mod1.mod2.a);")),
        ImmutableList.of(
            source("mod2.js",
                FILE_OVERVIEW,
                "var a$$module$mod2;"),
            source("mod1.js",
                ""),
            source("main.js",
                FILE_OVERVIEW,
                "use(a$$module$mod2);")));
  }

  public void testFixTypeNode() {
    test(
        ImmutableList.of(
            source("other.js",
                "export default class {}",
                "export class Foo {}",
                "/** @typedef {number|!Object} */export var NumOrObj;"),
            source("main.js",
                "import Def, * as ns from 'other';",
                "/**",
                " * @param {Def} arg1",
                " * @param {ns.Foo} arg2",
                " * @param {ns.NumOrObj} arg3",
                " */",
                "function use(arg1, arg2, arg3) {}")),
        ImmutableList.of(
            source("other.js",
                FILE_OVERVIEW,
                "var $jscompDefaultExport$$module$other = class {}",
                "class Foo$$module$other {}",
                "/** @typedef {number|!Object} */var NumOrObj$$module$other;"),
            source("main.js",
                FILE_OVERVIEW,
                "/**",
                " * @param {$jscompDefaultExport$$module$other} arg1",
                " * @param {Foo$$module$other} arg2",
                " * @param {NumOrObj$$module$other} arg3",
                " */",
                " function use$$module$main(arg1, arg2, arg3) {}")));

    test(
        ImmutableList.of(
            source("other.js",
                "/** @const */ export var repo = {};",
                "/** @const */ repo.Foo = class {}",
                "/** @typedef {number|!Object} */repo.NumOrObj"),
            source("main.js",
                "import * as ns from 'other';",
                "/**",
                " * @param {ns.repo.Foo} arg1",
                " * @param {ns.repo.NumOrObj} arg2",
                " */",
                "function use(arg1, arg2) {}")),
        ImmutableList.of(
            source("other.js",
                FILE_OVERVIEW,
                "/** @const */ var repo$$module$other = {};",
                "/** @const */ repo$$module$other.Foo = class {}",
                "/** @typedef {number|!Object} */repo$$module$other.NumOrObj"),
            source("main.js",
                FILE_OVERVIEW,
                "/**",
                " * @param {repo$$module$other.Foo} arg1",
                " * @param {repo$$module$other.NumOrObj} arg2",
                " */",
                "function use$$module$main(arg1, arg2) {}")));
  }

  public void preserveTypeCast() {
    test(
        ImmutableList.of(
            source("other.js",
                "/** @type {number|!Object} */",
                "export var foo = 42"),
            source("main.js",
                "import * as ns from 'other';",
                "use(/** @type {number} */(ns.foo), foo);")),
        ImmutableList.of(
            source("other.js",
                FILE_OVERVIEW,
                "/** @type {number|!Object} */",
                "var foo$$module$other = 42"),
            source("main.js",
                FILE_OVERVIEW,
                "use(/** @type {number} */(foo$$module$other), foo);")));
  }

  public void testExtendImportedClass() {
    SourceFile parent = source("parent.js",
        "export class Parent {}",
        "export default class {}");
    SourceFile parentExpected = source("parent.js",
        FILE_OVERVIEW,
        "class Parent$$module$parent {}",
        "var $jscompDefaultExport$$module$parent = class {}");

    test(
        ImmutableList.of(
            parent,
            source("main.js",
                "import {Parent} from 'parent';",
                "class Child extends Parent {",
                "  /** @param {Parent} parent */",
                "  useParent(parent) {}",
                "}")),
        ImmutableList.of(
            parentExpected,
            source("main.js",
                FILE_OVERVIEW,
                "class Child$$module$main extends Parent$$module$parent {",
                "  /** @param {Parent$$module$parent} parent */",
                "  useParent(parent) {}",
                "}")));

    test(
        ImmutableList.of(
            parent,
            source("main.js",
                "import Parent from 'parent';",
                "class Child extends Parent {",
                "  /** @param {Parent} parent */",
                "  useParent(parent) {}",
                "}")),
        ImmutableList.of(
            parentExpected,
            source("main.js",
                FILE_OVERVIEW,
                "class Child$$module$main extends $jscompDefaultExport$$module$parent {",
                "  /** @param {$jscompDefaultExport$$module$parent} parent */",
                "  useParent(parent) {}",
                "}")));

    test(
        ImmutableList.of(
            parent,
            source("main.js",
                "import Parent from 'parent';",
                "class Child extends Parent {",
                "  /** @param {./parent.Parent} parent */",
                "  useParent(parent) {}",
                "}")),
        ImmutableList.of(
            parentExpected,
            source("main.js",
                FILE_OVERVIEW,
                "class Child$$module$main extends $jscompDefaultExport$$module$parent {",
                "  /** @param {Parent$$module$parent} parent */",
                "  useParent(parent) {}",
                "}")));

    test(
        ImmutableList.of(
            parent,
            source("child.js",
                "import {Parent} from 'parent';",
                "export * from 'parent';",
                "export class Child extends Parent {",
                "  /** @param {Parent} parent */",
                "  useParent(parent) {}",
                "}"),
            source("main.js",
                "import {Child, Parent} from 'child';",
                "var obj = new Child();",
                "obj.useParent(new Parent())")),
        ImmutableList.of(
            parentExpected,
            source("child.js",
                FILE_OVERVIEW,
                "class Child$$module$child extends Parent$$module$parent {",
                "  /** @param {Parent$$module$parent} parent */",
                "  useParent(parent) {}",
                "}"),
            source("main.js",
                FILE_OVERVIEW,
                "var obj$$module$main = new Child$$module$child();",
                "obj$$module$main.useParent(new Parent$$module$parent)")));
  }

  public void testLoadError() {
    testError(
        "import name from 'module_does_not_exist'; use(name);",
        ES6ModuleLoader.LOAD_ERROR);
    testError(
        "export {name} from 'module_does_not_exist';",
        ES6ModuleLoader.LOAD_ERROR);
    testError(
        LINE_JOINER.join(
          "export var name;",
          "/** @param {./module/does/not/exists.Foo} arg */ function f(arg) {}"),
        ES6ModuleLoader.LOAD_ERROR);
  }

  public void assignImportedName() {
    SourceFile mod1 = source("mod1.js", "export var name = 12");

    test(
        ImmutableList.of(
          mod1,
          source("main.js",
            "import {name} from 'mod1';",
            "name = 42;")),
        null,
        Es6RewriteModule.IMPORTED_BINDING_ASSIGNMENT);

    test(
        ImmutableList.of(
          mod1,
          source("main.js",
            "import {name} from 'mod1';",
            "name++;")),
        null,
        Es6RewriteModule.IMPORTED_BINDING_ASSIGNMENT);

    test(
        ImmutableList.of(
          mod1,
          source("main.js",
            "import * as ns from 'mod1';",
            "ns = 42;")),
        null,
        Es6RewriteModule.IMPORTED_BINDING_ASSIGNMENT);

    test(
        ImmutableList.of(
          mod1,
          source("main.js",
            "import * as ns from 'mod1';",
            "ns.name = 42;")),
        null,
        Es6RewriteModule.IMPORTED_BINDING_ASSIGNMENT);

    test(
        ImmutableList.of(
          mod1,
          source("main.js",
            "import * as ns from 'mod1';",
            "ns.newName = 42;")),
        null,
        Es6RewriteModule.IMPORTED_BINDING_ASSIGNMENT);

    test(
        ImmutableList.of(
          mod1,
          source("mod2.js",
              "import * as ns1 from 'mod1';",
              "export {ns1};"),
          source("main.js",
              "import * as ns2 from 'mod2';",
              "ns2.ns1 = 42")),
        null,
        Es6RewriteModule.IMPORTED_BINDING_ASSIGNMENT);
  }

  public void testAssignImportedObject() {
    test(
        ImmutableList.of(
          source("mod1.js",
              "export var obj = { foo: 12 };"),
          source("main.js",
              "import {obj} from 'mod1';",
              "obj.foo = 42;",
              "obj.newName = 24")),
        ImmutableList.of(
          source("mod1.js",
              FILE_OVERVIEW,
              "var obj$$module$mod1 = { foo: 12 };"),
          source("main.js",
              FILE_OVERVIEW,
              "obj$$module$mod1.foo = 42;",
              "obj$$module$mod1.newName = 24;")));
  }

  public void useNamespaceNonGetProp() {
    SourceFile mod1 = source("mod1.js",
        "export var name");

    test(
        ImmutableList.of(
          mod1,
          source("main.js",
            "import * as ns from 'mod1';",
            "use(ns)")),
        null,
        Es6RewriteModule.MODULE_NAMESPACE_NON_GETPROP);

    SourceFile mod2 = source("mod2.js",
        "import * as ns1 from 'mod1';",
        "export {ns1};");

    test(
        ImmutableList.of(
          mod1,
          mod2,
          source("main.js",
              "import * as ns2 from 'mod2'",
              "use(ns2.ns1)")),
        null,
        Es6RewriteModule.MODULE_NAMESPACE_NON_GETPROP);
  }

  public void testGoogRequires_rewrite() {
    test(
        "const bar = goog.require('foo.bar'); export var x;",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "goog.require('foo.bar');",
            "const bar$$module$testcode = foo.bar;",
            "var x$$module$testcode;"));

    test(
        "export var x; const bar = goog.require('foo.bar');",
        LINE_JOINER.join(
            FILE_OVERVIEW,
            "var x$$module$testcode;",
            "goog.require('foo.bar');",
            "const bar$$module$testcode = foo.bar;"));

    test(
        ImmutableList.of(
          source("other.js"),
          source("main.js",
            "import * as s from 'other'; const bar = goog.require('foo.bar');")),
        ImmutableList.of(
          source("other.js"),
          source("main.js",
            FILE_OVERVIEW,
            "goog.require('foo.bar');",
            "const bar$$module$main = foo.bar;")));

    test(
        ImmutableList.of(
          source("other.js"),
          source("main.js",
            "const bar = goog.require('foo.bar'); import * as s from 'other';")),
        ImmutableList.of(
          source("other.js"),
          source("main.js",
            FILE_OVERVIEW,
            "goog.require('foo.bar');",
            "const bar$$module$main = foo.bar;")));
  }

  public void testGoogRequires_nonConst() {
    testError(
        "var bar = goog.require('foo.bar'); export var x;",
        LHS_OF_GOOG_REQUIRE_MUST_BE_CONST);

    testError(
        "export var x; var bar = goog.require('foo.bar');",
        LHS_OF_GOOG_REQUIRE_MUST_BE_CONST);

    test(
        ImmutableList.of(
          source("other.js"),
          source("main.js",
            "import * as s from 'other'; var bar = goog.require('foo.bar');")),
        null,
        LHS_OF_GOOG_REQUIRE_MUST_BE_CONST);

    test(
        ImmutableList.of(
          source("other.js"),
          source("main.js",
            "var bar = goog.require('foo.bar'); import * as s from 'other';")),
        null,
        LHS_OF_GOOG_REQUIRE_MUST_BE_CONST);
  }

  public void testGoogRequiresDestructuring_rewrite() {
    test(
        ImmutableList.of(
            source("other.js"),
            source("main.js",
                "import * as s from 'other';",
                "const {foo, bar} = goog.require('some.name.space');",
                "use(foo, bar);")),
        ImmutableList.of(
            source("other.js"),
            source("main.js",
                FILE_OVERVIEW,
                "goog.require('some.name.space')",
                "const {",
                "  foo: foo$$module$main,",
                "  bar: bar$$module$main,",
                "} = some.name.space;",
                "use(foo$$module$main, bar$$module$main);")));

    test(
        ImmutableList.of(
            source("other.js"),
            source("main.js",
                "import * as s from 'other';",
                "var {foo, bar} = goog.require('some.name.space');",
                "use(foo, bar);")),
        null,
        LHS_OF_GOOG_REQUIRE_MUST_BE_CONST);

    test(
        ImmutableList.of(
            source("other.js"),
            source("main.js",
                "import * as s from 'other';",
                "let {foo, bar} = goog.require('some.name.space');",
                "use(foo, bar);")),
        null,
        LHS_OF_GOOG_REQUIRE_MUST_BE_CONST);
  }

  public void testSortInputs() throws Exception {
    SourceFile a = source("a.js", "import 'b'; import 'c'");
    SourceFile b = source("b.js", "import 'd'");
    SourceFile c = source("c.js", "import 'd'");
    SourceFile d = source("d.js", "1;");

    // NOTE: The order of (b, c) is not important here. We are testing whether
    // addProvide(), addRequire() is properly working or not.
    assertSortedInputs(ImmutableList.of(a, b, c, d), ImmutableList.of(d, c, b, a));
    assertSortedInputs(ImmutableList.of(d, b, c, a), ImmutableList.of(d, b, c, a));
    assertSortedInputs(ImmutableList.of(d, c, b, a), ImmutableList.of(d, c, b, a));
    assertSortedInputs(ImmutableList.of(d, a, b, c), ImmutableList.of(d, c, b, a));
  }

  private void assertSortedInputs(List<SourceFile> shuffled, List<SourceFile> expected)
      throws Exception {

    CompilerOptions options = new CompilerOptions();
    options.setLanguageIn(LanguageMode.ECMASCRIPT6);
    options.setLanguageOut(LanguageMode.ECMASCRIPT5);
    options
        .getDependencyOptions()
        .setDependencySorting(true)
        .setEs6ModuleOrder(true);

    Compiler compiler = new Compiler(System.err);
    compiler.init(new ArrayList<SourceFile>(), shuffled, options);
    compiler.parseInputs();

    List<SourceFile> result = new ArrayList<>(expected.size());
    for (CompilerInput i : compiler.getInputsInOrder()) {
      result.add(i.getSourceFile());
    }

    assertEquals(expected, result);
  }
}
