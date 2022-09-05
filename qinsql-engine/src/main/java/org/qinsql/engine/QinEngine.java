/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.qinsql.engine;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.lealone.common.exceptions.ConfigException;
import org.lealone.common.logging.Logger;
import org.lealone.common.logging.LoggerFactory;
import org.lealone.common.util.CaseInsensitiveMap;
import org.lealone.common.util.ShutdownHookUtils;
import org.lealone.common.util.Utils;
import org.lealone.db.Constants;
import org.lealone.db.LealoneDatabase;
import org.lealone.db.PluggableEngine;
import org.lealone.db.PluginManager;
import org.lealone.db.SysProperties;
import org.lealone.main.Shell;
import org.lealone.net.NetNode;
import org.lealone.p2p.config.Config;
import org.lealone.p2p.config.Config.PluggableEngineDef;
import org.lealone.p2p.config.ConfigLoader;
import org.lealone.p2p.config.YamlConfigLoader;
import org.lealone.p2p.server.ClusterMetaData;
import org.lealone.p2p.server.P2pServerEngine;
import org.lealone.server.ProtocolServer;
import org.lealone.server.ProtocolServerEngine;
import org.lealone.server.TcpServerEngine;
import org.lealone.sql.SQLEngine;
import org.lealone.storage.StorageEngine;
import org.lealone.transaction.TransactionEngine;

public class QinEngine {

    private static final Logger logger = LoggerFactory.getLogger(QinEngine.class);

    public static SqlNode parse(String sql) throws SqlParseException {
        SqlParser.Config config = SqlParser.configBuilder()
                .setUnquotedCasing(org.apache.calcite.util.Casing.TO_LOWER).build();
        return parse(sql, config);
    }

    public static SqlNode parse(String sql, SqlParser.Config config) throws SqlParseException {
        SqlParser sqlParser = SqlParser.create(sql, config);
        return sqlParser.parseQuery();
    }

    public static void main(String[] args) {
        new QinEngine().start(args);
    }

    public static void embed(String[] args) {
        run(args, true, null);
    }

    // 外部调用者如果在独立的线程中启动Lealone，可以传递一个CountDownLatch等待Lealone启动就绪
    public static void run(String[] args, boolean embedded, CountDownLatch latch) {
        new QinEngine().run(embedded, latch);
    }

    private Config config;
    private String baseDir;
    private boolean isClusterMode;
    private String host;
    private String port;
    private String p2pHost;
    private String p2pPort;
    private String seeds;

    public void start(String[] args) {
        for (int i = 0; args != null && i < args.length; i++) {
            String arg = args[i].trim();
            if (arg.isEmpty())
                continue;
            if (arg.equals("-embed") || arg.equals("-client")) {
                Shell.main(args);
                return;
            } else if (arg.equals("-config")) {
                Config.setProperty("config", args[++i]);
            } else if (arg.equals("-cluster")) {
                isClusterMode = true;
            } else if (arg.equals("-host")) {
                host = args[++i];
            } else if (arg.equals("-port")) {
                port = args[++i];
            } else if (arg.equals("-p2pHost")) {
                p2pHost = args[++i];
            } else if (arg.equals("-p2pPort")) {
                p2pPort = args[++i];
            } else if (arg.equals("-baseDir")) {
                baseDir = args[++i];
            } else if (arg.equals("-seeds")) {
                seeds = args[++i];
            } else if (arg.equals("-help") || arg.equals("-?")) {
                showUsage();
                return;
            } else {
                continue;
            }
        }
        run(false, null);
    }

    private void showUsage() {
        println();
        println("Options are case sensitive. Supported options are:");
        println("-------------------------------------------------");
        println("[-help] or [-?]         Print the list of options");
        println("[-baseDir <dir>]        Database base dir");
        println("[-config <file>]        The config file");
        println("[-host <host>]          Tcp server host");
        println("[-port <port>]          Tcp server port");
        println("[-p2pHost <host>]       P2p server host");
        println("[-p2pPort <port>]       P2p server port");
        println("[-seeds <nodes>]        The seed node list");
        println("[-cluster]              Cluster mode");
        println("[-embed]                Embedded mode");
        println("[-client]               Client mode");
        println();
        println("Client or embedded mode options:");
        println("-------------------------------------------------");
    }

    private void println() {
        System.out.println();
    }

    private void println(String s) {
        System.out.println(s);
    }

