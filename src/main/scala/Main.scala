import org.apache.spark.sql.{DataFrame, SparkSession, Row}
import org.apache.spark.ml.Pipeline
import org.apache.spark.ml.feature.{StringIndexer, OneHotEncoder, VectorAssembler}
import org.apache.spark.ml.classification.{LogisticRegression, BinaryLogisticRegressionSummary}
import org.apache.spark.sql.functions.rand
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.functions.{col, when}

import org.apache.spark.sql.types.{LongType, StructField, StructType}

//Don't pay attention of red messages INFO (it's not an error)
/**
  * The programm that predict if a user clicks on an or not
  */
object Main extends App {

  /*// Disable logging
  Logger.getLogger("org").setLevel(Level.OFF)
  Logger.getLogger("akka").setLevel(Level.OFF)*/

  val context = SparkSession
    .builder()
    .appName("Word count")
    .master("local")
    .getOrCreate()

  context.sparkContext.setLogLevel("WARN")

  //Put your own path to the json file
  //select your variable to add and change inside the variable columnVectorialized and dataModel at the end of the code 
  val untreatedData = context.read.json("./src/resources/data-students.json").select("appOrSite","network", "timestamp", "user", "interests", "label")

  val df = untreatedData.withColumn("label", when(col("label") === true, 1).otherwise(0))
  .withColumn("network", Cleaner.udf_clean_network(untreatedData("network")))
  .withColumn("timestamp", Cleaner.udf_clean_timestamp(untreatedData("timestamp")))

  def handleInterest(data: DataFrame) : DataFrame = {
        //TODO: clean interests
      val renamedInterests= data.withColumn("listInterests", Cleaner.udf_renameI((data("interests")))).select("listInterests")
      val updatedInterestsArray = renamedInterests.withColumn("uniqueInterests", explode(split(renamedInterests("listInterests"), ","))).select("uniqueInterests").distinct()

      //identify the ones that are not well written
      //val nonIAB = updateInterestsArray.distinct().where(not(updateInterestsArray("listInterests").contains("IAB"))).show()
    
      val arrayOfInterests =  updatedInterestsArray.withColumn("interestsArray", split(updatedInterestsArray("uniqueInterests"), ",")).select( "interestsArray").show()

      //var target = network.withColumn("interestsArray", split(network("interests"), ",")).select("user", "interestsArray", "interests")

      var target = data.select("user", "interests")

    //list of string that represents the list of renamed interests
      val listOfUniqueInterest: List[String] = updatedInterestsArray.select("uniqueInterests").collect().map(row => row.toString()).toList

    //trying to add column to a dataframe using foldLefst given a list of columns to add and a dataframe 
    /*def addColumnsViaFold(df: DataFrame, columns: List[String]): DataFrame = {

        /*def udf_check(list: List[String]): UserDefinedFunction = {
          udf { (s: String) => {
            if (list.contains(s+"-")) 1 else 0
          }
        }*/

        columns.foldLeft(df)((acc, col) => {
          acc.withColumn(col, acc("interestsArray")(0).contains(col)) //udf_check(List(acc("interestsArray").toString()))(col))
        })
      }

        addColumnsViaFold(target, listOfUniqueInterest).show()*/

      /*for(i <- 0 until listOfUniqueInterest.length-1) {
        target.withColumn(listOfUniqueInterest(i), target("interests").contains(listOfUniqueInterest(i)))
      }*/
      //target.show()

      //var df = target.select("user")
    //result: a list of column that contains, for each user, either 1 if he/she is interested in the concerned interest(ie corresponds to the name of the column)
      val l1 = listOfUniqueInterest.map(x => target.withColumn(x, when(target("interests").contains(x.substring(1, x.size-2))|| target("interests").contains(x.substring(1, x.size-3)), 1).otherwise(0)).select(x))
      
    //TODO: 3 steps left
    //Step1: create a dataframe that contains each element of the list l1 as columns 

