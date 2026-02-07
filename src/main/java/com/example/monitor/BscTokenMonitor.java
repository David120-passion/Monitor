package com.example.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/**
 * 程序入口
 */
public class BscTokenMonitor {
    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(BscTokenMonitor.class);
    /** 配置文件路径 */
    private static final String CONFIG_FILE = "/application.properties";

    /**
     * 加载配置文件
     */
    private static Properties loadConfig() {
        Properties props = new Properties();
        try (InputStream input = BscTokenMonitor.class.getResourceAsStream(CONFIG_FILE)) {
            if (input == null) {
                log.warn("配置文件 {} 未找到，将使用默认值或命令行参数", CONFIG_FILE);
                return props;
            }
            props.load(input);
            log.info("成功加载配置文件: {}", CONFIG_FILE);
        } catch (Exception e) {
            log.error("加载配置文件失败: {}", CONFIG_FILE, e);
        }
        return props;
    }

    /**
     * 从环境变量、命令行参数或配置文件中获取配置值
     * 优先级：命令行参数 > 环境变量 > 配置文件 > 默认值
     */
    private static String getConfigValue(String[] args, String argName, Properties props, String propKey, String defaultValue) {
        // 优先使用命令行参数
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--" + argName) || args[i].equals("-" + argName.charAt(0))) {
                return args[i + 1];
            }
        }
        // 其次使用环境变量（转换为大写，下划线分隔）
        String envKey = propKey.toUpperCase().replace(".", "_");
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue.trim();
        }
        // 再次使用配置文件
        String value = props.getProperty(propKey);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        // 最后使用默认值
        return defaultValue;
    }

    /**
     * 程序入口函数
     *
     * @param args 命令行参数:
     *             --rpc-endpoint <url> 或 -r <url> : RPC 端点
     *             --token-address <address> 或 -t <address> : 代币地址
     *             --token-creation-block <block> 或 -b <block> : 代币创建区块号
     *             --db-host <host> : 数据库主机
     *             --db-port <port> : 数据库端口
     *             --db-name <name> : 数据库名称
     *             --db-username <username> : 数据库用户名
     *             --db-password <password> : 数据库密码
     *             --load-history-from-db <true/false> : 是否从数据库加载历史数据
     * @throws InterruptedException 中断异常
     */
    public static void main(String[] args) throws InterruptedException {
        // 加载配置文件
        Properties config = loadConfig();

        // 读取配置（优先级：命令行参数 > 配置文件 > 默认值）
        String rpcEndpoint = getConfigValue(args, "rpc-endpoint", config, "rpc.endpoint", 
                "https://bsc-mainnet.nodereal.io/v1/9613675572f24988819d24233504b290");
        String tokenAddress = getConfigValue(args, "token-address", config, "token.address", 
                "0xfe930c2d63aed9b82fc4dbc801920dd2c1a3224f");
        String tokenCreationBlock = getConfigValue(args, "token-creation-block", config, "token.creation.block", 
                "70418263");
        String dbHost = getConfigValue(args, "db-host", config, "database.host", "127.0.0.1");
        String dbPort = getConfigValue(args, "db-port", config, "database.port", "13306");
        String dbName = getConfigValue(args, "db-name", config, "database.name", "monitor");
        String dbUsername = getConfigValue(args, "db-username", config, "database.username", "root");
        String dbPassword = getConfigValue(args, "db-password", config, "database.password", "cljslrl0620");
        String loadHistoryFromDb = getConfigValue(args, "load-history-from-db", config, "load.history.from.database", "false");

        // 打印配置信息
        log.info("=== 配置信息 ===");
        log.info("RPC 端点: {}", rpcEndpoint);
        log.info("代币地址: {}", tokenAddress);
        log.info("代币创建区块: {}", tokenCreationBlock);
        log.info("数据库: {}:{}@{}", dbUsername, "***", dbHost + ":" + dbPort + "/" + dbName);
        log.info("从数据库加载历史数据: {}", loadHistoryFromDb);
        log.info("================");

        Web3j web3j = Web3j.build(new HttpService(rpcEndpoint));
        TokenInfoService tokenInfoService = new TokenInfoService(web3j);
        BigInteger decimals = tokenInfoService.loadDecimals(tokenAddress).orElse(BigInteger.valueOf(18));
        String symbol = tokenInfoService.loadSymbol(tokenAddress).orElse("TOKEN");
        log.info("Starting monitor for token={} decimals={}", symbol, decimals);

        // 创建数据库服务
        int dbPortInt;
        try {
            dbPortInt = Integer.parseInt(dbPort);
        } catch (NumberFormatException e) {
            log.error("无效的数据库端口: {}", dbPort);
            return;
        }
        DatabaseLogService databaseLogService = new DatabaseLogService(dbHost, dbPortInt, dbName, dbUsername, dbPassword);
        
        // 启动时归档所有表并清空
        log.info("开始归档数据库表...");
        databaseLogService.archiveAndClearAllTables();
        log.info("数据库表归档完成");
        
        DexPriceService priceService = new DexPriceService(web3j, tokenAddress, decimals, symbol);

        TradeAnalysisService tradeAnalysisService = new TradeAnalysisService(web3j, tokenAddress, decimals, symbol,
                priceService, databaseLogService);
        
        BigInteger creationBlock;
        try {
            creationBlock = new BigInteger(tokenCreationBlock);
        } catch (NumberFormatException e) {
            log.error("无效的代币创建区块号: {}", tokenCreationBlock);
            return;
        }
        
        boolean loadHistory = Boolean.parseBoolean(loadHistoryFromDb);
        LiquidityMonitorService liquidityMonitorService = new LiquidityMonitorService(web3j, tokenAddress, priceService,
                tradeAnalysisService, creationBlock, databaseLogService, loadHistory);
        liquidityMonitorService.registerInitialPairs();
        log.info("v2 v3池子初始化完成");
        liquidityMonitorService.start();
        log.info("流动性监控服务已启动");
        // 添加关闭钩子，确保程序退出时优雅关闭数据库服务
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down database service...");
            databaseLogService.shutdown();
            log.info("Database service shutdown complete.");
        }));

        CountDownLatch latch = new CountDownLatch(1);
        latch.await();
    }
}

/**

 */
