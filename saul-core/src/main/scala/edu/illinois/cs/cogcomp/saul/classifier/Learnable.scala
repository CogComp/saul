/** This software is released under the University of Illinois/Research and Academic Use License. See
  * the LICENSE file in the root folder for details. Copyright (c) 2016
  *
  * Developed by: The Cognitive Computations Group, University of Illinois at Urbana-Champaign
  * http://cogcomp.cs.illinois.edu/
  */
package edu.illinois.cs.cogcomp.saul.classifier

import java.io.File
import java.net.URL

import edu.illinois.cs.cogcomp.core.datastructures.vectors.ExceptionlessOutputStream
import edu.illinois.cs.cogcomp.core.io.IOUtils
import edu.illinois.cs.cogcomp.lbjava.classify.{ Classifier, FeatureVector, TestDiscrete }
import edu.illinois.cs.cogcomp.lbjava.learn.Learner.Parameters
import edu.illinois.cs.cogcomp.lbjava.learn._
import edu.illinois.cs.cogcomp.lbjava.parse.FoldParser.SplitPolicy
import edu.illinois.cs.cogcomp.lbjava.parse.{ FoldParser, Parser }
import edu.illinois.cs.cogcomp.saul.datamodel.DataModel
import edu.illinois.cs.cogcomp.saul.datamodel.edge.Link
import edu.illinois.cs.cogcomp.saul.datamodel.node.Node
import edu.illinois.cs.cogcomp.saul.datamodel.property.{ CombinedDiscreteProperty, Property, PropertyWithWindow }
import edu.illinois.cs.cogcomp.saul.lbjrelated.LBJLearnerEquivalent
import edu.illinois.cs.cogcomp.saul.parser.{ IterableToLBJavaParser, LBJavaParserToIterable }
import edu.illinois.cs.cogcomp.saul.test.TestReal
import edu.illinois.cs.cogcomp.saul.util.Logging
import me.tongfei.progressbar.ProgressBar

import scala.reflect.ClassTag

/** Represents an instance of a learnable model. Each [[Learnable]] instance is associated with a node instance in the
  * data model graph.
  *
  * @param node [[Node]] instance associated with the learnable model.
  * @param parameters Parameters for the Learner used
  * @param tag ClassTag of the type of data stored in [[node]]
  * @tparam T Type of the data stored in [[node]]
  */
abstract class Learnable[T <: AnyRef](val node: Node[T], val parameters: Parameters = new Learner.Parameters)(implicit tag: ClassTag[T]) extends LBJLearnerEquivalent with Logging {

  /** Whether to use caching */
  val useCache = false

  var isTraining = false

  def trainingInstances = node.getTrainingInstances

  def getClassNameForClassifier = this.getClass.getCanonicalName

  def getClassSimpleNameForClassifier = this.getClass.getSimpleName

  def feature: List[Property[T]] = node.properties.toList

  /** filter out the label from the features */
  def combinedProperties = if (label != null) new CombinedDiscreteProperty[T](this.feature.filterNot(_.name == label.name))
  else new CombinedDiscreteProperty[T](this.feature)

  def lbpFeatures = Property.convertToClassifier(combinedProperties)

  /** classifier need to be defined by the user */
  val classifier: Learner

  /** syntactic sugar to create simple calls to the function */
  def apply(example: AnyRef): String = classifier.discreteValue(example: AnyRef)

  /** specifications of the classifier and its model files  */
  classifier.setReadLexiconOnDemand()

  /** If you have multiple versions/variations of the same classifier, you can set the following variable, in order to
    * save each variation on different files with different suffixes
    */
  var modelSuffix = ""
  var modelDir = "models" + File.separator
  def lcFilePath = new URL(new URL("file:"), modelDir + getClassNameForClassifier + modelSuffix + ".lc")
  def lexFilePath = new URL(new URL("file:"), modelDir + getClassNameForClassifier + modelSuffix + ".lex")
  IOUtils.mkdir(modelDir)
  classifier.setModelLocation(lcFilePath)
  classifier.setLexiconLocation(lexFilePath)

  // create .lex file if it does not exist
  if (!IOUtils.exists(lexFilePath.getPath)) {
    val lexFile = ExceptionlessOutputStream.openCompressedStream(lexFilePath)
    if (classifier.getCurrentLexicon == null) lexFile.writeInt(0)
    else classifier.getCurrentLexicon.write(lexFile)
    lexFile.close()
  }

  // create .lc file if it does not exist
  if (!IOUtils.exists(lcFilePath.getPath)) {
    val lcFile = ExceptionlessOutputStream.openCompressedStream(lcFilePath)
    classifier.write(lcFile)
    lcFile.close()
  }

