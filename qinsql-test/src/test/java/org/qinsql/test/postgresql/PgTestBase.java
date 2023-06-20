/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.qinsql.test.postgresql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import org.junit.Before;
import org.lealone.common.exceptions.DbException;
import org.lealone.test.sql.SqlTestBase;
import org.qinsql.postgresql.server.PgServer;

public class PgTestBase extends SqlTestBase {
    @Before
    @Override
    public void setUpBefore() {
        try {
            conn = getPgConnection();
            stmt = conn.createStatement();
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }

    public static Connection getPgConnection() throws Exception {
        String url = "jdbc:postgresql://localhost:" + PgServer.DEFAULT_PORT + "/postgres";
        Properties info = new Properties();
        info.put("user", "postgres");
        info.put("password", "postgres");
        return DriverManager.getConnection(url, info);
    }

    public static void sqlException(SQLException e) {
        while (e != null) {
            System.err.println("SQLException:" + e);
            System.err.println("-----------------------------------");
            System.err.println("Message  : " + e.getMessage());
            System.err.println("SQLState : " + e.getSQLState());
            System.err.println("ErrorCode: " + e.getErrorCode());
            System.err.println();
            System.err.println();
            e = e.getNextException();
        }
    }
}