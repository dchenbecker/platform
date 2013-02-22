/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.pandora

import com.precog.common.Path
import com.precog.common.VectorCase
import com.precog.common.accounts._
import com.precog.common.kafka._
import com.precog.common.security._

import com.precog.bytecode.JType

import com.precog.daze._

import com.precog.quirrel._
import com.precog.quirrel.emitter._
import com.precog.quirrel.parser._
import com.precog.quirrel.typer._

import com.precog.yggdrasil._
import com.precog.yggdrasil.actor._
import com.precog.yggdrasil.nihdb._
import com.precog.yggdrasil.metadata._
import com.precog.yggdrasil.serialization._
import com.precog.yggdrasil.table._
import com.precog.yggdrasil.util._
import com.precog.yggdrasil.test.YId

import com.precog.muspelheim._
import com.precog.niflheim._
import com.precog.util.FilesystemFileOps

import akka.actor.{ActorRef, ActorSystem}
import akka.actor.Props
import akka.dispatch._
import akka.pattern.gracefulStop
import akka.util.{Duration, Timeout}
import akka.util.duration._

import blueeyes.bkka._
import blueeyes.json._

import com.weiglewilczek.slf4s.Logging

import org.slf4j.LoggerFactory

import org.specs2.mutable._
import org.specs2.specification.Fragments
  
import scalaz._
import scalaz.std.anyVal._
import scalaz.syntax.monad._
import scalaz.syntax.copointed._
import scalaz.effect.IO

import org.streum.configrity.Configuration
import org.streum.configrity.io.BlockFormat

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object NIHDBPlatformActor extends Logging {
  abstract class YggConfig
      extends EvaluatorConfig
      with StandaloneShardSystemConfig
      with ColumnarTableModuleConfig
      with BlockStoreColumnarTableModuleConfig {
    val config = Configuration parse {
      Option(System.getProperty("precog.storage.root")) map { "precog.storage.root = " + _ } getOrElse { "" }
    }

    // None of this is used, but is required to satisfy the cake
    val cookThreshold = 10
    val maxSliceSize = cookThreshold
    val smallSliceSize = 2
    val ingestConfig = None
    val idSource = new FreshAtomicIdSource
  }

  object yggConfig extends YggConfig

  case class SystemState(projectionsActor: ActorRef, actorSystem: ActorSystem)

  private[this] var state: Option[SystemState] = None
  private[this] val users = new AtomicInteger

  def actor = users.synchronized {
    users.getAndIncrement

    if (state.isEmpty) {
      logger.info("Allocating new projections actor")
      state = {
        val actorSystem = ActorSystem("NIHDBPlatformActor")
        val storageTimeout = Timeout(300 * 1000)

        implicit val M: Monad[Future] with Copointed[Future] = new blueeyes.bkka.FutureMonad(actorSystem.dispatcher) with Copointed[Future] {
          def copoint[A](f: Future[A]) = Await.result(f, storageTimeout.duration)
        }

        val accessControl = new UnrestrictedAccessControl[Future]

        val masterChef = actorSystem.actorOf(Props(Chef(VersionedCookedBlockFormat(Map(1 -> V1CookedBlockFormat)), VersionedSegmentFormat(Map(1 -> V1SegmentFormat)))))

        val projectionsActor = actorSystem.actorOf(Props(new NIHDBProjectionsActor(yggConfig.dataDir, yggConfig.archiveDir, FilesystemFileOps, masterChef, yggConfig.cookThreshold, storageTimeout, accessControl)))

        Some(SystemState(projectionsActor, actorSystem))
      }
    }

    state.get.projectionsActor
  }

  def release = users.synchronized {
    users.getAndDecrement

    // Allow for a grace period
    state.foreach { case SystemState(_, as) => as.scheduler.scheduleOnce(Duration(60, "seconds")) { checkUnused }}
  }

  def checkUnused = users.synchronized {
    if (users.get == 0) {
      state.foreach { 
        case SystemState(projectionsActor, actorSystem) =>
          logger.info("Culling unused projections actor")
          Await.result(gracefulStop(projectionsActor, Duration(5, "minutes"))(actorSystem), Duration(3, "minutes"))
          actorSystem.shutdown()
      }
      state = None
    }
  }
}

trait NIHDBPlatformSpecs extends ParseEvalStackSpecs[Future] 
    with LongIdMemoryDatasetConsumer[Future]
    with NIHDBColumnarTableModule 
    with NIHDBStorageMetadataSource { self =>
      
  override def map(fs: => Fragments): Fragments = step { startup() } ^ fs ^ step { shutdown() }
      
  lazy val psLogger = LoggerFactory.getLogger("com.precog.pandora.PlatformSpecs")

  abstract class YggConfig extends ParseEvalStackSpecConfig
      with IdSourceConfig
      with EvaluatorConfig
      with StandaloneShardSystemConfig
      with ColumnarTableModuleConfig
      with BlockStoreColumnarTableModuleConfig {
    val cookThreshold = 10
    val ingestConfig = None
  }

  object yggConfig extends YggConfig

  implicit val M: Monad[Future] with Copointed[Future] = new blueeyes.bkka.FutureMonad(asyncContext) with Copointed[Future] {
    def copoint[A](f: Future[A]) = Await.result(f, yggConfig.maxEvalDuration)
  }

  val accountFinder = None

  def Evaluator[N[+_]](N0: Monad[N])(implicit mn: Future ~> N, nm: N ~> Future) = 
    new Evaluator[N](N0)(mn,nm) with IdSourceScannerModule {
      val report = new LoggingQueryLogger[N, instructions.Line] with ExceptionQueryLogger[N, instructions.Line] with TimingQueryLogger[N, instructions.Line] {
        val M = N0
      }
      class YggConfig extends EvaluatorConfig {
        val idSource = new FreshAtomicIdSource
        val maxSliceSize = 10
      }
      val yggConfig = new YggConfig
  }

  override val accessControl = new UnrestrictedAccessControl[Future]

  val storageTimeout = Timeout(300 * 1000)

  val projectionsActor = NIHDBPlatformActor.actor

  val report = new LoggingQueryLogger[Future, instructions.Line] with ExceptionQueryLogger[Future, instructions.Line] with TimingQueryLogger[Future, instructions.Line] {
    implicit def M = self.M
  }

  trait TableCompanion extends NIHDBColumnarTableCompanion

  object Table extends TableCompanion

  def startup() { }
  
  def shutdown() {
    NIHDBPlatformActor.release
  }
}

class NIHDBBasicValidationSpecs extends BasicValidationSpecs with NIHDBPlatformSpecs

class NIHDBHelloQuirrelSpecs extends HelloQuirrelSpecs with NIHDBPlatformSpecs

class NIHDBLogisticRegressionSpecs extends LogisticRegressionSpecs with NIHDBPlatformSpecs

class NIHDBLinearRegressionSpecs extends LinearRegressionSpecs with NIHDBPlatformSpecs

class NIHDBClusteringSpecs extends ClusteringSpecs with NIHDBPlatformSpecs

class NIHDBMiscStackSpecs extends MiscStackSpecs with NIHDBPlatformSpecs

class NIHDBNonObjectStackSpecs extends NonObjectStackSpecs with NIHDBPlatformSpecs

class NIHDBRankSpecs extends RankSpecs with NIHDBPlatformSpecs

class NIHDBRenderStackSpecs extends RenderStackSpecs with NIHDBPlatformSpecs

class NIHDBUndefinedLiteralSpecs extends UndefinedLiteralSpecs with NIHDBPlatformSpecs