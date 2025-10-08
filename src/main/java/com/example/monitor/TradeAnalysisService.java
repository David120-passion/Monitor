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
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
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
import java.util.concurrent.ConcurrentHashMap;

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
     *    event Swap(
     *         PoolId indexed id,
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
                    TypeReference.create(Int24.class)
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

    /** Web3j HTTP 客户端 */
    private final Web3j web3j;
    /** WebSocket 订阅用的 Web3j 客户端 */
    private final Web3j subscriptionWeb3j;
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

    /**
     * 构造函数
     */
    public TradeAnalysisService(Web3j httpWeb3j, Web3j subscriptionWeb3j, String tokenAddress, BigInteger tokenDecimals, String tokenSymbol,
                                DexPriceService priceService, DatabaseLogService databaseLogService) {
        this.web3j = httpWeb3j;
        this.subscriptionWeb3j = subscriptionWeb3j;
        this.tokenAddress = tokenAddress.toLowerCase(Locale.ROOT);
        this.tokenSymbol = tokenSymbol;
        this.priceService = priceService;
        this.databaseLogService = databaseLogService;
        int decimals = tokenDecimals == null ? 18 : Math.max(tokenDecimals.intValue(), 0);
        this.decimalFactor = BigDecimal.TEN.pow(decimals);
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
     * 启动监听
     */
    public void start() {
//        BigInteger startBlock = BigInteger.valueOf(
//                63654079
//        );
//        BigInteger endBlock = BigInteger.valueOf(
//                63654081
//        );
//        EthFilter filter = new EthFilter(
//                DefaultBlockParameter.valueOf(startBlock),
//                DefaultBlockParameter.valueOf(endBlock), // 一直扫描到最新区块
//                tokenAddress);

        EthFilter filter = new EthFilter(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST, tokenAddress);
        filter.addSingleTopic(EventEncoder.encode(TRANSFER_EVENT));
        subscriptionWeb3j.ethLogFlowable(filter).subscribe(this::handleTransferLog, throwable ->
                log.error("Error processing trade analysis transfer log", throwable));
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

            Optional<Transaction> transactionOpt = fetchTransaction(txHash);
            if (!transactionOpt.isPresent()) {
                return;
            }
            String txFrom = transactionOpt.get().getFrom();
            if (txFrom == null) {
                return;
            }
            String txFromNormalized = normalizeAddress(txFrom);

            Optional<TransactionReceipt> receiptOpt = fetchTransactionReceipt(txHash);
            if (!receiptOpt.isPresent()) {
                return;
            }
            TransactionReceipt receipt = receiptOpt.get();
            if (receipt.getLogs() == null || receipt.getLogs().isEmpty()) {
                return;
            }

            Optional<TradeDetection> detectionOpt = analyseTransactionLogs(txFromNormalized, receipt.getLogs(),txHash);
            if (!detectionOpt.isPresent()) {
                return;
            }
            TradeDetection detection = detectionOpt.get();

            BigInteger blockNumber = logEntry.getBlockNumber();
            if (blockNumber == null) {
                blockNumber = receipt.getBlockNumber();
                if (blockNumber == null) {
                    return;
                }
            }
            Optional<Long> timestampOpt = fetchBlockTimestamp(blockNumber);
            long timestamp = timestampOpt.orElse(Instant.now().toEpochMilli());
            Optional<BigDecimal> priceOpt = priceService.getPriceAtBlock(blockNumber);
            BigDecimal price = priceOpt.orElse(BigDecimal.ZERO);
            BigDecimal usdValue = detection.tradeAmount.multiply(price);

            TradeSummary summary = updateTotals(txFromNormalized, detection.direction, detection.tradeAmount, usdValue);

            Instant eventTime = Instant.ofEpochMilli(timestamp);
            String formattedTime = eventTime.atZone(TARGET_ZONE).format(TIME_FORMATTER);
            String message = String.format("TRADE address=%s action=%s amount=%s %s price=$%s value=$%s totalBuyAmount=%s totalSellAmount=%s " +
                            "totalBuyValue=$%s totalSellValue=$%s netAmount=%s netValue=$%s avgBuyPrice=$%s avgSellPrice=$%s trades=%s block=%s time=%s txHash=%s",
                    txFrom,
                    detection.direction.getDisplay(),
                    detection.tradeAmount.setScale(6, RoundingMode.HALF_UP),
                    tokenSymbol,
                    price.setScale(8, RoundingMode.HALF_UP),
                    usdValue.setScale(6, RoundingMode.HALF_UP),
                    summary.totalBuyAmount.setScale(6, RoundingMode.HALF_UP),
                    summary.totalSellAmount.setScale(6, RoundingMode.HALF_UP),
                    summary.totalBuyValue.setScale(6, RoundingMode.HALF_UP),
                    summary.totalSellValue.setScale(6, RoundingMode.HALF_UP),
                    summary.netAmount.setScale(6, RoundingMode.HALF_UP),
                    summary.netValue.setScale(6, RoundingMode.HALF_UP),
                    summary.avgBuyPrice.setScale(8, RoundingMode.HALF_UP),
                    summary.avgSellPrice.setScale(8, RoundingMode.HALF_UP),
                    summary.tradeCount,
                    blockNumber,
                    formattedTime,
                    txHash);
            log.info(message);
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
        } catch (Exception ex) {
            log.error("Failed to analyse trade", ex);
        }
    }

    /**
     * 分析交易日志，匹配 Swap 与 Transfer 事件
     *
     * @param txFromNormalized 交易发起人地址（小写）
     * @param logs             交易所有日志
     * @return 交易识别结果
     */
    private Optional<TradeDetection> analyseTransactionLogs(String txFromNormalized, List<Log> logs, String txHash) {
        if (logs == null || logs.isEmpty()) {
            return Optional.empty();
        }
        BigInteger swapInRaw = BigInteger.ZERO;
        BigInteger swapOutRaw = BigInteger.ZERO;
        boolean swapMatched = false;
        boolean swapSignatureDetected = false;

        BigInteger transferInRaw = BigInteger.ZERO;
        BigInteger transferOutRaw = BigInteger.ZERO;

        for (Log entry : logs) {
            List<String> topics = entry.getTopics();
            if (topics == null || topics.isEmpty()) {
                continue;
            }
            String topic0 = topics.get(0).toLowerCase(Locale.ROOT);
            if (SUPPORTED_SWAP_SIGNATURES.contains(topic0)) {
                swapSignatureDetected = true;
                Optional<TokenFlow> flowOpt = analyseSwapLog(topic0, entry, topics);
                if (flowOpt.isPresent()) {
                    swapMatched = true;
                    TokenFlow flow = flowOpt.get();
                    swapInRaw = swapInRaw.add(flow.received);
                    swapOutRaw = swapOutRaw.add(flow.sent);
                }
            }

            if (!tokenAddress.equalsIgnoreCase(entry.getAddress())) {
                continue;
            }
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

            BigInteger value = (BigInteger) decoded.get(0).getValue();
            if (txFromNormalized.equals(normalizeAddress(from))) {
                transferOutRaw = transferOutRaw.add(value);
            }
            if (txFromNormalized.equals(normalizeAddress(to))) {
                transferInRaw = transferInRaw.add(value);
            }
        }

        if (swapMatched) {
            return buildTradeDetection(swapInRaw, swapOutRaw, txHash);
        }
        if (!swapSignatureDetected) {
            return Optional.empty();
        }
        if (transferInRaw.signum() == 0 && transferOutRaw.signum() == 0) {
            log.info("Zero trade amounts detected in tx {}", txHash);
            return Optional.empty();
        }
        return buildTradeDetection(transferInRaw, transferOutRaw, txHash);
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

    private Optional<TokenFlow> analyseSwapLog(String topic0, Log entry, List<String> topics) {
        Optional<DexPriceService.PairMetadata> metadataOpt = resolveSwapPairMetadata(topic0, entry, topics);
        if (!metadataOpt.isPresent()) {
            return Optional.empty();
        }
        DexPriceService.PairMetadata metadata = metadataOpt.get();
        String token0 = normalizeAddress(metadata.token0);
        String token1 = normalizeAddress(metadata.token1);
        boolean trackToken0 = tokenAddress.equalsIgnoreCase(token0);
        boolean trackToken1 = tokenAddress.equalsIgnoreCase(token1);
        if (!trackToken0 && !trackToken1) {
            return Optional.empty();
        }

        BigInteger traderDelta;
        if (SWAP_EVENT_SIGNATURE_V2_NORMALIZED.equals(topic0)) {
            List<Type> decoded = decodeEventData(entry.getData(), SWAP_EVENT_V2.getNonIndexedParameters());
            if (decoded.size() < 4) {
                return Optional.empty();
            }
            BigInteger amount0In = ((Uint256) decoded.get(0)).getValue();
            BigInteger amount1In = ((Uint256) decoded.get(1)).getValue();
            BigInteger amount0Out = ((Uint256) decoded.get(2)).getValue();
            BigInteger amount1Out = ((Uint256) decoded.get(3)).getValue();
            traderDelta = trackToken0
                    ? amount0Out.subtract(amount0In)
                    : amount1Out.subtract(amount1In);
        } else if (PANCAKE_EVENT_SIGNATURE_V3_NORMALIZED.equals(topic0) || UNISWAP_EVENT_SIGNATURE_V3_NORMALIZED.equals(topic0)) {
            List<TypeReference<Type>> parameters = PANCAKE_EVENT_SIGNATURE_V3_NORMALIZED.equals(topic0)
                    ? PANCAKE_EVENT_V3.getNonIndexedParameters()
                    : UNISWAP_EVENT_V3.getNonIndexedParameters();
            List<Type> decoded = decodeEventData(entry.getData(), parameters);
            if (decoded.size() < 2) {
                return Optional.empty();
            }
            BigInteger amount0 = ((Int256) decoded.get(0)).getValue();
            BigInteger amount1 = ((Int256) decoded.get(1)).getValue();
            BigInteger poolDelta = trackToken0 ? amount0 : amount1;
            traderDelta = poolDelta.negate();
        } else if (PANCAKESWAP_EVENT_SIGNATURE_V4_NORMALIZED.equals(topic0) || UNISWAP_EVENT_SIGNATURE_V4_NORMALIZED.equals(topic0)) {
            List<TypeReference<Type>> parameters = PANCAKESWAP_EVENT_SIGNATURE_V4_NORMALIZED.equals(topic0)
                    ? PANCAKESWAP_EVENT_V4.getNonIndexedParameters()
                    : UNISWAP_EVENT_V4.getNonIndexedParameters();
            List<Type> decoded = decodeEventData(entry.getData(), parameters);
            if (decoded.size() < 2) {
                return Optional.empty();
            }
            BigInteger amount0 = ((Int128) decoded.get(0)).getValue();
            BigInteger amount1 = ((Int128) decoded.get(1)).getValue();
            BigInteger poolDelta = trackToken0 ? amount0 : amount1;
            traderDelta = poolDelta.negate();
        } else {
            return Optional.empty();
        }

        BigInteger received = traderDelta.signum() > 0 ? traderDelta : BigInteger.ZERO;
        BigInteger sent = traderDelta.signum() < 0 ? traderDelta.negate() : BigInteger.ZERO;
        if (received.signum() == 0 && sent.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(new TokenFlow(received, sent));
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