    private void run(boolean embedded, CountDownLatch latch) {
        logger.info("QinSQL version: {}", Constants.RELEASE_VERSION);

        try {
            long t = System.currentTimeMillis();

            loadConfig();

            long t1 = (System.currentTimeMillis() - t);
            t = System.currentTimeMillis();

            beforeInit();
            init();
            afterInit(config);

            long t2 = (System.currentTimeMillis() - t);
            t = System.currentTimeMillis();

            if (embedded) {
                if (latch != null)
                    latch.countDown();
                return;
            }

            // ProtocolServer mainProtocolServer = startProtocolServers();

            startProtocolServers();

            long t3 = (System.currentTimeMillis() - t);
            long totalTime = t1 + t2 + t3;
            logger.info("Total time: {} ms (Load config: {} ms, Init: {} ms, Start: {} ms)", totalTime,
                    t1, t2, t3);
            logger.info("Exit with Ctrl+C");

            if (latch != null)
                latch.countDown();

            Thread thread = Thread.currentThread();
            if (thread.getName().equals("main"))
                thread.setName("CheckpointService");
            TransactionEngine te = PluginManager.getPlugin(TransactionEngine.class,
                    Constants.DEFAULT_TRANSACTION_ENGINE_NAME);
            te.getRunnable().run();

            // 在主线程中运行，避免出现DestroyJavaVM线程
            // if (mainProtocolServer != null)
            // mainProtocolServer.getRunnable().run();
        } catch (Exception e) {
            logger.error("Fatal error: unable to start lealone. See log for stacktrace.", e);
            System.exit(1);
        }
    }

    protected void beforeInit() {
    }

    protected void afterInit(Config config) {
    }

    private void loadConfig() {
        ConfigLoader loader;
        String loaderClass = Config.getProperty("config.loader");
        if (loaderClass != null
                && QinEngine.class.getResource("/" + loaderClass.replace('.', '/') + ".class") != null) {
            loader = Utils.construct(loaderClass, "configuration loading");
        } else {
            loader = new YamlConfigLoader();
        }
        Config config = loader.loadConfig();
        config = Config.mergeDefaultConfig(config);
        if (host != null || port != null) {
            if (host != null)
                config.listen_address = host;
            for (PluggableEngineDef e : config.protocol_server_engines) {
                if (TcpServerEngine.NAME.equalsIgnoreCase(e.name)) {
                    if (host != null)
                        e.parameters.put("host", host);
                    if (port != null)
                        e.parameters.put("port", port);
                }
            }
        }
        if (baseDir != null)
            config.base_dir = baseDir;
        if (isClusterMode) {
            if (baseDir == null
                    && NetNode.createTCP(config.listen_address).geInetAddress().isLoopbackAddress()) {
                String nodeId = config.listen_address.replace('.', '_');
                config.base_dir = config.base_dir + File.separator + "cluster" + File.separator + "node_"
                        + nodeId;
            }
            for (PluggableEngineDef e : config.protocol_server_engines) {
                if (P2pServerEngine.NAME.equalsIgnoreCase(e.name)) {
                    e.enabled = true;
                    if (p2pHost != null)
                        e.parameters.put("host", p2pHost);
                    if (p2pPort != null)
                        e.parameters.put("port", p2pPort);
                }
            }
        }
        if (seeds != null) {
            config.cluster_config.seed_provider.parameters.put("seeds", seeds);
        }
        loader.applyConfig(config);
        this.config = config;
    }

    private void init() {
        initBaseDir();
        initPluggableEngines();

        long t1 = System.currentTimeMillis();
        LealoneDatabase.getInstance(); // 提前触发对LealoneDatabase的初始化
        long t2 = System.currentTimeMillis();
        logger.info("Init lealone database: " + (t2 - t1) + " ms");

        // 如果启用了集群，集群的元数据表通过嵌入式的方式访问
        if (config.protocol_server_engines != null) {
            for (PluggableEngineDef def : config.protocol_server_engines) {
                if (def.enabled && P2pServerEngine.NAME.equalsIgnoreCase(def.name)) {
                    ClusterMetaData.init(LealoneDatabase.getInstance().getInternalConnection());
                    break;
                }
            }
        }
    }

    private void initBaseDir() {
        if (config.base_dir == null || config.base_dir.isEmpty())
            throw new ConfigException("base_dir must be specified and not empty");
        SysProperties.setBaseDir(config.base_dir);

        logger.info("Base dir: {}", config.base_dir);
    }

    // 严格按这样的顺序初始化: storage -> transaction -> sql -> protocol_server
    private void initPluggableEngines() {
        initStorageEngineEngines();
        initTransactionEngineEngines();
        initSQLEngines();
        initProtocolServerEngines();
    }

    private void initStorageEngineEngines() {
        registerAndInitEngines(config.storage_engines, "storage", "default.storage.engine", def -> {
            StorageEngine se = PluginManager.getPlugin(StorageEngine.class, def.name);
            if (se == null) {
                se = Utils.newInstance(def.name);
                PluginManager.register(se);
            }
            return se;
        });
    }

