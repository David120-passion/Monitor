package com.example.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Int24;
import org.web3j.abi.datatypes.generated.Uint24;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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
    /** 已监听的池子集合 */
    private final Set<String> watchedPools = ConcurrentHashMap.newKeySet();

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
    private static final Event BURN_EVENT = new Event("Burn",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Address.class, true)
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
        DexConstants.V2_FACTORIES.forEach(factory -> subscribeFactory(factory, PAIR_CREATED_EVENT));
        DexConstants.V3_FACTORIES.forEach(factory -> subscribeFactory(factory, POOL_CREATED_EVENT));
    }

    /**
     * 手动注册初始池子
     */
    public void registerInitialPairs() {
        priceService.findOrCreatePair(tokenAddress, DexConstants.BUSD_ADDRESS)
                .ifPresent(pair -> registerPool(pair.pairAddress, pair.token0, pair.token1));
        priceService.findOrCreatePair(tokenAddress, DexConstants.WBNB_ADDRESS)
                .ifPresent(pair -> registerPool(pair.pairAddress, pair.token0, pair.token1));
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
                    registerPool(pairAddress, token0, token1);
                    log.info("PAIR_CREATED pair={} token0={} token1={} liquidity={} time={}", pairAddress, token0, token1, liquidity,
                            Instant.now());
                    subscribeBurn(pairAddress, token0, token1);
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
                    registerPool(poolAddress, token0, token1);
                    log.info("POOL_CREATED pool={} token0={} token1={} fee={} tickSpacing={} time={}",
                            poolAddress, token0, token1, fee, tickSpacing, Instant.now());
                    subscribeBurn(poolAddress, token0, token1);
                }
            }
        } catch (Exception ex) {
            log.error("Failed to handle factory log", ex);
        }
    }

    /**
     * 订阅 Burn 事件以检测撤池
     *
     * @param pairAddress 池子地址
     * @param token0      token0 地址
     * @param token1      token1 地址
     */
    private void subscribeBurn(String pairAddress, String token0, String token1) {
        if (pairAddress == null || watchedPools.contains(pairAddress.toLowerCase())) {
            return;
        }
        watchedPools.add(pairAddress.toLowerCase());
        transferMonitorService.addLiquidityPair(pairAddress);
        EthFilter filter = new EthFilter(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST, pairAddress);
        filter.addSingleTopic(EventEncoder.encode(BURN_EVENT));
        web3j.ethLogFlowable(filter).subscribe(logEntry -> handleBurnLog(pairAddress, token0, token1, logEntry), throwable ->
                log.error("Error processing burn event", throwable));
    }

    /**
     * 处理 Burn 日志
     *
     * @param pairAddress 池子地址
     * @param token0      token0 地址
     * @param token1      token1 地址
     * @param logEntry    日志
     */
    private void handleBurnLog(String pairAddress, String token0, String token1, Log logEntry) {
        try {
            List<Type> data = FunctionReturnDecoder.decode(logEntry.getData(), BURN_EVENT.getNonIndexedParameters());
            if (data.size() < 2) {
                return;
            }
            BigInteger amount0 = (BigInteger) data.get(0).getValue();
            BigInteger amount1 = (BigInteger) data.get(1).getValue();
            Optional<DexPriceService.PairMetadata> metadataOpt = priceService.loadPairMetadata(pairAddress);
            BigDecimal normalized0 = metadataOpt
                    .map(meta -> new BigDecimal(amount0)
                            .divide(BigDecimal.TEN.pow(meta.token0Decimals.intValue()), 8, RoundingMode.HALF_UP))
                    .orElseGet(() -> new BigDecimal(amount0));
            BigDecimal normalized1 = metadataOpt
                    .map(meta -> new BigDecimal(amount1)
                            .divide(BigDecimal.TEN.pow(meta.token1Decimals.intValue()), 8, RoundingMode.HALF_UP))
                    .orElseGet(() -> new BigDecimal(amount1));
            log.info("POOL_REMOVED pair={} amount0={} amount1={} time={}", pairAddress, normalized0, normalized1, Instant.now());
            if (amount0.equals(BigInteger.ZERO) || amount1.equals(BigInteger.ZERO)) {
                transferMonitorService.removeLiquidityPair(pairAddress);
            }
        } catch (Exception ex) {
            log.error("Failed to handle burn log", ex);
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
    private void registerPool(String pairAddress, String token0, String token1) {
        transferMonitorService.addLiquidityPair(pairAddress);
        priceService.loadPairMetadata(pairAddress);
        log.info("POOL_REGISTERED pair={} token0={} token1={}", pairAddress, token0, token1);
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
}
