/** This software is released under the University of Illinois/Research and Academic Use License. See
  * the LICENSE file in the root folder for details. Copyright (c) 2016
  *
  * Developed by: The Cognitive Computations Group, University of Illinois at Urbana-Champaign
  * http://cogcomp.cs.illinois.edu/
  */
package edu.illinois.cs.cogcomp.saul.datamodel.node

import edu.illinois.cs.cogcomp.core.datastructures.vectors.{ ExceptionlessInputStream, ExceptionlessOutputStream }
import edu.illinois.cs.cogcomp.lbjava.classify.FeatureVector
import edu.illinois.cs.cogcomp.saul.datamodel.edge.Edge
import edu.illinois.cs.cogcomp.saul.datamodel.property.Property
import edu.illinois.cs.cogcomp.saul.datamodel.property.features.discrete.DiscreteProperty
import edu.illinois.cs.cogcomp.saul.util.Logging
import java.util.concurrent.atomic.AtomicInteger

import me.tongfei.progressbar.ProgressBar

import scala.collection.mutable
import scala.collection.mutable.{ ArrayBuffer, ListBuffer, HashMap => MutableHashMap, LinkedHashSet => MutableSet, Map => MutableMap }
import scala.reflect.ClassTag

trait NodeProperty[T <: AnyRef] extends Property[T] {
  def node: Node[T]
}

/** Representation of an instance inside the Node.
  * @param t original instance
  * @param keyFunc key function used to extract the key
  * @tparam T base type of the instances
  */
class NodeInstance[T](t: T, keyFunc: T => Any) {
  val key: Any = keyFunc(t)
  def apply = t

  override def hashCode(): Int = key.hashCode()

  override def equals(obj: scala.Any): Boolean = obj match {
    case nt2: NodeInstance[T] => key.equals(nt2.key)
  }
}

/** Represents an node in the data model graph.
  *
  * @param keyFunc Key function to distinguish between different objects in the collection.
  * @param tag ClassTag
  * @tparam T Type of the data represented in the node.
  */
class Node[T <: AnyRef](val keyFunc: T => Any = (x: T) => x, val tag: ClassTag[T]) extends Logging {

  type NT = NodeInstance[T]

  val outgoing = new ArrayBuffer[Edge[T, _]]()
  val incoming = new ArrayBuffer[Edge[_, T]]()

  val joinNodes = new ArrayBuffer[JoinNode[_, _]]()

  val properties = new ArrayBuffer[NodeProperty[T]]

  private val collection = MutableSet[NT]()

  def getAllInstances: Iterable[T] = this.collection.toSeq.map(_.apply)

  val trainingSet = MutableSet[NT]()

  val testingSet = MutableSet[NT]()

  def getTrainingInstances: Iterable[T] = this.trainingSet.toSeq.map(_.apply)

  def getTestingInstances: Iterable[T] = this.testingSet.toSeq.map(_.apply)

  val orderingMap = MutableMap[Int, NT]()
  val reverseOrderingMap = MutableMap[NT, Int]()

  def filterNode(property: DiscreteProperty[T], value: String): Node[T] = {
    val node = new Node[T](this.keyFunc, this.tag)
    node populate collection.filter {
      nt => property.sensor(nt.apply) == value
    }.toSeq.map(_.apply)
    node
  }

  def clear(): Unit = {
    collection.clear
    trainingSet.clear
    testingSet.clear
    for (e <- incoming) e.clear
    for (e <- outgoing) e.clear
  }

  private var count: AtomicInteger = new AtomicInteger()

  def contains(t: T): Boolean = collection.contains(toNT(t))

  def toNT(t: T): NT = new NT(t, keyFunc)

  private def containsNT(nt: NT): Boolean = collection.contains(nt)

  /** Adds an instance to the the [[Node]].
    *
    * @param instance Node instance.
    * @param train If the instance is a training instance.
    * @param populateEdge If populating edges from the current Node.
    * @param populateJoinNodes Boolean indication if join nodes needs to be populated.
    */
  def addInstance(
    instance: T,
    train: Boolean = true,
    populateEdge: Boolean = true,
    populateJoinNodes: Boolean = true
  ): Unit = {
    val nodeInstance = toNT(instance)

    if (containsNT(nodeInstance)) {
      logger.trace(s"The instance $instance is duplicate and it will be ignored! " +
        s"This might be because you add the same instance to both train and test set. ")
    } else {

      val order = count.incrementAndGet()

      if (train) {
        this.trainingSet.add(nodeInstance)
      } else {
        this.testingSet.add(nodeInstance)
      }

      this.collection.add(nodeInstance)
      this.orderingMap.put(order, nodeInstance)
      this.reverseOrderingMap.put(nodeInstance, order)

      if (populateEdge) {
        outgoing.foreach(_.populateUsingFrom(instance, train))
        incoming.foreach(_.populateUsingTo(instance, train))
      }

      if (populateJoinNodes) {
        // TODO: Populating join nodes takes significant amount of time on large graphs. Investigate.
        joinNodes.foreach(_.addFromChild(this, instance, train, populateEdge))
      }
    }
  }

  /** Populate current node instances using instances from a different node on the same type.
    * Usage Node: Expected usage is to copy nodes from one DataModel to another.
    *
    * @param n Node to populate instances from.
    */
  def populateFrom(n: Node[_])(implicit tag: ClassTag[instanceType]): Unit = {
    populate(n.getTrainingInstances.map(_.asInstanceOf[instanceType]), train = true, populateEdge = false, populateJoinNodes = false)
    populate(n.getTestingInstances.map(_.asInstanceOf[instanceType]), train = false, populateEdge = false, populateJoinNodes = false)
  }

