package rpi.teacher

import rpi.Config
import rpi.inference._
import rpi.util.Namespace
import viper.silver.{ast => sil}

import scala.collection.mutable

/**
  * Builds programs used to check hypotheses.
  *
  * @param teacher The pointer to the teacher.
  */
class CheckBuilder(teacher: Teacher) {
  // import utility methods
  import rpi.util.Expressions._
  import rpi.util.Statements._

  /**
    * Returns the pointer to the inference.
    *
    * @return The pointer to the inference.
    */
  private def inference: Inference = teacher.inference

  /**
    * Returns the pointer to the original program (labeled).
    *
    * @return The pointer to the original program.
    */
  private def original: sil.Program = inference.labeled

  /**
    * The namespace used to generate unique identifiers.
    */
  private var namespace: Namespace = _

  /**
    * The context information for the example extractor.
    */
  private var context: Context = _

  /**
    * The stack of statement buffers used to accumulate the instrumented statements. The stack is required to properly
    * handle control flow. The topmost buffer accumulates statements for the current branch.
    */
  private var stack: List[mutable.Buffer[sil.Stmt]] = _

  /**
    * Returns a program that performs the given checks.
    *
    * @param checks     The checks to perform.
    * @param hypothesis The hypothesis.
    * @return The program.
    */
  def basicCheck(checks: Seq[sil.Seqn], hypothesis: Hypothesis): (sil.Program, Context) = {
    clear()
    val instrumented = checks.map { check => instrument(check, hypothesis) }
    val program = buildProgram(instrumented, hypothesis)
    // return program and context
    (program, context)
  }

  /**
    * Builds a program performing to the given checks.
    *
    * @param checks The checks.
    * @return The program.
    */
  private def buildProgram(checks: Seq[sil.Seqn], hypothesis: Hypothesis): sil.Program = {
    val domains = Seq.empty
    val fields = inference.magic +: original.fields
    val functions = Seq.empty
    val predicates = {
      val existing = original.predicates
      val inferred = inference.predicates(hypothesis)
      existing ++ inferred
    }
    val methods = checks.map { check => builtMethod(check, hypothesis) }
    val extensions = Seq.empty
    val program = sil.Program(domains, fields, functions, predicates, methods, extensions)()
    println(program)
    program
  }

  /**
    * Builds a method performing to the given check.
    *
    * @param check The check.
    * @return The method.
    */
  private def builtMethod(check: sil.Seqn, hypothesis: Hypothesis): sil.Method = {
    val name = namespace.uniqueIdentifier(base = "check", Some(0))
    val arguments = Seq.empty
    val returns = Seq.empty
    val preconditions = Seq.empty
    val postconditions = Seq.empty
    val body = {
      val statements = check.ss
      val declarations = check
        .deepCollect { case variable: sil.LocalVar => variable }
        .distinct
        .map { variable => sil.LocalVarDecl(variable.name, variable.typ)() }
      Some(sil.Seqn(statements, declarations)())
    }
    sil.Method(name, arguments, returns, preconditions, postconditions, body)()
  }

  /**
    * Instruments the given sequence.
    *
    * @param sequence The sequence to instrument.
    * @return The instrumented sequence.
    */
  private def instrument(sequence: sil.Seqn, hypothesis: Hypothesis): sil.Seqn = {
    push()
    sequence.ss.foreach { statement => instrument(statement, hypothesis) }
    asSequence(pop())
  }

