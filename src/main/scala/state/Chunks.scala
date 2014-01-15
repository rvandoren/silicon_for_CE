package semper
package silicon
package state

import interfaces.state.{Chunk, PermissionChunk, FieldChunk, PredicateChunk, ChunkIdentifier}
import semper.silicon.state.terms._
import semper.silicon.state.FieldChunkIdentifier
import semper.silicon.state.DirectFieldChunk
import semper.silicon.state.PredicateChunkIdentifier
import semper.silicon.state.terms.Quantification
import semper.silicon.state.DirectPredicateChunk
import semper.silicon.state.DirectQuantifiedChunk

sealed trait DirectChunk extends PermissionChunk[DefaultFractionalPermissions, DirectChunk]

case class FieldChunkIdentifier(rcvr: Term, name: String) extends ChunkIdentifier {
  val args = rcvr :: Nil

  override def toString = s"$rcvr.$name"
}

case class DirectFieldChunk(rcvr: Term, name: String, value: Term, perm: DefaultFractionalPermissions)
    extends FieldChunk with DirectChunk {

  val args = rcvr :: Nil
  val id = FieldChunkIdentifier(rcvr, name)

	def +(perm: DefaultFractionalPermissions): DirectFieldChunk = this.copy(perm = this.perm + perm)
	def -(perm: DefaultFractionalPermissions): DirectFieldChunk = this.copy(perm = this.perm - perm)

	override def toString = "%s.%s -> %s # %s".format(rcvr, name, value, perm)
}

case class DirectQuantifiedChunk(name: String, value:Term, perm: DefaultFractionalPermissions) extends DirectChunk {
  val args = *() :: Nil   /* to make sure it does not match other chunks */
  val id = FieldChunkIdentifier(*(), name)

  def +(perm: DefaultFractionalPermissions): DirectQuantifiedChunk = this.copy(perm = this.perm + perm)
  def -(perm: DefaultFractionalPermissions): DirectQuantifiedChunk = this.copy(perm = this.perm - perm)

  override def toString = "∀ %s -> %s # %s".format(name, value, perm)
}

case class PredicateChunkIdentifier(name: String, args: List[Term]) extends ChunkIdentifier {
  override def toString = "%s(%s)".format(name, args.mkString(","))
}

case class DirectPredicateChunk(name: String,
                                args: List[Term],
                                snap: Term,
                                perm: DefaultFractionalPermissions,
                                nested: List[NestedChunk] = Nil)
    extends PredicateChunk with DirectChunk {

  terms.utils.assertSort(snap, "snapshot", terms.sorts.Snap)

  val id = PredicateChunkIdentifier(name, args)

	def +(perm: DefaultFractionalPermissions): DirectPredicateChunk = this.copy(perm = this.perm + perm)
	def -(perm: DefaultFractionalPermissions): DirectPredicateChunk = this.copy(perm = this.perm - perm)

	override def toString = "%s(%s;%s) # %s".format(name, args.mkString(","), snap, perm)
}


sealed trait NestedChunk extends Chunk

case class NestedFieldChunk(rcvr: Term, name: String, value: Term) extends FieldChunk with NestedChunk {
  val args = rcvr :: Nil
  val id = FieldChunkIdentifier(rcvr, name)

  def this(fc: DirectFieldChunk) = this(fc.rcvr, fc.name, fc.value)

  override def toString = "%s.%s -> %s".format(rcvr, name, value)
}

case class NestedPredicateChunk(name: String, args: List[Term], snap: Term, nested: List[NestedChunk] = Nil)
    extends PredicateChunk with NestedChunk {

  val id = PredicateChunkIdentifier(name, args)

  def this(pc: DirectPredicateChunk) = this(pc.name, pc.args, pc.snap, pc.nested)

  override def toString = "%s(%s;%s)".format(name, args.mkString(","), snap)
}