  /** Operator for adding a sequence of T into my table. */
  def populate(
    ts: Iterable[T],
    train: Boolean = true,
    populateEdge: Boolean = true,
    populateJoinNodes: Boolean = true
  ): Unit = {
    val pb = new ProgressBar("Population", ts.size)
    pb.start()
    ts.foreach(i => {
      addInstance(i, train, populateEdge, populateJoinNodes)
      pb.step()
    })
    pb.stop()
  }

  /** Relational operators */
  val nodeOfTypeT = this
  type instanceType = T

  def apply() = NodeSet(this)
  def apply(t: T) = SingletonSet(this, t)
  def apply(ts: Iterable[T]) = BasicSet(this, ts)

  def getWithRelativePosition(t: T, relativePos: Int): Option[T] = {
    getWithRelativePosition(t, relativePos, Nil)
  }

  def getWithRelativePosition(t: T, relativePos: Int, filters: Iterable[T => Any]): Option[T] = {
    if (relativePos == 0) {
      Some(t)
    } else {
      val nt = toNT(t)
      /** relative not equal to 0 */
      this.reverseOrderingMap.get(nt) match {
        case Some(ord) =>
          val targetOrd = ord + relativePos
          this.orderingMap.get(targetOrd) match {
            case Some(x) =>
              if (underSameParent(t, x.apply, filters)) {
                Some(x.apply)
              } else {
                None
              }
            case None => None
          }
        case _ => None
      }
    }
  }

  def nextOf(t: T, filters: List[Symbol]): Option[T] = {
    getWithWindow(t, 1, 1, Nil) match {
      case head :: more => head
      case Nil => None
    }
  }

  def prevOf(t: T, filters: List[Symbol]): Option[T] = {
    getWithWindow(t, -1, -1, Nil) match {
      case head :: more => head
      case Nil => None
    }
  }

  // TODO: add documentation to the following methods
  def getWithWindow(t: T, before: Int, after: Int): List[Option[T]] = {
    getWithWindow(t, before, after, Nil)
  }

  def getWithWindow(t: T, before: Int, after: Int, filterSym: T => Any): List[Option[T]] = {
    getWithWindow(t, before, after, filterSym :: Nil)
  }

  def underSameParent(t: T, x: T, filters: Iterable[T => Any]): Boolean = {
    filters.forall(f => f(t) == f(x))
  }

  def between(t1: T, t2: T, filter: Iterable[T => Any]): List[Option[T]] = {
    val wildCard = !underSameParent(t1, t2, filter)
    val nt1 = toNT(t1)
    val nt2 = toNT(t2)
    // If t1 and t2 are not under same parents, then we ignore the parent.
    (this.reverseOrderingMap.get(nt1), this.reverseOrderingMap.get(nt2)) match {
      case (Some(start), Some(end)) => {
        (start to end) map {
          position =>
            this.orderingMap.get(position) match {
              case Some(v) => if (wildCard || underSameParent(t1, v.apply, filter)) {
                Some(v.apply)
              } else {
                None
              }
              case None => None
            }
        }
      }.toList
      case _ => throw new Exception("Can't find element.")
    }
  }

  def getWithWindow(t: T, before: Int, after: Int, filters: Iterable[T => Any]): List[Option[T]] = {
    (this.reverseOrderingMap.get(toNT(t)) match {
      case Some(myOrder) =>
        val start = myOrder + before
        val end = myOrder + after
        val result = (start to end).flatMap(this.orderingMap.get).filter {
          x => underSameParent(t, x.apply, filters)
        }.toList

        val pos = result.indexOf(t)
        // Now we need to shift the list
        // before is negative, after is positive.
        val tShouldBeAt = -before
        if (tShouldBeAt < 0) {
          result map (Some(_))
        } else {

          val prependAmount = tShouldBeAt - pos
          val appendAmount = (after - before + 1) - (prependAmount + result.size)

          val toPrepend = Nil.padTo(prependAmount, None)
          val toAppend = Nil.padTo(appendAmount, None)

          val middle = result map (Some(_))
          toPrepend ::: middle ::: toAppend
        }
      case _ =>
        throw new Exception("Can't find order of " + t.toString)
    }).map(_.map(_.apply))
  }

  val derivedInstances = new mutable.HashMap[Int, FeatureVector]()

  def deriveInstances(properties: List[Property[_]]) = {
    val castedProperties = properties.map(_.asInstanceOf[Property[T]])
    orderingMap.foreach {
      case (instanceId, instance) =>
        val featureVector = new FeatureVector()
        castedProperties.foreach {
          property =>
            featureVector.addFeatures(property.featureVector(instance.apply))
        }
        derivedInstances.put(instanceId, featureVector)
    }
  }

  def writeDerivedInstances(out: ExceptionlessOutputStream) = {
    out.writeInt(count.get())
    out.writeInt(derivedInstances.size)
    derivedInstances.foreach {
      case (id, featureVector) =>
        out.writeInt(id)
        featureVector.write(out)
    }
  }

  def loadDerivedInstances(in: ExceptionlessInputStream) = {
    count = new AtomicInteger(in.readInt())
    val instanceCount = in.readInt()
    (0 until instanceCount).foreach {
      lineIndex =>
        val id = in.readInt()
        val featureVector = new FeatureVector()
        featureVector.read(in)
        derivedInstances.put(id, featureVector)
    }
  }

  /** list of hashmaps used inside properties for caching sensor values */
  final val propertyCacheList = new ListBuffer[MutableHashMap[_, Any]]()

  def clearPropertyCache(): Unit = {
    if (propertyCacheList.nonEmpty) {
      logger.info("clean property cache: cleaning " + propertyCacheList.size + " maps")
      propertyCacheList.foreach(_.clear)
    }
  }
}