    /**
        * Add Column Index to dataframe
        */
      def addColumnIndex(df: DataFrame) = {
        context.sqlContext.createDataFrame(
          df.rdd.zipWithIndex.map {
            case (row, index) => Row.fromSeq(row.toSeq :+ index)
          },
          // Create schema for index column
          StructType(df.schema.fields :+ StructField("index", LongType, false)))
      }

      val interestDF = l1.map(col=>{
        val df = col.toDF()
        addColumnIndex(df)
      })

    def recursiveJoinOnIndex(list: List[DataFrame]): DataFrame = { 
      if (list.isEmpty){ 
        null 
        }
      else if(list.size >1){ 
        list.head.join(recursiveJoinOnIndex(list.tail),"index") 
        }
        else {
          list.head 
        }
    }
      val interestData = recursiveJoinOnIndex(interestDF).drop("index")

      val dfIndexed = addColumnIndex(df)
      val interestDfIndexed = addColumnIndex(interestData)

      recursiveJoinOnIndex(List(dfIndexed,interestDfIndexed)).drop("index").drop("user").drop("interests").drop("[Other]")

  }

  val cleanData = handleInterest(df)
  cleanData.show()




  // this is used to implicitly convert an RDD to a DataFrame.
  import org.apache.spark.sql.functions._

  // Fetching column labels
  val colnames = cleanData.drop("label").schema.fields.map(col => col.name)
  colnames.map(col => println(col))

  // StringIndexer encodes a string column of labels to a column of label indices
  val indexers = colnames.map(
    col => new StringIndexer()
      .setInputCol(col)
      .setOutputCol(col + "Index")
      .setHandleInvalid("skip")
  )

  // Using one-hot encoding for representing states with binary values having only one digit 1
  /*val encoders = colnames.map(
    col => new OneHotEncoder()
      .setInputCol(col + "Index")
      .setOutputCol(col + "Encode")
  )*/

  val pipeline = new Pipeline().setStages(indexers /*++ encoders*/)
  println("pipeline done")
  val dfEncoded = pipeline.fit(cleanData).transform(cleanData)
println("encided data done")
  

  //val dfWithoutLabel = cleanData.drop("label")
  //val colnamesWithoutLabel = dfWithoutLabel.schema.fields.map(col => col.name)
  val renamedEncoded = colnames.map(col => col + "Index")


  

  //Add your variable inside the setInputCols by adding Encode after
  val columnVectorialized = new VectorAssembler()
    .setInputCols(renamedEncoded)
    .setOutputCol("features")

  val dataModel = columnVectorialized.transform(dfEncoded).select("label", "features")

  println("Vector assembler done")

  val lr = new LogisticRegression()
    .setMaxIter(10)
    .setRegParam(0.3)
    .setElasticNetParam(0.8)
    .setFeaturesCol("features")
    .setLabelCol("label")

  val splitData = dataModel.randomSplit(Array(0.7, 0.3))
  var (trainingData, testData) = (splitData(0), splitData(1))

  trainingData = trainingData.select("features", "label")
  trainingData.groupBy("label").count.show()
  testData = testData.select("features", "label")
  testData.groupBy("label").count.show()

  // Fit the model
  val lrModel = lr.fit(trainingData)

  println("lrModel done")

  val predictions = lrModel.transform(testData)

  println("predictions done")

  // Print the coefficients and intercept for logistic regression
  val trainingSummary = lrModel.summary

  // Obtain the objective per iteration.
  val objectiveHistory = trainingSummary.objectiveHistory

  // Obtain the metrics useful to judge performance on test data.
  // We cast the summary to a BinaryLogisticRegressionSummary since the problem is a
  // binary classification problem.
  val binarySummary = trainingSummary.asInstanceOf[BinaryLogisticRegressionSummary]

  // Obtain the receiver-operating characteristic as a dataframe and areaUnderROC.
  val roc = binarySummary.roc
  roc.show()
  println(s"areaUnderROC: ${binarySummary.areaUnderROC}")

  println("objectiveHistory:")
  objectiveHistory.foreach(loss => println(loss))

  println(s"Coefficients: ${lrModel.coefficients} Intercept: ${lrModel.intercept}")

  context.stop()
}
