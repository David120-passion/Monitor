package com.example.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Int24;
import org.web3j.abi.datatypes.generated.Int256;
import org.web3j.abi.datatypes.generated.Uint128;
import org.web3j.abi.datatypes.generated.Uint160;
import org.web3j.abi.datatypes.generated.Uint24;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 流动性池监控服务
 */
public class LiquidityMonitorService {
    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(LiquidityMonitorService.class);
    /** Web3j 客户端 */
    private final Web3j web3j;
    /** 目标代币地址 */
    private final String tokenAddress;
    /** 价格服务 */
    private final DexPriceService priceService;
    /** 交易分析服务 */
    private final TradeAnalysisService tradeAnalysisService;
    /** 已注册的池子集合 */
    private final Set<String> registeredPools = ConcurrentHashMap.newKeySet();
    /** 已订阅 Burn 事件的池子集合 */
    private final Set<String> burnSubscribedPools = ConcurrentHashMap.newKeySet();
    /** 已订阅 Mint 事件的池子集合 */
    private final Set<String> mintSubscribedPools = ConcurrentHashMap.newKeySet();
    /** 已知的 V4 池子元数据 */
    private final ConcurrentHashMap<String, V4PoolMetadata> v4Pools = new ConcurrentHashMap<>();
    /** V4 池子实时状态 */
    private final ConcurrentHashMap<String, V4PoolState> v4PoolStates = new ConcurrentHashMap<>();
    /** V2/V3 池子 TVL（美元）缓存 */
    private final ConcurrentHashMap<String, BigDecimal> poolTvlUsdCache = new ConcurrentHashMap<>();
    /** V4 池子 TVL（美元）缓存 */
    private final ConcurrentHashMap<String, BigDecimal> v4PoolTvlUsdCache = new ConcurrentHashMap<>();
    /** 已订阅 V4 ModifyLiquidity 事件的池子集合 */
    private final Set<String> v4SubscribedPools = ConcurrentHashMap.newKeySet();
    /** 区块时间缓存 */
    private final ConcurrentHashMap<BigInteger, Instant> blockTimestampCache = new ConcurrentHashMap<>();
    /** 代币创建区块 */
    private final BigInteger tokenCreationBlock;
    /** 日志查询单次最大区块跨度 */
    private static final BigInteger MAX_LOG_BLOCK_RANGE = BigInteger.valueOf(50_000L);
    /** 价格计算精度 */
    private static final MathContext PRICE_CONTEXT = new MathContext(40, RoundingMode.HALF_UP);
    /** Q96 固定点常量 */
    private static final BigDecimal Q96 = new BigDecimal(BigInteger.ONE.shiftLeft(96));
    /** 日志时间时区 */
    private static final ZoneId TARGET_TIME_ZONE = ZoneId.of("Asia/Shanghai");
    /** 日志时间格式 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** V2 PairCreated 事件 */
    private static final Event PAIR_CREATED_EVENT = new Event("PairCreated",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class),
                    TypeReference.create(Uint256.class)
            ));
    /** V3 PoolCreated 事件 */
    private static final Event POOL_CREATED_EVENT = new Event("PoolCreated",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Uint24.class,true),
                    TypeReference.create(Int24.class),
                    TypeReference.create(Address.class)
            ));
    /** V2 Burn 事件 */
    private static final Event BURN_EVENT_V2 = new Event("Burn",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Address.class, true)
            ));
    /** V3 Burn 事件 */
    private static final Event BURN_EVENT_V3 = new Event("Burn",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Int24.class, true),
                    TypeReference.create(Int24.class, true),
                    TypeReference.create(Uint128.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class)
            ));
    /** V2 Mint 事件 */
    private static final Event MINT_EVENT_V2 = new Event("Mint",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class)
            ));
    /** V3 Mint 事件 */
    private static final Event MINT_EVENT_V3 = new Event("Mint",
            Arrays.asList(
                    TypeReference.create(Address.class),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Int24.class, true),
                    TypeReference.create(Int24.class, true),
                    TypeReference.create(Uint128.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class)
            ));
    /** PancakeSwap V4 Initialize 事件 */
    private static final Event INITIALIZE_EVENT_V4_PANCAKE = new Event("Initialize",
            Arrays.asList(
                    TypeReference.create(Bytes32.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class),
                    TypeReference.create(Uint24.class),
                    TypeReference.create(Bytes32.class),
                    TypeReference.create(Uint160.class),
                    TypeReference.create(Int24.class)
            ));
    /** Uniswap V4 Initialize 事件 */
    private static final Event INITIALIZE_EVENT_V4_UNISWAP = new Event("Initialize",
            Arrays.asList(
                    TypeReference.create(Bytes32.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Uint24.class),
                    TypeReference.create(Int24.class),
                    TypeReference.create(Address.class),
                    TypeReference.create(Uint160.class),
                    TypeReference.create(Int24.class)
            ));
    /** V4 ModifyLiquidity 事件 */
    private static final Event MODIFY_LIQUIDITY_EVENT_V4 = new Event("ModifyLiquidity",
            Arrays.asList(
                    TypeReference.create(Bytes32.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Int24.class),
                    TypeReference.create(Int24.class),
                    TypeReference.create(Int256.class),
                    TypeReference.create(Bytes32.class)
            ));

    /**
     * 构造函数
     */
    public LiquidityMonitorService(Web3j web3j, String tokenAddress, DexPriceService priceService,
                                   TradeAnalysisService tradeAnalysisService, BigInteger tokenCreationBlock) {
        this.web3j = web3j;
        this.tokenAddress = tokenAddress.toLowerCase();
        this.priceService = priceService;
        this.tradeAnalysisService = tradeAnalysisService;
        this.tokenCreationBlock = tokenCreationBlock;
    }

    /**
     * 启动监听
     */
    public void start() {
        DexConstants.V2_FACTORIES.forEach(factory -> subscribeFactory(factory, PAIR_CREATED_EVENT));
        DexConstants.V3_FACTORIES.forEach(factory -> subscribeFactory(factory, POOL_CREATED_EVENT));
        DexConstants.V4_FACTORIES.forEach(this::subscribeV4Factory);
    }

    /**
     * 手动注册初始池子
     */
    public void registerInitialPairs() {
        priceService.findOrCreatePairs(tokenAddress, DexConstants.USDT_ADDRESS)
                .forEach(pair -> {
                    registerPool(pair);
                    subscribeMint(pair, MINT_EVENT_V2);
                    subscribeBurn(pair, BURN_EVENT_V2);
                });
        priceService.findOrCreatePairs(tokenAddress, DexConstants.WBNB_ADDRESS)
                .forEach(pair -> {
                    registerPool(pair);
                    subscribeMint(pair, MINT_EVENT_V2);
                    subscribeBurn(pair, BURN_EVENT_V2);
                });
        priceService.findOrCreateV3Pools(tokenAddress, DexConstants.USDT_ADDRESS)
                .forEach(pool -> {
                    registerPool(pool);
                    subscribeMint(pool, MINT_EVENT_V3);
                    subscribeBurn(pool, BURN_EVENT_V3);
                });
        priceService.findOrCreateV3Pools(tokenAddress, DexConstants.WBNB_ADDRESS)
                .forEach(pool -> {
                    registerPool(pool);
                    subscribeMint(pool, MINT_EVENT_V3);
                    subscribeBurn(pool, BURN_EVENT_V3);
                });
    }

    /**
     * 订阅指定工厂事件
     *
     * @param factoryAddress 工厂合约地址
     * @param event          事件
     */
    private void subscribeFactory(String factoryAddress, Event event) {
        EthFilter filter = new EthFilter(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST, factoryAddress);
        filter.addSingleTopic(EventEncoder.encode(event));
        web3j.ethLogFlowable(filter).subscribe(logEntry -> handleFactoryLog(event, logEntry), throwable ->
                log.error("Error processing factory event", throwable));
    }

    /**
     * 订阅 V4 工厂 Initialize 事件
     *
     * @param factoryAddress 工厂合约地址
     */
    private void subscribeV4Factory(String factoryAddress) {
        Event initEvent = resolveV4InitializeEvent(factoryAddress);
        DefaultBlockParameter subscriptionStart = prepareHistoricalSubscription(
                factoryAddress,
                initEvent,
                Collections.emptyList(),
                logEntry -> handleV4InitializeLog(factoryAddress, logEntry),
                "V4 initialize"
        );
        EthFilter initFilter = new EthFilter(subscriptionStart, DefaultBlockParameterName.LATEST, factoryAddress);
        initFilter.addSingleTopic(EventEncoder.encode(initEvent));
        web3j.ethLogFlowable(initFilter).subscribe(logEntry -> handleV4InitializeLog(factoryAddress, logEntry), throwable ->
                log.error("Error processing V4 initialize event", throwable));
    }

    /**
     * 根据工厂地址解析对应的 V4 Initialize 事件
     */
    private Event resolveV4InitializeEvent(String factoryAddress) {
        String normalized = normalizeAddress(factoryAddress);
        if (normalized != null && DexConstants.UNISWAP_V4_FACTORY.equalsIgnoreCase(normalized)) {
            return INITIALIZE_EVENT_V4_UNISWAP;
        }
        return INITIALIZE_EVENT_V4_PANCAKE;
    }

    /**
     * 处理工厂日志
     *
     * @param event    事件
     * @param logEntry 日志
     */
    private void handleFactoryLog(Event event, Log logEntry) {
        try {
            List<String> topics = logEntry.getTopics();
            if (event.equals(PAIR_CREATED_EVENT)) {
                if (topics.size() < 3) {
                    return;
                }
                String token0 = decodeAddress(topics.get(1));
                String token1 = decodeAddress(topics.get(2));
                List<Type> data = decodeEventData(logEntry.getData(), event);
                if (data.size() < 2) {
                    return;
                }
                String pairAddress = data.get(0).getValue().toString();
                BigInteger liquidity = (BigInteger) data.get(1).getValue();
                if (matchesTarget(token0, token1)) {
                    Optional<DexPriceService.PairMetadata> metadataOpt = priceService.loadPairMetadata(pairAddress, true,
                            DexPriceService.PoolType.V2, null);
                    if (metadataOpt.isPresent()) {
                        DexPriceService.PairMetadata metadata = metadataOpt.get();
                        registerPool(metadata);
                        log.info("PAIR_CREATED pair={} name={} token0={} token1={} liquidity={} time={}",
                                pairAddress,
                                metadata.getDisplayName(),
                                token0,
                                token1,
                                liquidity,
                                formatTimestamp(resolveLogTimestamp(logEntry)));
                        subscribeMint(metadata, MINT_EVENT_V2);
                        subscribeBurn(metadata, BURN_EVENT_V2);
                    } else {
                        log.info("PAIR_CREATED pair={} token0={} token1={} liquidity={} time={}",
                                pairAddress, token0, token1, liquidity, formatTimestamp(resolveLogTimestamp(logEntry)));
                    }
                }
            } else if (event.equals(POOL_CREATED_EVENT)) {
                if (topics.size() < 3) {
                    return;
                }
                String token0 = decodeAddress(topics.get(1));
                String token1 = decodeAddress(topics.get(2));
                BigInteger fee = new BigInteger(topics.get(3).substring(2),16);
                List<Type> data = decodeEventData(logEntry.getData(), event);
                if (data.size() < 2) {
                    return;
                }
                BigInteger tickSpacing = (BigInteger) data.get(0).getValue();
                String poolAddress = data.get(1).getValue().toString();
                if (matchesTarget(token0, token1)) {
                    Optional<DexPriceService.PairMetadata> metadataOpt = priceService.loadPairMetadata(poolAddress, false,
                            DexPriceService.PoolType.V3, fee);
                    if (metadataOpt.isPresent()) {
                        DexPriceService.PairMetadata metadata = metadataOpt.get();
                        registerPool(metadata);
                        log.info("POOL_CREATED pool={} name={} fee={} tickSpacing={} time={}",
                                poolAddress,
                                metadata.getDisplayName(),
                                formatFee(metadata.fee != null ? metadata.fee : fee),
                                tickSpacing,
                                formatTimestamp(resolveLogTimestamp(logEntry)));
                        subscribeMint(metadata, MINT_EVENT_V3);
                        subscribeBurn(metadata, BURN_EVENT_V3);
                    } else {
                        log.info("POOL_CREATED pool={} token0={} token1={} fee={} tickSpacing={} time={}",
                                poolAddress, token0, token1, fee, tickSpacing, formatTimestamp(resolveLogTimestamp(logEntry)));
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Failed to handle factory log", ex);
        }
    }

    /**
     * 安全解码事件数据
     */
    private List<Type> decodeEventData(String dataHex, Event event) {
        if (event == null) {
            return Collections.emptyList();
        }
        List<TypeReference<Type>> parameters = event.getNonIndexedParameters();
        if (parameters == null || parameters.isEmpty()) {
            return Collections.emptyList();
        }
        if (dataHex == null || dataHex.length() < 2) {
            return Collections.emptyList();
        }
        int expectedWords = parameters.size();
        int minimumLength = 2 + expectedWords * 64;
        if (dataHex.length() < minimumLength) {
            return Collections.emptyList();
        }
        try {
            return FunctionReturnDecoder.decode(dataHex, parameters);
        } catch (Exception ex) {
            log.debug("Failed to decode event data for {}", event.getName(), ex);
            return Collections.emptyList();
        }
    }

    /**
     * 处理 V4 Initialize 日志
     *
     * @param factoryAddress 工厂地址
     * @param logEntry       日志
     */
    private void handleV4InitializeLog(String factoryAddress, Log logEntry) {
        try {
            List<String> topics = logEntry.getTopics();
            if (topics.size() < 2) {
                return;
            }
            String poolIdTopic = topics.get(1);
            Event initEvent = resolveV4InitializeEvent(factoryAddress);
            List<Type> data = decodeEventData(logEntry.getData(), initEvent);
            String currency0;
            String currency1;
            BigInteger fee;
            BigInteger sqrtPriceX96;
            String hooks;
            if (initEvent == INITIALIZE_EVENT_V4_UNISWAP) {
                if (topics.size() < 4 || data.size() < 5) {
                    return;
                }
                currency0 = normalizeAddress(decodeAddress(topics.get(2)));
                currency1 = normalizeAddress(decodeAddress(topics.get(3)));
                fee = ((Uint24) data.get(0)).getValue();
                sqrtPriceX96 = ((Uint160) data.get(3)).getValue();
                hooks = normalizeAddress(((Address) data.get(2)).getValue());
            } else {
                if (topics.size() < 4 || data.size() < 5) {
                    return;
                }
                currency0 = normalizeAddress(decodeAddress(topics.get(2)));
                currency1 = normalizeAddress(decodeAddress(topics.get(3)));
                hooks = normalizeAddress(((Address) data.get(0)).getValue());
                fee = ((Uint24) data.get(1)).getValue();
                sqrtPriceX96 = ((Uint160) data.get(3)).getValue();
            }
            if (!matchesTarget(currency0, currency1)) {
                return;
            }
            String normalizedPoolId = normalizePoolId(poolIdTopic);
            V4PoolMetadata metadata = buildV4Metadata(factoryAddress,
                    normalizedPoolId,
                    currency0,
                    currency1,
                    fee,
                    hooks);
            V4PoolMetadata previous = v4Pools.putIfAbsent(normalizedPoolId, metadata);
            V4PoolMetadata effectiveMetadata = previous != null ? previous : metadata;
            BigInteger currency0Decimals = priceService.resolveTokenDecimals(currency0);
            BigInteger currency1Decimals = priceService.resolveTokenDecimals(currency1);
            if (previous == null) {
                registerV4PoolForTradeAnalysis(metadata, currency0Decimals, currency1Decimals);
            }
            Optional<BigDecimal> rawPriceOpt = calculateToken1PerToken0Price(sqrtPriceX96, currency0Decimals, currency1Decimals);
            Optional<BigDecimal> trackedPriceOpt = calculateTargetPriceFromSqrt(sqrtPriceX96, currency0Decimals, currency1Decimals, currency0, currency1);
            V4PoolState state = v4PoolStates.compute(normalizedPoolId, (key, existing) -> {
                V4PoolState target = existing != null ? existing : new V4PoolState();
                target.initialize(currency0Decimals, currency1Decimals, rawPriceOpt.orElse(null));
                return target;
            });
            if (previous == null && state != null) {
                String timestampText = formatTimestamp(resolveLogTimestamp(logEntry));
                String swapName = resolveV4SwapName(effectiveMetadata);
                String rawPriceText = rawPriceOpt.map(this::formatDecimal).orElse("unknown");
                String inversePriceText = rawPriceOpt
                        .filter(price -> price.compareTo(BigDecimal.ZERO) > 0)
                        .map(price -> BigDecimal.ONE.divide(price, 18, RoundingMode.HALF_UP))
                        .map(this::formatDecimal)
                        .orElse("unknown");
                String trackedPriceText = trackedPriceOpt.map(this::formatDecimal).orElse("unknown");
                log.info("poolId = {} topic = POOL_INITIALIZED_V4 swap={} name={} fee={} priceToken1PerToken0={} priceToken0PerToken1={} targetTokenPrice={}  time={}",
                        poolIdTopic,
                        swapName,
                        effectiveMetadata.getDisplayName(),
                        formatFee(fee),
                        rawPriceText,
                        inversePriceText,
                        trackedPriceText,
                        timestampText);
            }
            subscribeV4ModifyLiquidity(factoryAddress, poolIdTopic);
        } catch (Exception ex) {
            log.error("Failed to handle V4 initialize log", ex);
        }
    }

    /**
     * 订阅 V4 ModifyLiquidity 事件
     *
     * @param factoryAddress 工厂地址
     * @param poolIdTopic    池子 ID 主题
     */
    private void subscribeV4ModifyLiquidity(String factoryAddress, String poolIdTopic) {
        if (factoryAddress == null || poolIdTopic == null) {
            return;
        }
        String normalizedPoolId = normalizePoolId(poolIdTopic);
        String normalizedKey = normalizeAddress(factoryAddress) + ":" + normalizedPoolId;
        if (!v4SubscribedPools.add(normalizedKey)) {
            return;
        }
        DefaultBlockParameter subscriptionStart = prepareHistoricalSubscription(
                factoryAddress,
                MODIFY_LIQUIDITY_EVENT_V4,
                Collections.singletonList(normalizedPoolId),
                this::handleV4ModifyLiquidityLog,
                "V4 modify liquidity"
        );
        EthFilter modifyFilter = new EthFilter(subscriptionStart, DefaultBlockParameterName.LATEST, factoryAddress);
        modifyFilter.addSingleTopic(EventEncoder.encode(MODIFY_LIQUIDITY_EVENT_V4));
        modifyFilter.addOptionalTopics(normalizedPoolId);
        web3j.ethLogFlowable(modifyFilter).subscribe(logEntry -> handleV4ModifyLiquidityLog(logEntry), throwable ->
                log.error("Error processing V4 modify liquidity event", throwable));
    }

    /**
     * 处理 V4 ModifyLiquidity 日志
     *
     * @param logEntry 日志  想算v4的tvl 要么还得监听上 swap事件 把代币数量本地维护  如果能根据liquidity算出代币数量就好了
     *                 如果我只拿当前活跃区间的liquidity去计算tvl  那么会不会不准确
     */
    private void handleV4ModifyLiquidityLog(Log logEntry) {
        try {
            List<String> topics = logEntry.getTopics();
            if (topics.size() < 3) return;

            String poolId = normalizePoolId(topics.get(1));
            V4PoolMetadata metadata = v4Pools.get(poolId);
            if (metadata == null) return;

            List<Type> data = decodeEventData(logEntry.getData(), MODIFY_LIQUIDITY_EVENT_V4);
            if (data.size() < 4) return;

            String sender = normalizeAddress(decodeAddress(topics.get(2)));
            int tickLower = ((Int24) data.get(0)).getValue().intValue();
            int tickUpper = ((Int24) data.get(1)).getValue().intValue();
            BigInteger liquidityDelta = ((Int256) data.get(2)).getValue();
            int sign = liquidityDelta.signum();
            String action = sign > 0 ? "POOL_ADDED_V4" : (sign < 0 ? "POOL_REMOVED_V4" : "POOL_UPDATED_V4");
            Instant timestamp = resolveLogTimestamp(logEntry);

            // ✅ 获取代币精度
            BigInteger decimals0 = priceService.resolveTokenDecimals(metadata.currency0);
            BigInteger decimals1 = priceService.resolveTokenDecimals(metadata.currency1);

            V4PoolState existingState = v4PoolStates.get(poolId);
            BigDecimal currentPrice = existingState != null ? existingState.resolvePrice() : null;

            // ✅ 计算 tick 区间价格范围
            Optional<DexPriceService.PriceRange> priceRangeOpt = calculateV4PriceRange(metadata, decimals0, decimals1, tickLower, tickUpper);
            String priceRangeText = formatPriceRange(priceRangeOpt);

            Bytes32 saltBytes = (Bytes32) data.get(3);
            BigDecimal amount0Delta = BigDecimal.ZERO;
            BigDecimal amount1Delta = BigDecimal.ZERO;

            if (liquidityDelta.signum() != 0) {
                Optional<LiquidityEstimation> estimationOpt = estimateV4LiquidityDelta(
                        liquidityDelta,
                        tickLower,
                        tickUpper,
                        decimals0,
                        decimals1,
                        currentPrice
                );

                if (estimationOpt.isPresent()) {
                    LiquidityEstimation estimation = estimationOpt.get();
                    amount0Delta = estimation.amount0.multiply(BigDecimal.valueOf(sign));
                    amount1Delta = estimation.amount1.multiply(BigDecimal.valueOf(sign));
                }
            }

            final BigDecimal finalAmount0Delta = amount0Delta;
            final BigDecimal finalAmount1Delta = amount1Delta;

            // ✅ 更新池状态（非累计）
            V4PoolState state = v4PoolStates.compute(poolId, (key, existing) -> {
                V4PoolState target = existing != null ? existing : new V4PoolState();
                target.ensureDecimals(decimals0, decimals1);
                if (finalAmount0Delta.signum() != 0 || finalAmount1Delta.signum() != 0) {
                    target.applyDelta(finalAmount0Delta, finalAmount1Delta);
                }
                return target;
            });

            // ✅ 更新 TVL
            updateV4TvlUsdCache(metadata, state);

            String amount0Text = formatDecimal(finalAmount0Delta);
            String amount1Text = formatDecimal(finalAmount1Delta);
            String amount0Remaining = formatAmount(state != null ? state.getAmount0() : null);
            String amount1Remaining = formatAmount(state != null ? state.getAmount1() : null);
            String tvlRemaining = v4PoolTvlUsdCache.get(poolId) + " USD";
            String timestampText = formatTimestamp(timestamp);
            String swapName = resolveV4SwapName(metadata);
            String salt = saltBytes != null ? Numeric.toHexString(saltBytes.getValue()) : "";

            log.info("POOL_MODIFY_V4 poolId={} action={} swap={} name={} sender={} fee={} " +
                            "liquidityDelta={} amount0Delta={} amount1Delta={} priceRange={} " +
                            "amount0Remaining={} amount1Remaining={} tvlRemaining={} salt={} time={}",
                    poolId,
                    action,
                    swapName,
                    metadata.getDisplayName(),
                    sender,
                    formatFee(metadata.fee),
                    liquidityDelta,
                    amount0Text,
                    amount1Text,
                    priceRangeText,
                    amount0Remaining,
                    amount1Remaining,
                    tvlRemaining,
                    salt,
                    timestampText);
        } catch (Exception ex) {
            log.error("Failed to handle V4 ModifyLiquidity log", ex);
        }
    }

    /**
     * 订阅 Burn 事件以检测撤池
     *
     * @param metadata 池子元数据
     * @param event    事件定义
     */
    private void subscribeBurn(DexPriceService.PairMetadata metadata, Event event) {
        if (metadata == null || metadata.pairAddress == null || event == null) {
            return;
        }
        String eventTopic = EventEncoder.encode(event);
        String pairAddress = metadata.pairAddress;
        String token0 = metadata.token0;
        String token1 = metadata.token1;
        String normalized = pairAddress.toLowerCase() + ":" + eventTopic;
        if (!burnSubscribedPools.add(normalized)) {
            return;
        }
        EthFilter filter = new EthFilter(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST, pairAddress);
        filter.addSingleTopic(eventTopic);
        web3j.ethLogFlowable(filter).subscribe(logEntry -> handleBurnLog(pairAddress, token0, token1, event, logEntry), throwable ->
                log.error("Error processing burn event", throwable));
    }

    /**
     * 订阅 Mint 事件以检测加池
     *
     * @param metadata 池子元数据
     * @param event    事件定义
     */
    private void subscribeMint(DexPriceService.PairMetadata metadata, Event event) {
        if (metadata == null || metadata.pairAddress == null || event == null) {
            return;
        }
        String eventTopic = EventEncoder.encode(event);
        String pairAddress = metadata.pairAddress;
        String token0 = metadata.token0;
        String token1 = metadata.token1;
        String normalized = pairAddress.toLowerCase() + ":" + eventTopic;
        if (!mintSubscribedPools.add(normalized)) {
            return;
        }
        EthFilter filter = new EthFilter(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST, pairAddress);
        filter.addSingleTopic(eventTopic);
        web3j.ethLogFlowable(filter).subscribe(logEntry -> handleMintLog(pairAddress, token0, token1, event, logEntry), throwable ->
                log.error("Error processing mint event", throwable));
    }

    /**
     * 处理 Burn 日志
     *
     * @param pairAddress 池子地址
     * @param token0      token0 地址
     * @param token1      token1 地址
     * @param logEntry    日志
     */
    private void handleBurnLog(String pairAddress, String token0, String token1, Event event, Log logEntry) {
        try {
            DexPriceService.PoolType poolType = event.equals(BURN_EVENT_V2)
                    ? DexPriceService.PoolType.V2
                    : DexPriceService.PoolType.V3;
            Optional<DexPriceService.PairMetadata> metadataOpt = priceService.loadPairMetadata(pairAddress,
                    poolType == DexPriceService.PoolType.V2,
                    poolType,
                    null);
            if (event.equals(BURN_EVENT_V2)) {
                List<Type> data = FunctionReturnDecoder.decode(logEntry.getData(), event.getNonIndexedParameters());
                if (data.size() < 2) {
                    return;
                }
                BigInteger amount0 = (BigInteger) data.get(0).getValue();
                BigInteger amount1 = (BigInteger) data.get(1).getValue();
                BigDecimal normalized0 = normalizeAmount(amount0, metadataOpt, true);
                BigDecimal normalized1 = normalizeAmount(amount1, metadataOpt, false);
                String sender = logEntry.getTopics().size() > 1 ? decodeAddress(logEntry.getTopics().get(1)) : "";
                String to = logEntry.getTopics().size() > 2 ? decodeAddress(logEntry.getTopics().get(2)) : "";
                String pairName = formatPairName(metadataOpt, token0, token1);
                log.info("POOL_REMOVED pair={} name={} sender={} to={} token0={} token1={} amount0={} amount1={} time={}",
                        pairAddress, pairName, sender, to, token0, token1, normalized0, normalized1,
                        formatTimestamp(resolveLogTimestamp(logEntry)));
                if (amount0.equals(BigInteger.ZERO) || amount1.equals(BigInteger.ZERO)) {
                }
            } else if (event.equals(BURN_EVENT_V3)) {
                List<Type> data = FunctionReturnDecoder.decode(logEntry.getData(), event.getNonIndexedParameters());
                if (data.size() < 3) {
                    return;
                }
                BigInteger burnedLiquidity = (BigInteger) data.get(0).getValue();
                BigInteger amount0 = (BigInteger) data.get(1).getValue();
                BigInteger amount1 = (BigInteger) data.get(2).getValue();
                BigDecimal normalized0 = normalizeAmount(amount0, metadataOpt, true);
                BigDecimal normalized1 = normalizeAmount(amount1, metadataOpt, false);
                String owner = logEntry.getTopics().size() > 1 ? decodeAddress(logEntry.getTopics().get(1)) : "";
                int tickLower = logEntry.getTopics().size() > 2
                        ? ((Int24) FunctionReturnDecoder.decodeIndexedValue(logEntry.getTopics().get(2), TypeReference.create(Int24.class, true))).getValue().intValue()
                        : 0;
                int tickUpper = logEntry.getTopics().size() > 3
                        ? ((Int24) FunctionReturnDecoder.decodeIndexedValue(logEntry.getTopics().get(3), TypeReference.create(Int24.class, true))).getValue().intValue()
                        : 0;
                Optional<DexPriceService.PriceRange> priceRangeOpt = metadataOpt.flatMap(meta ->
                        priceService.calculateTargetPriceRange(meta, tickLower, tickUpper));
                String priceRange = formatPriceRange(priceRangeOpt);
                String pairName = formatPairName(metadataOpt, token0, token1);
                String feeText = metadataOpt.map(meta -> formatFee(meta.fee)).orElse(formatFee(null));
                log.info("POOL_REMOVED_V3 pool={} name={} owner={} fee={} priceRange={} token0={} token1={}  burnedLiquidity={} amount0={} amount1={} time={}",
                        pairAddress, pairName, owner, feeText, priceRange, token0, token1,  burnedLiquidity, normalized0, normalized1,
                        formatTimestamp(resolveLogTimestamp(logEntry)));
                if (amount0.equals(BigInteger.ZERO) || amount1.equals(BigInteger.ZERO)) {
                }
            }
            metadataOpt.ifPresent(this::refreshTvlCacheForPair);
        } catch (Exception ex) {
            log.error("Failed to handle burn log", ex);
        }
    }

    /**
     * 处理 Mint 日志
     *
     * @param pairAddress 池子地址
     * @param token0      token0 地址
     * @param token1      token1 地址
     * @param event       事件
     * @param logEntry    日志
     */
    private void handleMintLog(String pairAddress, String token0, String token1, Event event, Log logEntry) {
        try {
            DexPriceService.PoolType poolType = event.equals(MINT_EVENT_V2)
                    ? DexPriceService.PoolType.V2
                    : DexPriceService.PoolType.V3;
            Optional<DexPriceService.PairMetadata> metadataOpt = priceService.loadPairMetadata(pairAddress,
                    poolType == DexPriceService.PoolType.V2,
                    poolType,
                    null);
            if (event.equals(MINT_EVENT_V2)) {
                List<Type> data = FunctionReturnDecoder.decode(logEntry.getData(), event.getNonIndexedParameters());
                if (data.size() < 2) {
                    return;
                }
                BigInteger amount0 = (BigInteger) data.get(0).getValue();
                BigInteger amount1 = (BigInteger) data.get(1).getValue();
                BigDecimal normalized0 = normalizeAmount(amount0, metadataOpt, true);
                BigDecimal normalized1 = normalizeAmount(amount1, metadataOpt, false);
                String sender = logEntry.getTopics().size() > 1 ? decodeAddress(logEntry.getTopics().get(1)) : "";
                String pairName = formatPairName(metadataOpt, token0, token1);
                log.info("POOL_ADDED pair={} name={} sender={} token0={} token1={} amount0={} amount1={} time={}",
                        pairAddress, pairName, sender, token0, token1, normalized0, normalized1,
                        formatTimestamp(resolveLogTimestamp(logEntry)));
            } else if (event.equals(MINT_EVENT_V3)) {
                List<Type> data = FunctionReturnDecoder.decode(logEntry.getData(), event.getNonIndexedParameters());
                if (data.size() < 4) {
                    return;
                }
                String sender = data.get(0).getValue().toString();
                BigInteger liquidity = (BigInteger) data.get(1).getValue();
                BigInteger amount0 = (BigInteger) data.get(2).getValue();
                BigInteger amount1 = (BigInteger) data.get(3).getValue();
                BigDecimal normalized0 = normalizeAmount(amount0, metadataOpt, true);
                BigDecimal normalized1 = normalizeAmount(amount1, metadataOpt, false);
                String owner = logEntry.getTopics().size() > 1 ? decodeAddress(logEntry.getTopics().get(1)) : "";
                int tickLower = logEntry.getTopics().size() > 2
                        ? ((Int24) FunctionReturnDecoder.decodeIndexedValue(logEntry.getTopics().get(2), TypeReference.create(Int24.class, true))).getValue().intValue()
                        : 0;
                int tickUpper = logEntry.getTopics().size() > 3
                        ? ((Int24) FunctionReturnDecoder.decodeIndexedValue(logEntry.getTopics().get(3), TypeReference.create(Int24.class, true))).getValue().intValue()
                        : 0;
                Optional<DexPriceService.PriceRange> priceRangeOpt = metadataOpt.flatMap(meta ->
                        priceService.calculateTargetPriceRange(meta, tickLower, tickUpper));
                String priceRange = formatPriceRange(priceRangeOpt);
                String pairName = formatPairName(metadataOpt, token0, token1);
                String feeText = metadataOpt.map(meta -> formatFee(meta.fee)).orElse(formatFee(null));
                log.info("POOL_ADDED_V3 pool={} name={} sender={} owner={} fee={} priceRange={} token0={} token1={}  amount0={} amount1={} time={}",
                        pairAddress, pairName, sender, owner, feeText, priceRange, token0, token1,  normalized0, normalized1,
                        formatTimestamp(resolveLogTimestamp(logEntry)));
            }
            metadataOpt.ifPresent(this::refreshTvlCacheForPair);
        } catch (Exception ex) {
            log.error("Failed to handle mint log", ex);
        }
    }

    /**
     * 判断是否包含目标代币
     */
    private boolean matchesTarget(String token0, String token1) {
        return tokenAddress.equalsIgnoreCase(token0) || tokenAddress.equalsIgnoreCase(token1);
    }

    /**
     * 注册池子
     *
     * @param metadata 池子元数据
     */
    private void registerPool(DexPriceService.PairMetadata metadata) {
        if (metadata == null || metadata.pairAddress == null) {
            return;
        }
        priceService.loadPairMetadata(metadata.pairAddress,
                metadata.poolType == DexPriceService.PoolType.V2,
                metadata.poolType,
                metadata.fee);
        String normalized = metadata.pairAddress.toLowerCase();
        if (registeredPools.add(normalized)) {
            Optional<DexPriceService.PoolSnapshot> snapshotOpt = priceService.loadPoolSnapshot(metadata);
            snapshotOpt.ifPresent(snapshot -> cacheUsdTvl(metadata, snapshot));
            String amount0Text = snapshotOpt.map(snapshot -> snapshot.token0Amount)
                    .map(this::formatDecimal)
                    .orElse("unknown");
            String amount1Text = snapshotOpt.map(snapshot -> snapshot.token1Amount)
                    .map(this::formatDecimal)
                    .orElse("unknown");

            String tvlText = poolTvlUsdCache.get(normalized).toString() +"USD";
            String swapName = metadata.getSwapName();
            if (metadata.poolType == DexPriceService.PoolType.V3) {
                Optional<DexPriceService.PriceRange> priceRangeOpt = snapshotOpt
                        .filter(snapshot -> snapshot.currentTick != null && snapshot.tickSpacing != null)
                        .map(snapshot -> priceService.calculateTargetPriceRange(metadata,
                                snapshot.currentTick - snapshot.tickSpacing,
                                snapshot.currentTick + snapshot.tickSpacing))
                        .orElse(Optional.empty());
                String priceRangeText = formatPriceRange(priceRangeOpt);
                log.info("POOL_REGISTERED pair={} swap={} name={} fee={} amount0={} amount1={} priceRange={}  tvl={}",
                        metadata.pairAddress,
                        swapName,
                        metadata.getDisplayName(),
                        formatFee(metadata.fee),
                        amount0Text,
                        amount1Text,
                        priceRangeText,
                        tvlText);
            } else {
                log.info("POOL_REGISTERED pair={} swap={} name={} amount0={} amount1={} tvl={}",
                        metadata.pairAddress,
                        swapName,
                        metadata.getDisplayName(),
                        amount0Text,
                        amount1Text,
                        tvlText);
            }
        }
    }

    /**
     * 解码地址
     */
    private String decodeAddress(String topic) {
        String clean = org.web3j.utils.Numeric.cleanHexPrefix(topic);
        if (clean.length() < 40) {
            clean = String.format("%40s", clean).replace(' ', '0');
        }
        return "0x" + clean.substring(clean.length() - 40);
    }

    /**
     * 构建 V4 池子元数据
     */
    private V4PoolMetadata buildV4Metadata(String manager,
                                           String poolId,
                                           String currency0,
                                           String currency1,
                                           BigInteger fee,
                                           String hooks) {
        String symbol0 = priceService.resolveTokenSymbol(currency0);
        String symbol1 = priceService.resolveTokenSymbol(currency1);
        return new V4PoolMetadata(normalizeAddress(manager),
                poolId,
                currency0,
                currency1,
                fee,
                hooks,
                symbol0,
                symbol1);
    }

    /**
     * 构建 V4 专用的 PairMetadata
     */
    private DexPriceService.PairMetadata buildV4PairMetadata(V4PoolMetadata metadata,
                                                             BigInteger decimals0,
                                                             BigInteger decimals1,
                                                             DexPriceService.PoolType poolType) {
        if (metadata == null) {
            return null;
        }
        BigInteger safeDecimals0 = decimals0 != null ? decimals0 : BigInteger.valueOf(18);
        BigInteger safeDecimals1 = decimals1 != null ? decimals1 : BigInteger.valueOf(18);
        String swapName = resolveV4SwapName(metadata);
        return new DexPriceService.PairMetadata(
                metadata.poolId,
                metadata.currency0,
                metadata.currency1,
                safeDecimals0,
                safeDecimals1,
                metadata.currency0Symbol,
                metadata.currency1Symbol,
                poolType,
                metadata.fee,
                metadata.manager,
                swapName);
    }

    private void registerV4PoolForTradeAnalysis(V4PoolMetadata metadata,
                                                    BigInteger decimals0,
                                                    BigInteger decimals1) {
        if (metadata == null) {
            return;
        }
        DexPriceService.PairMetadata pairMetadata = buildV4PairMetadata(metadata, decimals0, decimals1, DexPriceService.PoolType.V4);
        if (pairMetadata != null) {
            priceService.registerV4Pool(pairMetadata);
            if (tradeAnalysisService != null) {
                tradeAnalysisService.registerV4Pool(pairMetadata);
            }
        }
    }

    /**
     * 计算 V4 池子的 TVL
     */
    private Optional<TvlInfo> calculateV4Tvl(V4PoolMetadata metadata, V4PoolState state) {
        if (metadata == null || state == null) {
            return Optional.empty();
        }
        DexPriceService.PairMetadata pseudo = buildV4PairMetadata(metadata, state.getDecimals0(), state.getDecimals1(),
                DexPriceService.PoolType.V4);
        if (pseudo == null) {
            return Optional.empty();
        }
        DexPriceService.PoolSnapshot snapshot = state.toSnapshot();
        if (snapshot == null) {
            return Optional.empty();
        }
        return calculateTvl(pseudo, snapshot);
    }

    /**
     * 解析 V4 交易所名称
     */
    private String resolveV4SwapName(V4PoolMetadata metadata) {
        return Optional.ofNullable(metadata)
                .map(data -> data.manager)
                .map(address -> DexConstants.FACTORY_NAMES.getOrDefault(address.toLowerCase(Locale.ROOT), address))
                .orElse("unknown");
    }

    /**
     * 归一化池子 ID
     */
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
     * 归一化地址
     */
    private String normalizeAddress(String address) {
        if (address == null) {
            return null;
        }
        String clean = address.toLowerCase(Locale.ROOT);
        if (!clean.startsWith("0x")) {
            clean = "0x" + clean;
        }
        return clean;
    }

    /**
     * 获取 V4 订阅起始区块参数
     */
    private DefaultBlockParameter getV4StartBlockParameter() {
        if (tokenCreationBlock != null && tokenCreationBlock.signum() >= 0) {
            return DefaultBlockParameter.valueOf(tokenCreationBlock);
        }
        return DefaultBlockParameterName.EARLIEST;
    }

    /**
     * 准备历史日志订阅
     */
    private DefaultBlockParameter prepareHistoricalSubscription(String contractAddress,
                                                                Event event,
                                                                List<String> optionalTopics,
                                                                Consumer<Log> handler,
                                                                String context) {
        DefaultBlockParameter startParameter = getV4StartBlockParameter();
        BigInteger startBlock = resolveStartBlock(startParameter);
        BigInteger latestBlock = fetchLatestBlockNumber();
        if (startBlock == null || latestBlock == null) {
            return startParameter;
        }
        if (latestBlock.subtract(startBlock).compareTo(MAX_LOG_BLOCK_RANGE) <= 0) {
            return startParameter;
        }
        BigInteger nextStart = fetchHistoricalLogsInChunks(contractAddress, event, optionalTopics, handler, context,
                startBlock, latestBlock);
        if (nextStart == null) {
            return startParameter;
        }
        return DefaultBlockParameter.valueOf(nextStart);
    }

    /**
     * 解析起始区块高度
     */
    private BigInteger resolveStartBlock(DefaultBlockParameter startParameter) {
        if (startParameter instanceof DefaultBlockParameterNumber) {
            return ((DefaultBlockParameterNumber) startParameter).getBlockNumber();
        }
        if (startParameter == DefaultBlockParameterName.EARLIEST) {
            return BigInteger.ZERO;
        }
        return null;
    }

    /**
     * 获取最新区块高度
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
     * 分段拉取历史日志
     */
    private BigInteger fetchHistoricalLogsInChunks(String contractAddress,
                                                   Event event,
                                                   List<String> optionalTopics,
                                                   Consumer<Log> handler,
                                                   String context,
                                                   BigInteger startBlock,
                                                   BigInteger latestBlock) {
        if (startBlock == null || latestBlock == null) {
            return null;
        }
        BigInteger current = startBlock;
        String eventTopic = EventEncoder.encode(event);
        while (current.compareTo(latestBlock) <= 0) {
            BigInteger end = current.add(MAX_LOG_BLOCK_RANGE).subtract(BigInteger.ONE);
            if (end.compareTo(latestBlock) > 0) {
                end = latestBlock;
            }
            EthFilter filter = new EthFilter(
                    DefaultBlockParameter.valueOf(current),
                    DefaultBlockParameter.valueOf(end),
                    contractAddress
            );
            filter.addSingleTopic(eventTopic);
            if (optionalTopics != null && !optionalTopics.isEmpty()) {
                filter.addOptionalTopics(optionalTopics.toArray(new String[0]));
            }
            try {
                EthLog response = web3j.ethGetLogs(filter).send();
                if (response.hasError()) {
                    log.warn("Failed to fetch {} logs for {} blocks {}-{} error={}",
                            context,
                            contractAddress,
                            current,
                            end,
                            response.getError());
                } else {
                    for (EthLog.LogResult<?> logResult : response.getLogs()) {
                        Object raw = logResult.get();
                        if (raw instanceof Log) {
                            handler.accept((Log) raw);
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Failed to fetch {} logs for {} blocks {}-{}", context, contractAddress, current, end, e);
            }
            current = end.add(BigInteger.ONE);
        }
        return latestBlock;
    }

    /**
     * 解析日志对应的时间
     *
     * @param logEntry 日志
     * @return 日志时间
     */
    private Instant resolveLogTimestamp(Log logEntry) {
        if (logEntry == null) {
            return Instant.now();
        }
        BigInteger blockNumber = logEntry.getBlockNumber();
        if (blockNumber == null) {
            return Instant.now();
        }
        Instant cached = blockTimestampCache.get(blockNumber);
        if (cached != null) {
            return cached;
        }
        Instant resolved = fetchBlockTimestamp(blockNumber).orElse(Instant.now());
        blockTimestampCache.putIfAbsent(blockNumber, resolved);
        return resolved;
    }

    /**
     * 格式化时间戳
     */
    private String formatTimestamp(Instant instant) {
        Instant effective = instant != null ? instant : Instant.now();
        return TIME_FORMATTER.format(effective.atZone(TARGET_TIME_ZONE));
    }

    /**
     * 获取区块时间戳
     *
     * @param blockNumber 区块高度
     * @return 时间戳
     */
    private Optional<Instant> fetchBlockTimestamp(BigInteger blockNumber) {
        try {
            EthBlock block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(blockNumber), false).send();
            if (block.getBlock() == null || block.getBlock().getTimestamp() == null) {
                return Optional.empty();
            }
            return Optional.of(Instant.ofEpochSecond(block.getBlock().getTimestamp().longValue()));
        } catch (IOException e) {
            log.error("Failed to fetch block timestamp", e);
            return Optional.empty();
        }
    }

    /**
     * 计算 V4 价格区间
     *
     * @param metadata  池子元数据
     * @param decimals0 货币 0 精度
     * @param decimals1 货币 1 精度
     * @param tickLower 下界 tick
     * @param tickUpper 上界 tick
     * @return 价格区间
     */
    private Optional<DexPriceService.PriceRange> calculateV4PriceRange(V4PoolMetadata metadata,
                                                                       BigInteger decimals0,
                                                                       BigInteger decimals1,
                                                                       int tickLower,
                                                                       int tickUpper) {
        if (metadata == null) {
            return Optional.empty();
        }
        DexPriceService.PairMetadata pseudo = buildV4PairMetadata(metadata, decimals0, decimals1, DexPriceService.PoolType.V3);
        return priceService.calculateTargetPriceRange(pseudo, tickLower, tickUpper);
    }

    /**
     * 估算 V4 流动性变化对应的代币数量
     *
     * @param liquidityDelta 流动性变化量
     * @param tickLower      下界 tick
     * @param tickUpper      上界 tick
     * @param decimals0      货币 0 精度
     * @param decimals1      货币 1 精度
     * @return 估算结果
     */
    private Optional<LiquidityEstimation> estimateV4LiquidityDelta(
            BigInteger liquidityDelta,
            int tickLower,
            int tickUpper,
            BigInteger decimals0,
            BigInteger decimals1,
            BigDecimal currentPriceToken1PerToken0) {

        if (liquidityDelta == null || liquidityDelta.equals(BigInteger.ZERO)) {
            return Optional.empty();
        }

        BigDecimal sqrtLower = sqrtRatioAtTick(tickLower);
        BigDecimal sqrtUpper = sqrtRatioAtTick(tickUpper);
        if (sqrtLower == null || sqrtUpper == null
                || sqrtLower.compareTo(BigDecimal.ZERO) <= 0
                || sqrtUpper.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }

        // ✅ 当前价格平方根
        BigDecimal sqrtCurrent = sqrtPrice(currentPriceToken1PerToken0);
        BigDecimal L = new BigDecimal(liquidityDelta.abs());
        BigDecimal amount0Raw;
        BigDecimal amount1Raw;

        // ✅ 判断区间关系
        if (sqrtCurrent.compareTo(sqrtLower) <= 0) {
            // 当前价格低于区间 → 全部为 token0
            amount0Raw = L.multiply(sqrtUpper.subtract(sqrtLower))
                    .divide(sqrtUpper.multiply(sqrtLower), PRICE_CONTEXT);
            amount1Raw = BigDecimal.ZERO;
        } else if (sqrtCurrent.compareTo(sqrtUpper) >= 0) {
            // 当前价格高于区间 → 全部为 token1
            amount0Raw = BigDecimal.ZERO;
            amount1Raw = L.multiply(sqrtUpper.subtract(sqrtLower));
        } else {
            // 当前价格在区间内
            amount0Raw = L.multiply(sqrtUpper.subtract(sqrtCurrent))
                    .divide(sqrtCurrent.multiply(sqrtUpper), PRICE_CONTEXT);
            amount1Raw = L.multiply(sqrtCurrent.subtract(sqrtLower));
        }

        // ✅ 除以代币精度
        BigDecimal amount0 = amount0Raw.divide(BigDecimal.TEN.pow(decimals0.intValue()), PRICE_CONTEXT);
        BigDecimal amount1 = amount1Raw.divide(BigDecimal.TEN.pow(decimals1.intValue()), PRICE_CONTEXT);

        return Optional.of(new LiquidityEstimation(amount0, amount1));
    }

    private BigDecimal sqrtPrice(BigDecimal priceToken1PerToken0) {
        return BigDecimal.valueOf(Math.sqrt(priceToken1PerToken0.doubleValue()));
    }

    /**
     * 解析当前价格对应的 sqrt 值
     */
    private BigDecimal resolveSqrtCurrent(int tickLower, int tickUpper, BigDecimal currentPriceToken1PerToken0) {
        BigDecimal sqrtFromPrice = sqrtDecimal(currentPriceToken1PerToken0);
        if (sqrtFromPrice != null) {
            return sqrtFromPrice.multiply(Q96, PRICE_CONTEXT);
        }
        return sqrtRatioAtTick((tickLower + tickUpper) / 2);
    }

    /**
     * 对价格开平方根
     */
    private BigDecimal sqrtDecimal(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        double sqrt = Math.sqrt(price.doubleValue());
        if (Double.isNaN(sqrt) || Double.isInfinite(sqrt)) {
            return null;
        }
        return new BigDecimal(Double.toString(sqrt), PRICE_CONTEXT);
    }

    /**
     * 归一化代币数量
     *
     * @param amount   原始数量
     * @param decimals 精度
     * @return 标准化数量
     */
    private BigDecimal normalizeTokenAmount(BigDecimal amount, BigInteger decimals) {
        if (amount == null) {
            return null;
        }
        int scale = decimals != null ? decimals.intValue() : 18;
        if (scale < 0) {
            scale = 0;
        }
        BigDecimal divisor = BigDecimal.TEN.pow(scale);
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return amount.divide(divisor, 18, RoundingMode.HALF_UP);
    }

    /**
     * 为数值应用符号
     *
     * @param amount 数值
     * @param sign   符号
     * @return 带符号的数值
     */
    private BigDecimal applySign(BigDecimal amount, int sign) {
        if (amount == null) {
            return null;
        }
        if (sign == 0) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(BigDecimal.valueOf(sign));
    }

    /**
     * 根据 sqrtPriceX96 计算 token1/token0 价格
     *
     * @param sqrtPriceX96 sqrtPrice 值
     * @param decimals0    货币 0 精度
     * @param decimals1    货币 1 精度
     * @return 价格
     */
    private Optional<BigDecimal> calculateToken1PerToken0Price(BigInteger sqrtPriceX96,
                                                               BigInteger decimals0,
                                                               BigInteger decimals1) {
        if (sqrtPriceX96 == null || sqrtPriceX96.equals(BigInteger.ZERO)) {
            return Optional.empty();
        }
        BigDecimal sqrtPrice = new BigDecimal(sqrtPriceX96);
        BigDecimal numerator = sqrtPrice.multiply(sqrtPrice, PRICE_CONTEXT);
        BigDecimal denominator = new BigDecimal(BigInteger.ONE.shiftLeft(192));
        BigDecimal ratio = numerator.divide(denominator, 18, RoundingMode.HALF_UP);
        return Optional.of(adjustForDecimals(ratio, decimals0, decimals1));
    }

    /**
     * 计算目标代币价格
     *
     * @param sqrtPriceX96 sqrtPrice 值
     * @param decimals0    货币 0 精度
     * @param decimals1    货币 1 精度
     * @param currency0    货币 0 地址
     * @param currency1    货币 1 地址
     * @return 目标代币价格
     */
    private Optional<BigDecimal> calculateTargetPriceFromSqrt(BigInteger sqrtPriceX96,
                                                              BigInteger decimals0,
                                                              BigInteger decimals1,
                                                              String currency0,
                                                              String currency1) {
        Optional<BigDecimal> rawPriceOpt = calculateToken1PerToken0Price(sqrtPriceX96, decimals0, decimals1);
        if (!rawPriceOpt.isPresent()) {
            return Optional.empty();
        }
        BigDecimal rawPrice = rawPriceOpt.get();
        if (currency0 != null && currency0.equalsIgnoreCase(tokenAddress)) {
            return Optional.of(rawPrice);
        }
        if (currency1 != null && currency1.equalsIgnoreCase(tokenAddress) && rawPrice.compareTo(BigDecimal.ZERO) > 0) {
            return Optional.of(BigDecimal.ONE.divide(rawPrice, 18, RoundingMode.HALF_UP));
        }
        return Optional.empty();
    }

    /**
     * 根据精度调整价格
     *
     * @param price     原始价格
     * @param decimals0 货币 0 精度
     * @param decimals1 货币 1 精度
     * @return 调整后的价格
     */
    private BigDecimal adjustForDecimals(BigDecimal price, BigInteger decimals0, BigInteger decimals1) {
        if (price == null) {
            return null;
        }
        int scale0 = decimals0 != null ? decimals0.intValue() : 18;
        int scale1 = decimals1 != null ? decimals1.intValue() : 18;
        int diff = scale0 - scale1;
        if (diff == 0) {
            return price;
        }
        BigDecimal factor = BigDecimal.TEN.pow(Math.abs(diff));
        if (diff > 0) {
            return price.multiply(factor, PRICE_CONTEXT);
        }
        return price.divide(factor, PRICE_CONTEXT);
    }

    /**
     * 根据 tick 计算对应的 sqrt 价格
     *
     * @param tick tick 值
     * @return sqrt 价格
     */
    private BigDecimal sqrtRatioAtTick(int tick) {
        double exponent = tick / 2.0d;
        double value = Math.pow(1.0001d, exponent);
        return new BigDecimal(Double.toString(value), PRICE_CONTEXT).multiply(Q96, PRICE_CONTEXT);
    }

    private BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null) {
            return BigDecimal.ZERO;
        }
        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, 18, RoundingMode.HALF_UP);
    }

    /**
     * 流动性估算结果
     */
    private static class LiquidityEstimation {
        /** 货币 0 数量 */
        private final BigDecimal amount0;
        /** 货币 1 数量 */
        private final BigDecimal amount1;

        private LiquidityEstimation(BigDecimal amount0, BigDecimal amount1) {
            this.amount0 = amount0;
            this.amount1 = amount1;
        }
    }

    /**
     * V4 池子状态
     */
    private static class V4PoolState {
        private BigInteger decimals0;
        private BigInteger decimals1;
        private BigDecimal amount0 = BigDecimal.ZERO;
        private BigDecimal amount1 = BigDecimal.ZERO;
        private BigDecimal priceToken1PerToken0;

        private synchronized void initialize(BigInteger decimals0, BigInteger decimals1, BigDecimal initialPrice) {
            if (decimals0 != null) {
                this.decimals0 = decimals0;
            }
            if (decimals1 != null) {
                this.decimals1 = decimals1;
            }
            this.amount0 = BigDecimal.ZERO;
            this.amount1 = BigDecimal.ZERO;
            setPrice(initialPrice);
        }

        private synchronized void ensureDecimals(BigInteger decimals0, BigInteger decimals1) {
            if (this.decimals0 == null && decimals0 != null) {
                this.decimals0 = decimals0;
            }
            if (this.decimals1 == null && decimals1 != null) {
                this.decimals1 = decimals1;
            }
        }

        private synchronized void applyDelta(BigDecimal delta0, BigDecimal delta1) {
            if (delta0 != null) {
                this.amount0 = nonNegative(amount0.add(delta0));
            }
            if (delta1 != null) {
                this.amount1 = nonNegative(amount1.add(delta1));
            }
            updatePriceFromAmounts();
        }

        private synchronized BigDecimal getAmount0() {
            return amount0;
        }

        private synchronized BigDecimal getAmount1() {
            return amount1;
        }

        private synchronized BigInteger getDecimals0() {
            return decimals0;
        }

        private synchronized BigInteger getDecimals1() {
            return decimals1;
        }

        private synchronized DexPriceService.PoolSnapshot toSnapshot() {
            BigDecimal price = resolvePrice();
            return new DexPriceService.PoolSnapshot(amount0, amount1, price, null, null, null);
        }

        private synchronized void setPrice(BigDecimal price) {
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                this.priceToken1PerToken0 = price;
            }
        }

        private synchronized void updatePriceFromAmounts() {
            if (amount0 != null && amount0.compareTo(BigDecimal.ZERO) > 0
                    && amount1 != null && amount1.compareTo(BigDecimal.ZERO) > 0) {
                this.priceToken1PerToken0 = amount1.divide(amount0, 18, RoundingMode.HALF_UP);
            }
        }

        private synchronized BigDecimal resolvePrice() {
            if (priceToken1PerToken0 != null && priceToken1PerToken0.compareTo(BigDecimal.ZERO) > 0) {
                return priceToken1PerToken0;
            }
            if (amount0 != null && amount0.compareTo(BigDecimal.ZERO) > 0
                    && amount1 != null && amount1.compareTo(BigDecimal.ZERO) > 0) {
                return amount1.divide(amount0, 18, RoundingMode.HALF_UP);
            }
            return priceToken1PerToken0;
        }

        private BigDecimal nonNegative(BigDecimal value) {
            if (value == null || value.compareTo(BigDecimal.ZERO) >= 0) {
                return value;
            }
            return BigDecimal.ZERO;
        }
    }

    /**
     * V4 池子元数据
     */
    private static class V4PoolMetadata {
        /** 管理员地址 */
        private final String manager;
        /** 池子 ID */
        private final String poolId;
        /** 货币 0 地址 */
        private final String currency0;
        /** 货币 1 地址 */
        private final String currency1;
        /** 手续费 */
        private final BigInteger fee;
        /** 钩子地址 */
        private final String hooks;
        /** 货币 0 符号 */
        private final String currency0Symbol;
        /** 货币 1 符号 */
        private final String currency1Symbol;

        /**
         * 构造函数
         */
        private V4PoolMetadata(String manager,
                               String poolId,
                               String currency0,
                               String currency1,
                               BigInteger fee,
                               String hooks,
                               String currency0Symbol,
                               String currency1Symbol) {
            this.manager = manager;
            this.poolId = poolId;
            this.currency0 = currency0;
            this.currency1 = currency1;
            this.fee = fee;
            this.hooks = hooks;
            this.currency0Symbol = currency0Symbol;
            this.currency1Symbol = currency1Symbol;
        }

        /**
         * 获取显示名称
         */
        private String getDisplayName() {
            String symbol0 = currency0Symbol != null ? currency0Symbol : currency0;
            String symbol1 = currency1Symbol != null ? currency1Symbol : currency1;
            return symbol0.toLowerCase(Locale.ROOT) + "-" + symbol1.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 格式化池子名称
     */
    private String formatPairName(Optional<DexPriceService.PairMetadata> metadataOpt, String token0, String token1) {
        return metadataOpt
                .map(DexPriceService.PairMetadata::getDisplayName)
                .orElse((token0 + "-" + token1).toLowerCase(Locale.ROOT));
    }

    /**
     * 格式化手续费
     */
    private String formatFee(BigInteger fee) {
        if (fee == null) {
            return "unknown";
        }
        BigDecimal percent = new BigDecimal(fee).divide(BigDecimal.valueOf(10_000L), 6, RoundingMode.HALF_UP);
        BigDecimal normalized = percent.stripTrailingZeros();
        return normalized.toPlainString() + "%";
    }

    /**
     * 格式化价格区间
     */
    private String formatPriceRange(Optional<DexPriceService.PriceRange> rangeOpt) {
        return rangeOpt
                .map(range -> String.format("[%s, %s]", formatDecimal(range.lower), formatDecimal(range.upper)))
                .orElse("[]");
    }

    /**
     * 计算并格式化 TVL 文本
     */
    private String formatTvl(Optional<TvlInfo> tvlOpt) {
        return tvlOpt
                .map(tvl -> {
                    String amount = formatDecimal(tvl.amount);
                    if (tvl.symbol == null || tvl.symbol.isEmpty()) {
                        return amount;
                    }
                    return amount + " " + tvl.symbol;
                })
                .orElse("unknown");
    }

    /**
     * 安全格式化数量
     */
    private String formatAmount(BigDecimal amount) {
        return amount != null ? formatDecimal(amount) : "unknown";
    }

    /**
     * 计算池子的 TVL
     */
    private Optional<TvlInfo> calculateTvl(DexPriceService.PairMetadata metadata, DexPriceService.PoolSnapshot snapshot) {
        if (metadata == null || snapshot == null) {
            return Optional.empty();
        }
        BigDecimal amount0 = snapshot.token0Amount;
        BigDecimal amount1 = snapshot.token1Amount;
        if (amount0 == null || amount1 == null) {
            return Optional.empty();
        }
        BigDecimal ratio = snapshot.priceToken1PerToken0;
        if (ratio == null || ratio.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }

        BigDecimal tvlAmount;
        String symbol;
        if (metadata.token1.equalsIgnoreCase(DexConstants.USDT_ADDRESS)) {
            symbol = resolveSymbol(metadata.token1Symbol, metadata.token1);
            tvlAmount = amount1.add(amount0.multiply(ratio, PRICE_CONTEXT));
        } else if (metadata.token0.equalsIgnoreCase(DexConstants.USDT_ADDRESS)) {
            symbol = resolveSymbol(metadata.token0Symbol, metadata.token0);
            tvlAmount = amount0.add(amount1.divide(ratio, 18, RoundingMode.HALF_UP));
        } else if (metadata.token0.equalsIgnoreCase(tokenAddress)) {
            symbol = resolveSymbol(metadata.token1Symbol, metadata.token1);
            tvlAmount = amount1.add(amount0.multiply(ratio, PRICE_CONTEXT));
        } else if (metadata.token1.equalsIgnoreCase(tokenAddress)) {
            symbol = resolveSymbol(metadata.token0Symbol, metadata.token0);
            Optional<BigDecimal> priceOpt = snapshot.priceForToken(tokenAddress, metadata);
            if (!priceOpt.isPresent() || priceOpt.get().compareTo(BigDecimal.ZERO) <= 0) {
                return Optional.empty();
            }
            tvlAmount = amount0.add(amount1.multiply(priceOpt.get(), PRICE_CONTEXT));
        } else {
            symbol = resolveSymbol(metadata.token1Symbol, metadata.token1);
            tvlAmount = amount1.add(amount0.multiply(ratio, PRICE_CONTEXT));
        }

        return Optional.of(new TvlInfo(tvlAmount.setScale(18, RoundingMode.HALF_UP), symbol));
    }

    /**
     * 计算池子的美元 TVL
     */
    private Optional<BigDecimal> calculateUsdTvlAmount(DexPriceService.PairMetadata metadata,
                                                       DexPriceService.PoolSnapshot snapshot) {
        if (metadata == null || snapshot == null) {
            return Optional.empty();
        }
        BigDecimal total = BigDecimal.ZERO;
        boolean hasValue = false;
        if (snapshot.token0Amount != null) {
            Optional<BigDecimal> price0Opt;
            if(tokenAddress.equalsIgnoreCase(metadata.token0)) {
                price0Opt = priceService.getCachedPriceInUsdt(metadata.token0);
            }else{
                price0Opt = priceService.getCachedPriceInUsdtForV2V3(metadata.token0);
            }
            if (price0Opt.isPresent()) {
                total = total.add(snapshot.token0Amount.multiply(price0Opt.get(), PRICE_CONTEXT), PRICE_CONTEXT);
                hasValue = true;
            }
        }
        if (snapshot.token1Amount != null) {
            Optional<BigDecimal> price1Opt;
            if(tokenAddress.equalsIgnoreCase(metadata.token1)){
                price1Opt = priceService.getCachedPriceInUsdt(metadata.token1);
            }else{
                price1Opt = priceService.getCachedPriceInUsdtForV2V3(metadata.token1);
            }

            if (price1Opt.isPresent()) {
                total = total.add(snapshot.token1Amount.multiply(price1Opt.get(), PRICE_CONTEXT), PRICE_CONTEXT);
                hasValue = true;
            }
        }
        if (!hasValue) {
            return Optional.empty();
        }
        return Optional.of(total.setScale(18, RoundingMode.HALF_UP));
    }

    /**
     * 刷新并缓存池子的美元 TVL
     */
    private void refreshTvlCacheForPair(DexPriceService.PairMetadata metadata) {
        if (metadata == null) {
            return;
        }
        Optional<DexPriceService.PoolSnapshot> snapshotOpt = priceService.loadPoolSnapshot(metadata);
        if (snapshotOpt.isPresent()) {
            cacheUsdTvl(metadata, snapshotOpt.get());
        } else {
            clearTvlCache(metadata);
        }
    }

    /**
     * 更新 V4 池子的美元 TVL 缓存
     */
    private void updateV4TvlUsdCache(V4PoolMetadata metadata, V4PoolState state) {
        if (metadata == null || state == null) {
            return;
        }
        BigInteger decimals0 = state.getDecimals0();
        if (decimals0 == null) {
            decimals0 = priceService.resolveTokenDecimals(metadata.currency0);
        }
        BigInteger decimals1 = state.getDecimals1();
        if (decimals1 == null) {
            decimals1 = priceService.resolveTokenDecimals(metadata.currency1);
        }
        DexPriceService.PairMetadata pairMetadata = buildV4PairMetadata(metadata, decimals0, decimals1, DexPriceService.PoolType.V4);
        if (pairMetadata == null) {
            return;
        }
        cacheUsdTvl(pairMetadata, state.toSnapshot());
    }

    /**
     * 将 TVL 写入对应缓存
     */
    private void cacheUsdTvl(DexPriceService.PairMetadata metadata, DexPriceService.PoolSnapshot snapshot) {
        if (metadata == null) {
            return;
        }
        Optional<BigDecimal> tvlUsdOpt = calculateUsdTvlAmount(metadata, snapshot);
        BigDecimal tvlUsd = tvlUsdOpt.orElse(null);
        if (metadata.poolType == DexPriceService.PoolType.V4) {
            String key = normalizePoolId(metadata.pairAddress);
            if (key == null) {
                return;
            }
            if (tvlUsdOpt.isPresent()) {
                v4PoolTvlUsdCache.put(key, tvlUsd);
            } else {
                v4PoolTvlUsdCache.remove(key);
            }
        } else {
            String key = normalizeAddress(metadata.pairAddress);
            if (key == null) {
                return;
            }
            if (tvlUsdOpt.isPresent()) {
                poolTvlUsdCache.put(key, tvlUsd);
            } else {
                poolTvlUsdCache.remove(key);
            }
        }
        priceService.updatePoolUsdTvl(metadata, tvlUsd);
    }

    /**
     * 清除指定池子的 TVL 缓存
     */
    private void clearTvlCache(DexPriceService.PairMetadata metadata) {
        if (metadata == null) {
            return;
        }
        if (metadata.poolType == DexPriceService.PoolType.V4) {
            String key = normalizePoolId(metadata.pairAddress);
            if (key != null) {
                v4PoolTvlUsdCache.remove(key);
            }
        } else {
            String key = normalizeAddress(metadata.pairAddress);
            if (key != null) {
                poolTvlUsdCache.remove(key);
            }
        }
        priceService.updatePoolUsdTvl(metadata, null);
    }

    /**
     * 解析符号
     */
    private String resolveSymbol(String symbol, String fallback) {
        if (symbol != null && !symbol.isEmpty()) {
            return symbol;
        }
        return fallback == null ? "" : fallback;
    }

    /**
     * TVL 信息
     */
    private static class TvlInfo {
        private final BigDecimal amount;
        private final String symbol;

        private TvlInfo(BigDecimal amount, String symbol) {
            this.amount = amount;
            this.symbol = symbol;
        }
    }

    /**
     * 格式化十进制数
     */
    private String formatDecimal(BigDecimal value) {
        if (value == null) {
            return "unknown";
        }
        BigDecimal scaled = value.setScale(6, RoundingMode.HALF_UP);
        if (scaled.compareTo(BigDecimal.ZERO) == 0 && value.compareTo(BigDecimal.ZERO) != 0) {
            int dynamicScale = Math.min(18, Math.max(6, value.scale() + 6));
            scaled = value.setScale(dynamicScale, RoundingMode.HALF_UP);
        }
        BigDecimal normalized = scaled.stripTrailingZeros();
        if (normalized.compareTo(BigDecimal.ZERO) == 0) {
            return value.compareTo(BigDecimal.ZERO) == 0 ? "0" : scaled.toPlainString();
        }
        return normalized.toPlainString();
    }

    /**
     * 归一化金额
     */
    private BigDecimal normalizeAmount(BigInteger rawAmount, Optional<DexPriceService.PairMetadata> metadataOpt, boolean isToken0) {
        return metadataOpt
                .map(meta -> {
                    BigInteger decimals = isToken0 ? meta.token0Decimals : meta.token1Decimals;
                    if (decimals == null) {
                        return new BigDecimal(rawAmount);
                    }
                    return new BigDecimal(rawAmount)
                            .divide(BigDecimal.TEN.pow(decimals.intValue()), 8, RoundingMode.HALF_UP);
                })
                .orElseGet(() -> new BigDecimal(rawAmount));
    }
}
