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

package org.apache.spark.sql.jdbc.v2

import java.sql.{Connection, Timestamp}

import org.apache.spark.SparkConf
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.analysis.TableAlreadyExistsException
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, Filter}
import org.apache.spark.sql.execution.datasources.v2.jdbc.JDBCTableCatalog
import org.apache.spark.sql.jdbc.DatabaseOnDocker
import org.apache.spark.sql.types._
import org.apache.spark.tags.DockerTest

/**
 * To run this test suite for a specific version (e.g., postgres:16.4-alpine)
 * {{{
 *   ENABLE_DOCKER_INTEGRATION_TESTS=1 POSTGRES_DOCKER_IMAGE_NAME=postgres:16.4-alpine
 *     ./build/sbt -Pdocker-integration-tests "testOnly *v2.PostgresIntegrationSuite"
 * }}}
 */
@DockerTest
class PostgresIntegrationSuite extends DockerJDBCIntegrationV2Suite with V2JDBCTest {
  override val catalogName: String = "postgresql"
  override val db = new DatabaseOnDocker {
    override val imageName = sys.env.getOrElse("POSTGRES_DOCKER_IMAGE_NAME", "postgres:16.4-alpine")
    override val env = Map(
      "POSTGRES_PASSWORD" -> "rootpass"
    )
    override val usesIpc = false
    override val jdbcPort = 5432
    override def getJdbcUrl(ip: String, port: Int): String =
      s"jdbc:postgresql://$ip:$port/postgres?user=postgres&password=rootpass"
  }
  override def sparkConf: SparkConf = super.sparkConf
    .set("spark.sql.catalog.postgresql", classOf[JDBCTableCatalog].getName)
    .set("spark.sql.catalog.postgresql.url", db.getJdbcUrl(dockerIp, externalPort))
    .set("spark.sql.catalog.postgresql.pushDownTableSample", "true")
    .set("spark.sql.catalog.postgresql.pushDownLimit", "true")
    .set("spark.sql.catalog.postgresql.pushDownAggregate", "true")
    .set("spark.sql.catalog.postgresql.pushDownOffset", "true")

  override def tablePreparation(connection: Connection): Unit = {
    connection.prepareStatement(
      "CREATE TABLE employee (dept INTEGER, name VARCHAR(32), salary NUMERIC(20, 2)," +
        " bonus double precision)").executeUpdate()
    connection.prepareStatement(
      s"""CREATE TABLE pattern_testing_table (
         |pattern_testing_col VARCHAR(50)
         |)
                   """.stripMargin
    ).executeUpdate()
    connection.prepareStatement(
      s"CREATE TABLE datetime_table (id INTEGER, time TIMESTAMP)"
    ).executeUpdate()
    Iterator.range(1, 3).foreach(i => {
      connection.prepareStatement(s"INSERT INTO datetime_table VALUES ($i, '2024-$i-$i $i:$i:$i')")
        .executeUpdate()
    })
  }

  override def testUpdateColumnType(tbl: String): Unit = {
    sql(s"CREATE TABLE $tbl (ID INTEGER)")
    var t = spark.table(tbl)
    var expectedSchema = new StructType()
      .add("ID", IntegerType, true, defaultMetadata(IntegerType))
    assert(t.schema === expectedSchema)
    sql(s"ALTER TABLE $tbl ALTER COLUMN id TYPE STRING")
    t = spark.table(tbl)
    expectedSchema = new StructType()
      .add("ID", StringType, true, defaultMetadata())
    assert(t.schema === expectedSchema)
    // Update column type from STRING to INTEGER
    val sql1 = s"ALTER TABLE $tbl ALTER COLUMN id TYPE INTEGER"
    checkError(
      exception = intercept[AnalysisException] {
        sql(sql1)
      },
      condition = "NOT_SUPPORTED_CHANGE_COLUMN",
      parameters = Map(
        "originType" -> "\"STRING\"",
        "newType" -> "\"INT\"",
        "newName" -> "`ID`",
        "originName" -> "`ID`",
        "table" -> s"`$catalogName`.`alt_table`"),
      context = ExpectedContext(fragment = sql1, start = 0, stop = 60)
    )
  }

  override def testCreateTableWithProperty(tbl: String): Unit = {
    sql(s"CREATE TABLE $tbl (ID INT)" +
      s" TBLPROPERTIES('TABLESPACE'='pg_default')")
    val t = spark.table(tbl)
    val expectedSchema = new StructType()
      .add("ID", IntegerType, true, defaultMetadata(IntegerType))
    assert(t.schema === expectedSchema)
  }

