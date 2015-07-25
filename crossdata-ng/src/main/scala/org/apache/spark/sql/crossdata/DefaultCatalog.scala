/*
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.spark.sql.crossdata

import java.io._

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.{Input, Output}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.{SparkContext, SparkConf, Logging}
import org.apache.spark.sql.{DataFrame, Row, SQLContext}
import org.apache.spark.sql.catalyst.CatalystConf
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.{LogicalRDD, SparkSqlSerializer}
import org.mapdb.{DB, DBMaker}

import scala.reflect.io.{Directory, Path}

/**
 * Default implementation of the [[org.apache.spark.sql.crossdata.XDCatalog]] with persistence using
 * [[http://www.mapdb.org/ MapDB web site]].
 * @param conf The [[org.apache.spark.sql.catalyst.CatalystConf]].
 * @param path Path to the file to be used as persistent data.
 */
class DefaultCatalog(val conf: CatalystConf,
                     xdContext: Option[XDContext] = None,
                     path: Option[String] = None) extends XDCatalog with Logging {

  private lazy val homeDir: String = System.getProperty("user.home")

  private lazy val dir: Directory =
    Path(homeDir + "/.crossdata").createDirectory(failIfExists = false)

  private val dbLocation = path match {
    case Some(v) => (v)
    case None => (dir + "/catalog")
  }

  val dbFile: File = new File(dbLocation)
  dbFile.getParentFile.mkdirs

  private val db: DB = DBMaker.newFileDB(dbFile).closeOnJvmShutdown.make

  private val tables: java.util.Map[String, LogicalPlan] = db.getHashMap("catalog")
  private val attributes: java.util.Map[String, Seq[Attribute]] = db.getHashMap("attributes")
  private val rdds: java.util.Map[String, RDD[Row]] = db.getHashMap("rdds")

  /**
   * @inheritdoc
   */
  override def open(): Unit = {
    logInfo("XDCatalog: open")
  }

  /**
   * @inheritdoc
   */
  override def tableExists(tableIdentifier: Seq[String]): Boolean = {
    logInfo("XDCatalog: tableExists")
    val tableName: String = tableIdentifier.mkString(".")
    (tables.containsKey(tableName)
      || (attributes.containsKey(tableName) && rdds.containsKey(tableName)))
  }

  /**
   * @inheritdoc
   */
  override def unregisterAllTables(): Unit = {
    logInfo("XDCatalog: unregisterAllTables")
    tables.clear
    attributes.clear
    rdds.clear
    db.commit
  }

  /**
   * @inheritdoc
   */
  override def unregisterTable(tableIdentifier: Seq[String]): Unit = {
    logInfo("XDCatalog: unregisterTable")
    val tableName: String = tableIdentifier.mkString(".")
    tables.remove(tableName)
    attributes.remove(tableName)
    rdds.remove(tableName)
    db.commit
  }

  /**
   * @inheritdoc
   */
  override def lookupRelation(tableIdentifier: Seq[String], alias: Option[String]): LogicalPlan = {
    logInfo("XDCatalog: lookupRelation")
    val tableName: String = alias match {
      case Some(a) => a
      case None => tableIdentifier.mkString(".")
    }
    if(tables.containsKey(tableName)){
      tables.get(tableName)
    } else {
      new LogicalRDD(attributes.get(tableName), rdds.get(tableName))(xdContext.get)
    }
  }

  /**
   * @inheritdoc
   */
  override def registerTable(tableIdentifier: Seq[String], plan: LogicalPlan): Unit = {
    logInfo("XDCatalog: registerTable")
    if(plan.isInstanceOf[LogicalRDD]){
      attributes.put(tableIdentifier.mkString("."), plan.asInstanceOf[LogicalRDD].output)
      rdds.put(tableIdentifier.mkString("."), plan.asInstanceOf[LogicalRDD].rdd)
    } else {
      tables.put(tableIdentifier.mkString("."), plan)
    }
    db.commit
  }

  /**
   * @inheritdoc
   */
  override def getTables(databaseName: Option[String]): Seq[(String, Boolean)] = {
    logInfo("XDCatalog: getTables")
    import collection.JavaConversions._
    val allTables: Seq[(String, Boolean)] = tables.map {
      case (name, _) => (name, false)
    }.toSeq
    allTables.addAll(0, attributes.map {
      case (name, _) => (name, false)
    }.toSeq)
    allTables
  }

  /**
   * @inheritdoc
   */
  override def refreshTable(databaseName: String, tableName: String): Unit = {
    logInfo("XDCatalog: refreshTable")
  }

  /**
   * @inheritdoc
   */
  override def close(): Unit = {
    db.close
  }
}
