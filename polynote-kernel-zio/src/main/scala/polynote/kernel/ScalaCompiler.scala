package polynote.kernel

import java.io.File
import cats.syntax.traverse._
import cats.instances.list._
import polynote.kernel.environment.Config
import polynote.kernel.util.{KernelReporter, LimitedSharingClassLoader, pathOf}
import zio.blocking.Blocking
import zio.system.{System, env}
import zio.internal.{ExecutionMetrics, Executor}
import zio.{Task, TaskR, ZIO}
import zio.interop.catz._

import scala.collection.mutable
import scala.reflect.internal.util.{AbstractFileClassLoader, NoSourceFile, Position, SourceFile}
import scala.reflect.io.VirtualDirectory
import scala.runtime.BoxedUnit
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global

class ScalaCompiler private (
  val global: Global,
  val notebookPackage: String,
  val classLoader: Task[AbstractFileClassLoader]
) {
  import global._
  private val packageName = TermName(notebookPackage)
  private val reporter = global.reporter.asInstanceOf[KernelReporter]

  private val compilerThread: Executor = new Executor {
    def yieldOpCount: Int = Int.MaxValue
    def metrics: Option[ExecutionMetrics] = None
    def submit(runnable: Runnable): Boolean = {
      global.ask(runnable.run)
      true
    }
    def here: Boolean = false
  }

  private def formatTypeInternal(typ: Type): String = typ match {
    case mt @ MethodType(params: List[Symbol], result: Type) =>
      val paramStr = params.map {
        sym => s"${sym.nameString}: ${formatTypeInternal(sym.typeSignatureIn(mt))}"
      }.mkString(", ")
      val resultType = formatTypeInternal(result)
      s"($paramStr) => $resultType"
    case NoType => "<Unknown>"
    case _ =>
      val typName = typ.typeSymbolDirect.rawname
      val typNameStr = typName.decoded
      typ.typeArgs.map(formatTypeInternal) match {
        case Nil => typNameStr
        case a if typNameStr == "<byname>" => s"=> $a"
        case a :: b :: Nil if typName.isOperatorName => s"$a $typNameStr $b"
        case a :: b :: Nil if typ.typeSymbol.owner.nameString == "scala" && (typNameStr == "Function1") =>
          s"$a => $b"
        case args if typ.typeSymbol.owner.nameString == "scala" && (typNameStr startsWith "Function") =>
          s"(${args.dropRight(1).mkString(",")}) => ${args.last}"
        case args => s"$typName[${args.mkString(", ")}]"
      }
  }

  private[kernel] def unsafeFormatType(typ: Type): String = formatTypeInternal(typ)

  def formatType(typ: Type): TaskR[Blocking, String] =
    zio.blocking.effectBlocking(formatTypeInternal(typ)).lock(compilerThread)

  def formatTypes(types: List[Type]): TaskR[Blocking, List[String]] =
    zio.blocking.effectBlocking(types.map(formatTypeInternal)).lock(compilerThread)

  def compileCell(
    cellCode: CellCode
  ): TaskR[Blocking, Class[_]] =
    for {
      compiled  <- cellCode.compile()
      className  = s"${packageName.encodedName.toString}.${cellCode.assignedTypeName.encodedName.toString}"
      cl        <- classLoader
      cls       <- zio.blocking.effectBlocking(Class.forName(className, false, cl).asInstanceOf[Class[AnyRef]])
    } yield cls

  def cellCode(
    name: String,
    code: String,
    priorCells: List[CellCode] = Nil,
    inputs: List[ValDef] = Nil,
    inheritedImports: Imports = Imports(),
    strictParse: Boolean = true
  ): Task[CellCode] = for {
    sourceFile  <- ZIO(newSourceFile(code, name))
    compileUnit <- ZIO(new global.RichCompilationUnit(sourceFile))
    parsed      <- if (strictParse) ZIO(reporter.attempt(newUnitParser(compileUnit).parseStats())).absolve else ZIO(newUnitParser(compileUnit).parseStats())
  } yield {
    CellCode(
      name, parsed, priorCells, inputs, inheritedImports, compileUnit, sourceFile
    )
  }

  // TODO: currently, there's no caching of the implicits, because what if you define a new implicit? Should we cache?
  def inferImplicits(types: List[Type]): TaskR[Blocking, List[Option[AnyRef]]] = {
    val (names, trees) = types.map {
      typ =>
        val name = freshTermName("anon$")(currentFreshNameCreator)
        name.encodedName.toString -> q"val $name: ${TypeTree(typ)} = implicitly[${TypeTree(typ)}]"
    }.unzip

    val cellName = "anonImplicits"

    def construct: TaskR[Class[_], AnyRef] =
      ZIO.accessM[Class[_]](cls => ZIO(cls.getDeclaredConstructors.head.newInstance().asInstanceOf[AnyRef]))

    def getField(name: String)(instance: AnyRef): TaskR[Class[_], Option[AnyRef]] =
      ZIO.accessM[Class[_]](cls => ZIO(Option(cls.getDeclaredMethod(name).invoke(instance))) orElse ZIO.succeed(None))

    def getFields(names: List[String])(instance: AnyRef): TaskR[Class[_], List[Option[AnyRef]]] =
      names.map(getField(_)(instance)).sequence

    // first we'll try to get all of them at once.
    compileCell(CellCode(cellName, trees)).flatMap {
      cls => (construct >>= getFields(names)).provide(cls)
    }.catchAll {
      err =>
        // if that doesn't compile (i.e. some implicits are missing) we'll try to compile each individually
        trees.zip(names).map {
          case (tree, name) =>
            compileCell(CellCode(cellName, List(tree))).flatMap {
              cls => (construct >>= getField(name)).provide(cls)
            }.catchAllCause {
              cause =>
                // TODO: log it?
                ZIO.succeed(None)
            }
        }.sequence
    }
  }

  private def copyAndReset[T <: Tree](tree: T): T = resetAttrs(tree.duplicate.setPos(NoPosition)).asInstanceOf[T]
  private def copyAndReset[T <: Tree](trees: List[T]): List[T] = trees.map(tree => copyAndReset(tree))

  private def template(stats: List[Tree]): Template = Template(
    List(treeBuilder.scalaDot(typeNames.AnyRef)), noSelfType, stats
  )

  private def template(stats: Tree*): Template = template(stats.toList)

  case class CellCode private[ScalaCompiler] (
    name: String,
    code: List[Tree],
    priorCells: List[CellCode] = Nil,
    inputs: List[ValDef] = Nil,
    inheritedImports: Imports = Imports(),
    compilationUnit: RichCompilationUnit = new global.RichCompilationUnit(NoSourceFile),
    sourceFile: SourceFile = NoSourceFile
  ) {
    // this copy of the code will be mutated by compile
    lazy val compiledCode: List[Tree] = code.map {
      stat => stat.duplicate.setPos(stat.pos)
    }

    // The name of the class (and its companion object, in case one is needed)
    lazy val assignedTypeName: TypeName = freshTypeName(name)(global.currentFreshNameCreator)
    lazy val assignedTermName: TermName = assignedTypeName.toTermName

    // Separate the implicit inputs, since they must be in their own parameter list
    lazy val (implicitInputs: List[ValDef], nonImplicitInputs: List[ValDef]) = inputs.partition(_.mods.isImplicit)

    // a position to encompass the whole synthetic tree
    private lazy val wrappedPos = Position.transparent(sourceFile, 0, 0, sourceFile.length + 1)

    // create constructor parameters to hold instances of prior cells; these are needed to access path-dependent types
    // for any classes, traits, type aliases, etc defined by previous cells
    lazy val priorCellInputs: List[ValDef] = priorCells.map {
      cell => ValDef(Modifiers(), TermName(s"_input${cell.assignedTypeName.decodedName.toString}"), tq"${cell.assignedTypeName}".setType(cell.cellClassType).setSymbol(cell.cellClassSymbol), EmptyTree)
    }

    // create imports for all types defined by previous cells
    lazy val priorCellTypeImports: List[Import] = priorCells.zip(priorCellInputs).map {
      case (cell, ValDef(_, term, _, _)) =>
        Import(
          Ident(term),
          cell.definedTypes.zipWithIndex.map {
            case (name, index) => ImportSelector(name, index, name, index)
          }
        )
    }

    // what output values does this code define?
    lazy val outputs: List[ValDef] = code.collect {
      case valDef: ValDef if valDef.mods.isPublic => valDef.duplicate
    }

    lazy val typedOutputs: List[ValDef] = {
      val prev = outputs.map(v => v.name -> v).toMap

      def notUnit(tpe: Type) = tpe != null && !(tpe <:< weakTypeOf[Unit]) && !(tpe <:< weakTypeOf[BoxedUnit])

      exitingTyper(compiledCode).collect {
        case vd@ValDef(_, name, tpt, _) if (prev contains name) && notUnit(vd.symbol.originalInfo) =>
          val tpe = vd.symbol.originalInfo
          val preTyper = prev(name)
          val typeTree = TypeTree(tpe)
          typeTree.setType(tpe)
          ValDef(preTyper.mods, preTyper.name, typeTree, EmptyTree).setPos(preTyper.pos)
      }
    }

    // what things does this code import?
    lazy val imports: List[Import] = code.collect {
      case i: Import => i.duplicate
    }

    // what types (classes, traits, objects, type aliases) does this code define?
    lazy val definedTypes: List[Name] = code.collect {
      case ModuleDef(mods, name, _) if mods.isPublic => name
      case ClassDef(mods, name, _, _) if mods.isPublic && mods.isCase => name.toTermName
      case ClassDef(mods, name, _, _) if mods.isPublic => name
      case TypeDef(mods, name, _, _) if mods.isPublic => name
    }

    private lazy val wrappedClass: ClassDef = {
      val priorCellParamList = copyAndReset(priorCellInputs)
      val nonImplicitParamList = copyAndReset(nonImplicitInputs)
      val implicitParamList = copyAndReset(implicitInputs)
      q"""
        class $assignedTypeName(..$priorCellParamList)(..$nonImplicitParamList)(..$implicitParamList) extends scala.Serializable {
          ..${priorCellTypeImports}
          ..${copyAndReset(inheritedImports.externalImports)}
          ..${copyAndReset(inheritedImports.localImports)}
          ..$compiledCode
        }
      """
    }

    // Wrap the code in a class within the given package. Constructing the class runs the code.
    // The constructor parameters are
    private lazy val wrapped: PackageDef = atPos(wrappedPos) {
      q"""
        package $packageName {
          $wrappedClass
        }
      """
    }

    // the type representing this cell's class. It may be null or NoType if invoked before compile is done!
    def cellClassType: Type = exitingTyper(wrappedClass.symbol.info)
    def cellClassSymbol: ClassSymbol = exitingTyper(wrappedClass.symbol.asClass)

    // Note – you mustn't typecheck and then compile the same instance; those trees are done for. Instead, make a copy
    // of this CellCode and typecheck that if you need info about the typed trees without compiling all the way
    private[kernel] lazy val typed = {
      val run = new Run()
      compilationUnit.body = wrapped
      unitOfFile.put(sourceFile.file, compilationUnit)
      global.globalPhase = run.namerPhase // make sure globalPhase matches run phase
      run.namerPhase.asInstanceOf[global.GlobalPhase].apply(compilationUnit)
      global.globalPhase = run.typerPhase // make sure globalPhase matches run phase
      run.typerPhase.asInstanceOf[global.GlobalPhase].apply(compilationUnit)
      exitingTyper(compilationUnit.body)
    }

    private[ScalaCompiler] def compile() = ZIO {
      val run = new Run()
      compilationUnit.body = wrapped
      unitOfFile.put(sourceFile.file, compilationUnit)
      ZIO {
        reporter.attempt {
          run.compileUnits(List(compilationUnit), run.namerPhase)
          exitingTyper(compilationUnit.body)
        }
      }.lock(compilerThread).absolve
    }.flatten

    private def usedInputs = {
      val classSymbol = cellClassSymbol
      val inputNames = inputs.map(_.name).toSet
      compilationUnit.body.collect {
        case Select(This(`assignedTypeName`), name: TermName) if inputNames contains name => name
        case id@Ident(name: TermName) if (id.symbol.ownerChain contains classSymbol) && (inputNames contains name) => name
      }
    }

    private def usedPriorCells: List[CellCode] = {
      val inputCellSymbols = cellClassSymbol.primaryConstructor.paramss.head.map(_.name).toSet
      val used = new mutable.HashSet[Name]()
      val traverser = new Traverser {
        override def traverse(tree: Tree): Unit = {
          if (tree.symbol != null && inputCellSymbols.contains(tree.symbol.name)) {
            tree match {
              case defTree: DefTree => // defining the thing, not using it
              case other if !other.pos.isTransparent =>
                used.add(tree.symbol.name)
              case other =>
            }
            super.traverse(tree)
          } else {
            super.traverse(tree)
          }
        }
      }
      traverser.traverse(compilationUnit.body)
      val results = priorCells.zip(priorCellInputs).filter {
        case (cell, term) => used contains term.name
      }.map(_._1)

      results
    }

    def splitImports(): Imports = {
      val priorCellSymbols = priorCells.map(_.cellClassSymbol)
      val imports = compiledCode.zip(code).collect {
        case (i: Import, orig: Import) => (i, orig)
      }
      val (local, external) = imports.partition {
        case (Import(expr, names), _) =>
          (expr.symbol.ownerChain contains cellClassSymbol) || expr.symbol.ownerChain.intersect(priorCellSymbols).nonEmpty
      }

      Imports(local.map(_._2.duplicate), external.map(_._2.duplicate))
    }

    /**
      * Make a new [[CellCode]] that uses a minimal subset of inputs and prior cells.
      * After invoking, this [[CellCode]] will not be compilable – bin it!
      */
    def pruneInputs(): Task[CellCode] = for {
      typedTree  <- ZIO(reporter.attempt(typed)).lock(compilerThread).absolve
      usedNames  <- ZIO(usedInputs).lock(compilerThread)
      usedDeps   <- ZIO(usedPriorCells)
      usedNameSet = usedNames.map(_.decodedName.toString).toSet
    } yield copy(priorCells = usedDeps, inputs = inputs.filter(usedNameSet contains _.name.decodedName.toString))

    /**
      * Transform the code statements using the given function.
      */
    def transformCode(fn: List[Tree] => List[Tree]): CellCode = copy(code = fn(code))
  }

  case class Imports(
    localImports: List[Import] = Nil,
    externalImports: List[Import] = Nil
  ) {
    def ++(that: Imports): Imports = Imports(localImports ++ that.localImports, externalImports ++ that.externalImports)
  }

}