  override def supportsTableSample: Boolean = true

  override def supportsIndex: Boolean = true

  override def indexOptions: String = "FILLFACTOR=70"

  test("SPARK-42964: SQLState: 42P07 - duplicated table") {
    val t1 = s"$catalogName.t1"
    val t2 = s"$catalogName.t2"
    withTable(t1, t2) {
      sql(s"CREATE TABLE $t1(c int)")
      sql(s"CREATE TABLE $t2(c int)")
      checkError(
        exception = intercept[TableAlreadyExistsException](sql(s"ALTER TABLE $t1 RENAME TO t2")),
        condition = "TABLE_OR_VIEW_ALREADY_EXISTS",
        parameters = Map("relationName" -> "`t2`")
      )
    }
  }

  test("SPARK-49162: Push down aggregate date_trunc function") {
    def testAggregatePushdown(format: String, expectedResult: Set[Row]): Unit = {
      val df = sql(
        s"""
            SELECT DATE_TRUNC('$format', time), COUNT(*)
            | FROM $catalogName.datetime_table
            | GROUP BY 1
         """.stripMargin
      )
      val aggregates = df.queryExecution.optimizedPlan.collect {
        case agg: Aggregate => agg
      }
      assert(aggregates.isEmpty)
      assert(df.collect().toSet === expectedResult)
    }

    testAggregatePushdown("YEAR",
      Set(Row(Timestamp.valueOf("2024-01-01 00:00:00.0"), 2)))
    testAggregatePushdown("MONTH",
      Set(
        Row(Timestamp.valueOf("2024-02-01 00:00:00.0"), 1),
        Row(Timestamp.valueOf("2024-01-01 00:00:00.0"), 1)
      ))
    testAggregatePushdown("DAY",
      Set(
        Row(Timestamp.valueOf("2024-02-02 00:00:00.0"), 1),
        Row(Timestamp.valueOf("2024-01-01 00:00:00.0"), 1)
      ))
    testAggregatePushdown("HOUR",
      Set(
        Row(Timestamp.valueOf("2024-02-02 02:00:00.0"), 1),
        Row(Timestamp.valueOf("2024-01-01 01:00:00.0"), 1)
      ))
    testAggregatePushdown("MINUTE",
      Set(
        Row(Timestamp.valueOf("2024-02-02 02:02:00.0"), 1),
        Row(Timestamp.valueOf("2024-01-01 01:01:00.0"), 1)
      ))
    testAggregatePushdown("SECOND",
      Set(
        Row(Timestamp.valueOf("2024-02-02 02:02:02.0"), 1),
        Row(Timestamp.valueOf("2024-01-01 01:01:01.0"), 1)
      ))
  }

  test("SPARK-49162: Push down filter date_trunc function") {
    def testFilterPushdown(format: String, date: String, expectedResult: Set[Row]): Unit = {
      val df = sql(
        s"""
            SELECT *
           | FROM $catalogName.datetime_table
           | WHERE DATE_TRUNC('$format', time) = '$date'
         """.stripMargin
      )
      val filters = df.queryExecution.optimizedPlan.collect {
        case f: Filter => f
      }
      assert(filters.isEmpty)
      assert(df.collect().toSet === expectedResult)
    }

    testFilterPushdown("YEAR", "2024-01-01 00:00:00.0",
      Set(
        Row(1, Timestamp.valueOf("2024-01-01 01:01:01.0")),
        Row(2, Timestamp.valueOf("2024-02-02 02:02:02.0"))
      ))
    testFilterPushdown("MONTH", "2024-02-01 00:00:00.0",
      Set(Row(2, Timestamp.valueOf("2024-02-02 02:02:02.0"))))
    testFilterPushdown("DAY", "2024-01-01 00:00:00.0",
      Set(Row(1, Timestamp.valueOf("2024-01-01 01:01:01.0"))))
    testFilterPushdown("HOUR", "2024-02-02 02:00:00.0",
      Set(Row(2, Timestamp.valueOf("2024-02-02 02:02:02.0"))))
    testFilterPushdown("MINUTE", "2024-01-01 01:01:00.0",
      Set(Row(1, Timestamp.valueOf("2024-01-01 01:01:01.0"))))
    testFilterPushdown("SECOND", "2024-02-02 02:02:03.0", Set())
  }
}
