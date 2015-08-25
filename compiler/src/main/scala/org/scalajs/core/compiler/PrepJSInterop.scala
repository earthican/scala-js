/* Scala.js compiler
 * Copyright 2013 LAMP/EPFL
 * @author Tobias Schlatter
 */

package org.scalajs.core.compiler

import scala.tools.nsc
import nsc._

import scala.collection.immutable.ListMap
import scala.collection.mutable

/** Prepares classes extending js.Any for JavaScript interop
 *
 * This phase does:
 * - Sanity checks for js.Any hierarchy
 * - Annotate subclasses of js.Any to be treated specially
 * - Rewrite calls to scala.Enumeration.Value (include name string)
 * - Create JSExport methods: Dummy methods that are propagated
 *   through the whole compiler chain to mark exports. This allows
 *   exports to have the same semantics than methods.
 *
 * @author Tobias Schlatter
 */
abstract class PrepJSInterop extends plugins.PluginComponent
                                with PrepJSExports
                                with transform.Transform
                                with Compat210Component {
  import PrepJSInterop._

  val jsAddons: JSGlobalAddons {
    val global: PrepJSInterop.this.global.type
  }

  val scalaJSOpts: ScalaJSOptions

  import global._
  import jsAddons._
  import definitions._
  import rootMirror._
  import jsDefinitions._

  val phaseName = "jsinterop"

  override def newPhase(p: nsc.Phase): StdPhase = new JSInteropPhase(p)

  class JSInteropPhase(prev: nsc.Phase) extends Phase(prev) {
    override def name: String = phaseName
    override def description: String = "Prepare ASTs for JavaScript interop"
    override def run(): Unit = {
      jsPrimitives.initPrepJSPrimitives()
      jsInterop.clearRegisteredExports()
      super.run()
    }
  }

  override protected def newTransformer(unit: CompilationUnit): Transformer =
    new JSInteropTransformer(unit)

  private object jsnme { // scalastyle:ignore
    val hasNext  = newTermName("hasNext")
    val next     = newTermName("next")
    val nextName = newTermName("nextName")
    val x        = newTermName("x")
    val Value    = newTermName("Value")
    val Val      = newTermName("Val")
  }

  private object jstpnme { // scalastyle:ignore
    val scala_ = newTypeName("scala") // not defined in 2.10's tpnme
  }

  class JSInteropTransformer(unit: CompilationUnit) extends Transformer {

    // Force evaluation of JSDynamicLiteral: Strangely, we are unable to find
    // nested objects in the JSCode phase (probably after flatten).
    // Therefore we force the symbol of js.Dynamic.literal here in order to
    // have access to it in JSCode.
    JSDynamicLiteral

    /** Kind of the directly enclosing (most nested) owner. */
    private var enclosingOwner: OwnerKind = OwnerKind.None

    /** Cumulative kinds of all enclosing owners. */
    private var allEnclosingOwners: OwnerKind = OwnerKind.None

    /** Nicer syntax for `allEnclosingOwners is kind`. */
    private def anyEnclosingOwner: OwnerKind = allEnclosingOwners

    /** Nicer syntax for `allEnclosingOwners isnt kind`. */
    private object noEnclosingOwner { // scalastyle:ignore
      @inline def is(kind: OwnerKind): Boolean =
        allEnclosingOwners isnt kind
    }

    private def enterOwner[A](kind: OwnerKind)(body: => A): A = {
      require(kind.isBaseKind, kind)
      val oldEnclosingOwner = enclosingOwner
      val oldAllEnclosingOwners = allEnclosingOwners
      enclosingOwner = kind
      allEnclosingOwners |= kind
      try {
        body
      } finally {
        enclosingOwner = oldEnclosingOwner
        allEnclosingOwners = oldAllEnclosingOwners
      }
    }

    /** Tests whether this is a ScalaDoc run.
     *
     *  There are some things we must not do in ScalaDoc runs because, because
     *  ScalaDoc runs don't do everything we need, for example constant-folding
     *  'final val's.
     *
     *  At the same time, it's no big deal to skip these things, because we
     *  won't reach the backend.
     *
     *  We don't completely disable this phase under ScalaDoc mostly because
     *  we want to keep the addition of `RawJSType` annotations, so that they
     *  appear in the doc.
     *
     *  Preparing exports, however, is a pure waste of time, which we cannot
     *  do properly anyway because of the aforementioned limitation.
     */
    private def forScaladoc = global.forScaladoc

    /** Whether to check that we have proper literals in some crucial places. */
    private def shouldCheckLiterals = !forScaladoc

    /** Whether to check and prepare exports. */
    private def shouldPrepareExports = !forScaladoc

    /** Whether we can define JS native entities in the current scope. */
    private def allowJSNative =
      noEnclosingOwner is OwnerKind.AnyClass

    /** Whether we can define JS native classes in the current scope. */
    private def allowJSNativeClass =
      noEnclosingOwner is OwnerKind.AnyClass

    /** Whether we can define any class/trait/method in the current scope.
     *
     *  This is overridden by [[allowJSNativeClass]] if the latter is true.
     */
    private def allowImplDef = noEnclosingOwner is OwnerKind.JSNative

    /** DefDefs in class templates that export methods to JavaScript */
    private val exporters = mutable.Map.empty[Symbol, mutable.ListBuffer[Tree]]

    override def transform(tree: Tree): Tree = postTransform { tree match {
      // Catch special case of ClassDef in ModuleDef
      case cldef: ClassDef if allowJSNativeClass && isJSAny(cldef) =>
        transformJSAny(cldef)

      // Catch forbidden implDefs
      case idef: ImplDef if !allowImplDef =>
        reporter.error(idef.pos, "Native JS traits, classes and objects " +
            "may not have inner traits, classes or objects")
        super.transform(tree)

      // Handle js.Anys
      case idef: ImplDef if isJSAny(idef) =>
        transformJSAny(idef)

      // @ScalaJSDefined is only valid on a js.Any
      case idef: ImplDef if idef.symbol.hasAnnotation(ScalaJSDefinedAnnotation) =>
        reporter.error(idef.pos,
            "@ScalaJSDefined is only allowed on classes extending js.Any")
        super.transform(tree)

      // Catch the definition of scala.Enumeration itself
      case cldef: ClassDef if cldef.symbol == ScalaEnumClass =>
        enterOwner(OwnerKind.EnumImpl) { super.transform(cldef) }

      // Catch Scala Enumerations to transform calls to scala.Enumeration.Value
      case idef: ImplDef if isScalaEnum(idef) =>
        val kind =
          if (idef.isInstanceOf[ModuleDef]) OwnerKind.EnumMod
          else OwnerKind.EnumClass
        enterOwner(kind) { super.transform(idef) }

      // Catch (Scala) ClassDefs to forbid js.Anys
      case cldef: ClassDef =>
        val sym = cldef.symbol
        if (shouldPrepareExports && sym.isTrait) {
          // Check that interface/trait is not exported
          for {
            exp <- exportsOf(sym)
            if !exp.ignoreInvalid
          } reporter.error(exp.pos, "You may not export a trait")
        }

        enterOwner(OwnerKind.NonEnumScalaClass) { super.transform(cldef) }

      // Module export sanity check (export generated in JSCode phase)
      case modDef: ModuleDef =>
        if (shouldPrepareExports)
          registerModuleExports(modDef.symbol.moduleClass)

        enterOwner(OwnerKind.NonEnumScalaMod) { super.transform(modDef) }

      // Catch ValOrDefDef in js.Any
      case vddef: ValOrDefDef if enclosingOwner is OwnerKind.RawJSType =>
        transformValOrDefDefInRawJSType(vddef)

      // Exporter generation
      case ddef: DefDef =>
        if (shouldPrepareExports) {
          // Generate exporters for this ddef if required
          exporters.getOrElseUpdate(ddef.symbol.owner,
              mutable.ListBuffer.empty) ++= genExportMember(ddef)
        }
        super.transform(tree)

      // Catch ValDefs in enumerations with simple calls to Value
      case ValDef(mods, name, tpt, ScalaEnumValue.NoName(optPar))
          if anyEnclosingOwner is OwnerKind.Enum =>
        val nrhs = ScalaEnumValName(tree.symbol.owner, tree.symbol, optPar)
        treeCopy.ValDef(tree, mods, name, transform(tpt), nrhs)

      // Catch Select on Enumeration.Value we couldn't transform but need to
      // we ignore the implementation of scala.Enumeration itself
      case ScalaEnumValue.NoName(_) if noEnclosingOwner is OwnerKind.EnumImpl =>
        reporter.warning(tree.pos,
            """Couldn't transform call to Enumeration.Value.
              |The resulting program is unlikely to function properly as this
              |operation requires reflection.""".stripMargin)
        super.transform(tree)

      case ScalaEnumValue.NullName() if noEnclosingOwner is OwnerKind.EnumImpl =>
        reporter.warning(tree.pos,
            """Passing null as name to Enumeration.Value
              |requires reflection at runtime. The resulting
              |program is unlikely to function properly.""".stripMargin)
        super.transform(tree)

      case ScalaEnumVal.NoName(_) if noEnclosingOwner is OwnerKind.EnumImpl =>
        reporter.warning(tree.pos,
            """Calls to the non-string constructors of Enumeration.Val
              |require reflection at runtime. The resulting
              |program is unlikely to function properly.""".stripMargin)
        super.transform(tree)

      case ScalaEnumVal.NullName() if noEnclosingOwner is OwnerKind.EnumImpl =>
        reporter.warning(tree.pos,
            """Passing null as name to a constructor of Enumeration.Val
              |requires reflection at runtime. The resulting
              |program is unlikely to function properly.""".stripMargin)
        super.transform(tree)

      // Catch calls to Predef.classOf[T]. These should NEVER reach this phase
      // but unfortunately do. In normal cases, the typer phase replaces these
      // calls by a literal constant of the given type. However, when we compile
      // the scala library itself and Predef.scala is in the sources, this does
      // not happen.
      //
      // The trees reach this phase under the form:
      //
      //   scala.this.Predef.classOf[T]
      //
      // If we encounter such a tree, depending on the plugin options, we fail
      // here or silently fix those calls.
      case TypeApply(
          classOfTree @ Select(Select(This(jstpnme.scala_), nme.Predef), nme.classOf),
          List(tpeArg)) =>
        if (scalaJSOpts.fixClassOf) {
          // Replace call by literal constant containing type
          if (typer.checkClassType(tpeArg)) {
            typer.typed { Literal(Constant(tpeArg.tpe.dealias.widen)) }
          } else {
            reporter.error(tpeArg.pos, s"Type ${tpeArg} is not a class type")
            EmptyTree
          }
        } else {
          reporter.error(classOfTree.pos,
              """This classOf resulted in an unresolved classOf in the jscode
                |phase. This is most likely a bug in the Scala compiler. ScalaJS
                |is probably able to work around this bug. Enable the workaround
                |by passing the fixClassOf option to the plugin.""".stripMargin)
          EmptyTree
        }

      // Fix for issue with calls to js.Dynamic.x()
      // Rewrite (obj: js.Dynamic).x(...) to obj.applyDynamic("x")(...)
      case Select(Select(trg, jsnme.x), nme.apply) if isJSDynamic(trg) =>
        val newTree = atPos(tree.pos) {
          Apply(
              Select(super.transform(trg), newTermName("applyDynamic")),
              List(Literal(Constant("x")))
          )
        }
        typer.typed(newTree, Mode.FUNmode, tree.tpe)


      // Fix for issue with calls to js.Dynamic.x()
      // Rewrite (obj: js.Dynamic).x to obj.selectDynamic("x")
      case Select(trg, jsnme.x) if isJSDynamic(trg) =>
        val newTree = atPos(tree.pos) {
          Apply(
              Select(super.transform(trg), newTermName("selectDynamic")),
              List(Literal(Constant("x")))
          )
        }
        typer.typed(newTree, Mode.FUNmode, tree.tpe)

      case _ => super.transform(tree)
    } }

    private def postTransform(tree: Tree) = tree match {
      case _ if !shouldPrepareExports =>
        tree

      case Template(parents, self, body) =>
        val clsSym = tree.symbol.owner
        val exports = exporters.get(clsSym).toIterable.flatten
        // Add exports to the template
        treeCopy.Template(tree, parents, self, body ++ exports)

      case memDef: MemberDef =>
        val sym = memDef.symbol
        if (sym.isLocalToBlock && !sym.owner.isCaseApplyOrUnapply) {
          // We exclude case class apply (and unapply) to work around SI-8826
          for {
            exp <- exportsOf(sym)
            if !exp.ignoreInvalid
          } {
            val msg = {
              val base = "You may not export a local definition"
              if (sym.owner.isPrimaryConstructor)
                base + ". To export a (case) class field, use the " +
                "meta-annotation scala.annotation.meta.field like this: " +
                "@(JSExport @field)."
              else
                base
            }
            reporter.error(exp.pos, msg)
          }
        }

        // Expose objects (modules) members of Scala.js-defined JS classes
        if (sym.isModule && (enclosingOwner is OwnerKind.JSClass)) {
          def shouldBeExposed: Boolean = {
            !sym.isSynthetic &&
            !isPrivateMaybeWithin(sym)
          }
          if (shouldBeExposed)
            sym.addAnnotation(ExposedJSMemberAnnot)
        }

        memDef

      case _ => tree
    }

    /**
     * Performs checks and rewrites specific to classes / objects extending
     * js.Any
     */
    private def transformJSAny(implDef: ImplDef) = {
      val sym = implDef.symbol

      lazy val badParent = sym.info.parents find { t =>
        /* We have to allow scala.Dynamic to be able to define js.Dynamic
         * and similar constructs. This causes the unsoundness filed as #1385.
         */
        !(t <:< JSAnyClass.tpe || t =:= AnyRefClass.tpe || t =:= DynamicClass.tpe)
      }

      def isNativeJSTraitType(tpe: Type): Boolean = {
        val sym = tpe.typeSymbol
        sym.isTrait && !sym.hasAnnotation(ScalaJSDefinedAnnotation)
      }

      if (sym.isAnonymousClass && !isJSLambda(sym))
        sym.addAnnotation(ScalaJSDefinedAnnotation)

      val isJSNative = !sym.hasAnnotation(ScalaJSDefinedAnnotation)

      implDef match {
        // Check that we do not have a case modifier
        case _ if implDef.mods.hasFlag(Flag.CASE) =>
          reporter.error(implDef.pos, "Classes and objects extending " +
              "js.Any may not have a case modifier")

        // Check that we do not extends a trait that does not extends js.Any
        case _ if badParent.isDefined && !isJSLambda(sym) =>
          val badName = badParent.get.typeSymbol.fullName
          reporter.error(implDef.pos, s"${sym.nameString} extends ${badName} " +
              "which does not extend js.Any.")

        // Check that @ScalaJSDefined is not used on an object
        case _ if !isJSNative && !sym.isClass =>
          reporter.error(implDef.pos,
              "Objects cannot be Scala.js-defined")

        // Check that a non-native JS class does not inherit directly from AnyRef
        case _ if !isJSNative && !sym.isTrait &&
            sym.info.parents.exists(_ =:= AnyRefClass.tpe) =>
          reporter.error(implDef.pos,
              "A Scala.js-defined JS class cannot directly extend AnyRef. " +
              "It must extend a JS class (native or not).")

        // Check that we do not inherit directly from a native JS trait
        case _ if !isJSNative && sym.info.parents.exists(isNativeJSTraitType) =>
          val obj = if (sym.isTrait) "trait" else "class"
          reporter.error(implDef.pos,
              s"A Scala.js-defined JS $obj cannot directly extend a native " +
              "JS trait.")

        // Check if we may have a JS native here
        case cldef: ClassDef
            if isJSNative && !allowJSNative && !allowJSNativeClass && !isJSLambda(sym) =>
          reporter.error(implDef.pos, "Native JS classes may not be " +
              "defined inside a class or trait")

        case _: ModuleDef if isJSNative && !allowJSNative =>
          reporter.error(implDef.pos, "Native JS objects may not be " +
              "defined inside a class or trait")

        case _ if isJSNative && sym.isLocalToBlock && !isJSLambda(sym) =>
          reporter.error(implDef.pos, "Local native JS classes and objects " +
              "are not allowed")

        // Check that this is not a class extending js.GlobalScope
        case _: ClassDef if isJSGlobalScope(implDef) &&
            implDef.symbol != JSGlobalScopeClass =>
          reporter.error(implDef.pos, "Only objects may extend js.GlobalScope")

        case _ =>
          // We cannot use sym directly, since the symbol
          // of a module is not its type's symbol but the value it declares
          val tSym = sym.tpe.typeSymbol

          tSym.setAnnotations(rawJSAnnot :: sym.annotations)
      }

      if (isJSNative && shouldPrepareExports) {
        // Check that a JS native type is not exported
        for {
          exp <- exportsOf(sym)
          if !exp.ignoreInvalid
        } {
          reporter.error(exp.pos,
              "You may not export a native JS class or object")
        }
      }

      if (shouldCheckLiterals)
        checkJSNameLiteral(sym)

      val kind = {
        if (!isJSNative) OwnerKind.JSClass
        else if (implDef.isInstanceOf[ModuleDef]) OwnerKind.JSNativeMod
        else OwnerKind.JSNativeClass
      }
      enterOwner(kind) { super.transform(implDef) }
    }

    /** Verify a ValOrDefDef inside a js.Any */
    private def transformValOrDefDefInRawJSType(tree: ValOrDefDef) = {
      val sym = tree.symbol

      // Exports are only valid on constructors of Scala.js-defined classes
      if (shouldPrepareExports) {
        if (sym.isClassConstructor && (enclosingOwner is OwnerKind.JSClass)) {
          // Implementation restriction
          for (exp <- exportsOf(sym)) {
            // Also report ignore-invalids, since they will become valid later!
            reporter.error(exp.pos,
                "Implementation restriction: exporting a Scala.js-defined " +
                "JS class is not supported")
          }
        } else {
          // Anything else is illegal
          lazy val memType = if (sym.isConstructor) "constructor" else "method"

          for {
            exp <- exportsOf(sym)
            if !exp.ignoreInvalid
          } {
            reporter.error(exp.pos,
                s"You may not export a $memType of a subclass of js.Any")
          }
        }

        /* Add the @ExposedJSMember annotation to exposed symbols in
         * Scala.js-defined classes.
         */
        if (enclosingOwner is OwnerKind.JSClass) {
          def shouldBeExposed: Boolean = {
            !sym.isConstructor &&
            !sym.isValueParameter &&
            !sym.isParamWithDefault &&
            !sym.isSynthetic &&
            !isPrivateMaybeWithin(sym)
          }
          if (shouldBeExposed) {
            sym.addAnnotation(ExposedJSMemberAnnot)

            /* The field being accessed must also be exposed, although it's
             * private.
             */
            if (sym.isAccessor)
              sym.accessed.addAnnotation(ExposedJSMemberAnnot)
          }
        }
      }

      /* If this is an accessor, copy a potential @JSName annotation from
       * the field since otherwise it will get lost for traits (since they
       * have no fields).
       */
      if (sym.isAccessor)
        sym.accessed.getAnnotation(JSNameAnnotation).foreach(sym.addAnnotation)

      if (sym.name == nme.apply && !sym.hasAnnotation(JSNameAnnotation)) {
        if (jsInterop.isJSGetter(sym)) {
          reporter.error(sym.pos, s"A member named apply represents function " +
              "application in JavaScript. A parameterless member should be " +
              "exported as a property. You must add @JSName(\"apply\")")
        } else if (enclosingOwner is OwnerKind.JSClass) {
          reporter.error(sym.pos,
              "A Scala.js-defined JavaScript class cannot declare a method " +
              "named `apply` without `@JSName`")
        }
      }

      if (jsInterop.isJSSetter(sym))
        checkSetterSignature(sym, tree.pos, exported = false)

      if (jsInterop.isJSBracketAccess(sym)) {
        if (enclosingOwner is OwnerKind.JSClass) {
          reporter.error(tree.pos,
              "@JSBracketAccess is not allowed in Scala.js-defined JS classes")
        }
      }

      if (jsInterop.isJSBracketCall(sym)) {
        if (enclosingOwner is OwnerKind.JSClass) {
          reporter.error(tree.pos,
              "@JSBracketCall is not allowed in Scala.js-defined JS classes")
        } else {
          // JS bracket calls must have at least one non-repeated parameter
          sym.tpe.paramss match {
            case (param :: _) :: _ if !isScalaRepeatedParamType(param.tpe) =>
              // ok
            case _ =>
              reporter.error(tree.pos, "@JSBracketCall methods must have at " +
                  "least one non-repeated parameter")
          }
        }
      }

      if (sym.hasAnnotation(NativeAttr)) {
        // Native methods are not allowed
        reporter.error(tree.pos, "Methods in a js.Any may not be @native")
      }

      if (shouldCheckLiterals)
        checkJSNameLiteral(sym)

      if (enclosingOwner is OwnerKind.JSClass) {
        // Private methods cannot be overloaded
        if (sym.isMethod && isPrivateMaybeWithin(sym)) {
          val alts = sym.owner.info.member(sym.name).filter(_.isMethod)
          if (alts.isOverloaded) {
            reporter.error(tree.pos,
                "Private methods in Scala.js-defined JS classes cannot be " +
                "overloaded. Use different names instead.")
          }
        }

        // private[Scope] methods must be final
        if (sym.isMethod && (sym.hasAccessBoundary && !sym.isProtected) &&
            !sym.isFinal) {
          reporter.error(tree.pos,
              "Qualified private members in Scala.js-defined JS classes " +
              "must be final")
        }

        // Traits must be pure interfaces
        if (sym.owner.isTrait && sym.isTerm && !sym.isConstructor) {
          if (!sym.isDeferred) {
            reporter.error(tree.pos,
                "A Scala.js-defined JS trait can only contain abstract members")
          } else if (isPrivateMaybeWithin(sym)) {
            reporter.error(tree.pos,
                "A Scala.js-defined JS trait cannot contain private members")
          }
        }
      }

      if (sym.isPrimaryConstructor || sym.isValueParameter ||
          sym.isParamWithDefault || sym.isAccessor ||
          sym.isParamAccessor || sym.isDeferred || sym.isSynthetic ||
          AllJSFunctionClasses.contains(sym.owner) ||
          (enclosingOwner is OwnerKind.JSClass)) {
        /* Ignore (i.e. allow) primary ctor, parameters, default parameter
         * getters, accessors, param accessors, abstract members, synthetic
         * methods (to avoid double errors with case classes, e.g. generated
         * copy method), js.Functions and js.ThisFunctions (they need abstract
         * methods for SAM treatment), and any member of a Scala.js-defined
         * JS class/trait.
         */
      } else if (jsPrimitives.isJavaScriptPrimitive(sym)) {
        // Force rhs of a primitive to be `sys.error("stub")` except for the
        // js.native primitive which displays an elaborate error message
        if (sym != JSPackage_native) {
          tree.rhs match {
            case Apply(trg, Literal(Constant("stub")) :: Nil)
                if trg.symbol == definitions.Sys_error =>
            case _ =>
              reporter.error(tree.pos,
                  "The body of a primitive must be `sys.error(\"stub\")`.")
          }
        }
      } else if (sym.isConstructor) {
        // Force secondary ctor to have only a call to the primary ctor inside
        tree.rhs match {
          case Block(List(Apply(trg, _)), Literal(Constant(())))
              if trg.symbol.isPrimaryConstructor &&
                 trg.symbol.owner == sym.owner =>
            // everything is fine here
          case _ =>
            reporter.error(tree.pos, "A secondary constructor of a class " +
                "extending js.Any may only call the primary constructor")
        }
      } else {
        // Check that the tree's body is either empty or calls js.native
        tree.rhs match {
          case sel: Select if sel.symbol == JSPackage_native =>
          case _ =>
            val pos = if (tree.rhs != EmptyTree) tree.rhs.pos else tree.pos
            reporter.warning(pos, "Members of traits, classes and objects " +
              "extending js.Any may only contain members that call js.native. " +
              "This will be enforced in 1.0.")
        }

        if (sym.tpe.resultType.typeSymbol == NothingClass &&
            tree.tpt.asInstanceOf[TypeTree].original == null) {
          // Warn if resultType is Nothing and not ascribed
          val name = sym.name.decoded.trim
          reporter.warning(tree.pos, s"The type of $name got inferred " +
              "as Nothing. To suppress this warning, explicitly ascribe " +
              "the type.")
        }
      }

      super.transform(tree)
    }

  }

  def isJSAny(sym: Symbol): Boolean =
    sym.tpe.typeSymbol isSubClass JSAnyClass

  /** Checks that a setter has the right signature.
   *
   *  Reports error messages otherwise.
   */
  def checkSetterSignature(sym: Symbol, pos: Position, exported: Boolean): Unit = {
    val typeStr = if (exported) "Exported" else "Raw JS"

    // Forbid setters with non-unit return type
    if (sym.tpe.resultType.typeSymbol != UnitClass) {
      reporter.error(pos, s"$typeStr setters must return Unit")
    }

    // Forbid setters with more than one argument
    sym.tpe.paramss match {
      case List(List(arg)) =>
        // Arg list is OK. Do additional checks.
        if (isScalaRepeatedParamType(arg.tpe))
          reporter.error(pos, s"$typeStr setters may not have repeated params")

        if (arg.hasFlag(reflect.internal.Flags.DEFAULTPARAM)) {
          val msg = s"$typeStr setters may not have default params"
          if (exported)
            reporter.warning(pos, msg + ". This will be enforced in 1.0.")
          else
            reporter.error(pos, msg)
        }

      case _ =>
        reporter.error(pos, s"$typeStr setters must have exactly one argument")
    }
  }

  private def isJSAny(implDef: ImplDef): Boolean = isJSAny(implDef.symbol)

  private def isJSGlobalScope(implDef: ImplDef) =
    implDef.symbol.tpe.typeSymbol isSubClass JSGlobalScopeClass

  private def isJSLambda(sym: Symbol) = sym.isAnonymousClass &&
    AllJSFunctionClasses.exists(sym.tpe.typeSymbol isSubClass _)

  private def isScalaEnum(implDef: ImplDef) =
    implDef.symbol.tpe.typeSymbol isSubClass ScalaEnumClass

  private def isJSDynamic(tree: Tree) = tree.tpe.typeSymbol == JSDynamicClass

  /** Tests whether the symbol has `private` in any form, either `private`,
   *  `private[this]` or `private[Enclosing]`.
   */
  private def isPrivateMaybeWithin(sym: Symbol): Boolean =
    sym.isPrivate || (sym.hasAccessBoundary && !sym.isProtected)

  /** Checks that argument to @JSName on [[sym]] is a literal.
   *  Reports an error on each annotation where this is not the case.
   */
  private def checkJSNameLiteral(sym: Symbol): Unit = {
    for {
      annot <- sym.getAnnotation(JSNameAnnotation)
      if annot.stringArg(0).isEmpty
    } {
      reporter.error(annot.pos,
        "The argument to JSName must be a literal string")
    }
  }

  private trait ScalaEnumFctExtractors {
    protected val methSym: Symbol

    protected def resolve(ptpes: Symbol*) = {
      val res = methSym suchThat {
        _.tpe.params.map(_.tpe.typeSymbol) == ptpes.toList
      }
      assert(res != NoSymbol)
      res
    }

    protected val noArg    = resolve()
    protected val nameArg  = resolve(StringClass)
    protected val intArg   = resolve(IntClass)
    protected val fullMeth = resolve(IntClass, StringClass)

    /**
     * Extractor object for calls to the targeted symbol that do not have an
     * explicit name in the parameters
     *
     * Extracts:
     * - `sel: Select` where sel.symbol is targeted symbol (no arg)
     * - Apply(meth, List(param)) where meth.symbol is targeted symbol (i: Int)
     */
    object NoName {
      def unapply(t: Tree): Option[Option[Tree]] = t match {
        case sel: Select if sel.symbol == noArg =>
          Some(None)
        case Apply(meth, List(param)) if meth.symbol == intArg =>
          Some(Some(param))
        case _ =>
          None
      }
    }

    object NullName {
      def unapply(tree: Tree): Boolean = tree match {
        case Apply(meth, List(Literal(Constant(null)))) =>
          meth.symbol == nameArg
        case Apply(meth, List(_, Literal(Constant(null)))) =>
          meth.symbol == fullMeth
        case _ => false
      }
    }

  }

  private object ScalaEnumValue extends {
    protected val methSym = getMemberMethod(ScalaEnumClass, jsnme.Value)
  } with ScalaEnumFctExtractors

  private object ScalaEnumVal extends {
    protected val methSym = {
      val valSym = getMemberClass(ScalaEnumClass, jsnme.Val)
      valSym.tpe.member(nme.CONSTRUCTOR)
    }
  } with ScalaEnumFctExtractors

  /**
   * Construct a call to Enumeration.Value
   * @param thisSym  ClassSymbol of enclosing class
   * @param nameOrig Symbol of ValDef where this call will be placed
   *                 (determines the string passed to Value)
   * @param intParam Optional tree with Int passed to Value
   * @return Typed tree with appropriate call to Value
   */
  private def ScalaEnumValName(
      thisSym: Symbol,
      nameOrig: Symbol,
      intParam: Option[Tree]) = {

    val defaultName = nameOrig.asTerm.getterName.encoded


    // Construct the following tree
    //
    //   if (nextName != null && nextName.hasNext)
    //     nextName.next()
    //   else
    //     <defaultName>
    //
    val nextNameTree = Select(This(thisSym), jsnme.nextName)
    val nullCompTree =
      Apply(Select(nextNameTree, nme.NE), Literal(Constant(null)) :: Nil)
    val hasNextTree = Select(nextNameTree, jsnme.hasNext)
    val condTree = Apply(Select(nullCompTree, nme.ZAND), hasNextTree :: Nil)
    val nameTree = If(condTree,
        Apply(Select(nextNameTree, jsnme.next), Nil),
        Literal(Constant(defaultName)))
    val params = intParam.toList :+ nameTree

    typer.typed {
      Apply(Select(This(thisSym), jsnme.Value), params)
    }
  }

  private def rawJSAnnot =
    Annotation(RawJSTypeAnnot.tpe, List.empty, ListMap.empty)

  private lazy val ScalaEnumClass = getRequiredClass("scala.Enumeration")

  /** checks if the primary constructor of the ClassDef `cldef` does not
   *  take any arguments
   */
  private def primCtorNoArg(cldef: ClassDef) =
    getPrimCtor(cldef.symbol.tpe).map(_.paramss == List(List())).getOrElse(true)

  /** return the MethodSymbol of the primary constructor of the given type
   *  if it exists
   */
  private def getPrimCtor(tpe: Type) =
    tpe.declaration(nme.CONSTRUCTOR).alternatives.collectFirst {
      case ctor: MethodSymbol if ctor.isPrimaryConstructor => ctor
    }

}