  private def setExtractor(): Unit = {
    if (feature != null) {
      logger.debug(s"Setting the feature extractors to be ${lbpFeatures.getCompositeChildren}")
      classifier.setExtractor(lbpFeatures)
    } else {
      logger.error("No features found!")
    }
  }

  private def setLabeler(): Unit = {
    if (label != null) {
      val oracle = Property.convertToClassifier(label)
      logger.debug(s"Setting the labeler to be ${oracle}")
      classifier.setLabeler(oracle)
    }
  }

  // set parameters for classifier
  setExtractor()
  setLabeler()

  private def removeModelFiles(): Unit = {
    IOUtils.rm(lcFilePath.getPath)
    IOUtils.rm(lexFilePath.getPath)
    createFiles()
  }

  /** This function prints a summary of the classifier
    */
  def printlnModel(): Unit = {
    classifier.write(System.out)
  }

  def save(): Unit = {
    removeModelFiles()
    val dummyClassifier = new SparsePerceptron()
    classifier.setExtractor(dummyClassifier)
    classifier.setLabeler(dummyClassifier)
    classifier.write(lcFilePath.getPath, lexFilePath.getPath)

    // after saving, get rid of the dummyClassifier in the classifier.
    setExtractor()
    setLabeler()
  }

  private def createFiles(): Unit = {
    // create the model directory if it does not exist
    IOUtils.mkdir(modelDir)
    // create .lex file if it does not exist
    if (!IOUtils.exists(lexFilePath.getPath)) {
      val lexFile = ExceptionlessOutputStream.openCompressedStream(lexFilePath)
      if (classifier.getCurrentLexicon == null) lexFile.writeInt(0)
      else classifier.getCurrentLexicon.write(lexFile)
      lexFile.close()
    }

    // create .lc file if it does not exist
    if (!IOUtils.exists(lcFilePath.getPath)) {
      val lcFile = ExceptionlessOutputStream.openCompressedStream(lcFilePath)
      classifier.write(lcFile)
      lcFile.close()
    }
  }

  /** Loads the model and lexicon for the classifier. Looks up in the local file system
    * and the files are not found, looks up in the classpath JARs.
    *
    * @param lcFile The path of the model file
    * @param lexFile The path of the lexicon file
    */
  def load(lcFile: String, lexFile: String): Unit = {
    if (IOUtils.exists(lcFile)) {
      logger.info(s"Reading model file ${IOUtils.getFileName(lcFile)} from local path.")
      classifier.readModel(lcFile)
    } else {
      val modelResourcesUrls = IOUtils.lsResources(getClass, lcFile)
      if (modelResourcesUrls.size() == 1) {
        logger.info(s"Reading model file ${IOUtils.getFileName(lcFile)} from classpath.")
        classifier.readModel(modelResourcesUrls.get(0))
      } else logger.error(s"Cannot find model file: ${lcFile}")
    }
    if (IOUtils.exists(lcFile)) {
      logger.info(s"Reading lexicon file ${IOUtils.getFileName(lexFile)} from local path.")
      classifier.readLexicon(lexFile)
    } else {
      val lexiconResourcesUrls = IOUtils.lsResources(getClass, lexFile)
      if (lexiconResourcesUrls.size() == 1) {
        logger.info(s"Reading lexicon file ${IOUtils.getFileName(lexFile)} from classpath.")
        classifier.readLexicon(lexiconResourcesUrls.get(0))
      } else logger.error(s"Cannot find lexicon file ${lexFile}")
    }

    setExtractor()
    setLabeler()
  }

  def load(lcFile: URL, lexFile: URL): Unit = {
    load(lcFile.getPath, lexFile.getPath)
  }

  def load(): Unit = {
    load(lcFilePath.getPath, lexFilePath.getPath)
  }

  def learn(iteration: Int): Unit = {
    createFiles()
    isTraining = true
    if (useCache) {
      if (node.derivedInstances.isEmpty) {
        logger.error("No cached data found. Please use \"dataModel.load(filepath)\" \n" +
          "If you don't have any cache saved, use \"datamodel.deriveInstances()\" to extract it, " +
          "and then save it with \"datamodel.write(filePath)\"  ")
      }
      learnWithDerivedInstances(iteration, node.derivedInstances.values)
    } else {
      learn(iteration, this.trainingInstances)
      //classifier.doneLearning()
    }
    isTraining = false
  }

