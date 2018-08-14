package provingground.learning
import provingground.{FiniteDistribution => FD, _}
import shapeless.HList._
import shapeless._

import scala.collection.immutable
import scala.language.higherKinds
import monix.eval._



abstract class GenMonixFiniteDistribution[State, Boat](
    nodeCoeffSeq: NodeCoeffSeq[State, Boat, Double])(
    implicit sd: StateDistribution[State, FD]){
      import NodeCoeffs._
      import StateDistribution._
      import nodeCoeffSeq.find

      /**
        * finite distribution for a random variable
        * @param initState initial state
        * @param randomVar random variable whose distribution is returned
        * @param epsilon cutoff
        * @tparam Y values of the random variable
        * @return finite distribution for the given random variable
        */
      def varDist[Y](initState: State)(randomVar: RandomVar[Y],
                                       epsilon: Double):Task[FD[Y]] =
        if (epsilon > 1) Task.now(FD.empty[Y])
        else
          randomVar match {
            case RandomVar.AtCoord(randomVarFmly, fullArg) =>
              varFamilyDist(initState)(randomVarFmly, epsilon)
                .map(_.getOrElse(fullArg, FD.empty[Y]))
            case _ =>
              find(randomVar)
                .map { nc =>
                  nodeCoeffDist(initState)(nc, epsilon)
                }
                .getOrElse(Task.now(FD.empty[Y]))
          }

      /**
        * finite distribution for a list of random variables
        * @param initState initial state
        * @param vl list of random variables
        * @param epsilon cutoff
        * @tparam Dom the `HList` giving the type of the variable list
        * @return finite distribution of `Dom`
        */
      def varListDist[Dom <: HList](initState: State)(vl: RandomVarList[Dom],
                                                      epsilon: Double): Task[FD[Dom]] =
        vl match {
          case RandomVarList.Nil => Task(FD.unif(HNil))
          case RandomVarList.Cons(head, tail) =>
            varDist(initState)(head, epsilon)
              .zip(varListDist(initState)(tail, epsilon))
              .map { case (xfd, yfd) =>
                for{
                  x <- xfd
                  y <- yfd
                } yield  x :: y }
        }

      def nodeCoeffDist[Y](initState: State)(
          nodeCoeffs: NodeCoeffs[State, Boat, Double, HNil, Y],
          epsilon: Double): Task[FD[Y]] =
        if (epsilon > 1) Task.now(FD.empty[Y])
        else
          nodeCoeffs match {
            case Target(_) => Task.now(FD.empty[Y])
            case bc: Cons[State, Boat, Double, HNil, Y] =>
              val p: Double = bc.headCoeff
              val d: Task[FD[Y]] =
                bc.headGen match {
                  case gen: GeneratorNode[Y] =>
                    nodeDist(initState)(gen, epsilon / p) map (_ * p)
                  case _ =>
                    throw new IllegalArgumentException(
                      "found node family for generating a simple random variable")
                }
                for {
                  a <- d
                  b <- nodeCoeffDist(initState)(bc.tail, epsilon)
                } yield a ++ b
          }

      def mapsSum[X, Y](first: Map[X, FD[Y]],
                        second: Map[X, FD[Y]]): Map[X, FD[Y]] =
        (for {
          k <- first.keySet union second.keySet
          v = first.getOrElse(k, FD.empty[Y]) ++ second.getOrElse(k, FD.empty[Y])
        } yield (k, v)).toMap

      def nodeCoeffFamilyMap[Dom <: HList, Y](initState: State)(
          nodeCoeffs: NodeCoeffs[State, Boat, Double, Dom, Y],
          baseDist: Task[FD[Dom]],
          epsilon: Double): Task[Map[Dom, FD[Y]]] =
        if (epsilon > 1) Task(Map())
        else
          nodeCoeffs match {
            case Target(_) => Task(Map())
            case bc: Cons[State, Boat, Double, Dom, Y] =>
              val p = bc.headCoeff
              for {
                a<- nodeFamilyDist(initState)(bc.headGen, baseDist, epsilon)
                b <- nodeCoeffFamilyMap(initState)(bc.tail,
                                                    baseDist,
                                                    epsilon / (1.0 - p))
                                                  } yield mapsSum(a, b)
          }

      def varFamilyDist[RDom <: HList, Y](initState: State)(
          randomVarFmly: RandomVarFamily[RDom, Y],
          epsilon: Double): Task[Map[RDom, FD[Y]]] =
        if (epsilon > 0) Task(Map())
        else
          find(randomVarFmly)
            .map { nc =>
              val base = varListDist(initState)(nc.output.polyDomain, epsilon)
              nodeCoeffFamilyMap(initState)(nc, base, epsilon)
            }.getOrElse(Task(Map()))

      def nodeFamilyDist[Dom <: HList, Y](initState: State)(
          generatorNodeFamily: GeneratorNodeFamily[Dom, Y],
          baseDist: Task[FD[Dom]],
          epsilon: Double): Task[Map[Dom, FD[Y]]] =
        generatorNodeFamily match {
          case node: GeneratorNode[Y] =>
            nodeDist(initState)(node, epsilon)
            .map((t) => Map(HNil -> t))
          case f: GeneratorNodeFamily.Pi[Dom, Y] =>
            baseDist.flatMap{(bd) =>
            val kvs: Vector[Task[(Dom, FD[Y])]] =
              for {
                Weighted(arg, p) <- bd.pmf
                dt = nodeDist(initState)(f.nodes(arg), epsilon / p) // actually a task
              } yield dt.map(d => arg -> d)
            Task.sequence(kvs).map(_.toMap)
          }
        }

        def nodeDist[Y](initState: State)(generatorNode: GeneratorNode[Y],
                                    epsilon: Double): Task[FD[Y]]

    }

