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

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
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
    /** 转账监控服务 */
    private final TransferMonitorService transferMonitorService;
    /** 已注册的池子集合 */
    private final Set<String> registeredPools = ConcurrentHashMap.newKeySet();
    /** 已订阅 Burn 事件的池子集合 */
    private final Set<String> burnSubscribedPools = ConcurrentHashMap.newKeySet();
    /** 已订阅 Mint 事件的池子集合 */
    private final Set<String> mintSubscribedPools = ConcurrentHashMap.newKeySet();
    /** 已知的 V4 池子元数据 */
    private final ConcurrentHashMap<String, V4PoolMetadata> v4Pools = new ConcurrentHashMap<>();
    /** 已订阅 V4 ModifyLiquidity 事件的池子集合 */
    private final Set<String> v4SubscribedPools = ConcurrentHashMap.newKeySet();
    /** 区块时间缓存 */
    private final ConcurrentHashMap<BigInteger, Instant> blockTimestampCache = new ConcurrentHashMap<>();
    /** 代币创建区块 */
    private final BigInteger tokenCreationBlock;
    /** 日志查询单次最大区块跨度 */
    private static final BigInteger MAX_LOG_BLOCK_RANGE = BigInteger.valueOf(50_000L);

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
                    TypeReference.create(Uint24.class),
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
    /** V4 Initialize 事件 */
    private static final Event INITIALIZE_EVENT_V4 = new Event("Initialize",
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
                                   TransferMonitorService transferMonitorService, BigInteger tokenCreationBlock) {
        this.web3j = web3j;
        this.tokenAddress = tokenAddress.toLowerCase();
        this.priceService = priceService;
        this.transferMonitorService = transferMonitorService;
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
        DefaultBlockParameter subscriptionStart = prepareHistoricalSubscription(
                factoryAddress,
                INITIALIZE_EVENT_V4,
                Collections.emptyList(),
                logEntry -> handleV4InitializeLog(factoryAddress, logEntry),
                "V4 initialize"
        );
        EthFilter initFilter = new EthFilter(subscriptionStart, DefaultBlockParameterName.LATEST, factoryAddress);
        initFilter.addSingleTopic(EventEncoder.encode(INITIALIZE_EVENT_V4));
        web3j.ethLogFlowable(initFilter).subscribe(logEntry -> handleV4InitializeLog(factoryAddress, logEntry), throwable ->
                log.error("Error processing V4 initialize event", throwable));
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
                List<Type> data = FunctionReturnDecoder.decode(logEntry.getData(), event.getNonIndexedParameters());
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
                                resolveLogTimestamp(logEntry));
                        subscribeMint(metadata, MINT_EVENT_V2);
                        subscribeBurn(metadata, BURN_EVENT_V2);
                    } else {
                        log.info("PAIR_CREATED pair={} token0={} token1={} liquidity={} time={}",
                                pairAddress, token0, token1, liquidity, resolveLogTimestamp(logEntry));
                    }
                }
            } else if (event.equals(POOL_CREATED_EVENT)) {
                if (topics.size() < 3) {
                    return;
                }
                String token0 = decodeAddress(topics.get(1));
                String token1 = decodeAddress(topics.get(2));
                List<Type> data = FunctionReturnDecoder.decode(logEntry.getData(), event.getNonIndexedParameters());
                if (data.size() < 3) {
                    return;
                }
                BigInteger fee = (BigInteger) data.get(0).getValue();
                BigInteger tickSpacing = (BigInteger) data.get(1).getValue();
                String poolAddress = data.get(2).getValue().toString();
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
                                resolveLogTimestamp(logEntry));
                        subscribeMint(metadata, MINT_EVENT_V3);
                        subscribeBurn(metadata, BURN_EVENT_V3);
                    } else {
                        log.info("POOL_CREATED pool={} token0={} token1={} fee={} tickSpacing={} time={}",
                                poolAddress, token0, token1, fee, tickSpacing, resolveLogTimestamp(logEntry));
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Failed to handle factory log", ex);
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
            if (topics.size() < 4) {
                return;
            }
            String poolIdTopic = topics.get(1);
            List<Type> data = FunctionReturnDecoder.decode(logEntry.getData(), INITIALIZE_EVENT_V4.getNonIndexedParameters());
            if (data.size() < 5) {
                return;
            }
            String currency0 = normalizeAddress(decodeAddress(topics.get(2)));
            String currency1 = normalizeAddress(decodeAddress(topics.get(3)));
            String hooks = normalizeAddress(((Address) data.get(0)).getValue().toString());
            BigInteger fee = ((Uint24) data.get(1)).getValue();
            byte[] parameters = ((Bytes32) data.get(2)).getValue();
            BigInteger sqrtPriceX96 = ((Uint160) data.get(3)).getValue();
            int tick = ((Int24) data.get(4)).getValue().intValue();
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
            if (previous == null) {
                Instant timestamp = resolveLogTimestamp(logEntry);
                String currency0Display = formatCurrencyDisplay(metadata.currency0Symbol, currency0);
                String currency1Display = formatCurrencyDisplay(metadata.currency1Symbol, currency1);
                log.info("POOL_INITIALIZED_V4 manager={} poolId={} name={} currency0={} currency1={} fee={} hooks={} parameters={} sqrtPriceX96={} tick={} time={}",
                        factoryAddress,
                        normalizedPoolId,
                        metadata.getDisplayName(),
                        currency0Display,
                        currency1Display,
                        formatFee(fee),
                        hooks,
                        org.web3j.utils.Numeric.toHexString(parameters),
                        sqrtPriceX96,
                        tick,
                        timestamp);
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
     * @param logEntry 日志
     */
    private void handleV4ModifyLiquidityLog(Log logEntry) {
        try {
            List<String> topics = logEntry.getTopics();
            if (topics.size() < 3) {
                return;
            }
            String poolId = normalizePoolId(topics.get(1));
            V4PoolMetadata metadata = v4Pools.get(poolId);
            if (metadata == null) {
                return;
            }
            List<Type> data = FunctionReturnDecoder.decode(logEntry.getData(), MODIFY_LIQUIDITY_EVENT_V4.getNonIndexedParameters());
            if (data.size() < 4) {
                return;
            }
            String sender = normalizeAddress(decodeAddress(topics.get(2)));
            int tickLower = ((Int24) data.get(0)).getValue().intValue();
            int tickUpper = ((Int24) data.get(1)).getValue().intValue();
            BigInteger liquidityDelta = ((Int256) data.get(2)).getValue();
            String salt = org.web3j.utils.Numeric.toHexString(((Bytes32) data.get(3)).getValue());
            int sign = liquidityDelta.signum();
            String action = sign > 0 ? "POOL_ADDED_V4" : (sign < 0 ? "POOL_REMOVED_V4" : "POOL_UPDATED_V4");
            Instant timestamp = resolveLogTimestamp(logEntry);
            String currency0Display = formatCurrencyDisplay(metadata.currency0Symbol, metadata.currency0);
            String currency1Display = formatCurrencyDisplay(metadata.currency1Symbol, metadata.currency1);
            log.info("{} manager={} poolId={} name={} sender={} currency0={} currency1={} fee={} hooks={} tickLower={} tickUpper={} liquidityDelta={} salt={} time={}",
                    action,
                    metadata.manager,
                    metadata.poolId,
                    metadata.getDisplayName(),
                    sender,
                    currency0Display,
                    currency1Display,
                    formatFee(metadata.fee),
                    metadata.hooks,
                    tickLower,
                    tickUpper,
                    liquidityDelta,
                    salt,
                    timestamp);
        } catch (Exception ex) {
            log.error("Failed to handle V4 modify liquidity log", ex);
        }
    }

    /**
     * 订阅 Burn 事件以检测撤池
     *
     * @param pairAddress 池子地址
     * @param token0      token0 地址
     * @param token1      token1 地址
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
     * @param pairAddress 池子地址
     * @param token0      token0 地址
     * @param token1      token1 地址
     * @param event       事件定义
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
                        pairAddress, pairName, sender, to, token0, token1, normalized0, normalized1, resolveLogTimestamp(logEntry));
                if (amount0.equals(BigInteger.ZERO) || amount1.equals(BigInteger.ZERO)) {
                    transferMonitorService.removeLiquidityPair(pairAddress);
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
                log.info("POOL_REMOVED_V3 pool={} name={} owner={} fee={} priceRange={} token0={} token1={} tickLower={} tickUpper={} burnedLiquidity={} amount0={} amount1={} time={}",
                        pairAddress, pairName, owner, feeText, priceRange, token0, token1, tickLower, tickUpper, burnedLiquidity, normalized0, normalized1, resolveLogTimestamp(logEntry));
                if (amount0.equals(BigInteger.ZERO) || amount1.equals(BigInteger.ZERO)) {
                    transferMonitorService.removeLiquidityPair(pairAddress);
                }
            }
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
                        pairAddress, pairName, sender, token0, token1, normalized0, normalized1, resolveLogTimestamp(logEntry));
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
                log.info("POOL_ADDED_V3 pool={} name={} sender={} owner={} fee={} priceRange={} token0={} token1={} tickLower={} tickUpper={} liquidity={} amount0={} amount1={} time={}",
                        pairAddress, pairName, sender, owner, feeText, priceRange, token0, token1, tickLower, tickUpper, liquidity, normalized0, normalized1, resolveLogTimestamp(logEntry));
            }
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
     * @param pairAddress 池子地址
     * @param token0      token0 地址
     * @param token1      token1 地址
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
            transferMonitorService.addLiquidityPair(metadata.pairAddress);
            if (metadata.poolType == DexPriceService.PoolType.V3) {
                log.info("POOL_REGISTERED pair={} name={} fee={}",
                        metadata.pairAddress,
                        metadata.getDisplayName(),
                        formatFee(metadata.fee));
            } else {
                log.info("POOL_REGISTERED pair={} name={}",
                        metadata.pairAddress,
                        metadata.getDisplayName());
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
     * 格式化货币展示文本
     *
     * @param symbol  货币符号
     * @param address 货币地址
     * @return 展示文本
     */
    private String formatCurrencyDisplay(String symbol, String address) {
        String normalized = normalizeAddress(address);
        if (normalized == null) {
            return (symbol != null && !symbol.isBlank()) ? symbol : "unknown";
        }
        if (isZeroAddress(normalized)) {
            return "BNB(" + normalized + ")";
        }
        if (symbol != null && !symbol.isBlank()) {
            return symbol + "(" + normalized + ")";
        }
        return normalized;
    }

    /**
     * 判断是否为零地址
     *
     * @param address 地址
     * @return 是否为零地址
     */
    private boolean isZeroAddress(String address) {
        return address != null && address.matches("0x0{40}");
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
     * 格式化十进制数
     */
    private String formatDecimal(BigDecimal value) {
        BigDecimal scaled = value.setScale(6, RoundingMode.HALF_UP);
        BigDecimal normalized = scaled.stripTrailingZeros();
        if (normalized.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
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
