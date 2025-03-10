/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
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
 *
 ******************************************************************************/

package io.questdb;

import io.questdb.cairo.*;
import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.cairo.sql.OperationFuture;
import io.questdb.griffin.SqlCompiler;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.SqlExecutionContextImpl;
import io.questdb.log.LogFactory;
import io.questdb.std.Files;
import io.questdb.std.Misc;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static io.questdb.test.tools.TestUtils.assertMemoryLeak;
import static io.questdb.test.tools.TestUtils.insertFromSelectPopulateTableStmt;

/**
 * Here we test edge cases around worker thread pool sizes and worker ids.
 * PGWire pool size is intentionally set to a higher value than the shared pool.
 */
public class ServerMainVectorGroupByTest extends AbstractBootstrapTest {

    private static final int PG_WIRE_POOL_SIZE = 4;
    private static final int SHARED_POOL_SIZE = 1;
    private static final int pgPort = PG_PORT + 10;
    private static Path path;

    @BeforeClass
    public static void setUpStatic() throws Exception {
        AbstractBootstrapTest.setUpStatic();
        path = new Path().of(root).concat("db").$();
        try {
            int pathLen = path.length();
            Files.remove(path.concat("sys.column_versions_purge_log.lock").$());
            Files.remove(path.trimTo(pathLen).concat("telemetry_config.lock").$());
            createDummyConfiguration(
                    HTTP_PORT + 10,
                    HTTP_MIN_PORT + 10,
                    pgPort,
                    ILP_PORT + 10,
                    PropertyKey.PG_WORKER_COUNT.getPropertyPath() + "=" + PG_WIRE_POOL_SIZE,
                    PropertyKey.SHARED_WORKER_COUNT.getPropertyPath() + "=" + SHARED_POOL_SIZE,
                    // Set vector aggregate queue to a small size to have better chances of work stealing.
                    PropertyKey.CAIRO_VECTOR_AGGREGATE_QUEUE_CAPACITY.getPropertyPath() + "=2"
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterClass
    public static void tearDownStatic() throws Exception {
        Misc.free(path);
        AbstractBootstrapTest.tearDownStatic();
    }

    @Test
    public void testKeyedGroupByDoesNotFail() throws Exception {
        String tableName = testName.getMethodName();
        assertMemoryLeak(() -> {
            try (
                    ServerMain qdb = new ServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext context = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                CairoEngine engine = qdb.getCairoEngine();
                CairoConfiguration cairoConfig = qdb.getConfiguration().getCairoConfiguration();

                Assert.assertEquals(SHARED_POOL_SIZE, qdb.getConfiguration().getWorkerPoolConfiguration().getWorkerCount());
                Assert.assertEquals(PG_WIRE_POOL_SIZE, qdb.getConfiguration().getPGWireConfiguration().getWorkerCount());

                TableToken tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName);
                assertQueryDoesNotFail("select max(l), s from " + tableToken.getTableName(), 5);
            }
        });
    }

    @Test
    public void testNonKeyedGroupByDoesNotFail() throws Exception {
        String tableName = testName.getMethodName();
        assertMemoryLeak(() -> {
            try (
                    ServerMain qdb = new ServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext context = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                CairoEngine engine = qdb.getCairoEngine();
                CairoConfiguration cairoConfig = qdb.getConfiguration().getCairoConfiguration();

                Assert.assertEquals(SHARED_POOL_SIZE, qdb.getConfiguration().getWorkerPoolConfiguration().getWorkerCount());
                Assert.assertEquals(PG_WIRE_POOL_SIZE, qdb.getConfiguration().getPGWireConfiguration().getWorkerCount());

                TableToken tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName);
                assertQueryDoesNotFail("select max(l) from " + tableToken.getTableName(), 1);
            }
        });
    }

    private static void assertQueryDoesNotFail(String query, int expectedRows) throws Exception {
        try (
                Connection conn = DriverManager.getConnection(getPgConnectionUri(pgPort), PG_CONNECTION_PROPERTIES);
                PreparedStatement stmt = conn.prepareStatement(query);
                ResultSet result = stmt.executeQuery()
        ) {
            int actualRows = 0;
            while (result.next()) {
                actualRows++;
            }
            Assert.assertEquals(expectedRows, actualRows);
        }
    }

    private static SqlExecutionContext executionContext(CairoEngine engine) {
        return new SqlExecutionContextImpl(engine, 1).with(
                AllowAllCairoSecurityContext.INSTANCE,
                null,
                null,
                -1,
                null);
    }

    private TableToken createPopulateTable(
            CairoConfiguration cairoConfig,
            CairoEngine engine,
            SqlCompiler compiler,
            SqlExecutionContext context,
            String tableName
    ) throws Exception {
        StringSink sink = Misc.getThreadLocalBuilder();
        sink.put("CREATE TABLE ");
        sink.put(tableName).put('(');
        sink.put(" l LONG,");
        sink.put(" s SYMBOL,");
        sink.put(" ts TIMESTAMP");
        sink.put(") TIMESTAMP(ts) PARTITION BY DAY");
        try (OperationFuture op = compiler.compile(sink.toString(), context).execute(null)) {
            op.await();
        }
        try (
                TableModel tableModel = new TableModel(cairoConfig, tableName, PartitionBy.DAY)
                        .col("l", ColumnType.LONG)
                        .col("s", ColumnType.SYMBOL)
                        .timestamp("ts");
                OperationFuture op = compiler.compile(insertFromSelectPopulateTableStmt(tableModel, 10000, "2020-01-01", 100), context).execute(null)
        ) {
            op.await();
        }
        return engine.getTableToken(tableName);
    }

    static {
        // log is needed to greedily allocate logger infra and
        // exclude it from leak detector
        LogFactory.getLog(ServerMainVectorGroupByTest.class);
    }
}
