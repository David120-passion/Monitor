package com.example.monitor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 转账统计聚合器
 */
public class TransferAggregator {
    /** 地址累计买入金额映射 */
    private final Map<String, BigDecimal> buyTotals = new HashMap<>();
    /** 地址累计卖出金额映射 */
    private final Map<String, BigDecimal> sellTotals = new HashMap<>();
    /** 最近 24 小时成交记录队列 */
    private final Deque<TransferRecord> recentTransfers = new ArrayDeque<>();

    /**
     * 记录买入数据
     *
     * @param address 地址
     * @param amount  金额
     * @param usdValue 美元价值
     * @param timestamp 时间戳
     */
    public synchronized void recordBuy(String address, BigDecimal amount, BigDecimal usdValue, long timestamp) {
        buyTotals.merge(address, amount, BigDecimal::add);
        recentTransfers.add(new TransferRecord(timestamp, usdValue, TransferRecord.Direction.BUY));
        cleanup(timestamp);
    }

    /**
     * 记录卖出数据
     *
     * @param address 地址
     * @param amount  金额
     * @param usdValue 美元价值
     * @param timestamp 时间戳
     */
    public synchronized void recordSell(String address, BigDecimal amount, BigDecimal usdValue, long timestamp) {
        sellTotals.merge(address, amount, BigDecimal::add);
        recentTransfers.add(new TransferRecord(timestamp, usdValue, TransferRecord.Direction.SELL));
        cleanup(timestamp);
    }

    /**
     * 获取地址累计买入
     *
     * @param address 地址
     * @return 累计买入
     */
    public synchronized BigDecimal getBuyTotal(String address) {
        return buyTotals.getOrDefault(address, BigDecimal.ZERO);
    }

    /**
     * 获取地址累计卖出
     *
     * @param address 地址
     * @return 累计卖出
     */
    public synchronized BigDecimal getSellTotal(String address) {
        return sellTotals.getOrDefault(address, BigDecimal.ZERO);
    }

    /**
     * 获取 24 小时成交额
     *
     * @return 24 小时成交额
     */
    public synchronized BigDecimal getVolume24h() {
        long cutoff = Instant.now().minusSeconds(24 * 3600).toEpochMilli();
        return recentTransfers.stream()
                .filter(record -> record.timestamp() >= cutoff)
                .map(TransferRecord::usdValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 获取净流入（买入-卖出）
     *
     * @return 净流入
     */
    public synchronized BigDecimal getNetFlow() {
        return recentTransfers.stream()
                .map(record -> record.direction() == TransferRecord.Direction.BUY
                        ? record.usdValue()
                        : record.usdValue().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 清理过期记录
     *
     * @param currentTimestamp 当前时间
     */
    private void cleanup(long currentTimestamp) {
        long cutoff = currentTimestamp - 24 * 3600 * 1000L;
        while (!recentTransfers.isEmpty() && recentTransfers.peek().timestamp() < cutoff) {
            recentTransfers.poll();
        }
    }

    /**
     * 转账记录结构体
     */
    public record TransferRecord(long timestamp, BigDecimal usdValue, Direction direction) {
        /** 方向枚举 */
        public enum Direction {
            /** 买入 */
            BUY,
            /** 卖出 */
            SELL
        }
    }
}