object ScalaCompiler {

  def apply(settings: Settings, classLoader: Task[AbstractFileClassLoader]): Task[ScalaCompiler] =
    classLoader.memoize.flatMap {
      classLoader => ZIO {
        val global = new Global(settings, KernelReporter(settings))
        new ScalaCompiler(global, "$notebook", classLoader)
      }
    }

  def apply(
    dependencyClasspath: List[File],
    modifySettings: Settings => Settings
  ): TaskR[Config with System, ScalaCompiler] = for {
    settings          <- ZIO(modifySettings(defaultSettings(new Settings(), dependencyClasspath)))
    global            <- ZIO(new Global(settings, KernelReporter(settings)))
    notebookPackage    = "$notebook"
    classLoader       <- makeClassLoader(settings).memoize
  } yield new ScalaCompiler(global, notebookPackage, classLoader)

  def makeClassLoader(settings: Settings): TaskR[Config, AbstractFileClassLoader] = for {
    dependencyClassLoader <- makeDependencyClassLoader(settings)
    compilerOutput        <- ZIO.fromOption(settings.outputDirs.getSingleOutput).mapError(_ => new IllegalArgumentException("Compiler must have a single output directory"))
  } yield new AbstractFileClassLoader(compilerOutput, dependencyClassLoader)