  /**
    * Instruments the given statement.
    *
    * @param statement The statement to instrument.
    */
  private def instrument(statement: sil.Stmt, hypothesis: Hypothesis): Unit =
    statement match {
      case sil.If(condition, thenBody, elseBody) =>
        // instrument branches
        val thenInstrumented = instrument(thenBody, hypothesis)
        val elseInstrumented = instrument(elseBody, hypothesis)
        // add instrumented conditional
        val instrumented = sil.If(condition, thenInstrumented, elseInstrumented)()
        addStatement(instrumented)
      case sil.Inhale(predicate: sil.PredicateAccessPredicate) =>
        // get specification instance
        val instance = getInstance(predicate)
        // inhale specification
        val adapted = adaptPredicate(predicate, instance, None)
        addInhale(adapted)
        // save state
        saveState(instance, inhaled = true)
      case sil.Exhale(predicate: sil.PredicateAccessPredicate) =>
        // get specification instance
        val instance = getInstance(predicate)
        // save state
        val label = saveState(instance, inhaled = false)
        // exhale specification
        foldAndExhale(instance, Seq.empty, depth = 0)(label, hypothesis)


      /*val body = hypothesis.get(instance)
      val folds = bar(sil.TrueLit()(), body)(label)

      folds.foreach { case (guard, resource) =>
        val fold = sil.If(guard, asSequence(sil.Fold(resource)()), skip)()
        addStatement(fold)
      }*/
      case sil.MethodCall(name, arguments, _) =>
        // TODO: Implement me.
        ???
      case _ =>
        addStatement(statement)
    }

  private def foldAndExhale(instance: Instance, guards: Seq[sil.Exp], depth: Int)
                           (implicit label: String, hypothesis: Hypothesis): Unit = {
    // auxiliary method to recursively process body
    def process(expression: sil.Exp, guards: Seq[sil.Exp]): Unit =
      expression match {
        case sil.TrueLit() => // do nothing
        case sil.And(left, right) =>
          process(left, guards)
          process(right, guards)
        case sil.Implies(left, right) =>
          process(right, guards :+ left)
        case sil.FieldAccessPredicate(resource, _) =>
          saveResource(resource)
        case sil.PredicateAccessPredicate(resource, _) =>
          if (depth < Config.foldDepth) {
            val name = resource.predicateName
            val arguments = resource.args
            val inner = inference.getInstance(name, arguments)
            foldAndExhale(inner, guards, depth + 1)
          } else saveResource(resource)
        case _ => ???
      }

    def saveResource(resource: sil.LocationAccess): Unit = {
      val name = namespace.uniqueIdentifier(base = "p", Some(0))
      context.addName(label, resource, name)
      savePermission(name, resource)
    }

    // process body
    val body = hypothesis.get(instance)
    process(body, guards)

    // create predicate
    val predicate = {
      val name = instance.name
      val arguments = instance.arguments
      val access = sil.PredicateAccess(arguments, name)()
      val info = ContextInfo(label, instance)
      sil.PredicateAccessPredicate(access, sil.FullPerm()())(info = info)
    }

    // create conditional fold statement
    val conditional = {
      val fold = sil.Fold(predicate)()
      if (guards.isEmpty) fold
      else sil.If(bigAnd(guards), asSequence(fold), skip)()
    }
    addStatement(conditional)

    // exhale top level instance
    if (depth == 0) addStatement(sil.Exhale(predicate)())
  }


  private def getInstance(predicate: sil.PredicateAccessPredicate): Instance = {
    // make sure all arguments are variable accesses
    val access = predicate.loc
    val (arguments, assignments) = {
      val empty = (Seq.empty[sil.LocalVar], Seq.empty[sil.LocalVarAssign])
      access.args.foldLeft(empty) {
        case ((variables, collected), variable: sil.LocalVar) =>
          (variables :+ variable, collected)
        case ((variables, collected), argument) =>
          val name = namespace.uniqueIdentifier(base = "t", Some(0))
          val variable = sil.LocalVar(name, argument.typ)()
          val assignment = sil.LocalVarAssign(variable, argument)()
          (variables :+ variable, collected :+ assignment)
      }
    }

    // TODO: Inhale permissions to make stuff self-framing
    if (!Config.useFraming) ???
    assignments.foreach { assignment => addStatement(assignment) }

    // create and return instance
    val name = access.predicateName
    inference.getInstance(name, arguments)
  }

