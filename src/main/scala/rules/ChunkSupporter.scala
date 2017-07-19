/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.silicon.rules

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import viper.silicon.interfaces.state._
import viper.silicon.interfaces.{Failure, Success, VerificationResult}
import viper.silicon.resources.{PropertyInterpreter, Resources}
import viper.silicon.state._
import viper.silicon.state.terms._
import viper.silicon.state.terms.perms.{IsNonPositive, IsPositive}
import viper.silicon.verifier.Verifier

trait ChunkSupportRules extends SymbolicExecutionRules {
  def consume(s: State,
              h: Heap,
              id: ChunkIdentifer,
              args: Seq[Term],
              perms: Term,
              failure: Failure,
              v: Verifier,
              description: String)
             (Q: (State, Heap, Term, Verifier) => VerificationResult)
             : VerificationResult

  def produce(s: State, h: Heap, ch: NonQuantifiedChunk, v: Verifier)
             (Q: (State, Heap, Verifier) => VerificationResult)
             : VerificationResult

  def withChunk[CH <: NonQuantifiedChunk: ClassTag]
               (s: State,
                h: Heap,
                id: ChunkIdentifer,
                args: Seq[Term],
                failure: Failure,
                v: Verifier)
               (Q: (State, Heap, CH, Verifier) => VerificationResult)
               : VerificationResult

  def withChunk[CH <: NonQuantifiedChunk: ClassTag]
               (s: State,
                h: Heap,
                id: ChunkIdentifer,
                args: Seq[Term],
                optPerms: Option[Term],
                failure: Failure,
                v: Verifier)
               (Q: (State, Heap, CH, Verifier) => VerificationResult)
               : VerificationResult

  def withChunk[CH <: NonQuantifiedChunk: ClassTag]
               (s: State,
                id: ChunkIdentifer,
                args: Seq[Term],
                failure: Failure,
                v: Verifier)
               (Q: (State, CH, Verifier) => VerificationResult)
               : VerificationResult

  def withChunk[CH <: NonQuantifiedChunk: ClassTag]
               (s: State,
                id: ChunkIdentifer,
                args: Seq[Term],
                optPerms: Option[Term],
                failure: Failure,
                v: Verifier)
               (Q: (State, CH, Verifier) => VerificationResult)
               : VerificationResult

  def withChunkIfPerm[CH <: NonQuantifiedChunk: ClassTag]
                     (s: State,
                      h: Heap,
                      id: ChunkIdentifer,
                      args: Seq[Term],
                      perms: Term,
                      failure: Failure,
                      v: Verifier)
                     (Q: (State, Heap, Option[CH], Verifier) => VerificationResult)
                     : VerificationResult

  def findChunk[CH <: NonQuantifiedChunk: ClassTag]
               (chunks: Iterable[Chunk],
                id: ChunkIdentifer,
                args: Iterable[Term],
                v: Verifier)
               : Option[CH]

  def findMatchingChunk[CH <: NonQuantifiedChunk: ClassTag]
                       (chunks: Iterable[Chunk],
                        chunk: CH,
                        v: Verifier)
                       : Option[CH]

  def findChunksWithID[CH <: NonQuantifiedChunk: ClassTag]
                      (chunks: Iterable[Chunk],
                       id: ChunkIdentifer)
                      : Iterable[CH]

}
// TODO: replace Failure by VerificationError
object chunkSupporter extends ChunkSupportRules with Immutable {
  def consume(s: State,
              h: Heap,
              id: ChunkIdentifer,
              args: Seq[Term],
              perms: Term,
              failure: Failure,
              v: Verifier,
              description: String)
             (Q: (State, Heap, Term, Verifier) => VerificationResult)
             : VerificationResult = {
    heuristicsSupporter.tryOperation[Heap, Term](description)(s, h, v)((s1, h1, v1, QS) => {
      consume(s1, h1, id, args, perms, failure, v1)((s2, h2, optSnap, v2) =>
        optSnap match {
          case Some(snap) =>
            QS(s2, h2, snap.convert(sorts.Snap), v2)
          case None =>
            /* Not having consumed anything could mean that we are in an infeasible
             * branch, or that the permission amount to consume was zero.
             * Returning a fresh snapshot in these cases should not lose any information.
             */
            val fresh = v2.decider.fresh(sorts.Snap)
            val s3 = s2.copy(functionRecorder = s2.functionRecorder.recordFreshSnapshot(fresh.applicable))
            QS(s3, h2, fresh, v2)
        })
    })(Q)
  }

