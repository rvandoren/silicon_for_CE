package rpi.inference

import rpi.inference.context.Specification
import rpi.util.SeqMap
import rpi.util.ast.Expressions._
import viper.silver.ast
import viper.silver.ast.Exp

/**
  * The super trait for all samples.
  */
trait Sample

/**
  * A positive sample.
  *
  * @param records The records.
  */
case class PositiveSample(records: Seq[Record]) extends Sample

/**
  * A negative sample.
  *
  * @param record The records.
  */
case class NegativeSample(record: Record) extends Sample

/**
  * An implication sample.
  *
  * @param left  The left-hand side of the implication.
  * @param right The right-hand side of the implication.
  */
case class ImplicationSample(left: Record, right: Seq[Record]) extends Sample

/**
  * A record representing a data pont.
  *
  * @param specification The specification.
  * @param abstraction   The state abstraction.
  * @param locations     The (under-approximate) set of location accesses that can be used to represent the resource for
  *                      which permissions are required.
  */
case class Record(specification: Specification, abstraction: Abstraction, locations: Set[ast.LocationAccess]) {
  override def toString: String = s"${specification.name}: $abstraction -> {${locations.mkString(", ")}}"
}

trait Abstraction {
  /**
    * Returns the value of the given atom.
    *
    * @param atom The atom to evaluate.
    * @return The value of the atom.
    */
  def value(atom: ast.Exp): Option[Boolean]

  /**
    * Returns the value of the given atoms.
    *
    * @param atoms The atoms to evaluate.
    * @return The value of the atoms.
    */
  def values(atoms: Seq[ast.Exp]): Seq[Option[Boolean]] =
    atoms.map { atom => value(atom) }
}

case class PartitionAbstraction(partitions: Map[ast.Exp, Int]) extends Abstraction {
  override def value(atom: Exp): Option[Boolean] =
    atom match {
      case operation@ast.BinExp(left, right) =>
        for {
          leftValue <- partitions.get(left)
          rightValue <- partitions.get(right)
        } yield operation match {
          case ast.EqCmp(_, _) => leftValue == rightValue
          case ast.NeCmp(_, _) => leftValue != rightValue
        }
      case _ =>
        ???
    }

  override def toString: String =
    partitions
      .foldLeft(Map.empty[Int, Seq[ast.Exp]]) {
        case (current, (atom, partition)) =>
          SeqMap.add(current, partition, atom)
      }
      .map { case (_, seq) => seq.mkString("{", ", ", "}") }
      .mkString("{", ", ", "}")
}

case class AtomicAbstraction(values: Map[ast.Exp, Boolean]) extends Abstraction {
  override def value(atom: Exp): Option[Boolean] =
    values.get(atom)

  /**
    * Computes the meet of this and the other abstract state.
    *
    * NOTE: The implementation assumes that the states are not conflicting, i.e., do not assign different values to the
    * same atom.
    *
    * @param other The other abstract state.
    * @return The meet.
    */
  def meet(other: AtomicAbstraction): AtomicAbstraction = {
    val combined = other
      .values
      .foldLeft(values) {
        case (map, (atom, value)) =>
          map.updated(atom, value)
      }
    AtomicAbstraction(combined)
  }

  override def toString: String =
    values
      .map { case (atom, value) => if (value) atom else simplify(makeNot(atom)) }
      .mkString("{", ", ", "}")
}