  def makeDependencyClassLoader(settings: Settings): TaskR[Config, ClassLoader] = Config.access.flatMap {
    config => ZIO {
      val dependencyClassPath = settings.classpath.value.split(File.pathSeparator).toSeq.map(new File(_).toURI.toURL)

      if (config.behavior.dependencyIsolation) {
        new LimitedSharingClassLoader(
          "^(scala|javax?|jdk|sun|com.sun|com.oracle|polynote|org.w3c|org.xml|org.omg|org.ietf|org.jcp|org.apache.spark|org.apache.hadoop|org.codehaus|org.slf4j|org.log4j)\\.",
          dependencyClassPath,
          getClass.getClassLoader)
      } else {
        new scala.reflect.internal.util.ScalaClassLoader.URLClassLoader(dependencyClassPath, getClass.getClassLoader)
      }
    }
  }

  def provider(
    dependencyClasspath: List[File],
    modifySettings: Settings => Settings
  ): TaskR[Config with System, ScalaCompiler.Provider] = apply(dependencyClasspath, modifySettings).map(Provider.of)

  def provider(dependencyClasspath: List[File]): TaskR[Config with System, ScalaCompiler.Provider] =
    provider(dependencyClasspath, identity[Settings])

  def defaultSettings(initial: Settings, classPath: List[File] = Nil): Settings = {
    val requiredPaths = List(
      pathOf(classOf[List[_]]),
      pathOf(polynote.runtime.Runtime.getClass),
      pathOf(classOf[scala.reflect.runtime.JavaUniverse]),
      pathOf(classOf[scala.tools.nsc.Global]),
      pathOf(classOf[jep.python.PyObject])
    ).distinct.map {
      case url if url.getProtocol == "file" => new File(url.getPath)
      case url => throw new IllegalStateException(s"Required path $url must be a local file, not ${url.getProtocol}")
    }

    val cp = classPath ++ requiredPaths

    val settings = initial.copy()
    settings.classpath.append(cp.map(_.getCanonicalPath).mkString(File.pathSeparator))
    settings.Yrangepos.value = true
    try {
      settings.YpartialUnification.value = true
    } catch {
      case err: Throwable =>  // not on Scala 2.11.11+ - that's OK, just won't get partial unification
    }
    settings.exposeEmptyPackage.value = true
    settings.Ymacroexpand.value = settings.MacroExpand.Normal
    settings.YpresentationAnyThread.value = true
    settings.Ydelambdafy.value = "inline"
    settings.outputDirs.setSingleOutput(new VirtualDirectory("(memory)", None))
    settings
  }

  trait Provider {
    val scalaCompiler: ScalaCompiler
  }

  object Provider {
    def of(compiler: ScalaCompiler): Provider = new Provider {
      override val scalaCompiler: ScalaCompiler = compiler
    }
  }

}