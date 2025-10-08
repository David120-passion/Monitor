package com.example.monitor;

import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 简单的数据库日志服务，负责将关键业务日志写入 H2 数据库。
 */
public class DatabaseLogService {
    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(DatabaseLogService.class);
    /** 数据源 */
    private final DataSource dataSource;

    /**
     * 使用默认账号密码初始化 H2 数据库。
     *
     * @param url 数据库连接 URL
     */
    public DatabaseLogService(String url) {
        this(url, "sa", "");
    }

    /**
     * 指定连接信息初始化 H2 数据库。
     *
     * @param url      数据库连接 URL
     * @param username 用户名
     * @param password 密码
     */
    public DatabaseLogService(String url, String username, String password) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        ds.setUser(username);
        ds.setPassword(password);
        this.dataSource = ds;
        initializeSchema();
    }

    /**
     * 初始化数据库表结构。
     */
    private void initializeSchema() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS pair_created_logs (" +
                    "id IDENTITY PRIMARY KEY, " +
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
                    "id IDENTITY PRIMARY KEY, " +
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
                    "id IDENTITY PRIMARY KEY, " +
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
                    "id IDENTITY PRIMARY KEY, " +
                    "pool_id VARCHAR(256) NOT NULL, " +
                    "action VARCHAR(64) NOT NULL, " +
                    "swap VARCHAR(128), " +
                    "name VARCHAR(256), " +
                    "sender VARCHAR(128), " +
                    "fee INTEGER, " +
                    "liquidity_delta DECIMAL(50, 0), " +
                    "amount0_delta DECIMAL(38, 18), " +
                    "amount1_delta DECIMAL(38, 18), " +
                    "price_range_lower DECIMAL(38, 18), " +
                    "price_range_upper DECIMAL(38, 18), " +
                    "amount0_remaining DECIMAL(38, 18), " +
                    "amount1_remaining DECIMAL(38, 18), " +
                    "tvl_remaining DECIMAL(38, 18), " +
                    "salt VARCHAR(128), " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS burn_logs_v2 (" +
                    "id IDENTITY PRIMARY KEY, " +
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
                    "id IDENTITY PRIMARY KEY, " +
                    "pool_address VARCHAR(128) NOT NULL, " +
                    "name VARCHAR(256), " +
                    "owner VARCHAR(128), " +
                    "fee INTEGER, " +
                    "price_range_lower DECIMAL(38, 18), " +
                    "price_range_upper DECIMAL(38, 18), " +
                    "token0 VARCHAR(128), " +
                    "token1 VARCHAR(128), " +
                    "burned_liquidity DECIMAL(50, 0), " +
                    "amount0 DECIMAL(38, 18), " +
                    "amount1 DECIMAL(38, 18), " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS mint_logs_v2 (" +
                    "id IDENTITY PRIMARY KEY, " +
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
                    "id IDENTITY PRIMARY KEY, " +
                    "pool_address VARCHAR(128) NOT NULL, " +
                    "name VARCHAR(256), " +
                    "sender VARCHAR(128), " +
                    "owner VARCHAR(128), " +
                    "fee INTEGER, " +
                    "price_range_lower DECIMAL(38, 18), " +
                    "price_range_upper DECIMAL(38, 18), " +
                    "token0 VARCHAR(128), " +
                    "token1 VARCHAR(128), " +
                    "amount0 DECIMAL(38, 18), " +
                    "amount1 DECIMAL(38, 18), " +
                    "event_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS trade_logs (" +
                    "id IDENTITY PRIMARY KEY, " +
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
                    "id IDENTITY PRIMARY KEY, " +
                    "pair_address VARCHAR(128) NOT NULL, " +
                    "swap VARCHAR(128), " +
                    "name VARCHAR(256), " +
                    "fee INTEGER, " +
                    "amount0 DECIMAL(38, 18), " +
                    "amount1 DECIMAL(38, 18), " +
                    "price_range_lower DECIMAL(38, 18), " +
                    "price_range_upper DECIMAL(38, 18), " +
                    "tvl DECIMAL(38, 18), " +
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
}

