package com.example.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 买卖分析服务
 */
public class TradeAnalysisService {
    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(TradeAnalysisService.class);
    /** 时间格式 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    /** 目标时区（UTC+8） */
    private static final ZoneId TARGET_ZONE = ZoneId.of("Asia/Shanghai");

    /** Transfer 事件定义 */
    private static final Event TRANSFER_EVENT = new Event("Transfer",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Uint256.class)
            ));
    /** Transfer 事件签名 */
    private static final String TRANSFER_EVENT_SIGNATURE = EventEncoder.encode(TRANSFER_EVENT)
            .toLowerCase(Locale.ROOT);

    /** V2 Swap 事件签名
     *     pancakeSwap
     *     event Swap(
     *         address indexed sender,
     *         uint amount0In,
     *         uint amount1In,
     *         uint amount0Out,
     *         uint amount1Out,
     *         address indexed to
     *     );
     *
     * uniswap
     *     event Swap(
     *         address indexed sender,
     *         uint amount0In,
     *         uint amount1In,
     *         uint amount0Out,
     *         uint amount1Out,
     *         address indexed to
     *     );
     *
     * */
    private static final Event SWAP_EVENT_V2 = new Event("Swap",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Address.class, true)
            ));
    private static final String SWAP_EVENT_SIGNATURE_V2 = EventEncoder.encode(SWAP_EVENT_V2);

    /** V3
     *
     * pankcakeSwap 事件签名
     *     event Swap(
     *         address indexed sender,
     *         address indexed recipient,
     *         int256 amount0,
     *         int256 amount1,
     *         uint160 sqrtPriceX96,
     *         uint128 liquidity,
     *         int24 tick,
     *         uint128 protocolFeesToken0,
     *         uint128 protocolFeesToken1
     *     );
     *
     *     uniswapSwap 事件签名
     *       event Swap(
     *         address indexed sender,
     *         address indexed recipient,
     *         int256 amount0,
     *         int256 amount1,
     *         uint160 sqrtPriceX96,
     *         uint128 liquidity,
     *         int24 tick
     *     );
     *
     * */

    private static final Event PANCAKE_EVENT_V3 = new Event("Swap",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Int256.class),
                    TypeReference.create(Int256.class),
                    TypeReference.create(Uint160.class),
                    TypeReference.create(Uint128.class),
                    TypeReference.create(Int24.class),
                    TypeReference.create(Uint128.class),
                    TypeReference.create(Uint128.class)
            ));
    private static final Event UNISWAP_EVENT_V3 = new Event("Swap",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Int256.class),
                    TypeReference.create(Int256.class),
                    TypeReference.create(Uint160.class),
                    TypeReference.create(Uint128.class),
                    TypeReference.create(Int24.class)
            ));
    private static final String PANCAKE_EVENT_SIGNATURE_V3 = EventEncoder.encode(PANCAKE_EVENT_V3);
    private static final String UNISWAP_EVENT_SIGNATURE_V3 = EventEncoder.encode(UNISWAP_EVENT_V3);
    /**
     * V4 Swap 事件签名
     * pancakeSwap
     *     event Swap(
     *         PoolId indexed id,
     *         address indexed sender,
     *         int128 amount0,
     *         int128 amount1,
     *         uint160 sqrtPriceX96,
     *         uint128 liquidity,
     *         int24 tick,
     *         uint24 fee,
     *         uint16 protocolFee
     *     );
     * uniswap
     *     event Swap(
     *         bytes32 indexed id,
     *         address indexed sender,
     *         int128 amount0,
     *         int128 amount1,
     *         uint160 sqrtPriceX96,
     *         uint128 liquidity,
     *         int24 tick,
     *         uint24 fee
     *     );
     */
    private static final Event PANCAKESWAP_EVENT_V4 = new Event("Swap",
            Arrays.asList(
                    TypeReference.create(Bytes32.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Int128.class),
                    TypeReference.create(Int128.class),
                    TypeReference.create(Uint160.class),
                    TypeReference.create(Uint128.class),
                    TypeReference.create(Int24.class),
                    TypeReference.create(Uint24.class),
                    TypeReference.create(Uint16.class)
            ));

    private static final Event UNISWAP_EVENT_V4 = new Event("Swap",
            Arrays.asList(
                    TypeReference.create(Bytes32.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Int128.class),
                    TypeReference.create(Int128.class),
                    TypeReference.create(Uint160.class),
                    TypeReference.create(Uint128.class),
                    TypeReference.create(Int24.class),
                    TypeReference.create(Uint24.class)
            ));
    private static final String PANCAKESWAP_EVENT_SIGNATURE_V4 = EventEncoder.encode(PANCAKESWAP_EVENT_V4);
    private static final String UNISWAP_EVENT_SIGNATURE_V4 = EventEncoder.encode(UNISWAP_EVENT_V4);


    private static final String SWAP_EVENT_SIGNATURE_V2_NORMALIZED = SWAP_EVENT_SIGNATURE_V2.toLowerCase(Locale.ROOT);
    private static final String UNISWAP_EVENT_SIGNATURE_V3_NORMALIZED = UNISWAP_EVENT_SIGNATURE_V3.toLowerCase(Locale.ROOT);
    private static final String PANCAKE_EVENT_SIGNATURE_V3_NORMALIZED = PANCAKE_EVENT_SIGNATURE_V3.toLowerCase(Locale.ROOT);
    private static final String UNISWAP_EVENT_SIGNATURE_V4_NORMALIZED = UNISWAP_EVENT_SIGNATURE_V4.toLowerCase(Locale.ROOT);
    private static final String PANCAKESWAP_EVENT_SIGNATURE_V4_NORMALIZED = PANCAKESWAP_EVENT_SIGNATURE_V4.toLowerCase(Locale.ROOT);

    /** 支持的 Swap 事件签名集合 */
    private static final Set<String> SUPPORTED_SWAP_SIGNATURES = new HashSet<>(Arrays.asList(
            SWAP_EVENT_SIGNATURE_V2_NORMALIZED,
            UNISWAP_EVENT_SIGNATURE_V3_NORMALIZED,
            PANCAKE_EVENT_SIGNATURE_V3_NORMALIZED,
            UNISWAP_EVENT_SIGNATURE_V4_NORMALIZED,
            PANCAKESWAP_EVENT_SIGNATURE_V4_NORMALIZED
    ));

    public static void main(String[] args) {
        SUPPORTED_SWAP_SIGNATURES.forEach(item -> System.out.println(item));
    }

    /** Web3j 客户端 */
    private final Web3j web3j;
    /** 监控的代币地址 */
    private final String tokenAddress;
    /** 代币符号 */
    private final String tokenSymbol;
    /** 价格服务 */
    private final DexPriceService priceService;
    /** 数据库存储服务 */
    private final DatabaseLogService databaseLogService;

    /** 按地址统计的成交数据 */
    private final ConcurrentHashMap<String, AddressTotals> totalsByAddress = new ConcurrentHashMap<>();
    /** 已处理的交易哈希 */
    private final Set<String> processedTransactions = ConcurrentHashMap.newKeySet();
    /** 代币精度因子 */
    private final BigDecimal decimalFactor;
    /** 已知的 V4 池子信息 */
    private final ConcurrentHashMap<String, DexPriceService.PairMetadata> v4Pools = new ConcurrentHashMap<>();
    /** 区块时间戳缓存 */
    private final ConcurrentHashMap<BigInteger, Long> blockTimestampCache = new ConcurrentHashMap<>();
    /** 异步处理线程池 */
    private final ExecutorService asyncExecutor;
    /** Transfer 日志定时轮询执行器 */
    private ScheduledExecutorService transferLogScheduler;
    /** Transfer 日志定时轮询任务 */
    private ScheduledFuture<?> transferLogTask;
    /** 上次处理的区块号 */
    private final AtomicReference<BigInteger> lastProcessedBlock = new AtomicReference<>();
    /** Transfer 日志轮询间隔（秒） */
    private static final long TRANSFER_LOG_POLL_INTERVAL_SECONDS = 10L;
    /** 日志查询单次最大区块跨度 */
    private static final BigInteger MAX_LOG_BLOCK_RANGE = BigInteger.valueOf(50_000L);

    /**
     * 构造函数
     */
    public TradeAnalysisService(Web3j web3j, String tokenAddress, BigInteger tokenDecimals, String tokenSymbol,
                                DexPriceService priceService, DatabaseLogService databaseLogService) {
        this.web3j = web3j;
        this.tokenAddress = tokenAddress.toLowerCase(Locale.ROOT);
        this.tokenSymbol = tokenSymbol;
        this.priceService = priceService;
        this.databaseLogService = databaseLogService;
        int decimals = tokenDecimals == null ? 18 : Math.max(tokenDecimals.intValue(), 0);
        this.decimalFactor = BigDecimal.TEN.pow(decimals);
        this.asyncExecutor = Executors.newFixedThreadPool(5, r -> {
            Thread thread = new Thread(r, "trade-analysis-async");
            thread.setDaemon(true);
            return thread;
        });
        this.transferLogScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "trade-analysis-transfer-polling");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 注册已知的 V4 池子
     *
     * @param metadata 池子元数据
     */
    public void registerV4Pool(DexPriceService.PairMetadata metadata) {
        if (metadata == null || metadata.pairAddress == null) {
            return;
        }
        String key = metadata.pairAddress.toLowerCase(Locale.ROOT);
        v4Pools.putIfAbsent(key, metadata);
        priceService.registerV4Pool(metadata);
    }

    /**
     * 启动监听（定时轮询方式）
     */
    public void start() {
        // 初始化时获取最新区块号作为起始点
        BigInteger latestBlock = fetchLatestBlockNumber();
        if (latestBlock != null) {
            lastProcessedBlock.set(latestBlock);
            log.info("Transfer log polling initialized from block {}", latestBlock);
        } else {
            log.warn("Failed to fetch latest block number, starting from block 0");
            lastProcessedBlock.set(BigInteger.ZERO);
        }
        
        // 启动定时轮询任务
        transferLogTask = transferLogScheduler.scheduleAtFixedRate(
                this::pollTransferLogs,
                0L,
                TRANSFER_LOG_POLL_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
        
        log.info("Transfer log polling task started (interval: {} seconds)", TRANSFER_LOG_POLL_INTERVAL_SECONDS);
    }
    
    /**
     * 定时拉取 Transfer 事件
     */
    private void pollTransferLogs() {
        try {
            BigInteger latestBlock = fetchLatestBlockNumber();
            if (latestBlock == null) {
                log.warn("Failed to fetch latest block number for Transfer log polling");
                return;
            }
            
            BigInteger lastBlock = lastProcessedBlock.get();
            if (lastBlock == null) {
                lastBlock = BigInteger.ZERO;
            }
            
            // 如果上次查询的区块号已经是最新区块，跳过
            if (lastBlock.compareTo(latestBlock) >= 0) {
                return;
            }
            
            // 从上次查询的区块+1开始，到最新区块，分段拉取
            BigInteger currentStart = lastBlock.add(BigInteger.ONE);
            BigInteger lastProcessed = lastBlock;
            
            while (currentStart.compareTo(latestBlock) <= 0) {
                BigInteger endBlock = currentStart.add(MAX_LOG_BLOCK_RANGE).subtract(BigInteger.ONE);
                if (endBlock.compareTo(latestBlock) > 0) {
                    endBlock = latestBlock;
                }
                
                // 拉取 Transfer 事件
                EthFilter filter = new EthFilter(
                        DefaultBlockParameter.valueOf(currentStart),
                        DefaultBlockParameter.valueOf(endBlock),
                        tokenAddress
                );
                filter.addSingleTopic(EventEncoder.encode(TRANSFER_EVENT));
                
                try {
                    EthLog response = web3j.ethGetLogs(filter).send();
                    if (response.hasError()) {
                        log.warn("Failed to fetch Transfer logs (blocks {}-{}): {}", 
                                currentStart, endBlock, response.getError());
                        break; // 出错时停止拉取
                    }
                    
                    int eventCount = 0;
                    if (response.getLogs() != null) {
                        for (EthLog.LogResult<?> logResult : response.getLogs()) {
                            Object raw = logResult.get();
                            if (raw instanceof Log) {
                                handleTransferLog((Log) raw);
                                eventCount++;
                            }
                        }
                    }
                    
                    if (eventCount > 0) {
                        log.debug("Polled {} Transfer events (blocks {}-{})", 
                                eventCount, currentStart, endBlock);
                    }
                    
                    lastProcessed = endBlock;
                    
                    // 如果已经到达最新区块，退出循环
                    if (endBlock.compareTo(latestBlock) >= 0) {
                        break;
                    }
                    
                    // 继续下一段
                    currentStart = endBlock.add(BigInteger.ONE);
                    
                } catch (Exception e) {
                    log.error("Error polling Transfer events (blocks {}-{})", 
                            currentStart, endBlock, e);
                    break; // 出错时停止拉取
                }
            }
            
            // 更新最后处理的区块号
            if (lastProcessed.compareTo(lastBlock) > 0) {
                lastProcessedBlock.set(lastProcessed);
            }
            
        } catch (Exception e) {
            log.error("Error in Transfer log polling task", e);
        }
    }
    
    /**
     * 获取最新区块号
     */
    private BigInteger fetchLatestBlockNumber() {
        try {
            return web3j.ethBlockNumber().send().getBlockNumber();
        } catch (IOException e) {
            log.error("Failed to fetch latest block number", e);
            return null;
        }
    }

    /**
     * 处理 Transfer 日志
     *
     * @param logEntry 日志
     */
    private void handleTransferLog(Log logEntry) {
        try {
            String txHash = logEntry.getTransactionHash();
            if (txHash == null || txHash.isEmpty()) {
                return;
            }
            if (!processedTransactions.add(txHash)) {
                return;
            }
            long startTime = System.currentTimeMillis();

            System.out.println("转账触发时间："+startTime);
            BigInteger blockNumber = logEntry.getBlockNumber();

            // 并发获取交易信息和交易回执，设置超时避免长时间阻塞
            CompletableFuture<Optional<Transaction>> transactionFuture = CompletableFuture.supplyAsync(
                    () -> fetchTransaction(txHash), asyncExecutor);
            CompletableFuture<Optional<TransactionReceipt>> receiptFuture = CompletableFuture.supplyAsync(
                    () -> fetchTransactionReceipt(txHash), asyncExecutor);

            // 等待两个结果，设置超时（2秒）
            Optional<Transaction> transactionOpt;
            Optional<TransactionReceipt> receiptOpt;
            try {
                transactionOpt = transactionFuture.get(2, TimeUnit.SECONDS);
                receiptOpt = receiptFuture.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("Timeout fetching transaction data for {} (2s)", txHash);
                return;
            } catch (java.util.concurrent.ExecutionException | InterruptedException e) {
                log.error("Failed to fetch transaction data for {}", txHash, e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            System.out.println("获取完交易树耗时:"+(System.currentTimeMillis() - startTime));

            if (!transactionOpt.isPresent() || !receiptOpt.isPresent()) {
                return;
            }

            Transaction transaction = transactionOpt.get();
            TransactionReceipt receipt = receiptOpt.get();

            String txFrom = transaction.getFrom();
            if (txFrom == null) {
                return;
            }
            String txFromNormalized = normalizeAddress(txFrom);

            if (receipt.getLogs() == null || receipt.getLogs().isEmpty()) {
                return;
            }

            // 确定区块号
            if (blockNumber == null) {
                blockNumber = receipt.getBlockNumber();
                if (blockNumber == null) {
                    return;
                }
            }

            // 获取区块时间戳（使用缓存）
            long timestamp = getBlockTimestamp(blockNumber);

            // 存储所有目标代币的 Transfer 事件（传入时间戳避免重复查询）
            storeTransferEvents(receipt.getLogs(), txHash, blockNumber, timestamp);

            Optional<TradeDetection> detectionOpt = analyseTransactionLogs(txFromNormalized, receipt.getLogs(), txHash);
            System.out.println("分析完交易树swap耗时:"+(System.currentTimeMillis() - startTime));
            if (!detectionOpt.isPresent()) {
                return;
            }
            TradeDetection detection = detectionOpt.get();

            Optional<BigDecimal> priceOpt = priceService.getPriceAtBlock(blockNumber);
            System.out.println("获取完价格耗时:"+(System.currentTimeMillis() - startTime));
            BigDecimal price = priceOpt.orElse(BigDecimal.ZERO);
            BigDecimal usdValue = detection.tradeAmount.multiply(price);

            TradeSummary summary = updateTotals(txFromNormalized, detection.direction, detection.tradeAmount, usdValue);

            Instant eventTime = Instant.ofEpochMilli(timestamp);
            String formattedTime = eventTime.atZone(TARGET_ZONE).format(TIME_FORMATTER);

            // 使用 StringBuilder 优化字符串拼接
            StringBuilder messageBuilder = new StringBuilder(512);
            messageBuilder.append("TRADE address=").append(txFrom)
                    .append(" action=").append(detection.direction.getDisplay())
                    .append(" amount=").append(detection.tradeAmount.setScale(6, RoundingMode.HALF_UP))
                    .append(" ").append(tokenSymbol)
                    .append(" price=$").append(price.setScale(8, RoundingMode.HALF_UP))
                    .append(" value=$").append(usdValue.setScale(6, RoundingMode.HALF_UP))
                    .append(" totalBuyAmount=").append(summary.totalBuyAmount.setScale(6, RoundingMode.HALF_UP))
                    .append(" totalSellAmount=").append(summary.totalSellAmount.setScale(6, RoundingMode.HALF_UP))
                    .append(" totalBuyValue=$").append(summary.totalBuyValue.setScale(6, RoundingMode.HALF_UP))
                    .append(" totalSellValue=$").append(summary.totalSellValue.setScale(6, RoundingMode.HALF_UP))
                    .append(" netAmount=").append(summary.netAmount.setScale(6, RoundingMode.HALF_UP))
                    .append(" netValue=$").append(summary.netValue.setScale(6, RoundingMode.HALF_UP))
                    .append(" avgBuyPrice=$").append(summary.avgBuyPrice.setScale(8, RoundingMode.HALF_UP))
                    .append(" avgSellPrice=$").append(summary.avgSellPrice.setScale(8, RoundingMode.HALF_UP))
                    .append(" trades=").append(summary.tradeCount)
                    .append(" block=").append(blockNumber)
                    .append(" time=").append(formattedTime)
                    .append(" txHash=").append(txHash);
            log.info(messageBuilder.toString());
            
            if (databaseLogService != null) {
                int tradeCount = summary.tradeCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) summary.tradeCount;
                databaseLogService.logTradeSummary(txHash,
                        txFromNormalized,
                        detection.direction.getDisplay(),
                        detection.tradeAmount,
                        tokenSymbol,
                        price,
                        usdValue,
                        summary.totalBuyAmount,
                        summary.totalSellAmount,
                        summary.totalBuyValue,
                        summary.totalSellValue,
                        summary.netAmount,
                        summary.netValue,
                        summary.avgBuyPrice,
                        summary.avgSellPrice,
                        tradeCount,
                        blockNumber,
                        eventTime);
            }
            long endTime = System.currentTimeMillis();
            System.out.println("转账处理完毕时间："+endTime);

            System.out.println("总耗时："+(endTime-startTime ));
        } catch (Exception ex) {
            log.error("Failed to analyse trade", ex);
        }
    }

    /**
     * 分析交易日志，匹配目标代币池子的 Swap 事件
     *
     * @param txFromNormalized 交易发起人地址（小写）
     * @param logs             交易所有日志
     * @param txHash           交易哈希
     * @return 交易识别结果
     */
    private Optional<TradeDetection> analyseTransactionLogs(String txFromNormalized, List<Log> logs, String txHash) {
        if (logs == null || logs.isEmpty()) {
            return Optional.empty();
        }

        BigInteger targetTokenDelta = BigInteger.ZERO;
        boolean swapMatched = false;

        for (Log entry : logs) {
            List<String> topics = entry.getTopics();
            if (topics == null || topics.isEmpty()) {
                continue;
            }
            String topic0 = topics.get(0).toLowerCase(Locale.ROOT);
            
            // 检查是否是支持的 Swap 事件
            if (!SUPPORTED_SWAP_SIGNATURES.contains(topic0)) {
                continue;
            }

            // V2/V3: 直接检查地址是否在 DexPriceService 的缓存中
            if (SWAP_EVENT_SIGNATURE_V2_NORMALIZED.equals(topic0) ||
                PANCAKE_EVENT_SIGNATURE_V3_NORMALIZED.equals(topic0) ||
                UNISWAP_EVENT_SIGNATURE_V3_NORMALIZED.equals(topic0)) {
                String poolAddress = entry.getAddress();
                if (poolAddress != null && priceService.isCachedPoolAddress(poolAddress)) {
                    Optional<BigInteger> deltaOpt = analyseSwapForTargetToken(topic0, entry, topics);
                    if (deltaOpt.isPresent()) {
                        targetTokenDelta = targetTokenDelta.add(deltaOpt.get());
                        swapMatched = true;
                    }
                }
            }
            // V4: 检查合约地址和 poolId
            else if (PANCAKESWAP_EVENT_SIGNATURE_V4_NORMALIZED.equals(topic0) ||
                     UNISWAP_EVENT_SIGNATURE_V4_NORMALIZED.equals(topic0)) {
                String contractAddress = entry.getAddress();
                if (contractAddress != null) {
                    String normalizedContract = normalizeAddress(contractAddress);
                    // 检查是否是 PancakeSwap 或 Uniswap V4 合约地址
                    if (normalizedContract.equals(normalizeAddress(DexConstants.PANCAKE_V4_FACTORY)) ||
                        normalizedContract.equals(normalizeAddress(DexConstants.UNISWAP_V4_FACTORY))) {
                        if (topics.size() >= 2) {
                            String poolId = normalizePoolId(topics.get(1));
                            if (poolId != null) {
                                // 先检查本地 v4Pools，再检查 DexPriceService 缓存
                                String poolIdLower = poolId.toLowerCase(Locale.ROOT);
                                boolean isV4Pool = v4Pools.containsKey(poolIdLower) || 
                                                   priceService.isCachedV4PoolId(poolId);
                                if (isV4Pool) {
                                    Optional<BigInteger> deltaOpt = analyseSwapForTargetToken(topic0, entry, topics);
                                    if (deltaOpt.isPresent()) {
                                        targetTokenDelta = targetTokenDelta.add(deltaOpt.get());
                                        swapMatched = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!swapMatched) {
            return Optional.empty();
        }

        // 如果目标代币的净变化为 0，说明目标代币只是作为中间代币（例如：USD1 -> 目标代币 -> BNB）
        // 这种情况不应该记录为交易
        if (targetTokenDelta.signum() == 0) {
            log.debug("Target token used as intermediate token in tx {} (net delta = 0), skipping", txHash);
            return Optional.empty();
        }

        // 根据目标代币的输入额（delta）判断买入/卖出
        // 注意：方向判断已根据用户反馈进行反转
        // delta > 0 表示收到目标代币，delta < 0 表示付出目标代币
        return buildTradeDetectionFromDelta(targetTokenDelta, txHash);
    }

    /**
     * 根据目标代币的输入额（delta）构建交易识别结果
     * delta > 0 表示用户向 uniswap 发送了目标代币（用户付出，卖出）
     * delta < 0 表示 uniswap 向用户发送了目标代币（用户收到，买入）
     *
     * @param delta  目标代币的输入额（正数=用户付出代币，负数=用户收到代币）
     * @param txHash 交易哈希
     * @return 交易识别结果
     */
    private Optional<TradeDetection> buildTradeDetectionFromDelta(BigInteger delta, String txHash) {
        if (delta == null || delta.signum() == 0) {
            log.info("Zero trade delta detected in tx {}", txHash);
            return Optional.empty();
        }

        BigDecimal tradeAmount = toDecimalAmount(delta.abs());
        // delta 表示目标代币的变化量（从用户的角度）
        // delta > 0: 用户向 uniswap 发送了目标代币（卖出目标代币，用目标代币换取其他代币）
        // delta < 0: uniswap 向用户发送了目标代币（买入目标代币，用其他代币换取目标代币）
        TradeDirection direction = delta.signum() > 0 ? TradeDirection.SELL : TradeDirection.BUY;
        
        BigDecimal totalIn = delta.signum() > 0 ? tradeAmount : BigDecimal.ZERO;  // 卖出时，用户付出目标代币
        BigDecimal totalOut = delta.signum() < 0 ? tradeAmount : BigDecimal.ZERO; // 买入时，用户收到目标代币

        return Optional.of(new TradeDetection(direction, tradeAmount, totalIn, totalOut));
    }

    private Optional<TradeDetection> buildTradeDetection(BigInteger totalInRaw, BigInteger totalOutRaw, String txHash) {
        BigDecimal totalIn = toDecimalAmount(totalInRaw);
        BigDecimal totalOut = toDecimalAmount(totalOutRaw);
        if (totalIn.signum() == 0 && totalOut.signum() == 0) {
            log.info("Zero trade amounts detected in tx {}", txHash);
            return Optional.empty();
        }

        BigDecimal tradeAmount;
        TradeDirection direction;
        if (totalIn.signum() > 0 && totalOut.signum() == 0) {
            direction = TradeDirection.BUY;
            tradeAmount = totalIn;
        } else if (totalOut.signum() > 0 && totalIn.signum() == 0) {
            direction = TradeDirection.SELL;
            tradeAmount = totalOut;
        } else {
            BigDecimal net = totalIn.subtract(totalOut);
            if (net.signum() == 0) {
                log.info("net in tx {}", txHash);
                return Optional.empty();
            }
            direction = net.signum() > 0 ? TradeDirection.BUY : TradeDirection.SELL;
            tradeAmount = net.abs();
        }

        return Optional.of(new TradeDetection(direction, tradeAmount, totalIn, totalOut));
    }

    /**
     * 分析 Swap 事件，计算目标代币的输入额（delta）
     * 正数 = 用户向 uniswap 发送了多少代币（用户付出）
     * 负数 = uniswap 需要向用户发送多少代币（用户收到）
     *
     * @param topic0 Swap 事件签名
     * @param entry  日志条目
     * @param topics 主题列表
     * @return 目标代币的输入额（delta），正数=用户付出代币，负数=用户收到代币
     */
    private Optional<BigInteger> analyseSwapForTargetToken(String topic0, Log entry, List<String> topics) {
        // 获取池子元数据
        Optional<DexPriceService.PairMetadata> metadataOpt = resolveSwapPairMetadata(topic0, entry, topics);
        if (!metadataOpt.isPresent()) {
            return Optional.empty();
        }
        DexPriceService.PairMetadata metadata = metadataOpt.get();
        
        // 判断目标代币是 token0 还是 token1（根据地址大小排序）
        String token0 = normalizeAddress(metadata.token0);
        String token1 = normalizeAddress(metadata.token1);
        String targetTokenNormalized = normalizeAddress(tokenAddress);
        boolean isToken0 = targetTokenNormalized.equals(token0);
        boolean isToken1 = targetTokenNormalized.equals(token1);
        
        if (!isToken0 && !isToken1) {
            return Optional.empty();
        }

        BigInteger targetTokenDelta;
        
        // V2 Swap 事件处理
        if (SWAP_EVENT_SIGNATURE_V2_NORMALIZED.equals(topic0)) {
            List<Type> decoded = decodeEventData(entry.getData(), SWAP_EVENT_V2.getNonIndexedParameters());
            if (decoded.size() < 4) {
                return Optional.empty();
            }
            BigInteger amount0In = ((Uint256) decoded.get(0)).getValue();
            BigInteger amount1In = ((Uint256) decoded.get(1)).getValue();
            BigInteger amount0Out = ((Uint256) decoded.get(2)).getValue();
            BigInteger amount1Out = ((Uint256) decoded.get(3)).getValue();
            
            // 目标代币的输入额 = 输入 - 输出（正数=用户付出代币，负数=用户收到代币）
            if (isToken0) {
                targetTokenDelta = amount0In.subtract(amount0Out);
            } else {
                targetTokenDelta = amount1In.subtract(amount1Out);
            }
        }
        // V3 Swap 事件处理
        else if (PANCAKE_EVENT_SIGNATURE_V3_NORMALIZED.equals(topic0) || UNISWAP_EVENT_SIGNATURE_V3_NORMALIZED.equals(topic0)) {
            List<TypeReference<Type>> parameters = PANCAKE_EVENT_SIGNATURE_V3_NORMALIZED.equals(topic0)
                    ? PANCAKE_EVENT_V3.getNonIndexedParameters()
                    : UNISWAP_EVENT_V3.getNonIndexedParameters();
            List<Type> decoded = decodeEventData(entry.getData(), parameters);
            if (decoded.size() < 2) {
                return Optional.empty();
            }
            BigInteger amount0 = ((Int256) decoded.get(0)).getValue();
            BigInteger amount1 = ((Int256) decoded.get(1)).getValue();
            
            // V3 中 amount0/amount1 是池子的变化量（正数=池子增加，负数=池子减少）
            // 用户的变化量 = 池子的变化量（正数=用户付出，负数=用户收到）
            BigInteger poolDelta = isToken0 ? amount0 : amount1;
            targetTokenDelta = poolDelta;
        }
        // V4 Swap 事件处理
        else if (PANCAKESWAP_EVENT_SIGNATURE_V4_NORMALIZED.equals(topic0) || UNISWAP_EVENT_SIGNATURE_V4_NORMALIZED.equals(topic0)) {
            List<TypeReference<Type>> parameters = PANCAKESWAP_EVENT_SIGNATURE_V4_NORMALIZED.equals(topic0)
                    ? PANCAKESWAP_EVENT_V4.getNonIndexedParameters()
                    : UNISWAP_EVENT_V4.getNonIndexedParameters();
            List<Type> decoded = decodeEventData(entry.getData(), parameters);
            if (decoded.size() < 2) {
                return Optional.empty();
            }
            BigInteger amount0 = ((Int128) decoded.get(0)).getValue();
            BigInteger amount1 = ((Int128) decoded.get(1)).getValue();
            
            // V4 中 amount0/amount1 是池子的变化量（正数=池子增加，负数=池子减少）
            // 注意：V4 与 V2/V3 相反，需要取反
            // 原先 池子增加（正数） = 用户收到代币（买入），池子减少（负数） = 用户付出代币（卖出）
            // 所以用户的变化量 = -池子的变化量（正数=用户收到代币，负数=用户付出代币）
            BigInteger poolDelta = isToken0 ? amount0 : amount1;
            targetTokenDelta = poolDelta.negate();
        } else {
            return Optional.empty();
        }

        return Optional.of(targetTokenDelta);
    }

    private Optional<DexPriceService.PairMetadata> resolveSwapPairMetadata(String topic0, Log entry, List<String> topics) {
        if (SWAP_EVENT_SIGNATURE_V2_NORMALIZED.equals(topic0)) {
            String address = entry.getAddress();
            if (address == null || address.isEmpty()) {
                return Optional.empty();
            }
            return fetchPairMetadata(address, DexPriceService.PoolType.V2);
        }
        if (PANCAKE_EVENT_SIGNATURE_V3_NORMALIZED.equals(topic0) || UNISWAP_EVENT_SIGNATURE_V3_NORMALIZED.equals(topic0)) {
            String address = entry.getAddress();
            if (address == null || address.isEmpty()) {
                return Optional.empty();
            }
            return fetchPairMetadata(address, DexPriceService.PoolType.V3);
        }
        if (PANCAKESWAP_EVENT_SIGNATURE_V4_NORMALIZED.equals(topic0) || UNISWAP_EVENT_SIGNATURE_V4_NORMALIZED.equals(topic0)) {
            if (topics.size() < 2) {
                return Optional.empty();
            }
            String poolId = normalizePoolId(topics.get(1));
            if (poolId == null || poolId.isEmpty()) {
                return Optional.empty();
            }
            DexPriceService.PairMetadata metadata = v4Pools.get(poolId.toLowerCase(Locale.ROOT));
            if (metadata != null) {
                return Optional.of(metadata);
            }
            return priceService.findCachedPairMetadata(poolId);
        }
        return Optional.empty();
    }

    private Optional<DexPriceService.PairMetadata> fetchPairMetadata(String address, DexPriceService.PoolType poolType) {
        if (address == null || address.isEmpty()) {
            return Optional.empty();
        }
        String normalized = normalizeAddress(address);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        Optional<DexPriceService.PairMetadata> cached = priceService.findCachedPairMetadata(normalized);
        if (cached.isPresent()) {
            return cached;
        }
        if (poolType == DexPriceService.PoolType.V2) {
            return priceService.loadPairMetadata(normalized);
        }
        return priceService.loadPairMetadata(normalized, false, poolType);
    }

    private List<Type> decodeEventData(String data, List<TypeReference<Type>> parameters) {
        if (data == null || data.length() < 2) {
            return Collections.emptyList();
        }
        try {
            return FunctionReturnDecoder.decode(data, parameters);
        } catch (Exception ex) {
            log.debug("Failed to decode swap event data", ex);
            return Collections.emptyList();
        }
    }

    private String normalizePoolId(String poolId) {
        if (poolId == null) {
            return null;
        }
        String clean = poolId.toLowerCase(Locale.ROOT);
        if (!clean.startsWith("0x")) {
            clean = "0x" + clean;
        }
        return clean;
    }

    /**
     * 记录 Swap 中目标代币的流向
     */
    private static class TokenFlow {
        private final BigInteger received;
        private final BigInteger sent;

        private TokenFlow(BigInteger received, BigInteger sent) {
            this.received = received;
            this.sent = sent;
        }
    }


    /**
     * 地址级别的成交累计数据
     */
    private static class AddressTotals {
        private BigDecimal totalBuyAmount = BigDecimal.ZERO;
        private BigDecimal totalSellAmount = BigDecimal.ZERO;
        private BigDecimal totalBuyValue = BigDecimal.ZERO;
        private BigDecimal totalSellValue = BigDecimal.ZERO;
        private long tradeCount = 0L;

        private TradeSummary toSummary() {
            BigDecimal netAmount = totalBuyAmount.subtract(totalSellAmount);
            BigDecimal netValue = totalBuyValue.subtract(totalSellValue);
            BigDecimal avgBuyPrice = totalBuyAmount.signum() == 0 ? BigDecimal.ZERO :
                    totalBuyValue.divide(totalBuyAmount, 18, RoundingMode.HALF_UP);
            BigDecimal avgSellPrice = totalSellAmount.signum() == 0 ? BigDecimal.ZERO :
                    totalSellValue.divide(totalSellAmount, 18, RoundingMode.HALF_UP);
            return new TradeSummary(totalBuyAmount, totalSellAmount, totalBuyValue, totalSellValue,
                    netAmount, netValue, avgBuyPrice, avgSellPrice, tradeCount);
        }
    }


    /**
     * 更新统计汇总
     *
     * @param address   交易地址
     * @param direction 交易方向
     * @param amount    交易数量
     * @param usdValue  交易价值
     * @return 汇总结果
     */
    private TradeSummary updateTotals(String address, TradeDirection direction, BigDecimal amount, BigDecimal usdValue) {
        String normalizedAddress = normalizeAddress(address);
        AddressTotals totals = totalsByAddress.compute(normalizedAddress, (addr, existing) -> {
            AddressTotals updated = existing == null ? new AddressTotals() : existing;
            if (direction == TradeDirection.BUY) {
                updated.totalBuyAmount = updated.totalBuyAmount.add(amount);
                updated.totalBuyValue = updated.totalBuyValue.add(usdValue);
            } else {
                updated.totalSellAmount = updated.totalSellAmount.add(amount);
                updated.totalSellValue = updated.totalSellValue.add(usdValue);
            }
            updated.tradeCount = updated.tradeCount + 1;
            return updated;
        });
        return totals.toSummary();
    }

    /**
     * 获取交易详情
     *
     * @param txHash 交易哈希
     * @return 交易对象
     */
    private Optional<Transaction> fetchTransaction(String txHash) {
        try {
            return web3j.ethGetTransactionByHash(txHash).send().getTransaction();
        } catch (IOException e) {
            log.error("Failed to fetch transaction {}", txHash, e);
            return Optional.empty();
        }
    }

    /**
     * 获取交易回执
     *
     * @param txHash 交易哈希
     * @return 交易回执
     */
    private Optional<TransactionReceipt> fetchTransactionReceipt(String txHash) {
        try {
            return web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
        } catch (IOException e) {
            log.error("Failed to fetch transaction receipt {}", txHash, e);
            return Optional.empty();
        }
    }

    /**
     * 获取区块时间戳（带缓存）
     *
     * @param blockNumber 区块高度
     * @return 时间戳（毫秒）
     */
    private long getBlockTimestamp(BigInteger blockNumber) {
        if (blockNumber == null) {
            return Instant.now().toEpochMilli();
        }
        
        // 先查缓存
        Long cached = blockTimestampCache.get(blockNumber);
        if (cached != null) {
            return cached;
        }
        
        // 缓存未命中，从链上获取
        try {
            EthBlock block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(blockNumber), false).send();
            if (block.getBlock() == null || block.getBlock().getTimestamp() == null) {
                long fallback = Instant.now().toEpochMilli();
                blockTimestampCache.put(blockNumber, fallback);
                return fallback;
            }
            long timestamp = block.getBlock().getTimestamp().multiply(BigInteger.valueOf(1000L)).longValue();
            blockTimestampCache.put(blockNumber, timestamp);
            return timestamp;
        } catch (IOException e) {
            log.error("Failed to fetch block timestamp for block {}", blockNumber, e);
            long fallback = Instant.now().toEpochMilli();
            blockTimestampCache.put(blockNumber, fallback);
            return fallback;
        }
    }

    /**
     * 获取区块时间戳（返回 Optional，用于兼容旧代码）
     *
     * @param blockNumber 区块高度
     * @return 时间戳
     */
    private Optional<Long> fetchBlockTimestamp(BigInteger blockNumber) {
        return Optional.of(getBlockTimestamp(blockNumber));
    }

    /**
     * 归一化地址为小写
     *
     * @param address 地址
     * @return 小写地址
     */
    private String normalizeAddress(String address) {
        return address == null ? "" : address.toLowerCase(Locale.ROOT);
    }

    /**
     * 将原始数量转换为带精度的十进制表示
     *
     * @param raw 原始整数
     * @return 十进制数量
     */
    private BigDecimal toDecimalAmount(BigInteger raw) {
        if (raw == null || raw.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(raw).divide(decimalFactor, 18, RoundingMode.HALF_UP);
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
     * 存储所有目标代币的 Transfer 事件
     *
     * @param logs        交易所有日志
     * @param txHash      交易哈希
     * @param blockNumber 区块号
     * @param timestamp   区块时间戳（毫秒），如果为 null 则从链上获取
     */
    private void storeTransferEvents(List<Log> logs, String txHash, BigInteger blockNumber, Long timestamp) {
        if (databaseLogService == null || logs == null || logs.isEmpty()) {
            return;
        }

        Instant eventTime;
        if (timestamp != null) {
            eventTime = Instant.ofEpochMilli(timestamp);
        } else if (blockNumber != null) {
            eventTime = Instant.ofEpochMilli(getBlockTimestamp(blockNumber));
        } else {
            eventTime = Instant.now();
        }

        for (Log entry : logs) {
            // 只处理目标代币的 Transfer 事件
            if (!tokenAddress.equalsIgnoreCase(entry.getAddress())) {
                continue;
            }

            List<String> topics = entry.getTopics();
            if (topics == null || topics.isEmpty()) {
                continue;
            }
            String topic0 = topics.get(0).toLowerCase(Locale.ROOT);
            if (!TRANSFER_EVENT_SIGNATURE.equals(topic0)) {
                continue;
            }
            if (topics.size() < 3) {
                continue;
            }

            String from = decodeAddress(topics.get(1));
            String to = decodeAddress(topics.get(2));
            List<Type> decoded = FunctionReturnDecoder.decode(entry.getData(), TRANSFER_EVENT.getNonIndexedParameters());
            if (decoded.isEmpty()) {
                continue;
            }

            BigInteger valueRaw = (BigInteger) decoded.get(0).getValue();
            if (valueRaw == null) {
                continue;
            }

            BigDecimal amount = toDecimalAmount(valueRaw);
            Integer logIndex = entry.getLogIndex() != null ? entry.getLogIndex().intValue() : null;

            databaseLogService.logTransfer(
                    txHash,
                    entry.getAddress(),
                    from,
                    to,
                    amount,
                    valueRaw,
                    logIndex,
                    blockNumber,
                    eventTime
            );
        }
    }

    /** 交易方向 */
    private enum TradeDirection {
        /** 买入 */
        BUY("买入"),
        /** 卖出 */
        SELL("卖出");

        /** 中文展示 */
        private final String display;

        TradeDirection(String display) {
            this.display = display;
        }

        public String getDisplay() {
            return display;
        }
    }

    /**
     * 交易汇总信息
     */
    private static class TradeSummary {
        private final BigDecimal totalBuyAmount;
        private final BigDecimal totalSellAmount;
        private final BigDecimal totalBuyValue;
        private final BigDecimal totalSellValue;
        private final BigDecimal netAmount;
        private final BigDecimal netValue;
        private final BigDecimal avgBuyPrice;
        private final BigDecimal avgSellPrice;
        private final long tradeCount;

        private TradeSummary(BigDecimal totalBuyAmount, BigDecimal totalSellAmount, BigDecimal totalBuyValue,
                             BigDecimal totalSellValue, BigDecimal netAmount, BigDecimal netValue,
                             BigDecimal avgBuyPrice, BigDecimal avgSellPrice, long tradeCount) {
            this.totalBuyAmount = totalBuyAmount;
            this.totalSellAmount = totalSellAmount;
            this.totalBuyValue = totalBuyValue;
            this.totalSellValue = totalSellValue;
            this.netAmount = netAmount;
            this.netValue = netValue;
            this.avgBuyPrice = avgBuyPrice;
            this.avgSellPrice = avgSellPrice;
            this.tradeCount = tradeCount;
        }
    }

    /**
     * 交易识别结果
     */
    private static class TradeDetection {
        private final TradeDirection direction;
        private final BigDecimal tradeAmount;
        private final BigDecimal totalIn;
        private final BigDecimal totalOut;

        private TradeDetection(TradeDirection direction, BigDecimal tradeAmount, BigDecimal totalIn, BigDecimal totalOut) {
            this.direction = direction;
            this.tradeAmount = tradeAmount;
            this.totalIn = totalIn;
            this.totalOut = totalOut;
        }
    }
}

