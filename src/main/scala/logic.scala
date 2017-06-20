// Copyright: 2017 https://github.com/fommil/drone-dynamic-agents/graphs
// License: http://www.apache.org/licenses/LICENSE-2.0
package logic

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

import scala.{ Int, None, Option }
import scala.collection.immutable.{ Map, Set }
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.Predef.ArrowAssoc

import cats.data.NonEmptyList
import cats.implicits._
import freestyle._

import algebra.drone._
import algebra.machines._

/**
 * @param backlog how many builds are waiting to be run on the ci
 * @param agents how many agents are fulfilling ci jobs
 * @param managed nodes that are available
 * @param alive nodes are currently alive, values are when they were
 *               started
 * @param pending nodes that we have recently changed the world of.
 *                These should be considered "unavailable". NOTE: we
 *                have a visibility problem here if we never hear back
 *                when they change world to available / alive. Values
 *                are when we requested a change of world.
 * @param time is the most recent clock tick from the service managing
 *             the nodes. Note that this is stale by definition, but
 *             there are best endeavours to refresh it regularly.
 */
final case class WorldView(
  backlog: Int,
  agents: Int,
  managed: NonEmptyList[Node],
  alive: Map[Node, ZonedDateTime],
  pending: Map[Node, ZonedDateTime],
  time: ZonedDateTime
)

@module @debug trait DynAgents {
  val d: Drone
  val m: Machines

  def initial: FS[WorldView] =
    (d.getBacklog |@| d.getAgents |@| m.getManaged |@| m.getAlive |@| m.getTime).map {
      case (db, da, mm, ma, mt) => WorldView(db, da, mm, ma, Map.empty, mt)
    }

  def update(old: WorldView): FS[WorldView] =
    for {
      snap    <- initial
      changed = symdiff(old.alive.keySet, snap.alive.keySet)
      pending = (old.pending -- changed).filterNot {
        case (_, started) => timediff(started, snap.time) >= 10.minutes
      }
      update = snap.copy(pending = pending)
    } yield update

  private def symdiff[T](a: Set[T], b: Set[T]): Set[T] =
    (a union b) -- (a intersect b)

  def act(world: WorldView): FS[WorldView] = world match {
    case NeedsAgent(node) =>
      for {
        _      <- m.start(node)
        update = world.copy(pending = Map(node -> world.time))
      } yield update

    case Stale(nodes) =>
      for {
        stopped <- nodes.traverse(m.stop)
        updates = stopped.map(_ -> world.time).toList.toMap
        update  = world.copy(pending = world.pending ++ updates)
      } yield update

    case _ => FreeS.pure(world)
  }

  private def timediff(from: ZonedDateTime,
                       to: ZonedDateTime): FiniteDuration =
    ChronoUnit.MINUTES.between(from, to).minutes

  // with a backlog, but no agents or pending nodes, start a node
  private object NeedsAgent {
    def unapply(world: WorldView): Option[Node] = world match {
      case WorldView(backlog, 0, managed, alive, pending, _)
          if backlog > 0 && alive.isEmpty && pending.isEmpty =>
        Option(managed.head)
      case _ => None
    }
  }

  // when there is no backlog, stop all alive nodes. However, since
  // Google / AWS charge per hour we only shut down machines in their
  // 58th+ minute.
  //
  // Assumes that we are called fairly regularly otherwise we may miss
  // this window (should we take control over calling getTime instead
  // of expecting it from the WorldView we are handed?).
  //
  // Safety net: even if there is a backlog, stop older agents.
  private object Stale {
    def unapply(world: WorldView): Option[NonEmptyList[Node]] = world match {
      case WorldView(backlog, _, _, alive, pending, time) if alive.nonEmpty =>
        val stale = (alive -- pending.keys).collect {
          case (n, started)
              if backlog == 0 && timediff(started, time).toMinutes % 60 >= 58 =>
            n
          case (n, started) if timediff(started, time) >= 5.hours => n
        }.toList
        NonEmptyList.fromList(stale)

      case _ => None
    }
  }

}
