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
import org.web3j.abi.datatypes.generated.Uint24;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    /** V4 池子详情缓存 */
    private final Map<String, V4PoolDetails> v4PoolDetails = new ConcurrentHashMap<>();

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
    /** V4 PoolCreated 事件 */
    private static final Event POOL_CREATED_EVENT_V4 = new Event("PoolCreated",
            Arrays.asList(
                    TypeReference.create(Bytes32.class, true),
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
    /** V4 ModifyLiquidity 事件 */
    private static final Event MODIFY_LIQUIDITY_EVENT_V4 = new Event("ModifyLiquidity",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Bytes32.class, true),
                    TypeReference.create(Int24.class),
                    TypeReference.create(Int24.class),
                    TypeReference.create(Int256.class),
                    TypeReference.create(Int256.class),
                    TypeReference.create(Int256.class),
                    TypeReference.create(Bytes32.class)
            ));

    /**
     * 构造函数
     */
    public LiquidityMonitorService(Web3j web3j, String tokenAddress, DexPriceService priceService,
                                   TransferMonitorService transferMonitorService) {
        this.web3j = web3j;
        this.tokenAddress = tokenAddress.toLowerCase();
        this.priceService = priceService;
        this.transferMonitorService = transferMonitorService;
    }

    /**
     * 启动监听
     */
    public void start() {
        DexConstants.V2_FACTORIES.stream()
                .filter(this::isNonZeroAddress)
                .forEach(factory -> subscribeFactory(factory, PAIR_CREATED_EVENT));
        DexConstants.V3_FACTORIES.stream()
                .filter(this::isNonZeroAddress)
                .forEach(factory -> subscribeFactory(factory, POOL_CREATED_EVENT));
        DexConstants.V4_POOL_MANAGERS.stream()
                .filter(this::isNonZeroAddress)
                .forEach(manager -> {
                    subscribeFactory(manager, POOL_CREATED_EVENT_V4);
                    subscribeFactory(manager, MODIFY_LIQUIDITY_EVENT_V4);
                });
    }

    private boolean isNonZeroAddress(String address) {
        if (address == null) {
            return false;
        }
        String normalized = address.trim();
        return !normalized.isEmpty() && !"0x0000000000000000000000000000000000000000".equalsIgnoreCase(normalized);
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
     * 订阅工厂事件
     *
     * @param factoryAddress 工厂地址
     * @param event          事件
     */
    private void subscribeFactory(String factoryAddress, Event event) {
        EthFilter filter = new EthFilter(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST, factoryAddress);
        filter.addSingleTopic(EventEncoder.encode(event));
        web3j.ethLogFlowable(filter).subscribe(logEntry -> handleFactoryLog(event, logEntry), throwable ->
                log.error("Error processing factory event", throwable));
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
                                Instant.now());
                        subscribeMint(metadata, MINT_EVENT_V2);
                        subscribeBurn(metadata, BURN_EVENT_V2);
                    } else {
                        log.info("PAIR_CREATED pair={} token0={} token1={} liquidity={} time={}",
                                pairAddress, token0, token1, liquidity, Instant.now());
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
                                Instant.now());
                        subscribeMint(metadata, MINT_EVENT_V3);
                        subscribeBurn(metadata, BURN_EVENT_V3);
                    } else {
                        log.info("POOL_CREATED pool={} token0={} token1={} fee={} tickSpacing={} time={}",
                                poolAddress, token0, token1, fee, tickSpacing, Instant.now());
                    }
                }
            } else if (event.equals(POOL_CREATED_EVENT_V4)) {
                handleV4PoolCreated(logEntry);
            } else if (event.equals(MODIFY_LIQUIDITY_EVENT_V4)) {
                handleV4ModifyLiquidityLog(logEntry);
            }
        } catch (Exception ex) {
            log.error("Failed to handle factory log", ex);
        }
    }

    private void handleV4PoolCreated(Log logEntry) {
        List<String> topics = logEntry.getTopics();
        if (topics.size() < 4) {
            return;
        }
        String poolId = normalizePoolId(topics.get(1));
        String token0 = decodeAddress(topics.get(2));
        String token1 = decodeAddress(topics.get(3));
        List<Type> data = FunctionReturnDecoder.decode(logEntry.getData(), POOL_CREATED_EVENT_V4.getNonIndexedParameters());
        if (data.size() < 3) {
            return;
        }
        BigInteger fee = ((Uint24) data.get(0)).getValue();
        int tickSpacing = ((Int24) data.get(1)).getValue().intValue();
        String hooks = ((Address) data.get(2)).getValue();
        DexPriceService.PairMetadata metadata = priceService.createOrUpdateV4PoolMetadata(poolId, token0, token1, fee);
        if (metadata == null) {
            return;
        }
        registerPool(metadata);
        String normalizedId = poolId.toLowerCase(Locale.ROOT);
        v4PoolDetails.put(normalizedId, new V4PoolDetails(poolId, metadata, fee, tickSpacing, hooks));
        log.info("POOL_CREATED_V4 poolId={} name={} token0={} token1={} fee={} tickSpacing={} hooks={} time={}",
                poolId,
                metadata.getDisplayName(),
                token0,
                token1,
                formatFee(metadata.fee != null ? metadata.fee : fee),
                tickSpacing,
                hooks,
                Instant.now());
    }

    private void handleV4ModifyLiquidityLog(Log logEntry) {
        List<String> topics = logEntry.getTopics();
        if (topics.size() < 3) {
            return;
        }
        String sender = decodeAddress(topics.get(1));
        String poolId = normalizePoolId(topics.get(2));
        String normalizedId = poolId.toLowerCase(Locale.ROOT);
        V4PoolDetails details = v4PoolDetails.get(normalizedId);
        Optional<DexPriceService.PairMetadata> metadataOpt = priceService.loadPairMetadata(poolId,
                false,
                DexPriceService.PoolType.V4,
                details != null ? details.fee : null);
        if (!metadataOpt.isPresent() && details != null && details.metadata != null) {
            metadataOpt = Optional.of(details.metadata);
        }
        if (!metadataOpt.isPresent()) {
            return;
        }
        List<Type> data = FunctionReturnDecoder.decode(logEntry.getData(), MODIFY_LIQUIDITY_EVENT_V4.getNonIndexedParameters());
        if (data.size() < 6) {
            return;
        }
        int tickLower = ((Int24) data.get(0)).getValue().intValue();
        int tickUpper = ((Int24) data.get(1)).getValue().intValue();
        BigInteger liquidityDelta = ((Int256) data.get(2)).getValue();
        BigInteger amount0Raw = ((Int256) data.get(3)).getValue();
        BigInteger amount1Raw = ((Int256) data.get(4)).getValue();
        BigInteger amount0 = amount0Raw.abs();
        BigInteger amount1 = amount1Raw.abs();
        BigDecimal normalized0 = normalizeAmount(amount0, metadataOpt, true);
        BigDecimal normalized1 = normalizeAmount(amount1, metadataOpt, false);
        Optional<DexPriceService.PriceRange> priceRangeOpt = metadataOpt.flatMap(meta ->
                priceService.calculateTargetPriceRange(meta, tickLower, tickUpper));
        String priceRange = formatPriceRange(priceRangeOpt);
        DexPriceService.PairMetadata metadata = metadataOpt.get();
        if (details == null || details.metadata != metadata) {
            v4PoolDetails.put(normalizedId, new V4PoolDetails(poolId,
                    metadata,
                    metadata.fee != null ? metadata.fee : (details != null ? details.fee : null),
                    details != null ? details.tickSpacing : 0,
                    details != null ? details.hooks : ""));
            details = v4PoolDetails.get(normalizedId);
        }
        String token0 = metadata.token0;
        String token1 = metadata.token1;
        String pairName = metadata.getDisplayName();
        String feeText = formatFee(metadata.fee);
        BigInteger liquidityAbs = liquidityDelta.abs();
        String hooks = details != null ? details.hooks : "";
        Instant now = Instant.now();
        if (liquidityDelta.signum() >= 0) {
            log.info("POOL_ADDED_V4 poolId={} name={} sender={} fee={} hooks={} token0={} token1={} tickLower={} tickUpper={} " +
                            "liquidityDelta={} amount0={} amount1={} priceRange={} time={}",
                    poolId,
                    pairName,
                    sender,
                    feeText,
                    hooks,
                    token0,
                    token1,
                    tickLower,
                    tickUpper,
                    liquidityAbs,
                    normalized0,
                    normalized1,
                    priceRange,
                    now);
        } else {
            log.info("POOL_REMOVED_V4 poolId={} name={} sender={} fee={} hooks={} token0={} token1={} tickLower={} tickUpper=" +
                            "{} liquidityDelta={} amount0={} amount1={} priceRange={} time={}",
                    poolId,
                    pairName,
                    sender,
                    feeText,
                    hooks,
                    token0,
                    token1,
                    tickLower,
                    tickUpper,
                    liquidityAbs,
                    normalized0,
                    normalized1,
                    priceRange,
                    now);
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
                        pairAddress, pairName, sender, to, token0, token1, normalized0, normalized1, Instant.now());
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
                        pairAddress, pairName, owner, feeText, priceRange, token0, token1, tickLower, tickUpper, burnedLiquidity, normalized0, normalized1, Instant.now());
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
                        pairAddress, pairName, sender, token0, token1, normalized0, normalized1, Instant.now());
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
                        pairAddress, pairName, sender, owner, feeText, priceRange, token0, token1, tickLower, tickUpper, liquidity, normalized0, normalized1, Instant.now());
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
            if (metadata.poolType != DexPriceService.PoolType.V4) {
                transferMonitorService.addLiquidityPair(metadata.pairAddress);
            }
            if (metadata.poolType == DexPriceService.PoolType.V3) {
                log.info("POOL_REGISTERED pair={} name={} fee={}",
                        metadata.pairAddress,
                        metadata.getDisplayName(),
                        formatFee(metadata.fee));
            } else if (metadata.poolType == DexPriceService.PoolType.V4) {
                log.info("POOL_REGISTERED_V4 poolId={} name={} fee={}",
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

    private String normalizePoolId(String topic) {
        if (topic == null) {
            return "";
        }
        String clean = Numeric.cleanHexPrefix(topic).toLowerCase(Locale.ROOT);
        if (clean.length() < 64) {
            clean = String.format("%64s", clean).replace(' ', '0');
        }
        return "0x" + clean;
    }

    private String formatPairName(Optional<DexPriceService.PairMetadata> metadataOpt, String token0, String token1) {
        return metadataOpt
                .map(DexPriceService.PairMetadata::getDisplayName)
                .orElse((token0 + "-" + token1).toLowerCase(Locale.ROOT));
    }

    private String formatFee(BigInteger fee) {
        if (fee == null) {
            return "unknown";
        }
        BigDecimal percent = new BigDecimal(fee).divide(BigDecimal.valueOf(10_000L), 6, RoundingMode.HALF_UP);
        BigDecimal normalized = percent.stripTrailingZeros();
        return normalized.toPlainString() + "%";
    }

    private String formatPriceRange(Optional<DexPriceService.PriceRange> rangeOpt) {
        return rangeOpt
                .map(range -> String.format("[%s, %s]", formatDecimal(range.lower), formatDecimal(range.upper)))
                .orElse("[]");
    }

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

    private static class V4PoolDetails {
        private final String poolId;
        private final DexPriceService.PairMetadata metadata;
        private final BigInteger fee;
        private final int tickSpacing;
        private final String hooks;

        private V4PoolDetails(String poolId,
                              DexPriceService.PairMetadata metadata,
                              BigInteger fee,
                              int tickSpacing,
                              String hooks) {
            this.poolId = poolId;
            this.metadata = metadata;
            this.fee = fee;
            this.tickSpacing = tickSpacing;
            this.hooks = hooks;
        }
    }
}
