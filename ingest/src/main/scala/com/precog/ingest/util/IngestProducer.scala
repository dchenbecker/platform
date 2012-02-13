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
package com.precog.ingest
package util

import kafka._ 

import scala.collection.mutable.ListBuffer

import java.util.Properties
import java.io.{File, FileReader}

import com.precog.analytics.{Path, Token}
import com.precog.common._
import com.precog.common.util.RealisticIngestMessage
import com.precog.common.util.AdSamples
import com.precog.common.util.DistributedSampleSet
import com.precog.ingest.service._

import akka.actor.ActorSystem
import akka.dispatch.Future
import akka.dispatch.Await
import akka.util.duration._

import blueeyes.bkka.AkkaDefaults

import blueeyes.core.http.MimeTypes
import blueeyes.core.http.MimeTypes._
import blueeyes.core.data.BijectionsChunkJson._
import blueeyes.core.http.HttpResponse
import blueeyes.core.service.HttpClient
import blueeyes.core.service.engines.HttpClientXLightWeb

import blueeyes.json.JsonAST._

import scalaz.NonEmptyList

abstract class IngestProducer(args: Array[String]) extends RealisticIngestMessage {

  lazy val config = loadConfig(args)

  lazy val messages = config.getProperty("messages", "1000").toInt
  lazy val delay = config.getProperty("delay", "100").toInt
  lazy val threadCount = config.getProperty("threads", "1").toInt

  def run() {

    val start = System.nanoTime

    val threads = 0.until(threadCount).map(_ => new Thread() {
      override def run() {
        val samples = List(
          ("/campaigns/", DistributedSampleSet(0, sampler = AdSamples.adCampaignSample _)),
          ("/organizations/", DistributedSampleSet(0, sampler = AdSamples.adOrganizationSample _)),
          ("/clicks/", DistributedSampleSet(0, sampler = AdSamples.interactionSample _)),
          ("/impressions/", DistributedSampleSet(0, sampler = AdSamples.interactionSample _)))
        
      
        samples.foreach {
          case (path, sample) =>
            def event = Event.fromJValue(Path(path), sample.next._1, Token.Root.tokenId)
            0.until(messages).foreach { i =>
              if(i % 10 == 0) println("Sending to [%s]: %d".format(path, i))
              send(event)
              if(delay > 0) {
                Thread.sleep(delay)
            }
        }
      }
    }})

    threads.foreach(_.start)
    threads.foreach(_.join)

    close

    println("Time: %.02f".format((System.nanoTime - start) / 1000000000.0))
  }

  def loadConfig(args: Array[String]): Properties = {
    if(args.length != 1) usage() 
    
    val config = new Properties()
    val file = new File(args(0))
    
    if(!file.exists) usage() 
    
    config.load(new FileReader(file))
    config
  }

  def usage() {
    println(usageMessage)
    sys.exit(1)
  }

  def usageMessage = 
    """
Usage: command {properties file}

Properites:
messages - number of messages to produce (default: 1000)
delay - delay between messages (<0 indicates no delay) (default: 100)
threads - number of producer threads (default: 1)
    """
  
  def send(event: Event): Unit
  def close(): Unit = ()
}

object WebappIngestProducer {
  def main(args: Array[String]) =  new WebappIngestProducer(args).run()
}

class WebappIngestProducer(args: Array[String]) extends IngestProducer(args) {
  lazy val base = config.getProperty("serviceUrl", "http://localhost:30050/vfs/")
  val client = new HttpClientXLightWeb 

  def send(event: Event) {

    val f: Future[HttpResponse[JValue]] = client.path(base)
                                                .query("tokenId", event.tokenId)
                                                .contentType(application/MimeTypes.json)
                                                .post[JValue](event.path.toString)(event.data)
    Await.ready(f, 10 seconds) 
    f.value match {
      case Some(Right(_)) => ()
      case Some(Left(ex)) => println("Error tracking data." + ex)
      case _              => println("Error tracking data. (Timeout most likely?)")
    }
  }

  override def usageMessage = super.usageMessage + """
serviceUrl - base url for web application (default: http://localhost:30050/vfs/)
  """

  override def close(): Unit = AkkaDefaults.actorSystem.shutdown
}

object DirectIngestProducer {
  def main(args: Array[String]) = new DirectIngestProducer(args).run()
}

class DirectIngestProducer(args: Array[String]) extends IngestProducer(args) {
  implicit val actorSystem = ActorSystem("direct_ingest")

  lazy val testTopic = config.getProperty("topicId", "test-topic-1")
  lazy val zookeeperHosts = config.getProperty("zookeeperHosts", "127.0.0.1:2181")
  lazy val store = kafkaStore(testTopic)

  def send(event: Event) {
    store.save(genEvent.sample.get)
  }

  def kafkaStore(topic: String): KafkaEventStore = {
    val props = new Properties()
    props.put("zk.connect", zookeeperHosts) 
    props.put("serializer.class", "com.precog.ingest.api.IngestMessageCodec")
  
    val defaultAddresses = NonEmptyList(MailboxAddress(0))

    val routeTable = new ConstantRouteTable(defaultAddresses)
    val messaging = new KafkaMessaging(topic, props)

    val qz = PrecogZookeeper.testPrecogZookeeper(zookeeperHosts)
    qz.setup
    val producerId = qz.acquireProducerId
    qz.close

    new KafkaEventStore(new EventRouter(routeTable, messaging), producerId)
  }
  
  override def usageMessage = super.usageMessage + """
topicId - kafka topic to publish events to (default: test-topic-1 )
zookeeperHosts - comma delimeted list of zookeeper hosts (default: 127.0.0.1:2181)
  """

  override def close() {
    Await.result(store.close, 10 seconds)
    actorSystem.shutdown
  }
}