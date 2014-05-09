/*
 * Stratio Meta
 *
 * Copyright (c) 2014, Stratio, All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */

package com.stratio.meta.deep;

import com.datastax.driver.core.Session;
import com.stratio.deep.config.DeepJobConfigFactory;
import com.stratio.deep.config.IDeepJobConfig;
import com.stratio.deep.context.DeepSparkContext;
import com.stratio.meta.common.data.*;
import com.stratio.meta.common.result.QueryResult;
import com.stratio.meta.common.result.Result;
import com.stratio.meta.core.engine.EngineConfig;
import com.stratio.meta.core.statements.MetaStatement;
import com.stratio.meta.core.statements.SelectStatement;
import com.stratio.meta.core.structures.Relation;
import com.stratio.meta.core.structures.SelectionClause;
import com.stratio.meta.deep.functions.*;
import com.stratio.meta.deep.utils.DeepUtils;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;

import java.util.*;

/**
 * Class that performs as a Bridge between Meta and Stratio Deep.
 */
public class Bridge {

    /**
     * Class logger.
     */
    private static final Logger LOG = Logger.getLogger(Bridge.class);

    /**
     * Default result size.
     */
    public static final int DEFAULT_RESULT_SIZE = 100000;

    /**
     * Deep Spark context.
     */
    private DeepSparkContext deepContext;

    /**
     * Datastax Java Driver session.
     */
    private Session session;

    /**
     * Global configuration.
     */
    private EngineConfig engineConfig;

    /**
     * Brigde Constructor.
     * @param session Cassandra session. {@link com.datastax.driver.core.Session}
     * @param deepSparkContext Spark context from Deep
     * @param config A {@link com.stratio.meta.core.engine.EngineConfig}, contains global configuration
     */
    public Bridge(Session session, DeepSparkContext deepSparkContext, EngineConfig config) {
        this.deepContext = deepSparkContext;
        this.session = session;
        this.engineConfig = config;
    }

    /**
     * Execute a Leaf node in the current plan.
     * @param stmt Statement which corresponds to this node.
     * @param isRoot Indicates if this node is root in this plan
     * @return a {@link com.stratio.meta.common.data.ResultSet}
     */
    public ResultSet executeLeafNode(MetaStatement stmt, boolean isRoot){
        SelectStatement ss = (SelectStatement) stmt;
        // LEAF
        String[] columnsSet = {};
        if(ss.getSelectionClause().getType() == SelectionClause.TYPE_SELECTION){
            columnsSet = DeepUtils.retrieveSelectorFields(ss);
        }
        IDeepJobConfig config = DeepJobConfigFactory.create().session(session)
                .host(engineConfig.getRandomCassandraHost()).rpcPort(engineConfig.getCassandraPort())
                .keyspace(ss.getKeyspace()).table(ss.getTableName());

        config = (columnsSet.length==0)? config.initialize(): config.inputColumns(columnsSet).initialize();

        JavaRDD rdd = deepContext.cassandraJavaRDD(config);
        //If where
        if(ss.isWhereInc()){
            List<Relation> where = ss.getWhere();
            for(Relation rel : where){
                rdd = doWhere(rdd, rel);
            }
        }
        return returnResult(rdd, isRoot, ss.getSelectionClause().getType() == SelectionClause.TYPE_COUNT, Arrays.asList(columnsSet));
    }

    /**
     * Executes a root node statement.
     * @param stmt Statement which corresponds to this node.
     * @param resultsFromChildren List of results from node children
     * @return a {@link com.stratio.meta.common.data.ResultSet}
     */
    public ResultSet executeRootNode(MetaStatement stmt, List<Result> resultsFromChildren){
        SelectStatement ss = (SelectStatement) stmt;
        // Retrieve RDDs and selected columns from children
        List<JavaRDD> children = new ArrayList<>();
        List<String> selectedCols = new ArrayList<>();
        for (Result child: resultsFromChildren){
            QueryResult qResult = (QueryResult) child;
            CassandraResultSet crset = (CassandraResultSet) qResult.getResultSet();
            Map<String, Cell> cells = crset.getRows().get(0).getCells();
            // RDD from child
            Cell cell = cells.get("RDD");
            JavaRDD rdd = (JavaRDD) cell.getValue();
            children.add(rdd);
        }

        // Retrieve selected columns without tablename
        for(String id: ss.getSelectionClause().getIds()){
            if(id.contains(".")){
                selectedCols.add(id.split("\\.")[1]);
            } else {
                selectedCols.add(id);
            }
        }

        //JOIN
        Map<String, String> fields = ss.getJoin().getColNames();
        Set<String> keys = fields.keySet();
        String field1 = keys.iterator().next();
        String field2 = fields.get(field1);

        LOG.debug("INNER JOIN on: " + field1 + " - " + field2);

        JavaRDD rdd1 = children.get(0);
        JavaRDD rdd2 = children.get(1);

        JavaPairRDD rddLeft = rdd1.map(new MapKeyForJoin(field1));
        JavaPairRDD rddRight = rdd2.map(new MapKeyForJoin(field2));

        JavaPairRDD joinRDD = rddLeft.join(rddRight);

        JavaRDD result = joinRDD.map(new JoinCells(field1));

        // Return MetaResultSet
        return returnResult(result, true, false, selectedCols);
    }