  private def consume(s: State,
                      h: Heap,
                      id: ChunkIdentifer,
                      args: Seq[Term],
                      perms: Term,
                      failure: Failure,
                      v: Verifier)
                     (Q: (State, Heap, Option[Term], Verifier) => VerificationResult)
                     : VerificationResult = {
    if (s.exhaleExt) {

      /* TODO: Integrate magic wand's transferring consumption into the regular,
       * (non-)exact consumption (the code following this if-branch)
       */

      def consumeFunction(s: State, h: Heap, perms: Term, v: Verifier) = {
        findChunk[NonQuantifiedChunk](h.values, id, args, v) match {
          case Some(ch) =>
            val toTake = PermMin(ch.perm, perms)
            val newChunk = ch.withPerm(PermMinus(ch.perm, toTake))
            val takenChunk = Some(ch.withPerm(toTake))
            (ConsumptionResult(PermMinus(perms, toTake), v), s, h - ch + newChunk, takenChunk)
          case None =>
            (Incomplete(perms), s, h, None)
        }
      }

      magicWandSupporter.transfer(s, perms, failure, v)(consumeFunction)((s1, optCh, v1) =>
        Q(s1, h, optCh.flatMap(ch => Some(ch.snap)), v1))
    } else if (Verifier.config.enableMoreCompleteExhale()) {
      consumeComplete(s, h, id, args, perms, failure, v)((s1, h1, snap1, v1) => {
        Q(s1, h1, snap1, v1)
      })
    } else {
      consumeGreedy(s, h, id, args, perms, failure, v)((s1, h1, snap1, v1) => {
        Q(s1, h1, snap1, v1)
      })
    }
  }

  private def consumeGreedy(s: State,
                            h: Heap,
                            id: ChunkIdentifer,
                            args: Seq[Term],
                            perms: Term,
                            failure: Failure,
                            v: Verifier)
                           (Q: (State, Heap, Option[Term], Verifier) => VerificationResult)
                           : VerificationResult = {
    if (terms.utils.consumeExactRead(perms, s.constrainableARPs)) {
      withChunkIfPerm[NonQuantifiedChunk](s, h, id, args, perms, failure, v)((s1, h1, optCh, v1) => {
        optCh match {
          case Some(ch) =>
            if (v1.decider.check (IsNonPositive(PermMinus(ch.perm, perms)), Verifier.config.checkTimeout () ) )
              Q (s1, h1 - ch, Some (ch.snap), v1)
            else
              Q (s1, h1 - ch + ch.withPerm(PermMinus(ch.perm, perms)), Some (ch.snap), v1)
          case None =>
            Q(s1, h1, None, v1)
        }
      })
    } else {
      withChunk[NonQuantifiedChunk](s, h, id, args, None, failure, v)((s1, h1, ch, v1) => {
        v1.decider.assume(PermLess(perms, ch.perm))
        Q(s1, h1 - ch + ch.withPerm(PermMinus(ch.perm, perms)), Some(ch.snap), v1)
      })
    }
  }

