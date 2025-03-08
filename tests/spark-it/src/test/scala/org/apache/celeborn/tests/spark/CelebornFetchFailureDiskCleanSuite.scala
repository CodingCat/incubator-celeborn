/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.celeborn.tests.spark

import java.io.File

import scala.collection.mutable

import org.apache.spark.shuffle.celeborn.{SparkUtils, TestCelebornShuffleManager}
import org.apache.spark.sql.SparkSession
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import org.apache.celeborn.client.ShuffleClient
import org.apache.celeborn.service.deploy.worker.Worker
import org.apache.celeborn.spark.FailedShuffleCleaner
import org.apache.celeborn.tests.spark.fetch_failure.{FailCommitShuffleReaderGetHook, FetchFailureTestBase, FileDeletionShuffleReaderGetHook, TestRunningStageManager}

class CelebornFetchFailureDiskCleanSuite extends AnyFunSuite
  with FetchFailureTestBase
  with BeforeAndAfterEach {

  override def beforeAll(): Unit = {
    logInfo("test initialized , setup Celeborn mini cluster")
    setupMiniClusterWithRandomPorts(workerNum = 1)
  }

  override def beforeEach(): Unit = {
    ShuffleClient.reset()
    FailedShuffleCleaner.reset()
  }

  override def afterEach(): Unit = {
    System.gc()
  }

  override def createWorker(map: Map[String, String]): Worker = {
    val storageDir = createTmpDir()
    workerDirs = workerDirs :+ storageDir
    super.createWorker(map ++ Map("celeborn.master.heartbeat.worker.timeout" -> "10s"), storageDir)
  }

  class CheckingThread(
      shuffleIdShouldNotExist: Seq[Int],
      shuffleIdMustExist: Seq[Int],
      sparkSession: SparkSession)
    extends Thread {
    var exception: Exception = _
    protected def checkDirStatus(): Boolean = {
      val deletedSuccessfully = shuffleIdShouldNotExist.forall(shuffleId => {
        workerDirs.forall(dir =>
          !new File(s"$dir/celeborn-worker/shuffle_data/" +
            s"${sparkSession.sparkContext.applicationId}/$shuffleId").exists())
      })
      val createdSuccessfully = shuffleIdMustExist.forall(shuffleId => {
        workerDirs.exists(dir =>
          new File(s"$dir/celeborn-worker/shuffle_data/" +
            s"${sparkSession.sparkContext.applicationId}/$shuffleId").exists())
      })
      deletedSuccessfully && createdSuccessfully
    }
    override def run(): Unit = {
      var allDataInShape = checkDirStatus()
      while (!allDataInShape) {
        Thread.sleep(1000)
        allDataInShape = checkDirStatus()
      }
    }
  }

  class CheckingThreadForStableStatus(
      shuffleIdShouldNotExist: Seq[Int],
      shuffleIdMustExist: Seq[Int],
      sparkSession: SparkSession)
    extends CheckingThread(shuffleIdShouldNotExist, shuffleIdMustExist, sparkSession) {
    override def run(): Unit = {
      val timeout = 240000
      var elapseTime = 0L
      var allDataInShape = checkDirStatus()
      while (!allDataInShape) {
        Thread.sleep(5000)
        println("init state not meet")
        allDataInShape = checkDirStatus()
      }
      while (allDataInShape) {
        Thread.sleep(5000)
        elapseTime += 5000
        if (elapseTime > timeout) {
          return
        }
        allDataInShape = checkDirStatus()
        if (!allDataInShape) {
          exception = new IllegalStateException("the directory state does not meet" +
            " the expected state")
          throw exception
        }
      }
    }
  }

  private def triggerStorageCheckThread(
      shuffleIdShouldNotExist: Seq[Int],
      shuffleIdMustExist: Seq[Int],
      sparkSession: SparkSession,
      forStableStatusChecking: Boolean): CheckingThread = {
    val checkingThread =
      if (!forStableStatusChecking) {
        new CheckingThread(shuffleIdShouldNotExist, shuffleIdMustExist, sparkSession)
      } else {
        new CheckingThreadForStableStatus(shuffleIdShouldNotExist, shuffleIdMustExist, sparkSession)
      }
    checkingThread.setDaemon(true)
    checkingThread.start()
    checkingThread
  }
  private def checkStorageValidation(checkingThread: Thread): Unit = {
    checkingThread.join(240 * 1000)
    if (checkingThread.isAlive || checkingThread.asInstanceOf[CheckingThread].exception != null) {
      throw new IllegalStateException("the storage checking status failed," +
        s"${}")
    }
  }
  // 1. for single level 1-1 lineage, the old disk space is cleaned before the spark application
  // finish
  test("celeborn spark integration test - (1-1 dep with, single level lineage) the failed shuffle file is cleaned up correctly") {
    if (Spark3OrNewer) {
      val sparkSession = createSparkSession(enableFailedShuffleCleaner = true)
      val celebornConf = SparkUtils.fromSparkConf(sparkSession.sparkContext.getConf)
      val hook = new FileDeletionShuffleReaderGetHook(
        celebornConf,
        workerDirs,
        shuffleIdToBeDeleted = Seq(0))
      TestCelebornShuffleManager.registerReaderGetHook(hook)
      val value = Range(1, 10000).mkString(",")
      val checkingThread =
        triggerStorageCheckThread(Seq(0), Seq(1), sparkSession, forStableStatusChecking = false)
      val tuples = sparkSession.sparkContext.parallelize(1 to 10000, 2)
        .map { i => (i, value) }.groupByKey(16).collect()
      checkStorageValidation(checkingThread)
      // verify result
      assert(hook.executed.get())
      assert(tuples.length == 10000)
      for (elem <- tuples) {
        assert(elem._2.mkString(",").equals(value))
      }
      sparkSession.stop()
    }
  }
  // 2. for multiple level 1-1 lineage, the old disk space is cleaned one by one
  test("celeborn spark integration test - (1-1 dep with, multi-level lineage) the failed shuffle file is cleaned up correctly") {
    if (Spark3OrNewer) {
      val sparkSession = createSparkSession(enableFailedShuffleCleaner = true)
      val celebornConf = SparkUtils.fromSparkConf(sparkSession.sparkContext.getConf)
      val hook =
        new FileDeletionShuffleReaderGetHook(
          celebornConf,
          workerDirs,
          shuffleIdToBeDeleted = Seq(0, 1),
          triggerStageId = Some(2))
      TestCelebornShuffleManager.registerReaderGetHook(hook)
      val value = Range(1, 10000).mkString(",")
      val checkingThread = triggerStorageCheckThread(
        Seq(0, 1),
        Seq(2, 3, 4),
        sparkSession,
        forStableStatusChecking = false)
      val tuples = sparkSession.sparkContext.parallelize(1 to 10000, 2)
        .map { i => (i, value) }.groupByKey(16).map {
          case (k, elements) =>
            (k, elements.map(str => str.toLowerCase))
        }.groupByKey(4).groupByKey(2).collect()
      checkStorageValidation(checkingThread)
      // verify result
      assert(hook.executed.get())
      assert(tuples.length == 10000)
      for (elem <- tuples) {
        assert(elem._2.flatten.flatten.mkString(",").equals(value))
      }
      sparkSession.stop()
    }
  }

  // 3. for single level M-1 lineage, the single failed disk space is cleaned
  test(
    "celeborn spark integration test - (M-1 dep with single level lineage) the single failed shuffle file is cleaned up correctly") {
    if (Spark3OrNewer) {
      val sparkSession = createSparkSession(enableFailedShuffleCleaner = true)
      val celebornConf = SparkUtils.fromSparkConf(sparkSession.sparkContext.getConf)
      val hook = new FileDeletionShuffleReaderGetHook(
        celebornConf,
        workerDirs,
        shuffleIdToBeDeleted = Seq(0))
      TestCelebornShuffleManager.registerReaderGetHook(hook)
      val checkingThread =
        triggerStorageCheckThread(Seq(0), Seq(1, 2), sparkSession, forStableStatusChecking = false)
      import sparkSession.implicits._
      val df1 = Seq((1, "a"), (2, "b")).toDF("id", "data").groupBy("id").count()
      val df2 = Seq((2, "c"), (2, "d")).toDF("id", "data").groupBy("id").count()
      val tuples = df1.hint("merge").join(df2, "id").select("*").collect()
      checkStorageValidation(checkingThread)
      // verify result
      assert(hook.executed.get())
      val expect = "[2,1,2]"
      assert(tuples.head.toString().equals(expect))
      sparkSession.stop()
    }
  }

  // 4. for single level M-1 lineage, all failed disk spaces are cleaned
  test("celeborn spark integration test - (M-1 dep with single-level lineage) all failed disk spaces are cleaned") {
    if (Spark3OrNewer) {
      val sparkSession = createSparkSession(enableFailedShuffleCleaner = true)
      val celebornConf = SparkUtils.fromSparkConf(sparkSession.sparkContext.getConf)
      val hook = new FileDeletionShuffleReaderGetHook(
        celebornConf,
        workerDirs,
        shuffleIdToBeDeleted = Seq(0, 1))
      TestCelebornShuffleManager.registerReaderGetHook(hook)
      val checkingThread = triggerStorageCheckThread(
        Seq(0, 1),
        Seq(2, 3),
        sparkSession,
        forStableStatusChecking = false)
      import sparkSession.implicits._
      val df1 = Seq((1, "a"), (2, "b")).toDF("id", "data").groupBy("id").count()
      val df2 = Seq((2, "c"), (3, "d")).toDF("id", "data").groupBy("id").count()
      val tuples = df1.hint("merge").join(df2, "id").select("*").collect()
      checkStorageValidation(checkingThread)
      // verify result
      assert(hook.executed.get())
      val expect = "[2,1,1]"
      println(tuples.head.toString())
      assert(tuples.head.toString().equals(expect))
      sparkSession.stop()
    }
  }

  // 6. for multiple level M - 1 lineage , all failed disk spaces are cleaned
  test("celeborn spark integration test - (M-1 dep with multi-level lineage) the failed shuffle files are all cleaned up" +
    " correctly") {
    if (Spark3OrNewer) {
      val sparkSession = createSparkSession(enableFailedShuffleCleaner = true)
      val celebornConf = SparkUtils.fromSparkConf(sparkSession.sparkContext.getConf)
      val hook = new FileDeletionShuffleReaderGetHook(
        celebornConf,
        workerDirs,
        shuffleIdToBeDeleted = Seq(0, 1, 2, 3),
        triggerStageId = Some(4))
      TestCelebornShuffleManager.registerReaderGetHook(hook)
      val checkingThread = triggerStorageCheckThread(
        Seq(0, 1, 2, 3),
        Seq(4, 5, 6, 7),
        sparkSession,
        forStableStatusChecking = false)
      import sparkSession.implicits._
      val df1 = Seq((1, "a"), (2, "b")).toDF("id", "data").groupBy("id").count()
        .withColumnRenamed("count", "countId").groupBy("countId").count()
        .withColumnRenamed("count", "df1_count")
      val df2 = Seq((2, "c"), (3, "d")).toDF("id", "data").groupBy("id").count()
        .withColumnRenamed("count", "countId").groupBy("countId").count()
        .withColumnRenamed("count", "df2_count")
      val tuples = df1.hint("merge").join(df2, "countId").select("*").collect()
      checkStorageValidation(checkingThread)
      // verify result
      assert(hook.executed.get())
      val expect = "[1,2,2]"
      assert(tuples.head.toString().equals(expect))
      sparkSession.stop()
    }
  }

  // 7. if the dependency is 1 to M , we should not clean it
  test("celeborn spark integration test - Do not clean up the shuffle files being referred by more than one stages") {
    if (Spark3OrNewer) {
      System.setProperty(
        FailedShuffleCleaner.RUNNING_STAGE_CHECKER_CLASS,
        "org.apache.celeborn.tests.spark.fetch_failure.TestRunningStageManager")
      FailedShuffleCleaner.reset()
      // create dummy running stages
      TestRunningStageManager.runningStages += 2
      FailedShuffleCleaner.celebornShuffleIdToReferringStages.put(0, new mutable.HashSet[Int])
      FailedShuffleCleaner.celebornShuffleIdToReferringStages.get(0) += 2
      val sparkSession = createSparkSession(enableFailedShuffleCleaner = true)
      val celebornConf = SparkUtils.fromSparkConf(sparkSession.sparkContext.getConf)
      val hook = new FileDeletionShuffleReaderGetHook(
        celebornConf,
        workerDirs,
        shuffleIdToBeDeleted = Seq(0))
      TestCelebornShuffleManager.registerReaderGetHook(hook)
      val checkingThread =
        triggerStorageCheckThread(Seq(), Seq(0, 1), sparkSession, forStableStatusChecking = true)
      import sparkSession.implicits._
      val df1 = Seq((1, "a"), (2, "b")).toDF("id", "data").groupBy("id").count()
      val tuple = df1.collect().map(r => r.getAs[Int]("id"))
      checkStorageValidation(checkingThread)
      // verify result
      assert(hook.executed.get())
      val expect = "[2,1]"
      assert(tuple.mkString("[", ",", "]").equals(expect))
      sparkSession.stop()
    }
  }
  // 8. if the dependency is 1 to M but failed in commit phase, we should just clean it
  test("celeborn spark integration test - clear the failed-to-commit shuffle file even it is referred by more than once") {
    if (Spark3OrNewer) {
      System.setProperty(
        FailedShuffleCleaner.RUNNING_STAGE_CHECKER_CLASS,
        "org.apache.celeborn.tests.spark.fetch_failure.TestRunningStageManager")
      FailedShuffleCleaner.reset()
      // create dummy running stages
      TestRunningStageManager.runningStages += 2
      FailedShuffleCleaner.celebornShuffleIdToReferringStages.put(0, new mutable.HashSet[Int])
      FailedShuffleCleaner.celebornShuffleIdToReferringStages.get(0) += 2
      val sparkSession = createSparkSession(enableFailedShuffleCleaner = true)
      val celebornConf = SparkUtils.fromSparkConf(sparkSession.sparkContext.getConf)
      val hook = new FailCommitShuffleReaderGetHook(celebornConf)
      TestCelebornShuffleManager.registerReaderGetHook(hook)
      val checkingThread =
        triggerStorageCheckThread(Seq(0, 2), Seq(1), sparkSession, forStableStatusChecking = true)
      import sparkSession.implicits._
      val df1 = Seq((1, "a"), (2, "b")).toDF("id", "data").groupBy("id").count()
      val tuples = df1.collect().map(r => r.getAs[Int]("id"))
      checkStorageValidation(checkingThread)
      // verify result
      assert(hook.executed.get())
      val expect = "[2,1]"
      assert(tuples.mkString("[", ",", "]").equals(expect))
      sparkSession.stop()
    }
  }
  test("celeborn spark integration test - clean up the shuffle files if" +
    " the referring stage has finished") {
    if (Spark3OrNewer) {
      System.setProperty(
        FailedShuffleCleaner.RUNNING_STAGE_CHECKER_CLASS,
        "org.apache.celeborn.tests.spark.fetch_failure.TestRunningStageManager")
      FailedShuffleCleaner.reset()
      // create dummy running stages
      FailedShuffleCleaner.celebornShuffleIdToReferringStages.put(0, new mutable.HashSet[Int])
      FailedShuffleCleaner.celebornShuffleIdToReferringStages.get(0) += 2
      val sparkSession = createSparkSession(enableFailedShuffleCleaner = true)
      val celebornConf = SparkUtils.fromSparkConf(sparkSession.sparkContext.getConf)
      val hook = new FileDeletionShuffleReaderGetHook(
        celebornConf,
        workerDirs,
        shuffleIdToBeDeleted = Seq(0))
      TestCelebornShuffleManager.registerReaderGetHook(hook)
      val checkingThread =
        triggerStorageCheckThread(Seq(), Seq(1), sparkSession, forStableStatusChecking = true)
      import sparkSession.implicits._
      val df1 = Seq((1, "a"), (2, "b")).toDF("id", "data").groupBy("id").count()
      val tuple = df1.collect().map(r => r.getAs[Int]("id"))
      checkStorageValidation(checkingThread)
      // verify result
      assert(hook.executed.get())
      val expect = "[2,1]"
      assert(tuple.mkString("[", ",", "]").equals(expect))
      sparkSession.stop()
    }
  }
}