object PrepJSInterop {
  private final class OwnerKind(val baseKinds: Int) extends AnyVal {
    import OwnerKind._

    @inline def isBaseKind: Boolean =
      Integer.lowestOneBit(baseKinds) == baseKinds && baseKinds != 0 // exactly 1 bit on

    @inline def |(that: OwnerKind): OwnerKind =
      new OwnerKind(this.baseKinds | that.baseKinds)

    @inline def is(that: OwnerKind): Boolean =
      (this.baseKinds & that.baseKinds) != 0

    @inline def isnt(that: OwnerKind): Boolean =
      !this.is(that)
  }

  private object OwnerKind {
    /** No owner, i.e., we are at the top-level. */
    val None = new OwnerKind(0x00)

    // Base kinds - those form a partition of all possible enclosing owners

    /** A Scala class/trait that does not extend Enumeration. */
    val NonEnumScalaClass = new OwnerKind(0x01)
    /** A Scala object that does not extend Enumeration. */
    val NonEnumScalaMod = new OwnerKind(0x02)
    /** A native JS class/trait, which extends js.Any. */
    val JSNativeClass = new OwnerKind(0x04)
    /** A native JS object, which extends js.Any. */
    val JSNativeMod = new OwnerKind(0x08)
    /** A Scala.js-defined JS class/trait. */
    val JSClass = new OwnerKind(0x10)
    /** A Scala class/trait that extends Enumeration. */
    val EnumClass = new OwnerKind(0x20)
    /** A Scala object that extends Enumeration. */
    val EnumMod = new OwnerKind(0x40)
    /** The Enumeration class itself. */
    val EnumImpl = new OwnerKind(0x80)

    // Compound kinds

    /** A Scala class/trait, possibly Enumeration-related. */
    val ScalaClass = NonEnumScalaClass | EnumClass | EnumImpl
    /** A Scala object, possibly Enumeration-related. */
    val ScalaMod = NonEnumScalaMod | EnumMod
    /** A Scala class, trait or object, i.e., anything not extending js.Any. */
    val ScalaThing = ScalaClass | ScalaMod

    /** A Scala class/trait/object extending Enumeration, but not Enumeration itself. */
    val Enum = EnumClass | EnumMod

    /** A native JS class/trait/object. */
    val JSNative = JSNativeClass | JSNativeMod
    /** A raw JS type, i.e., something extending js.Any. */
    val RawJSType = JSNative | JSClass

    /** Anything defined in Scala.js, i.e., anything but a native JS declaration. */
    val ScalaJSDefined = ScalaThing | JSClass
    /** Any kind of class/trait, i.e., a Scala or raw JS class/trait. */
    val AnyClass = ScalaClass | JSNativeClass | JSClass
  }
}