  private def adaptPredicate(predicate: sil.PredicateAccessPredicate, instance: Instance, label: Option[String]): sil.PredicateAccessPredicate = {
    val name = predicate.loc.predicateName
    val arguments = instance.arguments
    val access = sil.PredicateAccess(arguments, name)()
    val permission = predicate.perm
    val info = label.map { name => ContextInfo(name, instance) }.getOrElse(sil.NoInfo)
    sil.PredicateAccessPredicate(access, permission)(info = info)
  }

  /**
    * Saves the state relevant for the given specification instance.
    *
    * @param instance The instance.
    * @return The label of the state.
    */
  private def saveState(instance: Instance, inhaled: Boolean): String = {
    val label = namespace.uniqueIdentifier(base = "s", Some(0))
    // save values of variables
    instance
      .arguments
      .foreach {
        case variable: sil.LocalVar =>
          val name = s"${label}_${variable.name}"
          saveValue(name, variable)
      }
    // save values of atoms
    instance
      .actualAtoms
      .zipWithIndex
      .foreach {
        case (atom, index) =>
          val name = s"${label}_$index"
          saveValue(name, atom)
      }
    // add label
    addStatement(sil.Label(label, Seq.empty)())
    if (inhaled) context.addInhaled(label, instance)
    else context.addExhaled(label, instance)
    label
  }

  def bar(guard: sil.Exp, expression: sil.Exp)(implicit label: String): Seq[(sil.Exp, sil.PredicateAccessPredicate)] =
    expression match {
      case sil.TrueLit() => Seq.empty
      case sil.And(left, right) =>
        val a = bar(guard, left)
        val b = bar(guard, right)
        b ++ a
      case sil.Implies(left, right) =>
        bar(and(guard, left), right)
      case sil.FieldAccessPredicate(resource, _) =>
        val name = namespace.uniqueIdentifier(base = "p", Some(0))
        context.addName(label, resource, name)
        savePermission(name, resource)
        Seq.empty
      case sil.PredicateAccessPredicate(resource, permission) =>
        // TODO: Prettify me.
        val instance = {
          val name = resource.predicateName
          val arguments = resource.args
          inference.getInstance(name, arguments)
        }

        val info = ContextInfo(label, instance)


        val name = namespace.uniqueIdentifier(base = "p", Some(0))


        val predicate = sil.PredicateAccessPredicate(resource, permission)(info = info)
        context.addName(label, resource, name)
        savePermission(name, resource)
        Seq((guard, predicate))
      case _ => ???
    }

  /**
    * Saves the currently held permission amount for the given resource in a variable with the given name.
    *
    * @param name     The name of the variable.
    * @param resource The resource.
    */
  private def savePermission(name: String, resource: sil.ResourceAccess): Unit = {
    val value = sil.CurrentPerm(resource)()
    saveValue(name, value)
  }

  /**
    * Saves the value of the given expression in a variable with the given name.
    *
    * @param name       The name of the variable.
    * @param expression The expression to save.
    */
  private def saveValue(name: String, expression: sil.Exp): Unit = {
    val variable = sil.LocalVar(name, expression.typ)()
    if (Config.useBranching && expression.typ == sil.Bool) {
      // create conditional
      val thenBody = asSequence(sil.LocalVarAssign(variable, sil.TrueLit()())())
      val elseBody = asSequence(sil.LocalVarAssign(variable, sil.FalseLit()())())
      addStatement(sil.If(expression, thenBody, elseBody)())
    } else {
      // create assignment
      addStatement(sil.LocalVarAssign(variable, expression)())
    }
  }

  private def addInhale(predicate: sil.PredicateAccessPredicate): Unit = {
    addStatement(sil.Inhale(predicate)())
    addStatement(sil.Unfold(predicate)())
  }

  private def addStatement(statement: sil.Stmt): Unit =
    stack.head.append(statement)

  private def clear(): Unit = {
    namespace = new Namespace
    context = new Context
    stack = List.empty
  }

  private def push(): Unit =
    stack = mutable.Buffer.empty[sil.Stmt] :: stack

  private def pop(): Seq[sil.Stmt] =
    stack match {
      case head :: tail =>
        stack = tail
        head
    }
}