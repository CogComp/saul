package edu.illinois.cs.cogcomp.saul.constraint

import edu.illinois.cs.cogcomp.lbjava.infer._
import edu.illinois.cs.cogcomp.lbjava.learn.Learner
import edu.illinois.cs.cogcomp.saul.datamodel.property.Property
import edu.illinois.cs.cogcomp.saul.lbjrelated.LBJLearnerEquivalent

/** We need to define the langauge of constraints here to work with the first order constraints that are programmed in
  * our main LBP script. The wrapper just gives us a java firstorderconstraint object in the shell of an scala object.
  * in this way our language works on scala objects.
  */

object ConstraintTypeConversion {

  implicit def singlePropertyToList[T <: AnyRef](property: Property[T]): List[Property[T]] = {
    property :: Nil
  }

  implicit def learnerToLFS(l: Learner): LBJLearnerEquivalent = {
    new LBJLearnerEquivalent {
      override val classifier = l
    }
  }

  implicit def LfsToLearner(l: LBJLearnerEquivalent): Learner = {
    l.classifier
  }

  implicit def constraintWrapper(p: FirstOrderConstraint): FirstOrderConstraints = {
    new FirstOrderConstraints(p)
  }

  implicit def javaCollToMyQuantifierWrapper[T](coll: java.util.Collection[T]): QuantifierWrapper[T] = {
    import scala.collection.JavaConversions._
    new QuantifierWrapper[T](coll.toSeq)
  }

  implicit def scalaCollToMyQuantifierWrapper[T](coll: Seq[T]): QuantifierWrapper[T] = {
    new QuantifierWrapper[T](coll)
  }
}

// TODO: one should be able to replace fold with reduce and remove ___result; right?
class QuantifierWrapper[T](val coll: Seq[T]) {
  def _exists(p: T => FirstOrderConstraint): FirstOrderConstraint = {
    val dummyConstraint = new FirstOrderConstant(false)
    coll.map(p).foldLeft[FirstOrderConstraint](dummyConstraint)(new FirstOrderDisjunction(_, _))
  }

  def _forall(p: T => FirstOrderConstraint): FirstOrderConstraint = {
    val dummyConstraint = new FirstOrderConstant(true)
    coll.map(p).foldLeft[FirstOrderConstraint](dummyConstraint)(new FirstOrderConjunction(_, _))
  }

  /** transfer the constraint to a constant
    * These functions can be slow, if not used properly
    * The best performance is when n is too big (close to the size of the collection) or too small
    */
  def _atmost(n: Int)(p: T => FirstOrderConstraint): FirstOrderConstraint = {
    val constraintCombinations = coll.map(p).combinations(n + 1)
    val listOfConjunctions = for {
      constraints <- constraintCombinations
      dummyConstraint = new FirstOrderConstant(true)
    } yield constraints.foldLeft[FirstOrderConstraint](dummyConstraint)(new FirstOrderConjunction(_, _))

    val dummyConstraint = new FirstOrderConstant(false)
    new FirstOrderNegation(listOfConjunctions.toList.foldLeft[FirstOrderConstraint](dummyConstraint)(new FirstOrderDisjunction(_, _)))
  }

  /** transfer the constraint to a constant
    * These functions can be slow, if not used properly
    * The best performance is when n is too big (close to the size of the collection) or too small
    */
  def _atleast(n: Int)(p: T => FirstOrderConstraint): FirstOrderConstraint = {
    val constraintCombinations = coll.map(p).combinations(n)
    val listOfConjunctions = for {
      constraints <- constraintCombinations
      dummyConstraint = new FirstOrderConstant(true)
    } yield constraints.foldLeft[FirstOrderConstraint](dummyConstraint)(new FirstOrderConjunction(_, _))

    val dummyConstraint = new FirstOrderConstant(false)
    listOfConjunctions.toList.foldLeft[FirstOrderConstraint](dummyConstraint)(new FirstOrderDisjunction(_, _))
  }
}

class FirstOrderConstraints(val r: FirstOrderConstraint) {

  def ==>(other: FirstOrderConstraint) = new FirstOrderImplication(this.r, other)

  def <==>(other: FirstOrderConstraint) = new FirstOrderDoubleImplication(this.r, other)

  def unary_! = new FirstOrderNegation(this.r)

  def and(other: FirstOrderConstraint) = new FirstOrderConjunction(this.r, other)

  def or(other: FirstOrderConstraint) = new FirstOrderDisjunction(this.r, other)

}

class LHSFirstOrderEqualityWithValueLBP(cls: Learner, t: AnyRef) {

  // probably we need to write here
  // LHSFirstOrderEqualityWithValueLBP(cls : Learner, t : AnyRef) extends ConstraintTrait

  val lbjRepr = new FirstOrderVariable(cls, t)

  def is(v: String): FirstOrderConstraint = {
    new FirstOrderEqualityWithValue(true, lbjRepr, v)
  }

  //TODO: not sure if this works correctly. Make sure it works.
  def is(v: LHSFirstOrderEqualityWithValueLBP): FirstOrderConstraint = {
    new FirstOrderEqualityWithVariable(true, lbjRepr, v.lbjRepr)
  }

  def isTrue: FirstOrderConstraint = is("true")

  def isNotTrue: FirstOrderConstraint = is("false")
}
