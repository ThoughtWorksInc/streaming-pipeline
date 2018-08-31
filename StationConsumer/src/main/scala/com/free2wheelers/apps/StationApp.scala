package com.free2wheelers.apps

import com.free2wheelers.apps.StationStatusTransformation.{informationJson2DF, statusInformationJson2DF}
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.spark.sql.types.TimestampType
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

case class statusType(station_id: String, bikes_available: Int, docks_available: Int, is_renting: Boolean,
                      is_returning: Boolean, last_updated: java.sql.Timestamp)

case class infoType(station_id: String, name: String, latitude: Double, longitude: Double)

object StationApp {
  def main(args: Array[String]): Unit = {

    val retryPolicy = new ExponentialBackoffRetry(1000, 3)

    val zookeeperConnectionString = if (args.isEmpty) "zookeeper:2181" else args(0)

    val zkClient = CuratorFrameworkFactory.newClient(zookeeperConnectionString, retryPolicy)

    zkClient.start()

    val kafkaBrokers = new String(zkClient.getData.forPath("/free2wheelers/stationStatus/kafkaBrokers"))

    val topic = new String(zkClient.getData.watched.forPath("/free2wheelers/stationStatus/topic"))

    val infomationTopic = new String(zkClient.getData.watched.forPath("/free2wheelers/stationInformation/topic"))

    //TODO: change this to use the latest location when it's available
    val latestStationInfoLocation = new String(
      zkClient.getData.watched.forPath("/free2wheelers/stationInformation/dataLocation"))

    val checkpointLocation = new String(
      zkClient.getData.watched.forPath("/free2wheelers/output/checkpointLocation"))

    val outputLocation = new String(
      zkClient.getData.watched.forPath("/free2wheelers/output/dataLocation"))
    val spark = SparkSession.builder
      .appName("StationConsumer")
      .getOrCreate()

    import spark.implicits._

    val stationInformationDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaBrokers)
      .option("subscribe", infomationTopic)
      .option("startingOffsets", "latest")
      .load()
      .selectExpr("CAST(value AS STRING) as raw_payload")
      .transform(df => informationJson2DF(df, spark))
      .as[infoType]
      .groupByKey(r => r.station_id)
      .reduceGroups((r1, r2) => r1)
      .map(_._2)

    val infoWriteToMemory = stationInformationDF
      .writeStream
      .format("memory")
      .outputMode("complete")
      .queryName("stationInformationTable")
      .start()

    val stationInfoDataframe = spark.sql("select * from stationInformationTable").toDF()

    val status = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaBrokers)
      .option("subscribe", topic)
      .option("startingOffsets", "latest")
      .load()
      .selectExpr("CAST(value AS STRING) as raw_payload")
      .transform(t => statusInformationJson2DF(t, spark))
      .withColumn("last_updated_x",$"last_updated".cast(TimestampType))
      .drop("last_updated")
      .withColumnRenamed("last_updated_x","last_updated")
      .withWatermark("last_updated","10 minutes")
      .as[statusType]
      .groupByKey(r => r.station_id)
      .reduceGroups((r1, r2) => if (r1.last_updated.after(r2.last_updated)) r1 else r2)
      .map(_._2)

    val dataframe = status
      .join(stationInfoDataframe, "station_id")

   val query= dataframe.repartition(1)
      .writeStream
      .format("overwriteCSV")
      .outputMode("complete")
      .option("header", true)
      .option("truncate", false)
      .option("checkpointLocation", checkpointLocation)
      .option("path", outputLocation)
      .start()

    query.awaitTermination

  }

  def trans(spark: SparkSession, dataFrame: DataFrame): DataFrame = {
    import spark.implicits._
    dataFrame
      .as[statusType]
      .groupByKey(r => r.station_id)
      .reduceGroups((r1, r2) => if (r1.last_updated.after(r2.last_updated)) r1 else r2)
      .map(_._2)
      .toDF()
  }
}