  def learn(iteration: Int = 10, parser: Parser)(implicit dummyImplicit: DummyImplicit): Unit = {
    val trainingIterable = new LBJavaParserToIterable[T](parser)
    learn(iteration, trainingIterable)
  }

  def learn(iteration: Int, data: Iterable[T]): Unit = {
    createFiles()

    val oracle = Property.convertToClassifier(label)
    logger.debug(s"==> Learning using the feature extractors to be ${lbpFeatures.getCompositeChildren}")
    logger.debug(s"==> Learning using the labeler to be ${oracle}")
    logger.debug(classifier.getExtractor.getCompositeChildren.toString)
    logger.debug(classifier.getLabeler.toString)
    logger.info(s"Learnable: Learn with data of size ${data.size}")

    isTraining = true
    val pb = new ProgressBar("Training", iteration)
    pb.start()

    (iteration to 1 by -1).foreach(remainingIteration => {

      node.clearPropertyCache()
      data.foreach(classifier.learn)
      pb.step()
    })

    pb.stop()
    classifier.doneLearning()
    isTraining = false
  }

  def learnWithDerivedInstances(numIterations: Int, featureVectors: Iterable[FeatureVector]): Unit = {
    isTraining = true
    val pb = new ProgressBar("Training", numIterations)
    pb.start()

    val propertyNameSet = feature.map(_.name).toSet
    (0 until numIterations).foreach { _ =>
      featureVectors.foreach {
        fullFeatureVector =>
          val featureVector = new FeatureVector()
          val numFeatures = fullFeatureVector.size()
          (0 until numFeatures).foreach {
            featureIndex =>
              val feature = fullFeatureVector.getFeature(featureIndex)
              val propertyName = feature.getGeneratingClassifier
              if (label != null && label.name.equals(propertyName)) {
                featureVector.addLabel(feature)
              } else if (propertyNameSet.contains(propertyName)) {
                featureVector.addFeature(feature)
              }
          }
          classifier.learn(featureVector)
      }
      pb.step()
    }
    pb.stop()
    classifier.doneLearning()
    isTraining = false
  }

  def forget() = this.classifier.forget()

  /** Test with the test data, retrieve internally
    *
    * @return a [[Results]] object
    */
  def test(): Results = {
    val testData = node.getTestingInstances
    test(testData)
  }

  /** Test with given data, use internally
    *
    * @param testData if the collection of data is not given it is derived from the data model based on its type
    * @param prediction it is the property that we want to evaluate it if it is null then the prediction of the classifier is the default
    * @param groundTruth it is the property that we want to evaluate the prediction against it, if it is null then the gold label derived from the classifier is used
    * @param exclude it is the label that we want to exclude fro evaluation, this is useful for evaluating the multi-class classifiers when we need to measure overall F1 instead of accuracy and we need to exclude the negative class
    * @return List of [[Results]]
    */
  def test(testData: Iterable[T] = null, prediction: Property[T] = null, groundTruth: Property[T] = null,
    exclude: String = ""): Results = {
    isTraining = false
    val testParser = new IterableToLBJavaParser[T](if (testData == null) {
      node.getTestingInstances
    } else (testData))
    // TODO: expose the granularity parameter
    val outputGranularity = 0
    test(testParser, prediction, groundTruth, exclude, outputGranularity)
  }

  def test(testParser: Parser, prediction: Property[T], groundTruth: Property[T], exclude: String, outputGranularity: Int): Results = {
    testParser.reset()
    val logging = if (loggerConfig.Logger("edu.illinois.cs.cogcomp.saul.classifier.Learnable").isLevelInfo()) true else false
    val tester = {
      if (prediction == null && groundTruth == null) {
        TestDiscrete.testDiscrete(classifier, classifier.getLabeler, testParser)
      } else {
        val predictionClassifer = Property.convertToClassifier(prediction)
        val groundTruthClassifier = Property.convertToClassifier(groundTruth)
        TestDiscrete.testDiscrete(new TestDiscrete(), predictionClassifer, groundTruthClassifier, testParser, logging, outputGranularity)
      }
    }

    if (!exclude.isEmpty) {
      tester.addNull(exclude)
    }
    tester.printPerformance(System.out)
    val perLabelResults = tester.getLabels.map { label =>
      ResultPerLabel(label, tester.getF1(label), tester.getPrecision(label), tester.getRecall(label),
        tester.getAllClasses, tester.getLabeled(label), tester.getPredicted(label), tester.getCorrect(label))
    }
    val overalResultArray = tester.getOverallStats()
    val overalResult = OverallResult(overalResultArray(0), overalResultArray(1), overalResultArray(2))
    Results(perLabelResults, ClassifierUtils.getAverageResults(perLabelResults), overalResult)
  }

