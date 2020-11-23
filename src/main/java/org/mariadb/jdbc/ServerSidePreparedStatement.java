/*
 *
 * MariaDB Client for Java
 *
 * Copyright (c) 2012-2014 Monty Program Ab.
 * Copyright (c) 2015-2020 MariaDB Corporation Ab.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to Monty Program Ab info@montyprogram.com.
 *
 */

package org.mariadb.jdbc;

import java.sql.*;
import java.util.*;
import org.mariadb.jdbc.internal.com.read.dao.Results;
import org.mariadb.jdbc.internal.com.read.resultset.SelectResultSet;
import org.mariadb.jdbc.internal.com.send.parameters.ParameterHolder;
import org.mariadb.jdbc.internal.logging.Logger;
import org.mariadb.jdbc.internal.logging.LoggerFactory;
import org.mariadb.jdbc.internal.util.dao.ServerPrepareResult;
import org.mariadb.jdbc.internal.util.exceptions.ExceptionFactory;

public class ServerSidePreparedStatement extends BasePrepareStatement implements Cloneable {

  private static final Logger logger = LoggerFactory.getLogger(ServerSidePreparedStatement.class);

  protected int parameterCount = -1;
  private String sql;
  private ServerPrepareResult serverPrepareResult = null;
  private MariaDbResultSetMetaData metadata;
  private MariaDbParameterMetaData parameterMetaData;
  private Map<Integer, ParameterHolder> currentParameterHolder;
  private List<ParameterHolder[]> queryParameters = new ArrayList<>();
  private boolean mustExecuteOnMaster;

  /**
   * Constructor for creating Server prepared statement.
   *
   * @param connection current connection
   * @param sql Sql String to prepare
   * @param resultSetScrollType one of the following <code>ResultSet</code> constants: <code>
   *     ResultSet.TYPE_FORWARD_ONLY</code>, <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or
   *     <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
   * @param resultSetConcurrency a concurrency type; one of <code>ResultSet.CONCUR_READ_ONLY</code>
   *     or <code>ResultSet.CONCUR_UPDATABLE</code>
   * @param autoGeneratedKeys a flag indicating whether auto-generated keys should be returned; one
   *     of <code>Statement.RETURN_GENERATED_KEYS</code> or <code>Statement.NO_GENERATED_KEYS</code>
   * @param exceptionFactory Exception factory
   * @throws SQLException exception
   */
  public ServerSidePreparedStatement(
      MariaDbConnection connection,
      String sql,
      int resultSetScrollType,
      int resultSetConcurrency,
      int autoGeneratedKeys,
      ExceptionFactory exceptionFactory)
      throws SQLException {
    super(
        connection, resultSetScrollType, resultSetConcurrency, autoGeneratedKeys, exceptionFactory);
    this.sql = sql;
    currentParameterHolder = Collections.synchronizedMap(new TreeMap<>());
    mustExecuteOnMaster = protocol.isMasterConnection();
    prepare(this.sql);
  }

  /**
   * Clone statement.
   *
   * @param connection connection
   * @return Clone statement.
   * @throws CloneNotSupportedException if any error occur.
   */
  public ServerSidePreparedStatement clone(MariaDbConnection connection)
      throws CloneNotSupportedException {
    ServerSidePreparedStatement clone = (ServerSidePreparedStatement) super.clone(connection);
    clone.metadata = metadata;
    clone.parameterMetaData = parameterMetaData;
    clone.queryParameters = new ArrayList<>();
    clone.mustExecuteOnMaster = mustExecuteOnMaster;
    // force prepare
    try {
      clone.prepare(sql);
    } catch (SQLException e) {
      throw new CloneNotSupportedException("PrepareStatement not ");
    }
    return clone;
  }

  private void prepare(String sql) throws SQLException {
    try {
      serverPrepareResult = protocol.prepare(sql, mustExecuteOnMaster);
      setMetaFromResult();
    } catch (SQLException e) {
      try {
        this.close();
      } catch (Exception ee) {
        // eat exception.
      }
      logger.error("error preparing query", e);
      throw exceptionFactory.raiseStatementError(connection, this).create(e);
    }
  }

