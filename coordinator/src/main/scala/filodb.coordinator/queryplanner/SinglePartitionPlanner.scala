package filodb.coordinator.queryplanner

import filodb.core.query.{PromQlQueryParams, QueryConfig, QueryContext}
import filodb.query.{BinaryJoin, LabelNames, LabelValues, LogicalPlan,
                     SeriesKeysByFilters, SetOperator, TsCardinalities}
import filodb.query.LogicalPlan._
import filodb.query.exec._

/**
  * SinglePartitionPlanner is responsible for planning in situations where time series data is
  * distributed across multiple clusters.
  *
  * @param planners map of clusters names in the local partition to their Planner objects
  * @param plannerSelector a function that selects the planner name given the metric name
  * @param defaultPlanner TsCardinalities queries are routed here.
  *   Note: this is a temporary fix only to support TsCardinalities queries.
  *     These must be routed to planners according to the data they govern, and
  *     this information isn't accessible without this parameter.
  */
class SinglePartitionPlanner(planners: Map[String, QueryPlanner],
                             defaultPlanner: String,  // TODO: remove this-- see above.
                             plannerSelector: String => String,
                             datasetMetricColumn: String,
                             queryConfig: QueryConfig)
  extends QueryPlanner {

  private val inProcessPlanDispatcher = InProcessPlanDispatcher(queryConfig)
  def materialize(logicalPlan: LogicalPlan, qContext: QueryContext): ExecPlan = {

    logicalPlan match {
      case lp: BinaryJoin          => materializeBinaryJoin(lp, qContext)
      case lp: LabelValues         => materializeLabelValues(lp, qContext)
      case lp: LabelNames          => materializeLabelNames(lp, qContext)
      case lp: SeriesKeysByFilters => materializeSeriesKeysFilters(lp, qContext)
      case lp: TsCardinalities     => materializeTsCardinalities(lp, qContext)
      case _                       => materializeSimpleQuery(logicalPlan, qContext)

    }
  }

  /**
    * Returns planner for first metric in logical plan
    * If logical plan does not have metric, first planner present in planners is returned
    */
  private def getPlanner(logicalPlan: LogicalPlan): QueryPlanner = {
    val planner = LogicalPlanUtils.getMetricName(logicalPlan, datasetMetricColumn)
      .map(x => planners(plannerSelector(x)))
    if(planner.isEmpty)  planners.values.head else planner.head
  }

  /**
   * Returns lhs and rhs planners of BinaryJoin
   */
  private def getBinaryJoinPlanners(binaryJoin: BinaryJoin) : Seq[QueryPlanner] = {
    val lhsPlanners = binaryJoin.lhs match {
      case b: BinaryJoin => getBinaryJoinPlanners(b)
      case _             => Seq(getPlanner(binaryJoin.lhs))

    }

    val rhsPlanners = binaryJoin.rhs match {
      case b: BinaryJoin => getBinaryJoinPlanners(b)
      case _             => Seq(getPlanner(binaryJoin.rhs))

    }
    lhsPlanners ++ rhsPlanners
  }

  private def materializeSimpleQuery(logicalPlan: LogicalPlan, qContext: QueryContext): ExecPlan = {
    getPlanner(logicalPlan).materialize(logicalPlan, qContext)
  }

  private def materializeBinaryJoin(logicalPlan: BinaryJoin, qContext: QueryContext): ExecPlan = {
    val allPlanners = getBinaryJoinPlanners(logicalPlan)

    if (allPlanners.forall(_.equals(allPlanners.head))) allPlanners.head.materialize(logicalPlan, qContext)
    else {

      val lhsQueryContext = qContext.copy(origQueryParams = qContext.origQueryParams.asInstanceOf[PromQlQueryParams].
        copy(promQl = LogicalPlanParser.convertToQuery(logicalPlan.lhs)))
      val rhsQueryContext = qContext.copy(origQueryParams = qContext.origQueryParams.asInstanceOf[PromQlQueryParams].
        copy(promQl = LogicalPlanParser.convertToQuery(logicalPlan.rhs)))

      val lhsExec = logicalPlan.lhs match {
        case b: BinaryJoin   => materializeBinaryJoin(b, lhsQueryContext)
        case               _ => getPlanner(logicalPlan.lhs).materialize(logicalPlan.lhs, lhsQueryContext)
      }

      val rhsExec = logicalPlan.rhs match {
        case b: BinaryJoin => materializeBinaryJoin(b, rhsQueryContext)
        case _             => getPlanner(logicalPlan.rhs).materialize(logicalPlan.rhs, rhsQueryContext)
      }

      if (logicalPlan.operator.isInstanceOf[SetOperator])
        SetOperatorExec(qContext, inProcessPlanDispatcher, Seq(lhsExec), Seq(rhsExec), logicalPlan.operator,
          LogicalPlanUtils.renameLabels(logicalPlan.on, datasetMetricColumn),
          LogicalPlanUtils.renameLabels(logicalPlan.ignoring, datasetMetricColumn), datasetMetricColumn,
          rvRangeFromPlan(logicalPlan))
      else
        BinaryJoinExec(qContext, inProcessPlanDispatcher, Seq(lhsExec), Seq(rhsExec), logicalPlan.operator,
          logicalPlan.cardinality, LogicalPlanUtils.renameLabels(logicalPlan.on, datasetMetricColumn),
          LogicalPlanUtils.renameLabels(logicalPlan.ignoring, datasetMetricColumn),
          LogicalPlanUtils.renameLabels(logicalPlan.include, datasetMetricColumn), datasetMetricColumn,
          rvRangeFromPlan(logicalPlan))
    }
  }

  private def materializeLabelValues(logicalPlan: LogicalPlan, qContext: QueryContext) = {
    val execPlans = planners.values.toList.distinct.map(_.materialize(logicalPlan, qContext))
    if (execPlans.size == 1) execPlans.head
    else LabelValuesDistConcatExec(qContext, inProcessPlanDispatcher, execPlans)
  }

  private def materializeLabelNames(logicalPlan: LogicalPlan, qContext: QueryContext) = {
    val execPlans = planners.values.toList.distinct.map(_.materialize(logicalPlan, qContext))
    if (execPlans.size == 1) execPlans.head
    else LabelNamesDistConcatExec(qContext, inProcessPlanDispatcher, execPlans)
  }

  private def materializeSeriesKeysFilters(logicalPlan: LogicalPlan, qContext: QueryContext) = {
    val execPlans = planners.values.toList.distinct.map(_.materialize(logicalPlan, qContext))
    if (execPlans.size == 1) execPlans.head
    else PartKeysDistConcatExec(qContext, inProcessPlanDispatcher, execPlans)
  }

  private def materializeTsCardinalities(logicalPlan: TsCardinalities, qContext: QueryContext): ExecPlan = {
    // TODO: this is a hacky fix to prevent delegation to planners with reduce-incompatible data.
    planners.find{case (name, _) => name == defaultPlanner}.get._2.materialize(logicalPlan, qContext)
  }
}

