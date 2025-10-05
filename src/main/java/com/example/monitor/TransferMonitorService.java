package com.example.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 转账监控服务
 */
public class TransferMonitorService {
    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(TransferMonitorService.class);
    /** Web3j 客户端 */
    private final Web3j web3j;
    /** 监控的代币地址 */
    private final String tokenAddress;
    /** 代币精度 */
    private final BigInteger tokenDecimals;
    /** 代币符号 */
    private final String tokenSymbol;
    /** 价格服务 */
    private final DexPriceService priceService;
    /** 统计聚合器 */
    private final TransferAggregator aggregator;
    /** 已知流动性池地址集合 */
    private final Set<String> liquidityPairs = ConcurrentHashMap.newKeySet();
    /** 需要忽略买卖统计的流动性调整交易 */
    private final ConcurrentHashMap<String, BigInteger> liquidityAdjustmentTxs = new ConcurrentHashMap<>();
    /** 流动性调整交易保留的最大区块跨度 */
    private static final BigInteger LIQUIDITY_TX_TTL_BLOCKS = BigInteger.valueOf(500L);

    /** Transfer 事件定义 */
    private static final Event TRANSFER_EVENT = new Event("Transfer",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Uint256.class)
            ));

    /**
     * 构造函数
     */
    public TransferMonitorService(Web3j web3j, String tokenAddress, BigInteger tokenDecimals, String tokenSymbol,
                                  DexPriceService priceService, TransferAggregator aggregator) {
        this.web3j = web3j;
        this.tokenAddress = tokenAddress.toLowerCase();
        this.tokenDecimals = tokenDecimals;
        this.tokenSymbol = tokenSymbol;
        this.priceService = priceService;
        this.aggregator = aggregator;
    }

    /**
     * 启动事件监听
     */
    public void start() {
        EthFilter filter = new EthFilter(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST, tokenAddress);
        filter.addSingleTopic(EventEncoder.encode(TRANSFER_EVENT));
        web3j.ethLogFlowable(filter).subscribe(this::handleTransferLog, throwable ->
                log.error("Error processing transfer log", throwable));
    }

    /**
     * 增加已知流动性池
     *
     * @param pairAddress 池子地址
     */
    public void addLiquidityPair(String pairAddress) {
        if (pairAddress != null) {
            liquidityPairs.add(pairAddress.toLowerCase());
        }
    }

    /**
     * 标记在统计时需要忽略的流动性调整交易
     *
     * @param txHash      交易哈希
     * @param blockNumber 区块高度
     */
    public void markLiquidityAdjustmentTx(String txHash, BigInteger blockNumber) {
        if (txHash == null || txHash.isEmpty()) {
            return;
        }
        if (blockNumber != null) {
            cleanupOldLiquidityTxs(blockNumber);
            liquidityAdjustmentTxs.put(txHash.toLowerCase(Locale.ROOT), blockNumber);
        } else {
            liquidityAdjustmentTxs.put(txHash.toLowerCase(Locale.ROOT), null);
        }
    }

    /**
     * 移除已知流动性池
     *
     * @param pairAddress 池子地址
     */
    public void removeLiquidityPair(String pairAddress) {
        if (pairAddress != null) {
            liquidityPairs.remove(pairAddress.toLowerCase());
        }
    }

    /**
     * 处理 Transfer 日志
     *
     * @param logEntry 日志
     */
    private void handleTransferLog(Log logEntry) {
        try {
            List<String> topics = logEntry.getTopics();
            if (topics.size() < 3) {
                return;
            }
            String from = decodeAddress(topics.get(1));
            String to = decodeAddress(topics.get(2));
            String fromNormalized = from.toLowerCase();
            String toNormalized = to.toLowerCase();
            List<Type> data = FunctionReturnDecoder.decode(logEntry.getData(), TRANSFER_EVENT.getNonIndexedParameters());
            if (data.isEmpty()) {
                return;
            }
            BigInteger rawValue = (BigInteger) data.get(0).getValue();
            BigDecimal amount = new BigDecimal(rawValue).divide(BigDecimal.TEN.pow(tokenDecimals.intValue()), 18, RoundingMode.HALF_UP);
            BigInteger blockNumber = logEntry.getBlockNumber();
            long timestamp = fetchBlockTimestamp(blockNumber).orElse(Instant.now().toEpochMilli());
            Optional<BigDecimal> priceOpt = priceService.getPriceAtBlock(blockNumber);
            BigDecimal price = priceOpt.orElse(BigDecimal.ZERO);
            BigDecimal usdValue = amount.multiply(price);

            cleanupOldLiquidityTxs(blockNumber);

            String txHash = logEntry.getTransactionHash();
            if (txHash != null && liquidityAdjustmentTxs.containsKey(txHash.toLowerCase(Locale.ROOT))) {
                log.debug("Ignoring transfer for liquidity adjustment txHash={}", txHash);
                return;
            }

            boolean fromPool = liquidityPairs.contains(fromNormalized);
            boolean toPool = liquidityPairs.contains(toNormalized);

            if (fromPool && !toPool) {
                aggregator.recordBuy(toNormalized, amount, usdValue, timestamp);
            } else if (toPool && !fromPool) {
                aggregator.recordSell(fromNormalized, amount, usdValue, timestamp);
            }

            BigDecimal toBuyTotal = aggregator.getBuyTotal(toNormalized);
            BigDecimal fromSellTotal = aggregator.getSellTotal(fromNormalized);

            log.info("TRANSFER from={} to={} amount={} {} price=${} block={} time={} toBuyTotal={} fromSellTotal={} volume24h=${} netFlow=${}",
                    from,
                    to,
                    amount,
                    tokenSymbol,
                    price.setScale(8, RoundingMode.HALF_UP),
                    blockNumber,
                    Instant.ofEpochMilli(timestamp),
                    toBuyTotal,
                    fromSellTotal,
                    aggregator.getVolume24h().setScale(2, RoundingMode.HALF_UP),
                    aggregator.getNetFlow().setScale(2, RoundingMode.HALF_UP));
        } catch (Exception ex) {
            log.error("Failed to handle transfer log", ex);
        }
    }

    /**
     * 获取区块时间戳
     *
     * @param blockNumber 区块高度
     * @return 时间戳
     */
    private Optional<Long> fetchBlockTimestamp(BigInteger blockNumber) {
        try {
            EthBlock block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(blockNumber), false).send();
            if (block.getBlock() == null) {
                return Optional.empty();
            }
            return Optional.of(block.getBlock().getTimestamp().multiply(BigInteger.valueOf(1000L)).longValue());
        } catch (IOException e) {
            log.error("Failed to fetch block timestamp", e);
            return Optional.empty();
        }
    }

    /**
     * 解码地址
     *
     * @param topic 主题数据
     * @return 地址
     */
    private String decodeAddress(String topic) {
        String clean = Numeric.cleanHexPrefix(topic);
        if (clean.length() < 40) {
            clean = String.format("%40s", clean).replace(' ', '0');
        }
        return "0x" + clean.substring(clean.length() - 40);
    }

    /**
     * 清理过期的流动性调整交易记录
     *
     * @param currentBlock 当前处理的区块
     */
    private void cleanupOldLiquidityTxs(BigInteger currentBlock) {
        if (currentBlock == null || liquidityAdjustmentTxs.isEmpty()) {
            return;
        }
        BigInteger threshold = currentBlock.subtract(LIQUIDITY_TX_TTL_BLOCKS);
        liquidityAdjustmentTxs.entrySet().removeIf(entry -> {
            BigInteger recordedBlock = entry.getValue();
            if (recordedBlock == null) {
                return false;
            }
            return recordedBlock.compareTo(threshold) < 0;
        });
    }
}
