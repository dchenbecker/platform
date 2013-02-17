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
package com.precog.daze

import org.specs2.mutable._

import com.precog.yggdrasil._
import com.precog.common.Path

import scalaz._

trait TypeLibSpecs[M[+_]] extends Specification
    with EvaluatorTestSupport[M]
    with LongIdMemoryDatasetConsumer[M] { self =>

  import dag._
  import instructions._
  import library._
  
  val testAPIKey = "testAPIKey"
  
  def testEval(graph: DepGraph): Set[SEvent] = {
    consumeEval(testAPIKey, graph, Path.Root) match {
      case Success(results) => results
      case Failure(error) => throw error
    }
  }

  "the type functions" should {
    "return correct booleans for isNumber" in {
      val line = Line(1, 1, "")

      val input = dag.Operate(BuiltInFunction1Op(isNumber),
        dag.LoadLocal(Const(CString("/het/numbersAcrossSlices2"))(line))(line))(line)

      val result = testEval(input)

      result must haveSize(24)

      val result2 = result.toSeq collect {
        case (ids, SBoolean(b)) if ids.length == 1 => b
      }

      val (trues, falses) = result2 partition identity

      trues.length mustEqual 9
      falses.length mustEqual 15
    }
  
    "return correct booleans for isBoolean" in {
      val line = Line(1, 1, "")

      val input = dag.Operate(BuiltInFunction1Op(isBoolean),
        dag.LoadLocal(Const(CString("/het/numbersAcrossSlices2"))(line))(line))(line)

      val result = testEval(input)

      result must haveSize(24)

      val result2 = result.toSeq collect {
        case (ids, SBoolean(b)) if ids.length == 1 => b
      }

      val (trues, falses) = result2 partition identity

      trues.length mustEqual 4
      falses.length mustEqual 20
    }
  
    "return correct booleans for isNull" in {
      val line = Line(1, 1, "")

      val input = dag.Operate(BuiltInFunction1Op(isNull),
        dag.LoadLocal(Const(CString("/het/numbersAcrossSlices2"))(line))(line))(line)

      val result = testEval(input)

      result must haveSize(24)

      val result2 = result.toSeq collect {
        case (ids, SBoolean(b)) if ids.length == 1 => b
      }

      val (trues, falses) = result2 partition identity

      trues.length mustEqual 2
      falses.length mustEqual 22
    }
  
    "return correct booleans for isString" in {
      val line = Line(1, 1, "")

      val input = dag.Operate(BuiltInFunction1Op(isString),
        dag.LoadLocal(Const(CString("/het/numbersAcrossSlices2"))(line))(line))(line)

      val result = testEval(input)

      result must haveSize(24)

      val result2 = result.toSeq collect {
        case (ids, SBoolean(b)) if ids.length == 1 => b
      }

      val (trues, falses) = result2 partition identity

      trues.length mustEqual 1
      falses.length mustEqual 23
    }
  
    "return correct booleans for isObject" in {
      val line = Line(1, 1, "")

      val input = dag.Operate(BuiltInFunction1Op(isObject),
        dag.LoadLocal(Const(CString("/het/numbersAcrossSlices2"))(line))(line))(line)

      val result = testEval(input)

      result must haveSize(24)

      val result2 = result.toSeq collect {
        case (ids, SBoolean(b)) if ids.length == 1 => b
      }

      val (trues, falses) = result2 partition identity

      trues.length mustEqual 3
      falses.length mustEqual 21
    }
  
    "return correct booleans for isArray" in {
      val line = Line(1, 1, "")

      val input = dag.Operate(BuiltInFunction1Op(isArray),
        dag.LoadLocal(Const(CString("/het/numbersAcrossSlices2"))(line))(line))(line)

      val result = testEval(input)

      result must haveSize(24)

      val result2 = result.toSeq collect {
        case (ids, SBoolean(b)) if ids.length == 1 => b
      }

      val (trues, falses) = result2 partition identity

      trues.length mustEqual 5
      falses.length mustEqual 19
    }
  }
}

object TypeLibSpecs extends TypeLibSpecs[test.YId] with test.YIdInstances