  private void setMetaFromResult() {
    parameterCount = serverPrepareResult.getParameters().length;
    metadata =
        new MariaDbResultSetMetaData(
            serverPrepareResult.getColumns(), protocol.getUrlParser().getOptions(), false);
    parameterMetaData = new MariaDbParameterMetaData(serverPrepareResult.getParameters());
  }

  public void setParameter(final int parameterIndex, final ParameterHolder holder)
      throws SQLException {
    currentParameterHolder.put(parameterIndex - 1, holder);
  }

  @Override
  public void addBatch() throws SQLException {
    validParameters();
    queryParameters.add(currentParameterHolder.values().toArray(new ParameterHolder[0]));
  }

  /**
   * Add batch.
   *
   * @param sql typically this is a SQL <code>INSERT</code> or <code>UPDATE</code> statement
   * @throws SQLException every time since that method is forbidden on prepareStatement
   */
  @Override
  public void addBatch(final String sql) throws SQLException {
    throw exceptionFactory
        .raiseStatementError(connection, this)
        .create("Cannot do addBatch(String) on preparedStatement");
  }

  public void clearBatch() {
    queryParameters.clear();
  }

  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    return parameterMetaData;
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return metadata;
  }

  /**
   * Submits a batch of send to the database for execution and if all send execute successfully,
   * returns an array of update counts. The <code>int</code> elements of the array that is returned
   * are ordered to correspond to the send in the batch, which are ordered according to the order in
   * which they were added to the batch. The elements in the array returned by the method <code>
   * executeBatch</code> may be one of the following:
   *
   * <ol>
   *   <li>A number greater than or equal to zero -- indicates that the command was processed
   *       successfully and is an update count giving the number of rows in the database that were
   *       affected by the command's execution
   *   <li>A value of <code>SUCCESS_NO_INFO</code> -- indicates that the command was processed
   *       successfully but that the number of rows affected is unknown. If one of the send in a
   *       batch update fails to execute properly, this method throws a <code>BatchUpdateException
   *       </code>, and a JDBC driver may or may not continue to process the remaining send in the
   *       batch. However, the driver's behavior must be consistent with a particular DBMS, either
   *       always continuing to process send or never continuing to process send. If the driver
   *       continues processing after a failure, the array returned by the method <code>
   *       BatchUpdateException.getUpdateCounts</code> will contain as many elements as there are
   *       send in the batch, and at least one of the elements will be the following:
   *   <li>A value of <code>EXECUTE_FAILED</code> -- indicates that the command failed to execute
   *       successfully and occurs only if a driver continues to process send after a command fails
   * </ol>
   *
   * <p>The possible implementations and return values have been modified in the Java 2 SDK,
   * Standard Edition, version 1.3 to accommodate the option of continuing to proccess send in a
   * batch update after a <code>BatchUpdateException</code> object has been thrown.
   *
   * @return an array of update counts containing one element for each command in the batch. The
   *     elements of the array are ordered according to the order in which send were added to the
   *     batch.
   * @throws SQLException if a database access error occurs, this method is called on a closed
   *     <code>Statement</code> or the driver does not support batch statements. Throws {@link
   *     BatchUpdateException} (a subclass of <code>SQLException</code>) if one of the send sent to
   *     the database fails to execute properly or attempts to return a result set.
   * @see #addBatch
   * @see DatabaseMetaData#supportsBatchUpdates
   * @since 1.3
   */
  @Override
  public int[] executeBatch() throws SQLException {
    checkClose();
    int queryParameterSize = queryParameters.size();
    if (queryParameterSize == 0) {
      return new int[0];
    }
    executeBatchInternal(queryParameterSize);
    return results.getCmdInformation().getUpdateCounts();
  }

  /**
   * Execute batch, like executeBatch(), with returning results with long[]. For when row count may
   * exceed Integer.MAX_VALUE.
   *
   * @return an array of update counts (one element for each command in the batch)
   * @throws SQLException if a database error occur.
   */
  @Override
  public long[] executeLargeBatch() throws SQLException {
    checkClose();
    int queryParameterSize = queryParameters.size();
    if (queryParameterSize == 0) {
      return new long[0];
    }
    executeBatchInternal(queryParameterSize);
    return results.getCmdInformation().getLargeUpdateCounts();
  }

  private void executeBatchInternal(int queryParameterSize) throws SQLException {
    lock.lock();
    executing = true;
    try {
      executeQueryPrologue(serverPrepareResult);
      if (queryTimeout != 0) {
        setTimerTask(true);
      }

      results =
          new Results(
              this,
              0,
              true,
              queryParameterSize,
              true,
              resultSetScrollType,
              resultSetConcurrency,
              autoGeneratedKeys,
              protocol.getAutoIncrementIncrement(),
              null,
              null);

      // if  multi send capacity
      if ((options.useBatchMultiSend || options.useBulkStmts)
          && (protocol.executeBatchServer(
              mustExecuteOnMaster, serverPrepareResult, results, sql, queryParameters))) {
        if (metadata == null) {
          setMetaFromResult(); // first prepare
        }
        results.commandEnd();
        return;
      }

      // send query one by one, reading results for each query before sending another one
      SQLException exception = null;
      if (queryTimeout > 0) {
        for (int counter = 0; counter < queryParameterSize; counter++) {
          ParameterHolder[] parameterHolder = queryParameters.get(counter);
          try {
            protocol.stopIfInterrupted();
            serverPrepareResult.resetParameterTypeHeader();
            protocol.executePreparedQuery(
                mustExecuteOnMaster, serverPrepareResult, results, parameterHolder);
          } catch (SQLException queryException) {
            if (options.continueBatchOnError
                && protocol.isConnected()
                && !protocol.isInterrupted()) {
              if (exception == null) {
                exception = queryException;
              }
            } else {
              throw queryException;
            }
          }
        }
      } else {
        for (int counter = 0; counter < queryParameterSize; counter++) {
          ParameterHolder[] parameterHolder = queryParameters.get(counter);
          try {
            serverPrepareResult.resetParameterTypeHeader();
            protocol.executePreparedQuery(
                mustExecuteOnMaster, serverPrepareResult, results, parameterHolder);
          } catch (SQLException queryException) {
            if (options.continueBatchOnError) {
              if (exception == null) {
                exception = queryException;
              }
            } else {
              throw queryException;
            }
          }
        }
      }
      if (exception != null) {
        throw exception;
      }

      results.commandEnd();
    } catch (SQLException initialSqlEx) {
      throw executeBatchExceptionEpilogue(initialSqlEx, queryParameterSize);
    } finally {
      executeBatchEpilogue();
      lock.unlock();
    }
  }

  // must have "lock" locked before invoking
  private void executeQueryPrologue(ServerPrepareResult serverPrepareResult) throws SQLException {
    executing = true;
    if (closed) {
      throw exceptionFactory
          .raiseStatementError(connection, this)
          .create("execute() is called on closed statement");
    }
    protocol.prologProxy(
        serverPrepareResult, maxRows, protocol.getProxy() != null, connection, this);
  }

  @Override
  public ResultSet executeQuery() throws SQLException {
    if (execute()) {
      return results.getResultSet();
    }
    return SelectResultSet.createEmptyResultSet();
  }

  /**
   * Executes the SQL statement in this <code>PreparedStatement</code> object, which must be an SQL
   * Data Manipulation Language (DML) statement, such as <code>INSERT</code>, <code>UPDATE</code> or
   * <code>DELETE</code>; or an SQL statement that returns nothing, such as a DDL statement.
   * Result-set are permitted for historical reason, even if spec indicate to throw exception.
   *
   * @return either (1) the row count for SQL Data Manipulation Language (DML) statements or (2) 0
   *     for SQL statements that return nothing
   * @throws SQLException if a database access error occurs; this method is called on a closed
   *     <code>PreparedStatement</code>
   */
  @Override
  public int executeUpdate() throws SQLException {
    if (execute()) {
      return 0;
    }
    return getUpdateCount();
  }

  @Override
  public void clearParameters() {
    currentParameterHolder.clear();
  }

  @Override
  public boolean execute() throws SQLException {
    return executeInternal(getFetchSize());
  }

  protected void validParameters() throws SQLException {
    for (int i = 0; i < parameterCount; i++) {
      if (currentParameterHolder.get(i) == null) {
        logger.error("Parameter at position {} is not set", (i + 1));
        throw exceptionFactory
            .raiseStatementError(connection, this)
            .create("Parameter at position " + (i + 1) + " is not set", "07004");
      }
    }
  }

  protected boolean executeInternal(int fetchSize) throws SQLException {
    validParameters();

    lock.lock();
    try {
      executeQueryPrologue(serverPrepareResult);
      if (queryTimeout != 0) {
        setTimerTask(false);
      }

      ParameterHolder[] parameterHolders =
          currentParameterHolder.values().toArray(new ParameterHolder[0]);

      results =
          new Results(
              this,
              fetchSize,
              false,
              1,
              true,
              resultSetScrollType,
              resultSetConcurrency,
              autoGeneratedKeys,
              protocol.getAutoIncrementIncrement(),
              sql,
              parameterHolders);

      serverPrepareResult.resetParameterTypeHeader();
      protocol.executePreparedQuery(
          mustExecuteOnMaster, serverPrepareResult, results, parameterHolders);

      results.commandEnd();
      return results.getResultSet() != null;

    } catch (SQLException exception) {
      throw executeExceptionEpilogue(exception);
    } finally {
      executeEpilogue();
      lock.unlock();
    }
  }

  /**
   * Releases this <code>Statement</code> object's database and JDBC resources immediately instead
   * of waiting for this to happen when it is automatically closed. It is generally good practice to
   * release resources as soon as you are finished with them to avoid tying up database resources.
   *
   * <p>Calling the method <code>close</code> on a <code>Statement</code> object that is already
   * closed has no effect.
   *
   * <p><B>Note:</B>When a <code>Statement</code> object is closed, its current <code>ResultSet
   * </code> object, if one exists, is also closed.
   *
   * @throws SQLException if a database access error occurs
   */
  @Override
  public void close() throws SQLException {
    // No possible future use for the cached results, so these can be cleared
    // This makes the cache eligible for garbage collection earlier if the statement is not
    // immediately garbage collected
    if (protocol != null && serverPrepareResult != null) {
      try {
        serverPrepareResult.getUnProxiedProtocol().releasePrepareStatement(serverPrepareResult);
      } catch (SQLException e) {
        // eat
      }
    }
    super.close();
  }

  protected int getParameterCount() {
    return parameterCount;
  }

  /**
   * Return sql String value.
   *
   * @return String representation
   */
  public String toString() {
    StringBuilder sb = new StringBuilder("sql : '" + serverPrepareResult.getSql() + "'");
    if (parameterCount > 0) {
      sb.append(", parameters : [");
      for (int i = 0; i < parameterCount; i++) {
        ParameterHolder holder = currentParameterHolder.get(i);
        if (holder == null) {
          sb.append("null");
        } else {
          sb.append(holder.toString());
        }
        if (i != parameterCount - 1) {
          sb.append(",");
        }
      }
      sb.append("]");
    }
    return sb.toString();
  }

  /**
   * Permit to retrieve current connection thread id, or -1 if unknown.
   *
   * @return current connection thread id.
   */
  public long getServerThreadId() {
    return serverPrepareResult.getUnProxiedProtocol().getServerThreadId();
  }
}
