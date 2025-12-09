package com.example.monitor;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 简单的数据库日志服务，负责将关键业务日志写入 MySQL 数据库。
 */
public class DatabaseLogService {
    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(DatabaseLogService.class);
    /** 数据源 */
    private final DataSource dataSource;
    /** 异步执行数据库操作的线程池 */
    private final ExecutorService dbExecutor;

    /**
     * 使用默认连接信息初始化 MySQL 数据库。
     *
     * @param host     数据库主机地址
     * @param port     数据库端口
     * @param database 数据库名称
     * @param username 用户名
     * @param password 密码
     */
    public DatabaseLogService(String host, int port, String database, String username, String password) {
        MysqlDataSource ds = new MysqlDataSource();
        try {
            ds.setServerName(host);
            ds.setPortNumber(port);
            ds.setDatabaseName(database);
            ds.setUser(username);
            ds.setPassword(password);
            ds.setUseSSL(false);
            ds.setAllowPublicKeyRetrieval(true);
            ds.setServerTimezone("UTC");
        } catch (SQLException ex) {
            log.error("Failed to configure MySQL data source", ex);
            throw new RuntimeException("Failed to initialize database connection", ex);
        }
        this.dataSource = ds;
        this.dbExecutor = Executors.newFixedThreadPool(4, new ThreadFactory() {
            private int counter = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "db-log-writer-" + (++counter));
                thread.setDaemon(true);
                return thread;
            }
        });
        initializeSchema();
    }

    /**
     * 归档所有表并清空原表
     * 在每次启动时调用，将现有数据归档到带时间戳的表中，然后清空原表
     */
    public void archiveAndClearAllTables() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            
            // 获取所有需要归档的表名
            List<String> tablesToArchive = getTablesToArchive(connection);
            
            if (tablesToArchive.isEmpty()) {
                log.info("没有需要归档的表");
                return;
            }
            
            // 生成归档表名的时间戳后缀（格式：2025-12-09_19-14）
            String timestampSuffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
            
            log.info("开始归档 {} 个表，时间戳后缀: {}", tablesToArchive.size(), timestampSuffix);
            
            int successCount = 0;
            int failCount = 0;
            
            for (String tableName : tablesToArchive) {
                try {
                    // 检查表是否存在且有数据
                    if (!tableExists(connection, tableName)) {
                        log.debug("表 {} 不存在，跳过", tableName);
                        continue;
                    }
                    
                    long rowCount = getTableRowCount(connection, tableName);
                    if (rowCount == 0) {
                        log.debug("表 {} 为空，跳过归档", tableName);
                        continue;
                    }
                    
                    // 生成归档表名（例如：2025-12-09_19-14-pair_created_logs）
                    String archiveTableName = timestampSuffix + "-" + tableName;
                    
                    // 创建归档表（复制表结构）
                    String createArchiveTableSql = String.format(
                        "CREATE TABLE IF NOT EXISTS `%s` LIKE `%s`",
                        archiveTableName, tableName
                    );
                    statement.execute(createArchiveTableSql);
                    log.debug("创建归档表: {}", archiveTableName);
                    
                    // 复制数据到归档表
                    String copyDataSql = String.format(
                        "INSERT INTO `%s` SELECT * FROM `%s`",
                        archiveTableName, tableName
                    );
                    int copiedRows = statement.executeUpdate(copyDataSql);
                    log.info("表 {} 归档完成: {} 行数据已复制到 {}", tableName, copiedRows, archiveTableName);
                    
                    // 清空原表
                    String truncateSql = String.format("TRUNCATE TABLE `%s`", tableName);
                    statement.execute(truncateSql);
                    log.info("表 {} 已清空", tableName);
                    
                    successCount++;
                    
                } catch (SQLException e) {
                    log.error("归档表 {} 失败", tableName, e);
                    failCount++;
                }
            }
            
            log.info("表归档完成: 成功 {} 个，失败 {} 个", successCount, failCount);
            
        } catch (SQLException e) {
            log.error("归档表时发生错误", e);
        }
    }
    
    /**
     * 获取需要归档的表名列表
     */
    private List<String> getTablesToArchive(Connection connection) {
        // 定义所有需要归档的表名
        List<String> tables = Arrays.asList(
            "pair_created_logs",
            "pool_created_logs",
            "v4_initialize_logs",
            "v4_modify_liquidity_logs",
            "burn_logs_v2",
            "burn_logs_v3",
            "mint_logs_v2",
            "mint_logs_v3",
            "trade_logs",
            "pool_registration_logs",
            "transfer_logs"
        );
        
        // 过滤出实际存在的表
        List<String> existingTables = new ArrayList<>();
        for (String table : tables) {
            if (tableExists(connection, table)) {
                existingTables.add(table);
            }
        }
        
        return existingTables;
    }
    
    /**
     * 检查表是否存在
     */
    private boolean tableExists(Connection connection, String tableName) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getTables(null, null, tableName, new String[]{"TABLE"})) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("检查表是否存在时出错: {}", tableName, e);
            return false;
        }
    }
    
    /**
     * 获取表的行数
     */
    private long getTableRowCount(Connection connection, String tableName) {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM `" + tableName + "`")) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.error("获取表 {} 行数时出错", tableName, e);
        }
        return 0;
    }

    /**
     * 关闭数据库服务，优雅关闭线程池
     */
    public void shutdown() {
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.shutdown();
            try {
                if (!dbExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    dbExecutor.shutdownNow();
                    if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.warn("Database executor did not terminate gracefully");
                    }
                }
            } catch (InterruptedException e) {
                dbExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 初始化数据库表结构。
     */
    private void initializeSchema() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS pair_created_logs (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "pair_address VARCHAR(128) NOT NULL, " +
                    "swap VARCHAR(128), " +
                    "name VARCHAR(256), " +
                    "token0 VARCHAR(128), " +
                    "token1 VARCHAR(128), " +
                    "liquidity DECIMAL(50, 0), " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS pool_created_logs (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "pool_address VARCHAR(128) NOT NULL, " +
                    "swap VARCHAR(128), " +
                    "name VARCHAR(256), " +
                    "token0 VARCHAR(128), " +
                    "token1 VARCHAR(128), " +
                    "fee INTEGER, " +
                    "tick_spacing INTEGER, " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS v4_initialize_logs (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "pool_id VARCHAR(256) NOT NULL, " +
                    "swap VARCHAR(128), " +
                    "name VARCHAR(256), " +
                    "currency0 VARCHAR(128), " +
                    "currency1 VARCHAR(128), " +
                    "fee INTEGER, " +
                    "hooks VARCHAR(128), " +
                    "price_token1_per_token0 DECIMAL(38, 18), " +
                    "price_token0_per_token1 DECIMAL(38, 18), " +
                    "target_token_price DECIMAL(38, 18), " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS v4_modify_liquidity_logs (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "pool_id VARCHAR(256) NOT NULL, " +
                    "action VARCHAR(64) NOT NULL, " +
                    "swap VARCHAR(128), " +
                    "name VARCHAR(256), " +
                    "sender VARCHAR(128), " +
                    "fee INTEGER, " +
                    "liquidity_delta DECIMAL(50, 0), " +
                    "amount0_delta DECIMAL(38, 18), " +
                    "amount1_delta DECIMAL(38, 18), " +
                    "price_range_lower DECIMAL(65, 18), " +
                    "price_range_upper DECIMAL(65, 18), " +
                    "amount0_remaining DECIMAL(38, 18), " +
                    "amount1_remaining DECIMAL(38, 18), " +
                    "tvl_remaining DECIMAL(38, 18), " +
                    "salt VARCHAR(128), " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS burn_logs_v2 (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "pair_address VARCHAR(128) NOT NULL, " +
                    "name VARCHAR(256), " +
                    "sender VARCHAR(128), " +
                    "recipient VARCHAR(128), " +
                    "token0 VARCHAR(128), " +
                    "token1 VARCHAR(128), " +
                    "amount0 DECIMAL(38, 18), " +
                    "amount1 DECIMAL(38, 18), " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS burn_logs_v3 (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "pool_address VARCHAR(128) NOT NULL, " +
                    "name VARCHAR(256), " +
                    "owner VARCHAR(128), " +
                    "fee INTEGER, " +
                    "price_range_lower DECIMAL(65, 18), " +
                    "price_range_upper DECIMAL(65, 18), " +
                    "token0 VARCHAR(128), " +
                    "token1 VARCHAR(128), " +
                    "burned_liquidity DECIMAL(50, 0), " +
                    "amount0 DECIMAL(38, 18), " +
                    "amount1 DECIMAL(38, 18), " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS mint_logs_v2 (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "pair_address VARCHAR(128) NOT NULL, " +
                    "name VARCHAR(256), " +
                    "sender VARCHAR(128), " +
                    "token0 VARCHAR(128), " +
                    "token1 VARCHAR(128), " +
                    "amount0 DECIMAL(38, 18), " +
                    "amount1 DECIMAL(38, 18), " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS mint_logs_v3 (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "pool_address VARCHAR(128) NOT NULL, " +
                    "name VARCHAR(256), " +
                    "sender VARCHAR(128), " +
                    "owner VARCHAR(128), " +
                    "fee INTEGER, " +
                    "price_range_lower DECIMAL(65, 18), " +
                    "price_range_upper DECIMAL(65, 18), " +
                    "token0 VARCHAR(128), " +
                    "token1 VARCHAR(128), " +
                    "amount0 DECIMAL(38, 18), " +
                    "amount1 DECIMAL(38, 18), " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS trade_logs (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "tx_hash VARCHAR(128) NOT NULL, " +
                    "address VARCHAR(128) NOT NULL, " +
                    "action VARCHAR(32) NOT NULL, " +
                    "amount DECIMAL(38, 18), " +
                    "token_symbol VARCHAR(64), " +
                    "price DECIMAL(38, 18), " +
                    "notional_value DECIMAL(38, 18), " +
                    "total_buy_amount DECIMAL(38, 18), " +
                    "total_sell_amount DECIMAL(38, 18), " +
                    "total_buy_value DECIMAL(38, 18), " +
                    "total_sell_value DECIMAL(38, 18), " +
                    "net_amount DECIMAL(38, 18), " +
                    "net_value DECIMAL(38, 18), " +
                    "avg_buy_price DECIMAL(38, 18), " +
                    "avg_sell_price DECIMAL(38, 18), " +
                    "trade_count INTEGER, " +
                    "block_number BIGINT, " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS pool_registration_logs (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "pair_address VARCHAR(128) NOT NULL, " +
                    "swap VARCHAR(128), " +
                    "name VARCHAR(256), " +
                    "fee INTEGER, " +
                    "amount0 DECIMAL(38, 18), " +
                    "amount1 DECIMAL(38, 18), " +
                    "price_range_lower DECIMAL(65, 18), " +
                    "price_range_upper DECIMAL(65, 18), " +
                    "tvl DECIMAL(38, 18), " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS transfer_logs (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "tx_hash VARCHAR(128) NOT NULL, " +
                    "token_address VARCHAR(128) NOT NULL, " +
                    "from_address VARCHAR(128) NOT NULL, " +
                    "to_address VARCHAR(128) NOT NULL, " +
                    "amount DECIMAL(38, 18) NOT NULL, " +
                    "amount_raw DECIMAL(50, 0), " +
                    "log_index INTEGER, " +
                    "block_number BIGINT, " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            
        } catch (SQLException ex) {
            log.error("Failed to initialise database schema", ex);
        }
    }


    public void logPairCreated(String pairAddress,
                               String swap,
                               String name,
                               String token0,
                               String token1,
                               BigInteger liquidity,
                               Instant eventTime) {
        if (pairAddress == null) {
            return;
        }
        String sql = "INSERT INTO pair_created_logs (pair_address, swap, name, token0, token1, liquidity, event_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, pairAddress);
            ps.setString(2, swap);
            ps.setString(3, name);
            ps.setString(4, token0);
            ps.setString(5, token1);
            setBigInteger(ps, 6, liquidity);
            setTimestamp(ps, 7, eventTime);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.error("Failed to persist pair created log for {}", pairAddress, ex);
        }
    }

    public void logPoolCreated(String poolAddress,
                               String swap,
                               String name,
                               String token0,
                               String token1,
                               BigInteger fee,
                               BigInteger tickSpacing,
                               Instant eventTime) {
        if (poolAddress == null) {
            return;
        }
        String sql = "INSERT INTO pool_created_logs (pool_address, swap, name, token0, token1, fee, tick_spacing, event_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, poolAddress);
            ps.setString(2, swap);
            ps.setString(3, name);
            ps.setString(4, token0);
            ps.setString(5, token1);
            setInteger(ps, 6, fee);
            setInteger(ps, 7, tickSpacing);
            setTimestamp(ps, 8, eventTime);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.error("Failed to persist pool created log for {}", poolAddress, ex);
        }
    }

    public void logV4Initialize(String poolId,
                                String swap,
                                String name,
                                String currency0,
                                String currency1,
                                BigInteger fee,
                                String hooks,
                                BigDecimal priceToken1PerToken0,
                                BigDecimal priceToken0PerToken1,
                                BigDecimal targetTokenPrice,
                                Instant eventTime) {
        if (poolId == null) {
            return;
        }
        String sql = "INSERT INTO v4_initialize_logs (pool_id, swap, name, currency0, currency1, fee, hooks, " +
                "price_token1_per_token0, price_token0_per_token1, target_token_price, event_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, poolId);
            ps.setString(2, swap);
            ps.setString(3, name);
            ps.setString(4, currency0);
            ps.setString(5, currency1);
            setInteger(ps, 6, fee);
            ps.setString(7, hooks);
            setBigDecimal(ps, 8, priceToken1PerToken0);
            setBigDecimal(ps, 9, priceToken0PerToken1);
            setBigDecimal(ps, 10, targetTokenPrice);
            setTimestamp(ps, 11, eventTime);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.error("Failed to persist v4 initialize log for {}", poolId, ex);
        }
    }

    public void logV4ModifyLiquidity(String poolId,
                                     String action,
                                     String swap,
                                     String name,
                                     String sender,
                                     BigInteger fee,
                                     BigInteger liquidityDelta,
                                     BigDecimal amount0Delta,
                                     BigDecimal amount1Delta,
                                     BigDecimal priceRangeLower,
                                     BigDecimal priceRangeUpper,
                                     BigDecimal amount0Remaining,
                                     BigDecimal amount1Remaining,
                                     BigDecimal tvlRemaining,
                                     String salt,
                                     Instant eventTime) {
        if (poolId == null || action == null) {
            return;
        }
        String sql = "INSERT INTO v4_modify_liquidity_logs (pool_id, action, swap, name, sender, fee, liquidity_delta, amount0_delta, " +
                "amount1_delta, price_range_lower, price_range_upper, amount0_remaining, amount1_remaining, tvl_remaining, salt, event_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, poolId);
            ps.setString(2, action);
            ps.setString(3, swap);
            ps.setString(4, name);
            ps.setString(5, sender);
            setInteger(ps, 6, fee);
            setBigInteger(ps, 7, liquidityDelta);
            setBigDecimal(ps, 8, amount0Delta);
            setBigDecimal(ps, 9, amount1Delta);
            setBigDecimal(ps, 10, priceRangeLower);
            setBigDecimal(ps, 11, priceRangeUpper);
            setBigDecimal(ps, 12, amount0Remaining);
            setBigDecimal(ps, 13, amount1Remaining);
            setBigDecimal(ps, 14, tvlRemaining);
            ps.setString(15, salt);
            setTimestamp(ps, 16, eventTime);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.error("Failed to persist v4 modify liquidity log for {}", poolId, ex);
        }
    }

    public void logBurnV2(String pairAddress,
                          String name,
                          String sender,
                          String recipient,
                          String token0,
                          String token1,
                          BigDecimal amount0,
                          BigDecimal amount1,
                          Instant eventTime) {
        if (pairAddress == null) {
            return;
        }
        String sql = "INSERT INTO burn_logs_v2 (pair_address, name, sender, recipient, token0, token1, amount0, amount1, event_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, pairAddress);
            ps.setString(2, name);
            ps.setString(3, sender);
            ps.setString(4, recipient);
            ps.setString(5, token0);
            ps.setString(6, token1);
            setBigDecimal(ps, 7, amount0);
            setBigDecimal(ps, 8, amount1);
            setTimestamp(ps, 9, eventTime);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.error("Failed to persist burn v2 log for {}", pairAddress, ex);
        }
    }

    public void logBurnV3(String poolAddress,
                          String name,
                          String owner,
                          BigInteger fee,
                          BigDecimal priceRangeLower,
                          BigDecimal priceRangeUpper,
                          String token0,
                          String token1,
                          BigInteger burnedLiquidity,
                          BigDecimal amount0,
                          BigDecimal amount1,
                          Instant eventTime) {
        if (poolAddress == null) {
            return;
        }
        String sql = "INSERT INTO burn_logs_v3 (pool_address, name, owner, fee, price_range_lower, price_range_upper, token0, token1, " +
                "burned_liquidity, amount0, amount1, event_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, poolAddress);
            ps.setString(2, name);
            ps.setString(3, owner);
            setInteger(ps, 4, fee);
            setBigDecimal(ps, 5, priceRangeLower);
            setBigDecimal(ps, 6, priceRangeUpper);
            ps.setString(7, token0);
            ps.setString(8, token1);
            setBigInteger(ps, 9, burnedLiquidity);
            setBigDecimal(ps, 10, amount0);
            setBigDecimal(ps, 11, amount1);
            setTimestamp(ps, 12, eventTime);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.error("Failed to persist burn v3 log for {}", poolAddress, ex);
        }
    }

    public void logMintV2(String pairAddress,
                          String name,
                          String sender,
                          String token0,
                          String token1,
                          BigDecimal amount0,
                          BigDecimal amount1,
                          Instant eventTime) {
        if (pairAddress == null) {
            return;
        }
        String sql = "INSERT INTO mint_logs_v2 (pair_address, name, sender, token0, token1, amount0, amount1, event_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, pairAddress);
            ps.setString(2, name);
            ps.setString(3, sender);
            ps.setString(4, token0);
            ps.setString(5, token1);
            setBigDecimal(ps, 6, amount0);
            setBigDecimal(ps, 7, amount1);
            setTimestamp(ps, 8, eventTime);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.error("Failed to persist mint v2 log for {}", pairAddress, ex);
        }
    }

    public void logMintV3(String poolAddress,
                          String name,
                          String sender,
                          String owner,
                          BigInteger fee,
                          BigDecimal priceRangeLower,
                          BigDecimal priceRangeUpper,
                          String token0,
                          String token1,
                          BigDecimal amount0,
                          BigDecimal amount1,
                          Instant eventTime) {
        if (poolAddress == null) {
            return;
        }
        String sql = "INSERT INTO mint_logs_v3 (pool_address, name, sender, owner, fee, price_range_lower, price_range_upper, token0, token1, amount0, amount1, event_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, poolAddress);
            ps.setString(2, name);
            ps.setString(3, sender);
            ps.setString(4, owner);
            setInteger(ps, 5, fee);
            setBigDecimal(ps, 6, priceRangeLower);
            setBigDecimal(ps, 7, priceRangeUpper);
            ps.setString(8, token0);
            ps.setString(9, token1);
            setBigDecimal(ps, 10, amount0);
            setBigDecimal(ps, 11, amount1);
            setTimestamp(ps, 12, eventTime);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.error("Failed to persist mint v3 log for {}", poolAddress, ex);
        }
    }

    public void logTradeSummary(String txHash,
                                String address,
                                String action,
                                BigDecimal amount,
                                String tokenSymbol,
                                BigDecimal price,
                                BigDecimal notionalValue,
                                BigDecimal totalBuyAmount,
                                BigDecimal totalSellAmount,
                                BigDecimal totalBuyValue,
                                BigDecimal totalSellValue,
                                BigDecimal netAmount,
                                BigDecimal netValue,
                                BigDecimal avgBuyPrice,
                                BigDecimal avgSellPrice,
                                Integer tradeCount,
                                BigInteger blockNumber,
                                Instant eventTime) {
        if (txHash == null || address == null || action == null) {
            return;
        }
        // 异步执行数据库操作
        dbExecutor.execute(() -> {
            String sql = "INSERT INTO trade_logs (tx_hash, address, action, amount, token_symbol, price, notional_value, total_buy_amount, total_sell_amount, total_buy_value, total_sell_value, net_amount, net_value, avg_buy_price, avg_sell_price, trade_count, block_number, event_time) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, txHash);
                ps.setString(2, address);
                ps.setString(3, action);
                setBigDecimal(ps, 4, amount);
                ps.setString(5, tokenSymbol);
                setBigDecimal(ps, 6, price);
                setBigDecimal(ps, 7, notionalValue);
                setBigDecimal(ps, 8, totalBuyAmount);
                setBigDecimal(ps, 9, totalSellAmount);
                setBigDecimal(ps, 10, totalBuyValue);
                setBigDecimal(ps, 11, totalSellValue);
                setBigDecimal(ps, 12, netAmount);
                setBigDecimal(ps, 13, netValue);
                setBigDecimal(ps, 14, avgBuyPrice);
                setBigDecimal(ps, 15, avgSellPrice);
                setInteger(ps, 16, tradeCount);
                setBigInteger(ps, 17, blockNumber);
                setTimestamp(ps, 18, eventTime);
                ps.executeUpdate();
            } catch (SQLException ex) {
                log.error("Failed to persist trade log for {}", txHash, ex);
            }
        });
    }

    public void logPoolRegistration(String pairAddress,
                                    String swap,
                                    String name,
                                    BigInteger fee,
                                    BigDecimal amount0,
                                    BigDecimal amount1,
                                    BigDecimal priceRangeLower,
                                    BigDecimal priceRangeUpper,
                                    BigDecimal tvl,
                                    Instant eventTime) {
        if (pairAddress == null) {
            return;
        }
        String sql = "INSERT INTO pool_registration_logs (pair_address, swap, name, fee, amount0, amount1, price_range_lower, price_range_upper, tvl, event_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, pairAddress);
            ps.setString(2, swap);
            ps.setString(3, name);
            setInteger(ps, 4, fee);
            setBigDecimal(ps, 5, amount0);
            setBigDecimal(ps, 6, amount1);
            setBigDecimal(ps, 7, priceRangeLower);
            setBigDecimal(ps, 8, priceRangeUpper);
            setBigDecimal(ps, 9, tvl);
            setTimestamp(ps, 10, eventTime);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.error("Failed to persist pool registration log for {}", pairAddress, ex);
        }
    }

    /**
     * 记录 Transfer 事件（异步执行）
     *
     * @param txHash       交易哈希
     * @param tokenAddress 代币地址
     * @param fromAddress  发送方地址
     * @param toAddress    接收方地址
     * @param amount       转账金额（已转换精度）
     * @param amountRaw    原始金额（未转换精度）
     * @param logIndex     日志索引
     * @param blockNumber  区块号
     * @param eventTime    事件时间
     */
    public void logTransfer(String txHash,
                           String tokenAddress,
                           String fromAddress,
                           String toAddress,
                           BigDecimal amount,
                           BigInteger amountRaw,
                           Integer logIndex,
                           BigInteger blockNumber,
                           Instant eventTime) {
        if (txHash == null || tokenAddress == null || fromAddress == null || toAddress == null || amount == null) {
            return;
        }
        // 异步执行数据库操作
        dbExecutor.execute(() -> {
            String sql = "INSERT INTO transfer_logs (tx_hash, token_address, from_address, to_address, amount, amount_raw, log_index, block_number, event_time) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, txHash);
                ps.setString(2, tokenAddress);
                ps.setString(3, fromAddress);
                ps.setString(4, toAddress);
                setBigDecimal(ps, 5, amount);
                setBigInteger(ps, 6, amountRaw);
                setInteger(ps, 7, logIndex);
                setBigInteger(ps, 8, blockNumber);
                setTimestamp(ps, 9, eventTime);
                ps.executeUpdate();
            } catch (SQLException ex) {
                log.error("Failed to persist transfer log for tx {}", txHash, ex);
            }
        });
    }

    private void setBigInteger(PreparedStatement ps, int index, BigInteger value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DECIMAL);
        } else {
            ps.setBigDecimal(index, new BigDecimal(value));
        }
    }

    private void setInteger(PreparedStatement ps, int index, BigInteger value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value.intValue());
        }
    }

    private void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private void setBigDecimal(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DECIMAL);
        } else {
            ps.setBigDecimal(index, value);
        }
    }

    private void setTimestamp(PreparedStatement ps, int index, Instant instant) throws SQLException {
        if (instant == null) {
            ps.setTimestamp(index, null);
        } else {
            ps.setTimestamp(index, Timestamp.from(instant));
        }
    }

    /**
     * V4 池子初始化数据
     */
    public static class V4InitializeData {
        public final String poolId;
        public final String swap;
        public final String name;
        public final String currency0;
        public final String currency1;
        public final BigInteger fee;
        public final String hooks;

        public V4InitializeData(String poolId, String swap, String name, String currency0, String currency1,
                                BigInteger fee, String hooks) {
            this.poolId = poolId;
            this.swap = swap;
            this.name = name;
            this.currency0 = currency0;
            this.currency1 = currency1;
            this.fee = fee;
            this.hooks = hooks;
        }
    }

    /**
     * 从数据库加载所有 V4 池子的初始化数据
     *
     * @param targetTokenAddress 目标代币地址（可选，如果提供则只加载包含该代币的池子）
     * @return V4 池子初始化数据列表
     */
    public List<V4InitializeData> loadV4InitializeData(String targetTokenAddress) {
        List<V4InitializeData> results = new ArrayList<>();
        String sql;
        
        if (targetTokenAddress != null && !targetTokenAddress.isEmpty()) {
            // 如果提供了目标代币地址，只加载包含该代币的池子
            sql = "SELECT DISTINCT pool_id, swap, name, currency0, currency1, fee, hooks " +
                    "FROM v4_initialize_logs " +
                    "WHERE LOWER(currency0) = LOWER(?) OR LOWER(currency1) = LOWER(?) " +
                    "ORDER BY event_time DESC";
        } else {
            // 加载所有池子
            sql = "SELECT DISTINCT pool_id, swap, name, currency0, currency1, fee, hooks " +
                    "FROM v4_initialize_logs " +
                    "ORDER BY event_time DESC";
        }
        
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (targetTokenAddress != null && !targetTokenAddress.isEmpty()) {
                statement.setString(1, targetTokenAddress);
                statement.setString(2, targetTokenAddress);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String poolId = rs.getString("pool_id");
                    String swap = rs.getString("swap");
                    String name = rs.getString("name");
                    String currency0 = rs.getString("currency0");
                    String currency1 = rs.getString("currency1");
                    BigInteger fee = rs.getObject("fee") != null ? BigInteger.valueOf(rs.getInt("fee")) : null;
                    String hooks = rs.getString("hooks");
                    
                    if (poolId != null && currency0 != null && currency1 != null) {
                        results.add(new V4InitializeData(poolId, swap, name, currency0, currency1, fee, hooks));
                    }
                }
            }
        } catch (SQLException ex) {
            log.error("Failed to load V4 initialize data from database", ex);
        }
        return results;
    }
}