  private def consumeComplete(s: State,
                              h: Heap,
                              id: ChunkIdentifer,
                              args: Seq[Term],
                              perms: Term,
                              failure: Failure,
                              v: Verifier)
                             (Q: (State, Heap, Option[Term], Verifier) => VerificationResult)
                             : VerificationResult = {
    val relevantChunks = ListBuffer[NonQuantifiedChunk]()
    val otherChunks = ListBuffer[Chunk]()
    h.values foreach {
      case c: NonQuantifiedChunk if c.id == id => relevantChunks.append(c)
      case ch => otherChunks.append(ch)
    }

    if (relevantChunks.isEmpty) {
      // if no permission is exhaled, return none
      if (v.decider.check(perms === NoPerm(), Verifier.config.checkTimeout())) {
        Q(s, h, None, v)
      } else {
        failure
      }
    } else {
      val consumeExact = terms.utils.consumeExactRead(perms, s.constrainableARPs)

      var pNeeded = perms
      var pSum: Term = NoPerm()
      val newChunks = ListBuffer[NonQuantifiedChunk]()
      val equalities = ListBuffer[Term]()

      val definitiveAlias = findChunk[NonQuantifiedChunk](relevantChunks, id, args, v)
      val (snap, fresh) = definitiveAlias match {
        case Some(alias) => (alias.snap, None)
        case None =>
          val sort = relevantChunks.head.snap.sort
          val f = v.decider.appliedFresh("complete_fresh", sort, s.relevantQuantifiedVariables)
          (f, Some(f))
      }
      var moreNeeded = true

      relevantChunks.sortWith((b1, _) => b1.args == args) foreach { ch =>
        if (moreNeeded) {
          val eq = And(ch.args.zip(args).map { case (t1, t2) => t1 === t2 })
          pSum = PermPlus(pSum, Ite(eq, ch.perm, NoPerm()))
          val pTakenBody = Ite(eq, PermMin(ch.perm, pNeeded), NoPerm())
          val pTakenMacro = v.decider.freshMacro("complete_pTaken", Seq(), pTakenBody)
          val pTaken = App(pTakenMacro, Seq())
          val newChunk = ch.withSnap(ch.snap).withPerm(PermMinus(ch.perm, pTaken))
          equalities.append(Implies(And(IsPositive(ch.perm), eq), snap === newChunk.snap))
          pNeeded = PermMinus(pNeeded, pTaken)

          if (!v.decider.check(IsNonPositive(newChunk.perm), Verifier.config.splitTimeout())) {
            newChunks.append(newChunk)
          }

          val toCheck = if (consumeExact) pNeeded === NoPerm() else IsPositive(pSum)
          moreNeeded = !v.decider.check(toCheck, Verifier.config.splitTimeout())
        } else {
          newChunks.append(ch)
        }
      }

      val allChunks = otherChunks ++ newChunks
      val interpreter = new PropertyInterpreter(allChunks, v)
      newChunks foreach { ch =>
        val resource = Resources.resourceDescriptions(ch.resourceID)
        v.decider.assume(interpreter.buildPathConditionsForChunk(ch, resource.instanceProperties))
        v.decider.assume(interpreter.buildPathConditionsForResource(ch.resourceID, resource.staticProperties))
      }
      v.decider.assume(equalities)

      val newHeap = Heap(allChunks)
      // TODO: remove cast
      val sOpt = fresh.map { f =>
        s.copy(functionRecorder = s.functionRecorder.recordFreshSnapshot(f.applicable.asInstanceOf[Function]))
      }
      val s1 = sOpt.getOrElse(s)

      if (!moreNeeded) {
        if (!consumeExact) {
          v.decider.assume(PermLess(perms, pSum))
        }
        Q(s1, newHeap, Some(snap), v)
      } else {
        val toCheck = if (consumeExact) pNeeded === NoPerm() else IsPositive(pSum)
        v.decider.assert(toCheck) {
          case true =>
            if (!consumeExact) {
              v.decider.assume(PermLess(perms, pSum))
            }
            Q(s1, newHeap, Some(snap), v)
          case false => failure
        }
      }
    }
  }

  def produce(s: State, h: Heap, ch: NonQuantifiedChunk, v: Verifier)
             (Q: (State, Heap, Verifier) => VerificationResult) = {
    Q(s, stateConsolidator.merge(h, ch, v), v)
  }

  def withChunk[CH <: NonQuantifiedChunk: ClassTag]
               (s: State,
                h: Heap,
                id: ChunkIdentifer,
                args: Seq[Term],
                failure: Failure,
                v: Verifier)
               (Q: (State, Heap, CH, Verifier) => VerificationResult)
               : VerificationResult = {

    executionFlowController.tryOrFail2[Heap, CH](s.copy(h = h), v)((s1, v1, QS) =>
      findChunk[CH](s1.h.values, id, args, v1) match {
        case Some(chunk) =>
          QS(s1.copy(h = s.h), s1.h, chunk, v1)

        case None =>
          if (v1.decider.checkSmoke())
            Success() /* TODO: Mark branch as dead? */
          else
            failure
      }
    )(Q)
  }