  /** Test with real-valued (continuous) data. Runs Spearman's and Pearson's correlations.
    *
    * @param testData The continuous data to test on
    */
  def testContinuous(testData: Iterable[T] = null): Unit = {
    isTraining = false
    val testReader = new IterableToLBJavaParser[T](if (testData == null) node.getTestingInstances else testData)
    testReader.reset()
    new TestReal(classifier, classifier.getLabeler, testReader)
  }

  @scala.annotation.tailrec
  private final def chunkData(ts: List[Iterable[T]], i: Int, curr: Int, acc: (Iterable[T], Iterable[T])): (Iterable[T], Iterable[T]) = {
    ts match {
      case head :: more =>
        acc match {
          case (train, test) =>
            if (i == curr) {
              // we found the test part
              chunkData(more, i, curr + 1, (train, head))
            } else {
              chunkData(more, i, curr + 1, (head ++ train, test))
            }
        }
      case Nil => acc
    }
  }

  /** Run k fold cross validation using the training data. The strategy to split the instances can be set to
    * [[SplitPolicy.random]], [[SplitPolicy.sequential]], [[SplitPolicy.kth]] or [[SplitPolicy.manual]]
    * if the data splitting policy is not 'Manual', the number of folds must be greater than 1. Otherwise it's value
    * doesn't really matter.
    *
    * @param k number of folds
    * @param splitPolicy strategy to split the instances into k folds.
    */
  def crossValidation(k: Int, splitPolicy: SplitPolicy = SplitPolicy.random,
    prediction: Property[T] = null, groundTruth: Property[T] = null, exclude: String = "", outputGranularity: Int = 0): Seq[Results] = {
    val testReader = new IterableToLBJavaParser[T](trainingInstances)
    logger.debug("size training instances inside crossValidation" + trainingInstances.size)
    val foldParser = new FoldParser(testReader, k, splitPolicy, 0, false, trainingInstances.size)
    (0 until k).map { fold =>
      // training
      foldParser.setPivot(fold)
      foldParser.setFromPivot(false)
      logger.info(s"Training on all folds except $k")
      learn(10, foldParser)

      // testing
      foldParser.reset()
      logger.info(s"Testing on fold $k")
      foldParser.setFromPivot(true)
      test(foldParser, prediction, groundTruth, exclude, outputGranularity)
    }
  }

  /** Label property for users classifier */
  def label: Property[T]

  def using(properties: Property[T]*): List[Property[T]] = {
    properties.toList
  }

  def using(properties: List[Property[T]]): List[Property[T]] = {
    using(properties: _*)
  }

  // TODO Move the window properties out of Learner class.
  /** A windows of properties
    *
    * @param before always negative (or 0)
    * @param after always positive (or 0)
    */
  def windowWithin[U <: AnyRef](datamodel: DataModel, before: Int, after: Int, properties: List[Property[T]])(implicit uTag: ClassTag[U], tTag: ClassTag[T]) = {
    val fromTag = tTag
    val toTag = uTag

    val fls = datamodel.edges.filter(r => r.from.tag.equals(fromTag) && r.to.tag.equals(toTag)).map(_.forward.asInstanceOf[Link[T, U]]) ++
      datamodel.edges.filter(r => r.to.tag.equals(fromTag) && r.from.tag.equals(toTag)).map(_.backward.asInstanceOf[Link[T, U]])

    getWindowWithFilters(before, after, fls.map(e => (t: T) => e.neighborsOf(t).head), properties)
  }

  def window(before: Int, after: Int)(properties: List[Property[T]]): Property[T] = {
    getWindowWithFilters(before, after, Nil, properties)
  }

  private def getWindowWithFilters(before: Int, after: Int, filters: Iterable[T => Any], properties: List[Property[T]]): Property[T] = {
    new PropertyWithWindow[T](node, before, after, filters, properties)
  }

  def nextWithIn[U <: AnyRef](datamodel: DataModel, properties: List[Property[T]])(implicit uTag: ClassTag[U]): Property[T] = {
    this.windowWithin[U](datamodel, 0, 1, properties.toList)
  }

  def prevWithIn[U <: AnyRef](datamodel: DataModel, property: Property[T]*)(implicit uTag: ClassTag[U]): Property[T] = {
    this.windowWithin[U](datamodel, -1, 0, property.toList)
  }

  def nextOf(properties: List[Property[T]]): Property[T] = {
    window(0, 1)(properties)
  }

  def prevOf(properties: List[Property[T]]): Property[T] = {
    window(0, 1)(properties)
  }

}