/**
  * resolving a general specification of a recursive generative model as finite distributions, depending on truncation;
  * the coefficients of the various generator nodes should be `Double`
  *
  * @param nodeCoeffSeq the various generator nodes with coefficients for various random variables and families
  * @param sd finite distributions from the initial state corresponding to random variables and families
  * @tparam State scala type of the initial state
  */
case class MonixFiniteDistribution[State, Boat](
    nodeCoeffSeq: NodeCoeffSeq[State, Boat, Double])(
    implicit sd: StateDistribution[State, FD]) extends GenMonixFiniteDistribution[State, Boat](nodeCoeffSeq) {

  /**
    * update coefficients, to be used in complex islands
    * @param dataSeq the new coefficients
    * @return [[MonixFiniteDistribution]] with updated coefficients
    */
  def updateAll(
      dataSeq: Seq[GeneratorNodeFamily.Value[_ <: HList, _, Double]]) =
    MonixFiniteDistribution(nodeCoeffSeq.updateAll(dataSeq))

    import StateDistribution._

  /**
    * recursively determines the finite distribution given a generator node;
    * the main work is done here
    *
    * @param initState  initial state
    * @param generatorNode generator node to resolve
    * @param epsilon cutoff
    * @tparam Y values of the corresponding random variable
    * @return distribution corresponding to the `output` random variable
    */
  def nodeDist[Y](initState: State)(generatorNode: GeneratorNode[Y],
                                    epsilon: Double): Task[FD[Y]] =
    if (epsilon > 1) Task.now(FD.empty[Y])
    else {
      import GeneratorNode._
      generatorNode match {
        case Init(input) =>
          Task(sd.value(initState)(input))
        case Map(f, input, _) =>
          varDist(initState)(input, epsilon).map(_.map(f).purge(epsilon))
        case MapOpt(f, input, _) =>
          varDist(initState)(input, epsilon).map(_.condMap(f).purge(epsilon))
        case ZipMap(f, input1, input2, _) =>
          val d1 = varDist(initState)(input1, epsilon).map(_.flatten)
          val d2 = varDist(initState)(input2, epsilon).map(_.flatten)
          d1.zip(d2).map {
            case (xd, yd) =>
              xd.zip(yd).map{ case (x, y) => f(x, y) }.purge(epsilon)
            }
        case ZipMapOpt(f, input1, input2, _) =>
          val d1 = varDist(initState)(input1, epsilon).map(_.flatten)
          val d2 = varDist(initState)(input2, epsilon).map(_.flatten)
          d1.zip(d2).map {
            case (xd, yd) =>
              xd.zip(yd).condMap{ case (x, y) => f(x, y) }.purge(epsilon)
            }
        case ZipFlatMap(baseInput, fiberVar, f, _) =>
          val baseDist = varDist(initState)(baseInput, epsilon).map(_.flatten)
          baseDist.flatMap{(bd) =>
            val pmfT =
              bd.pmf
              .map{
                case Weighted(x1, p1) =>
                  val fiberDistT = varDist(initState)(fiberVar(x1), epsilon / p1).map(_.flatten)
                  val tv =
                    fiberDistT
                    .flatMap{
                      fiberDist =>
                        Task.sequence(for {
                          Weighted(x2, p2) <- fiberDist.pmf
                        } yield Task(Weighted(f(x1, x2), p1 * p2))
                        )
                  }
                  tv
              }
              Task.sequence(pmfT).map((vv) => FD(vv.flatten))
            }
        case FlatMap(baseInput, fiberNode, _) =>
          val baseDist = varDist(initState)(baseInput, epsilon).map(_.flatten)
          baseDist.flatMap{(bd) =>
            val pmfT =
              bd.pmf
              .map{
                case Weighted(x1, p1) =>
                  val fiberDistT = nodeDist(initState)(fiberNode(x1), epsilon / p1).map(_.flatten)
                  val tv =
                    fiberDistT
                    .flatMap{
                      fiberDist =>
                        Task.sequence(for {
                          Weighted(x2, p2) <- fiberDist.pmf
                        } yield Task(Weighted(x2, p1 * p2))
                        )
                  }
                  tv
              }
              Task.sequence(pmfT).map((vv) => FD(vv.flatten))
            }
        case FlatMapOpt(baseInput, fiberNodeOpt, _) =>
          val baseDist = varDist(initState)(baseInput, epsilon).map(_.flatten)
          baseDist.flatMap{(bd) =>
            val pmfT =
              bd.pmf
              .map{
                case Weighted(x1, p1) =>
                  val nodeOpt = fiberNodeOpt(x1).toVector
                  val fiberDistOptT =
                    nodeOpt.map(node => nodeDist(initState)(node, epsilon / p1).map(_.flatten))
                  val tv =
                    fiberDistOptT.
                    map{ //task
                      fiberDistOpt =>
                      fiberDistOpt
                      .map{ // vector, pretending to be option
                      fiberDist =>
                        Task.sequence(for {
                          Weighted(x2, p2) <- fiberDist.pmf
                        } yield Task(Weighted(x2, p1 * p2))
                        )
                      }
                    }
                      tv
                    }
              Task.sequence(pmfT).map((vv) => FD(vv.flatten))
            }

          // val baseDist = varDist(initState)(baseInput, epsilon).map(_.flatten)
          // val pmf =
          //   for {
          //     Weighted(x1, p1) <- baseDist.pmf
          //     node             <- fiberNodeOpt(x1).toVector
          //     fiberDist = nodeDist(initState)(node, epsilon / p1).map(_.flatten)
          //     Weighted(x2, p2) <- fiberDist.pmf
          //   } yield Weighted(x2, p1 * p2)
          // FD(pmf).flatten.purge(epsilon)
        case FiberProductMap(quot, fiberVar, f, baseInput, _) =>
          val d1          = varDist(initState)(baseInput, epsilon).flatten
          val byBase      = d1.pmf.groupBy { case Weighted(x, p) => quot(x) } // pmfs grouped by terms in quotient
          val baseWeights = byBase.mapValues(v => v.map(_.weight).sum) // weights of terms in the quotient
          val pmf: immutable.Iterable[Weighted[Y]] =
            for {
              (z, pmf1) <- byBase // `z` is in the base, `pmf1` is all terms above `z`
              d2 = varDist(initState)(fiberVar(z), epsilon / baseWeights(z)).flatten // distribution of the fiber at `z`
              d = FD(pmf1)
                .zip(d2)
                .map { case (x1, x2) => f(x1, x2) }
                .flatten // mapped distribution over `z`
              wtd <- d.pmf // terms in pmf over z, should be flatMapped over `z`
            } yield wtd
          FD(pmf.toVector).map(_.purge(epsilon))
        case tc: ThenCondition[o, Y] =>
          import tc._
          val base = nodeDist(initState)(gen, epsilon)
          import Sort._
          condition match {
            case _: All[_]    => base
            case c: Filter[_] => base.conditioned(c.pred).map(_.purge(epsilon))
            case Restrict(f)  => base.condMap(f).purge(epsilon)
          }
        case isle: Island[Y, State, o, b] =>
          import isle._
          val (isleInit, boat) = initMap(initState)                       // initial condition for island, boat to row back
          val isleOut          = varDist(isleInit)(islandOutput, epsilon) //result for the island
          isleOut
            .map(export(boat, _))
            .map(_.purge(epsilon)) // exported result seen outside
        case isle: ComplexIsland[o, Y, State, b, Double] =>
          import isle._
          val (isleInit, boat, isleCoeffs) = initMap(initState)
          val isleOut =
            updateAll(isleCoeffs.toSeq) // coefficients changed to those for the island
              .varDist(isleInit)(islandOutput, epsilon)
          isleOut.map(export(boat, _)).map(_.purge(epsilon))
      }
    }
}