    private void initTransactionEngineEngines() {
        registerAndInitEngines(config.transaction_engines, "transaction", "default.transaction.engine",
                def -> {
                    TransactionEngine te;
                    try {
                        te = PluginManager.getPlugin(TransactionEngine.class, def.name);
                        if (te == null) {
                            te = Utils.newInstance(def.name);
                            PluginManager.register(te);
                        }
                    } catch (Throwable e) {
                        te = PluginManager.getPlugin(TransactionEngine.class,
                                Constants.DEFAULT_TRANSACTION_ENGINE_NAME);
                        if (te == null) {
                            throw e;
                        }
                        logger.warn("Transaction engine " + def.name + " not found, use " + te.getName()
                                + " instead");
                    }
                    return te;
                });
    }

    private void initSQLEngines() {
        registerAndInitEngines(config.sql_engines, "sql", "default.sql.engine", def -> {
            SQLEngine se = PluginManager.getPlugin(SQLEngine.class, def.name);
            if (se == null) {
                se = Utils.newInstance(def.name);
                PluginManager.register(se);
            }
            return se;
        });
    }

    private void initProtocolServerEngines() {
        registerAndInitEngines(config.protocol_server_engines, "protocol server", null, def -> {
            // 如果ProtocolServer的配置参数中没有指定host，那么就取listen_address的值
            if (!def.getParameters().containsKey("host") && config.listen_address != null)
                def.getParameters().put("host", config.listen_address);
            ProtocolServerEngine pse = PluginManager.getPlugin(ProtocolServerEngine.class, def.name);
            if (pse == null) {
                pse = Utils.newInstance(def.name);
                PluginManager.register(pse);
            }
            return pse;
        });
    }

    private static interface CallableTask<V> {
        V call(PluggableEngineDef def) throws Exception;
    }

    private <T> void registerAndInitEngines(List<PluggableEngineDef> engines, String name,
            String defaultEngineKey, CallableTask<T> callableTask) {
        long t1 = System.currentTimeMillis();
        if (engines != null) {
            name += " engine";
            for (PluggableEngineDef def : engines) {
                if (!def.enabled)
                    continue;

                // 允许后续的访问不用区分大小写
                CaseInsensitiveMap<String> parameters = new CaseInsensitiveMap<>(def.getParameters());
                def.setParameters(parameters);

                checkName(name, def);
                T result;
                try {
                    result = callableTask.call(def);
                } catch (Throwable e) {
                    String msg = "Failed to register " + name + ": " + def.name;
                    checkException(msg, e);
                    return;
                }
                PluggableEngine pe = (PluggableEngine) result;
                if (defaultEngineKey != null && Config.getProperty(defaultEngineKey) == null)
                    Config.setProperty(defaultEngineKey, pe.getName());
                try {
                    initPluggableEngine(pe, def);
                } catch (Throwable e) {
                    String msg = "Failed to init " + name + ": " + def.name;
                    checkException(msg, e);
                }
            }
        }
        long t2 = System.currentTimeMillis();
        logger.info("Init " + name + "s" + ": " + (t2 - t1) + " ms");
    }

    private static void checkException(String msg, Throwable e) {
        if (e instanceof ConfigException)
            throw (ConfigException) e;
        else if (e instanceof RuntimeException)
            throw new ConfigException(msg, e);
        else
            logger.warn(msg, e);
    }

    private static void checkName(String engineName, PluggableEngineDef def) {
        if (def.name == null || def.name.trim().isEmpty())
            throw new ConfigException(engineName + " name is missing.");
    }

    private void initPluggableEngine(PluggableEngine pe, PluggableEngineDef def) {
        Map<String, String> parameters = def.getParameters();
        if (!parameters.containsKey("base_dir"))
            parameters.put("base_dir", config.base_dir);
        pe.init(parameters);
    }

    private ProtocolServer startProtocolServers() throws Exception {
        ProtocolServer mainProtocolServer = null;
        if (config.protocol_server_engines != null) {
            for (PluggableEngineDef def : config.protocol_server_engines) {
                if (def.enabled) {
                    ProtocolServerEngine pse = PluginManager.getPlugin(ProtocolServerEngine.class,
                            def.name);
                    ProtocolServer protocolServer = pse.getProtocolServer();
                    startProtocolServer(protocolServer);
                }
            }
        }
        return mainProtocolServer;
    }

    private void startProtocolServer(final ProtocolServer server) throws Exception {
        server.setServerEncryptionOptions(config.server_encryption_options);
        server.start();
        final String name = server.getName();
        ShutdownHookUtils.addShutdownHook(server, () -> {
            server.stop();
            logger.info(name + " stopped");
        });
        logger.info(name + " started, host: {}, port: {}", server.getHost(), server.getPort());
    }
}