    /**
     * General execution. Depending on the type execution will divide up.
     * @param stmt Statement which corresponds to this node.
     * @param resultsFromChildren List of results from node children
     * @param isRoot Indicates if this node is root in this plan
     * @return a {@link com.stratio.meta.common.data.ResultSet}
     */
    public ResultSet execute(MetaStatement stmt, List<Result> resultsFromChildren, boolean isRoot){

        LOG.info("Executing deep for: " + stmt.toString());

        if(!(stmt instanceof SelectStatement)){
            CassandraResultSet crs = new CassandraResultSet();
            crs.add(new Row("RESULT", new Cell("NOT supported yet")));

            Map colDefs = new HashMap<String, ColumnDefinition>();
            colDefs.put("RESULT", new ColumnDefinition(String.class));
            crs.setColumnDefinitions(colDefs);

            return crs;
        }

        if(resultsFromChildren.isEmpty()){
            // LEAF
            return executeLeafNode(stmt, isRoot);
        } else {
            // (INNER NODE) NO LEAF
            return executeRootNode(stmt, resultsFromChildren);
        }
    }

    /**
     * Build a ResultSet from a RDD depending the context.
     * @param rdd RDD which corresponds to Spark result.
     * @param isRoot Indicates if this node is root in this plan.
     * @param isCount Indicates if this query have a COUNT clause.
     * @param selectedCols List of columns selected in current SelectStatement.
     * @return ResultSet containing the result of built.
     */
    private ResultSet returnResult(JavaRDD rdd, boolean isRoot, boolean isCount, List<String> selectedCols){
        if(isRoot){
            if(isCount){
                return DeepUtils.buildCountResult(rdd);
            }
            return DeepUtils.buildResultSet(rdd.take(DEFAULT_RESULT_SIZE), selectedCols);
        } else {
            CassandraResultSet crs = new CassandraResultSet();
            crs.add(new Row("RDD", new Cell(rdd)));

            Map colDefs = new HashMap<String, ColumnDefinition>();
            colDefs.put("RDD", new ColumnDefinition(JavaRDD.class));
            crs.setColumnDefinitions(colDefs);

            LOG.info("LEAF: rdd.count=" + ((int) rdd.count()));
            return crs;
        }
    }

    /**
     * Take a RDD and a Relation and apply suitable filter to the RDD. Execute where clause on Deep.
     * @param rdd RDD which filter must be applied.
     * @param rel {@link com.stratio.meta.core.structures.Relation} to apply
     * @return A new RDD with the result.
     */
    private JavaRDD doWhere(JavaRDD rdd, Relation rel){
        String operator = rel.getOperator();
        JavaRDD result = null;
        String cn = rel.getIdentifiers().get(0);
        if(cn.contains(".")){
            String[] ksAndTableName = cn.split("\\.");
            cn = ksAndTableName[1];
        }
        Object termValue = rel.getTerms().get(0).getTermValue();

        LOG.info("Rdd input size: " + rdd.count());
        switch (operator){
            case "=":
                result = rdd.filter(new DeepEquals(cn, termValue));
                break;
            case "<>":
                result = rdd.filter(new NotEquals(cn,termValue));
                break;
            case ">":
                result = rdd.filter(new GreaterThan(cn,termValue));
                break;
            case ">=":
                result = rdd.filter(new GreaterEqualThan(cn,termValue));
                break;
            case "<":
                result = rdd.filter(new LessThan(cn,termValue));
                break;
            case "<=":
                result = rdd.filter(new LessEqualThan(cn,termValue));
                break;
            default:
                LOG.error("Operator not supported: " + operator);
                result = null;
        }
        return result;
    }
}