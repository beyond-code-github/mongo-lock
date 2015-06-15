/*
 * Copyright 2015 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.lock

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import org.joda.time.chrono.ISOChronology
import org.joda.time.{DateTime, DateTimeZone, Duration}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest._
import uk.gov.hmrc.lock.LockFormats.Lock
import uk.gov.hmrc.mongo.MongoSpecSupport

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}

class LockRepositoryConcurrencySpec extends WordSpecLike with Matchers with OptionValues with MongoSpecSupport with ScalaFutures with LoneElement with Inside with Inspectors {

  "If multiple threads try to obtain a lock simultaneously, the repository" should {
    "Ensure that the thread that wins gets true returned, and the thread that loses gets false returned with explicitly released locks" in new ConcurrentTestCase {
      override def explicitlyReleaseLock = true

      runConcurrencyTest()
    }
    "Ensure that the thread that wins gets true returned, and the thread that loses gets false returned with expiring locks" in new ConcurrentTestCase {
      override val explicitlyReleaseLock = false
      runConcurrencyTest()
    }
  }

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(
    timeout = scaled(Span(60, Seconds)),
    interval = scaled(Span(150, Millis))
  )

  trait ConcurrentTestCase extends Matchers {

    private val repo = new LockRepository() {

      override def withCurrentTime[A](f: (DateTime) => A) = f(nextTime())

      val startingTime = new DateTime(0, DateTimeZone.UTC)
      private val instantCounter = new AtomicLong(startingTime.getMillis)

      def nextTime() = new DateTime(instantCounter.incrementAndGet(), ISOChronology.getInstanceUTC)
    }

    def await[A](future: Future[A]) = Await.result(future, FiniteDuration(5L, TimeUnit.SECONDS))

    def explicitlyReleaseLock: Boolean

    def runConcurrencyTest() {
      val numberOfThreads = 20
      val lockName = "lock"

      await(repo.removeAll)

      (1 to 50) map { i =>
        val results: Future[Seq[Boolean]] = Future.sequence((1 to numberOfThreads) map { n =>
          testLocking(lockName, s"owner-$i$n")
        }).flatMap { resultsWithOwners: Seq[(Boolean, String)] =>
          val results = resultsWithOwners.map(_._1)
          val releaseAllLocks = resultsWithOwners.map { case (locked, owner) =>
            if (locked) {
              if (explicitlyReleaseLock) repo.releaseLock(lockName, owner)
              else repo.findById(lockName).flatMap {
                case None => throw new Exception("Should exist.")
                case Some(lock) => repo.save(lock.copy(expiryTime = repo.nextTime().minusDays(1)))
              }
            }
            else Future.successful(())
          }
          Future.sequence(releaseAllLocks).
            map(_ => results)
        }

        exactly(1, results.futureValue) should be(true)
      }

    }

    def beBefore(bound: DateTime) = new Matcher[DateTime] {
      def apply(left: DateTime) = MatchResult(
        left.isBefore(bound),
        s"$left is not before $bound",
        s"$left is before $bound"
      )
    }

    def beAfter(bound: DateTime) = new Matcher[DateTime] {
      def apply(left: DateTime) = MatchResult(
        left.isAfter(bound),
        s"$left is not after $bound",
        s"$left is after $bound"
      )
    }

    def testLocking(lockId: String, myOwnerId: String): Future[(Boolean, String)] = {
      val timeBeforeLocking = repo.nextTime()
      val expireLockAfter = Duration.standardDays(1)

      repo.lock(lockId, myOwnerId, expireLockAfter).flatMap {
        locked =>
          val timeAfterLocking = repo.nextTime()
          repo.findAll.map {
            actualLocks =>
              if (locked) {
                inside(actualLocks) { case List(Lock(id, owner, created, expiry)) =>
                  owner shouldBe myOwnerId
                  created should (beAfter(timeBeforeLocking) and beBefore(timeAfterLocking))
                  expiry shouldBe created.plus(expireLockAfter)
                }
              } else {
                actualLocks should (have size 0 or have size 1)
                no(actualLocks) should have('owner(myOwnerId))
              }
              (locked, myOwnerId)
          }
      }
    }
  }

}