  def withChunk[CH <: NonQuantifiedChunk: ClassTag]
               (s: State,
                h: Heap,
                id: ChunkIdentifer,
                args: Seq[Term],
                optPerms: Option[Term],
                failure: Failure,
                v: Verifier)
               (Q: (State, Heap, CH, Verifier) => VerificationResult)
               : VerificationResult = {

    withChunk[CH](s, h, id, args, failure, v)((s1, h1, ch, v1) => {
      val permCheck = optPerms match {
        case Some(p) => PermAtMost(p, ch.perm)
        case None => ch.perm !== NoPerm()
      }

      v1.decider.assert(permCheck) {
        case true =>
          v1.decider.assume(permCheck)
          Q(s1, h1, ch, v1)
        case false =>
          failure
      }
    })
  }

  def withChunk[CH <: NonQuantifiedChunk: ClassTag]
               (s: State,
                id: ChunkIdentifer,
                args: Seq[Term],
                failure: Failure,
                v: Verifier)
               (Q: (State, CH, Verifier) => VerificationResult)
               : VerificationResult =
    withChunk[CH](s, s.h, id, args, failure, v)((s1, h1, ch, v1) => Q(s1.copy(h = h1), ch, v1))

  def withChunk[CH <: NonQuantifiedChunk: ClassTag]
               (s: State,
                id: ChunkIdentifer,
                args: Seq[Term],
                optPerms: Option[Term],
                failure: Failure,
                v: Verifier)
               (Q: (State, CH, Verifier) => VerificationResult)
               : VerificationResult =
    withChunk[CH](s, s.h, id, args, optPerms, failure, v)((s1, h1, ch, v1) => Q(s1.copy(h = h1), ch, v1))

  /**
    * Just like withChunk, but calls the continuation with <code>None</code> if the permission value is
    * <code>NoPerm()</code>.
    */
  def withChunkIfPerm[CH <: NonQuantifiedChunk: ClassTag]
                     (s: State,
                      h: Heap,
                      id: ChunkIdentifer,
                      args: Seq[Term],
                      perms: Term,
                      failure: Failure,
                      v: Verifier)
                     (Q: (State, Heap, Option[CH], Verifier) => VerificationResult)
                     : VerificationResult = {

    executionFlowController.tryOrFail2[Heap, Option[CH]](s.copy(h = h), v)((s1, v1, QS) =>
      findChunk[CH](s1.h.values, id, args, v1) match {
        case Some(ch) =>
          val permCheck = PermAtMost(perms, ch.perm)
          v1.decider.assert(permCheck) {
            case true =>
              v1.decider.assume(permCheck)
              QS(s1.copy(h = s.h), s1.h, Some(ch), v1)
            case false =>
              failure
          }

        case None =>
          if (v1.decider.checkSmoke())
            Success() /* TODO: Mark branch as dead? */
          else if (s1.retrying && v1.decider.check(perms === NoPerm(), Verifier.config.checkTimeout()))
            QS(s1.copy(h = s.h), s1.h, None, v1)
          else
            failure
      }
    )(Q)
  }

  def findChunk[CH <: NonQuantifiedChunk: ClassTag]
               (chunks: Iterable[Chunk],
                id: ChunkIdentifer,
                args: Iterable[Term],
                v: Verifier)
               : Option[CH] = {
    val relevantChunks = findChunksWithID[CH](chunks, id)
    findChunkLiterally(relevantChunks, args) orElse findChunkWithProver(relevantChunks, args, v)
  }

  def findMatchingChunk[CH <: NonQuantifiedChunk: ClassTag]
                       (chunks: Iterable[Chunk], chunk: CH, v: Verifier): Option[CH] = {
    findChunk[CH](chunks, chunk.id, chunk.args, v)
  }

  def findChunksWithID[CH <: NonQuantifiedChunk: ClassTag](chunks: Iterable[Chunk], id: ChunkIdentifer): Iterable[CH] = {
    chunks.flatMap {
      case c: CH if id == c.id => Some(c)
      case _ => None
    }
  }

  private def findChunkLiterally[CH <: NonQuantifiedChunk](chunks: Iterable[CH], args: Iterable[Term]) = {
    chunks find (ch => ch.args == args)
  }

  private def findChunkWithProver[CH <: NonQuantifiedChunk](chunks: Iterable[CH], args: Iterable[Term], v: Verifier) = {
      chunks find (ch =>
        args.size == ch.args.size &&
        v.decider.check(And(ch.args zip args map (x => x._1 === x._2)), Verifier.config.checkTimeout()))
  }

}
