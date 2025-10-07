package com.example.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Int24;
import org.web3j.abi.datatypes.generated.Uint112;
import org.web3j.abi.datatypes.generated.Uint128;
import org.web3j.abi.datatypes.generated.Uint160;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint24;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * DEX 价格服务
 */
public class DexPriceService {
    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(DexPriceService.class);
    /** Web3j 客户端实例 */
    private final Web3j web3j;
    /** 目标代币地址 */
    private final String tokenAddress;
    /** 代币精度 */
    private final BigInteger tokenDecimals;
    /** 代币符号 */
    private final String tokenSymbol;

    /** token 信息服务 */
    private final TokenInfoService tokenInfoService;
    /** 代币精度缓存 */
    private final Map<String, BigInteger> decimalsCache = new ConcurrentHashMap<>();
    /** 代币符号缓存 */
    private final Map<String, String> symbolCache = new ConcurrentHashMap<>();
    /** 按 token 组合缓存的 V2 配对信息（按池子地址去重） */
    private final Map<String, Map<String, PairMetadata>> pairCacheByTokens = new ConcurrentHashMap<>();
    /** 按地址缓存的池子信息 */
    private final Map<String, PairMetadata> pairCacheByAddress = new ConcurrentHashMap<>();
    /** 按 token、顺序与费率缓存的 V3 池子信息 */
    private final Map<String, Map<String, PairMetadata>> v3PoolCache = new ConcurrentHashMap<>();
    /** 按 token、顺序缓存的 V4 池子信息 */
    private final Map<String, Map<String, PairMetadata>> v4PoolCache = new ConcurrentHashMap<>();
    /** 区块价格缓存 */
    private final Map<BigInteger, BigDecimal> priceCache = new ConcurrentHashMap<>();
    /** 最近一次成功获取的价格 */
    private final AtomicReference<BigDecimal> lastKnownPrice = new AtomicReference<>();
    /** 池子价格样本缓存 */
    private final Map<String, PriceSample> poolPriceCache = new ConcurrentHashMap<>();
    /** 价格刷新调度器 */
    private final ScheduledExecutorService priceRefreshExecutor;

    /** 价格计算精度 */
    private static final MathContext PRICE_MATH_CONTEXT = new MathContext(40, RoundingMode.HALF_UP);
    /** 价格样本最大存活时间（毫秒） */
    private static final long PRICE_SAMPLE_TTL_MILLIS = 15_000L;

    /**
     * 构造函数
     *
     * @param web3j         Web3j 客户端
     * @param tokenAddress  代币地址
     * @param tokenDecimals 代币精度
     * @param tokenSymbol   代币符号
     */
    public DexPriceService(Web3j web3j, String tokenAddress, BigInteger tokenDecimals, String tokenSymbol) {
        this.web3j = web3j;
        this.tokenAddress = tokenAddress;
        this.tokenDecimals = tokenDecimals;
        this.tokenSymbol = tokenSymbol;
        this.tokenInfoService = new TokenInfoService(web3j);
        this.priceRefreshExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "dex-price-refresh");
                thread.setDaemon(true);
                return thread;
            }
        });
        initializePriceSampler();
    }

    /**
     * 初始化价格采样器，预加载常用池子并启动后台刷新任务
     */
    private void initializePriceSampler() {
        try {
            refreshInitialPools();
        } catch (Exception ex) {
            log.warn("Failed to preload pools for price sampler", ex);
        }
        startPriceRefreshTask();
    }

    /**
     * 扫描目标代币常见的交易池以初始化缓存
     */
    private void refreshInitialPools() {
        List<PairMetadata> initialPools = new ArrayList<>();
        initialPools.addAll(findOrCreatePairs(tokenAddress, DexConstants.USDT_ADDRESS));
        initialPools.addAll(findOrCreatePairs(tokenAddress, DexConstants.WBNB_ADDRESS));
        initialPools.addAll(findOrCreateV3Pools(tokenAddress, DexConstants.USDT_ADDRESS));
        initialPools.addAll(findOrCreateV3Pools(tokenAddress, DexConstants.WBNB_ADDRESS));
        initialPools.addAll(findOrCreateV4Pools(tokenAddress, DexConstants.USDT_ADDRESS));
        initialPools.addAll(findOrCreateV4Pools(tokenAddress, DexConstants.WBNB_ADDRESS));
        initialPools.addAll(findOrCreatePairs(DexConstants.WBNB_ADDRESS, DexConstants.USDT_ADDRESS));
        initialPools.addAll(findOrCreateV3Pools(DexConstants.WBNB_ADDRESS, DexConstants.USDT_ADDRESS));
        initialPools.addAll(findOrCreateV4Pools(DexConstants.WBNB_ADDRESS, DexConstants.USDT_ADDRESS));

        Set<String> seen = new HashSet<>();
        for (PairMetadata metadata : initialPools) {
            if (metadata == null || metadata.pairAddress == null) {
                continue;
            }
            String key = normalize(metadata.pairAddress);
            if (seen.add(key)) {
                refreshPriceForPool(metadata);
            }
        }
        log.info("Initialized price cache for {} pools", seen.size());
    }

    /**
     * 启动后台价格刷新线程
     */
    private void startPriceRefreshTask() {
        priceRefreshExecutor.scheduleAtFixedRate(() -> {
            try {
                refreshAllKnownPools();
            } catch (Exception ex) {
                log.error("Failed to refresh pool prices", ex);
            }
        }, 0L, 5L, TimeUnit.SECONDS);
    }

    /**
     * 刷新所有已知池子的价格样本
     */
    private void refreshAllKnownPools() {
        List<PairMetadata> snapshot = new ArrayList<>(pairCacheByAddress.values());
        for (PairMetadata metadata : snapshot) {
            refreshPriceForPool(metadata);
        }
    }

    /**
     * 刷新指定池子的价格样本
     */
    private Optional<PriceSample> refreshPriceForPool(PairMetadata metadata) {
        if (metadata == null || metadata.pairAddress == null) {
            return Optional.empty();
        }
        try {
            Optional<PriceSample> sampleOpt = buildPriceSample(metadata);
            sampleOpt.ifPresent(sample -> poolPriceCache.put(normalize(metadata.pairAddress), sample));
            return sampleOpt;
        } catch (Exception ex) {
            log.debug("Failed to refresh price sample for pool {}", metadata.pairAddress, ex);
            return Optional.empty();
        }
    }

    /**
     * 根据池子类型构建价格样本
     */
    private Optional<PriceSample> buildPriceSample(PairMetadata metadata) {
        if (metadata.poolType == PoolType.V3 || metadata.poolType == PoolType.V4) {
            return buildConcentratedPriceSample(metadata);
        }
        return buildV2PriceSample(metadata);
    }

    /**
     * 构建 V2 池子的价格样本
     */
    private Optional<PriceSample> buildV2PriceSample(PairMetadata metadata) {
        Optional<Reserves> reservesOpt = getReserves(metadata, null);
        if (!reservesOpt.isPresent()) {
            return Optional.empty();
        }
        Reserves reserves = reservesOpt.get();
        BigInteger token0Decimals = metadata.token0Decimals != null ? metadata.token0Decimals : fetchDecimals(metadata.token0);
        BigInteger token1Decimals = metadata.token1Decimals != null ? metadata.token1Decimals : fetchDecimals(metadata.token1);
        BigDecimal reserve0 = reserves.getReserve0()
                .divide(BigDecimal.TEN.pow(token0Decimals.intValue()), 18, RoundingMode.HALF_UP);
        BigDecimal reserve1 = reserves.getReserve1()
                .divide(BigDecimal.TEN.pow(token1Decimals.intValue()), 18, RoundingMode.HALF_UP);
        if (!hasSufficientLiquidity(reserve0, reserve1)) {
            return Optional.empty();
        }
        if (reserve0.compareTo(BigDecimal.ZERO) <= 0 || reserve1.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        BigDecimal priceToken1PerToken0 = reserve1.divide(reserve0, 18, RoundingMode.HALF_UP);
        BigDecimal priceToken0PerToken1 = priceToken1PerToken0.compareTo(BigDecimal.ZERO) > 0
                ? BigDecimal.ONE.divide(priceToken1PerToken0, 18, RoundingMode.HALF_UP)
                : null;
        BigDecimal tvlInToken1 = priceToken1PerToken0.multiply(reserve0, PRICE_MATH_CONTEXT).add(reserve1);
        BigDecimal tvlInToken0 = priceToken0PerToken1 != null
                ? priceToken0PerToken1.multiply(reserve1, PRICE_MATH_CONTEXT).add(reserve0)
                : reserve0;
        long now = System.currentTimeMillis();
        return Optional.of(new PriceSample(metadata,
                priceToken1PerToken0,
                priceToken0PerToken1,
                reserve0,
                reserve1,
                tvlInToken1,
                tvlInToken0,
                tvlInToken1,
                now));
    }

    /**
     * 构建 V3/V4 池子的价格样本
     */
    private Optional<PriceSample> buildConcentratedPriceSample(PairMetadata metadata) {
        Optional<Slot0Data> slot0Opt = getSlot0(metadata, null);
        if (!slot0Opt.isPresent()) {
            return Optional.empty();
        }
        Optional<BigDecimal> priceOpt = derivePriceToken1PerToken0(slot0Opt.get(), metadata);
        if (!priceOpt.isPresent()) {
            return Optional.empty();
        }
        BigDecimal priceToken1PerToken0 = priceOpt.get();
        BigDecimal priceToken0PerToken1 = priceToken1PerToken0.compareTo(BigDecimal.ZERO) > 0
                ? BigDecimal.ONE.divide(priceToken1PerToken0, 18, RoundingMode.HALF_UP)
                : null;
        BigDecimal liquidity = fetchConcentratedLiquidity(metadata);
        long now = System.currentTimeMillis();
        return Optional.of(new PriceSample(metadata,
                priceToken1PerToken0,
                priceToken0PerToken1,
                null,
                null,
                null,
                null,
                liquidity,
                now));
    }

    /**
     * 根据 slot0 结果推导 token1/token0 价格
     */
    private Optional<BigDecimal> derivePriceToken1PerToken0(Slot0Data slot0, PairMetadata metadata) {
        if (slot0 == null || metadata == null || slot0.sqrtPriceX96 == null || slot0.sqrtPriceX96.equals(BigInteger.ZERO)) {
            return Optional.empty();
        }
        BigDecimal sqrtPrice = new BigDecimal(slot0.sqrtPriceX96);
        BigDecimal numerator = sqrtPrice.multiply(sqrtPrice, PRICE_MATH_CONTEXT);
        BigDecimal denominator = new BigDecimal(BigInteger.ONE.shiftLeft(192));
        BigDecimal price = numerator.divide(denominator, 18, RoundingMode.HALF_UP);
        price = adjustForDecimals(price, metadata.token0Decimals, metadata.token1Decimals)
                .setScale(18, RoundingMode.HALF_UP);
        return Optional.of(price);
    }

    /**
     * 查询 V3/V4 池子的流动性
     */
    private BigDecimal fetchConcentratedLiquidity(PairMetadata metadata) {
        try {
            Function function = new Function(
                    "liquidity",
                    Collections.emptyList(),
                    Collections.singletonList(new TypeReference<Uint128>() {
                    })
            );
            String encoded = FunctionEncoder.encode(function);
            Transaction tx = Transaction.createEthCallTransaction(null, metadata.pairAddress, encoded);
            EthCall response = web3j.ethCall(tx, resolveBlockParameter(null)).send();
            if (response.isReverted()) {
                return null;
            }
            List<Type> values = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
            if (values.isEmpty()) {
                return null;
            }
            BigInteger raw = ((Uint128) values.get(0)).getValue();
            return new BigDecimal(raw);
        } catch (Exception ex) {
            log.debug("Failed to fetch concentrated liquidity for pool {}", metadata.pairAddress, ex);
            return null;
        }
    }

    /**
     * 判断价格样本是否过期
     */
    private boolean isSampleStale(PriceSample sample, long nowMillis) {
        if (sample == null) {
            return true;
        }
        return nowMillis - sample.updatedAt > PRICE_SAMPLE_TTL_MILLIS;
    }

    /**
     * 选择第一个大于零的权重
     */
    private static BigDecimal firstPositive(BigDecimal... values) {
        if (values == null) {
            return null;
        }
        for (BigDecimal value : values) {
            if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
                return value;
            }
        }
        return null;
    }

    /**
     * 根据区块高度解析默认参数
     */
    private DefaultBlockParameter resolveBlockParameter(BigInteger blockNumber) {
        return blockNumber == null ? DefaultBlockParameterName.LATEST : DefaultBlockParameter.valueOf(blockNumber);
    }

    /**
     * 获取指定区块的代币美元价格
     *
     * @param blockNumber 区块高度
     * @return 价格
     */
    public Optional<BigDecimal> getPriceAtBlock(BigInteger blockNumber) {
        if (blockNumber == null) {
            return Optional.ofNullable(lastKnownPrice.get());
        }

        BigDecimal cached = priceCache.get(blockNumber);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<BigDecimal> directBusd = getBestPriceForPair(tokenAddress, DexConstants.USDT_ADDRESS, blockNumber);
        if (directBusd.isPresent()) {
            return Optional.of(storePrice(blockNumber, directBusd.get()));
        }

        Optional<BigDecimal> viaWbnb = combinePrices(
                getBestPriceForPair(tokenAddress, DexConstants.WBNB_ADDRESS, blockNumber),
                getBestPriceForPair(DexConstants.WBNB_ADDRESS, DexConstants.USDT_ADDRESS, blockNumber)
        );
        if (viaWbnb.isPresent()) {
            return Optional.of(storePrice(blockNumber, viaWbnb.get()));
        }

        BigDecimal fallback = lastKnownPrice.get();
        if (fallback != null) {
            return Optional.of(fallback);
        }

        return Optional.empty();
    }

    /**
     * 获取指定代币的美元价格（基于缓存）
     *
     * @param tokenAddress 代币地址
     * @return 价格（USDT）
     */
    public Optional<BigDecimal> getCachedPriceInUsdt(String tokenAddress) {
        if (tokenAddress == null) {
            return Optional.empty();
        }
        if (tokenAddress.equalsIgnoreCase(DexConstants.USDT_ADDRESS)) {
            return Optional.of(BigDecimal.ONE);
        }

        Optional<BigDecimal> direct = getBestPriceForPair(tokenAddress, DexConstants.USDT_ADDRESS, null);
        if (direct.isPresent()) {
            return Optional.of(direct.get().setScale(18, RoundingMode.HALF_UP));
        }

        Optional<BigDecimal> viaWbnb = combinePrices(
                getBestPriceForPair(tokenAddress, DexConstants.WBNB_ADDRESS, null),
                getBestPriceForPair(DexConstants.WBNB_ADDRESS, DexConstants.USDT_ADDRESS, null)
        );
        if (viaWbnb.isPresent()) {
            return Optional.of(viaWbnb.get().setScale(18, RoundingMode.HALF_UP));
        }

        return Optional.empty();
    }

    /**
     * 缓存并规范化价格
     *
     * @param blockNumber 区块高度
     * @param price       原始价格
     * @return 规范化后的价格
     */
    private BigDecimal storePrice(BigInteger blockNumber, BigDecimal price) {
        BigDecimal normalized = price.setScale(8, RoundingMode.HALF_UP);
        priceCache.put(blockNumber, normalized);
        lastKnownPrice.set(normalized);
        return normalized;
    }

    /**
     * 获取指定 token 对的最佳价格
     *
     * @param baseToken  基础代币
     * @param quoteToken 报价代币
     * @param blockNumber 区块高度
     * @return 价格
     */
    private Optional<BigDecimal> getBestPriceForPair(String baseToken, String quoteToken, BigInteger blockNumber) {
        List<WeightedPrice> weightedPrices = new ArrayList<>();
        weightedPrices.addAll(collectPricesFromPairs(findOrCreatePairs(baseToken, quoteToken), baseToken));
        weightedPrices.addAll(collectPricesFromPairs(findOrCreateV3Pools(baseToken, quoteToken), baseToken));
        weightedPrices.addAll(collectPricesFromPairs(findOrCreateV4Pools(baseToken, quoteToken), baseToken));

        if (weightedPrices.isEmpty()) {
            return Optional.empty();
        }

        Optional<BigDecimal> weightedMedian = calculateWeightedMedianPrice(weightedPrices);
        if (weightedMedian.isPresent()) {
            return Optional.of(weightedMedian.get().setScale(18, RoundingMode.HALF_UP));
        }

        Optional<BigDecimal> weightedAverage = calculateWeightedAveragePrice(weightedPrices);
        return weightedAverage.map(value -> value.setScale(18, RoundingMode.HALF_UP));
    }

    /**
     * 计算加权中位数
     */
    private Optional<BigDecimal> calculateWeightedMedianPrice(List<WeightedPrice> weightedPrices) {
        if (weightedPrices == null || weightedPrices.isEmpty()) {
            return Optional.empty();
        }
        List<WeightedPrice> valid = weightedPrices.stream()
                .filter(w -> w != null && w.getPrice() != null && w.getWeight() != null
                        && w.getPrice().compareTo(BigDecimal.ZERO) > 0
                        && w.getWeight().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(WeightedPrice::getPrice))
                .collect(Collectors.toList());
        if (valid.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal totalWeight = valid.stream()
                .map(WeightedPrice::getWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal halfWeight = totalWeight.divide(BigDecimal.valueOf(2), 18, RoundingMode.HALF_UP);
        for (WeightedPrice weightedPrice : valid) {
            cumulative = cumulative.add(weightedPrice.getWeight());
            if (cumulative.compareTo(halfWeight) >= 0) {
                return Optional.of(weightedPrice.getPrice());
            }
        }
        return Optional.of(valid.get(valid.size() - 1).getPrice());
    }

    /**
     * 计算加权平均
     */
    private Optional<BigDecimal> calculateWeightedAveragePrice(List<WeightedPrice> weightedPrices) {
        if (weightedPrices == null || weightedPrices.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (WeightedPrice weightedPrice : weightedPrices) {
            if (weightedPrice == null || weightedPrice.getPrice() == null || weightedPrice.getWeight() == null) {
                continue;
            }
            if (weightedPrice.getPrice().compareTo(BigDecimal.ZERO) <= 0
                    || weightedPrice.getWeight().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            weightedSum = weightedSum.add(
                    weightedPrice.getPrice().multiply(weightedPrice.getWeight(), PRICE_MATH_CONTEXT),
                    PRICE_MATH_CONTEXT);
            totalWeight = totalWeight.add(weightedPrice.getWeight());
        }
        if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        BigDecimal average = weightedSum.divide(totalWeight, 18, RoundingMode.HALF_UP);
        return Optional.of(average);
    }

    /**
     * 遍历池子集合并收集价格
     *
     * @param pairs       池子列表
     * @param baseToken   基础代币
     * @return 价格列表
     */
    private List<WeightedPrice> collectPricesFromPairs(List<PairMetadata> pairs, String baseToken) {
        if (pairs == null || pairs.isEmpty() || baseToken == null) {
            return Collections.emptyList();
        }
        List<WeightedPrice> prices = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (PairMetadata metadata : pairs) {
            if (metadata == null || metadata.pairAddress == null) {
                continue;
            }
            String key = normalize(metadata.pairAddress);
            PriceSample sample = poolPriceCache.get(key);
            if (isSampleStale(sample, now)) {
                Optional<PriceSample> refreshed = refreshPriceForPool(metadata);
                if (refreshed.isPresent()) {
                    sample = refreshed.get();
                }
            }
            if (sample != null) {
                Optional<WeightedPrice> weighted = sample.toWeightedPrice(baseToken);
                if (weighted.isPresent()) {
                    prices.add(weighted.get());
                    continue;
                }
            }
            Optional<BigDecimal> fallback = calculatePrice(metadata, baseToken, null);
            if (fallback.isPresent()) {
                BigDecimal weight = sample != null
                        ? firstPositive(sample.tvlInToken0, sample.tvlInToken1, sample.liquidityWeight)
                        : null;
                if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
                    weight = BigDecimal.ONE;
                }
                prices.add(new WeightedPrice(fallback.get().setScale(18, RoundingMode.HALF_UP), weight));
            }
        }
        return prices;
    }

    /**
     * 合并两段价格路径
     *
     * @param first  第一路径
     * @param second 第二路径
     * @return 合并价格
     */
    private Optional<BigDecimal> combinePrices(Optional<BigDecimal> first, Optional<BigDecimal> second) {
        if (first.isPresent() && second.isPresent()) {
            BigDecimal combined = first.get().multiply(second.get(), PRICE_MATH_CONTEXT);
            return Optional.of(combined.setScale(18, RoundingMode.HALF_UP));
        }
        return Optional.empty();
    }

    /**
     * 计算指定池子的价格
     *
     * @param metadata    池子信息
     * @param baseToken   基础代币
     * @param blockNumber 区块高度
     * @return 价格
     */
    private Optional<BigDecimal> calculatePrice(PairMetadata metadata, String baseToken, BigInteger blockNumber) {
        if (metadata == null) {
            return Optional.empty();
        }
        if (metadata.poolType == PoolType.V3 || metadata.poolType == PoolType.V4) {
            return calculateV3Price(metadata, baseToken, blockNumber);
        }
        return calculateV2Price(metadata, baseToken, blockNumber);
    }

    /**
     * 获取池子中代币的储备量
     *
     * @param pairMetadata 池子信息
     * @param blockNumber  区块高度
     * @return 储备数据
     */
    private Optional<Reserves> getReserves(PairMetadata pairMetadata, BigInteger blockNumber) {
        Function function = new Function(
                "getReserves",
                Collections.emptyList(),
                Arrays.asList(
                        new TypeReference<Uint112>() {
                        },
                        new TypeReference<Uint112>() {
                        },
                        new TypeReference<Uint32>() {
                        }
                )
        );
        String data = FunctionEncoder.encode(function);
        Transaction tx = Transaction.createEthCallTransaction(null, pairMetadata.pairAddress, data);
        try {
            EthCall response = web3j.ethCall(tx, resolveBlockParameter(blockNumber)).send();
            if (response.isReverted()) {
                log.warn("getReserves reverted for pair {}", pairMetadata.pairAddress);
                return Optional.empty();
            }
            List<Type> values = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
            if (values.size() < 2) {
                return Optional.empty();
            }
            BigDecimal reserve0 = new BigDecimal(((Uint112) values.get(0)).getValue());
            BigDecimal reserve1 = new BigDecimal(((Uint112) values.get(1)).getValue());
            return Optional.of(new Reserves(reserve0, reserve1));
        } catch (IOException e) {
            log.error("Failed to query reserves for pair {}", pairMetadata.pairAddress, e);
            return Optional.empty();
        }
    }

    /**
     * 根据储备计算价格
     *
     * @param pairMetadata 池子信息
     * @param blockNumber  区块高度
     * @return 价格
     */
    private Optional<BigDecimal> calculateV2Price(PairMetadata pairMetadata, String baseToken, BigInteger blockNumber) {
        Optional<Reserves> reservesOpt = getReserves(pairMetadata, blockNumber);
        if (!reservesOpt.isPresent()) {
            return Optional.empty();
        }
        Reserves reserves = reservesOpt.get();
        if (pairMetadata.token0.equalsIgnoreCase(baseToken)) {
            BigDecimal baseReserve = reserves.getReserve0()
                    .divide(BigDecimal.TEN.pow(pairMetadata.token0Decimals.intValue()), 18, RoundingMode.HALF_UP);
            BigDecimal quoteReserve = reserves.getReserve1()
                    .divide(BigDecimal.TEN.pow(pairMetadata.token1Decimals.intValue()), 18, RoundingMode.HALF_UP);
            if (!hasSufficientLiquidity(baseReserve, quoteReserve)) {
                return Optional.empty();
            }
            if (baseReserve.compareTo(BigDecimal.ZERO) == 0) {
                return Optional.empty();
            }
            return Optional.of(quoteReserve.divide(baseReserve, 18, RoundingMode.HALF_UP));
        } else if (pairMetadata.token1.equalsIgnoreCase(baseToken)) {
            BigDecimal baseReserve = reserves.getReserve1()
                    .divide(BigDecimal.TEN.pow(pairMetadata.token1Decimals.intValue()), 18, RoundingMode.HALF_UP);
            BigDecimal quoteReserve = reserves.getReserve0()
                    .divide(BigDecimal.TEN.pow(pairMetadata.token0Decimals.intValue()), 18, RoundingMode.HALF_UP);
            if (!hasSufficientLiquidity(baseReserve, quoteReserve)) {
                return Optional.empty();
            }
            if (baseReserve.compareTo(BigDecimal.ZERO) == 0) {
                return Optional.empty();
            }
            return Optional.of(quoteReserve.divide(baseReserve, 18, RoundingMode.HALF_UP));
        }
        return Optional.empty();
    }

    /**
     * 判断池子是否具有足够的流动性
     *
     * @param baseReserve  基础代币储备
     * @param quoteReserve 报价代币储备
     * @return 是否满足阈值
     */
    private boolean hasSufficientLiquidity(BigDecimal baseReserve, BigDecimal quoteReserve) {
        BigDecimal threshold = new BigDecimal("0.001");
        return baseReserve.compareTo(threshold) >= 0 && quoteReserve.compareTo(threshold) >= 0;
    }

    /**
     * 计算 V3 池子价格
     *
     * @param pairMetadata 池子信息
     * @param baseToken    基础代币
     * @param blockNumber  区块高度
     * @return 价格
     */
    private Optional<BigDecimal> calculateV3Price(PairMetadata pairMetadata, String baseToken, BigInteger blockNumber) {
        Optional<Slot0Data> slot0Opt = getSlot0(pairMetadata, blockNumber);
        if (!slot0Opt.isPresent()) {
            return Optional.empty();
        }
        Slot0Data slot0 = slot0Opt.get();
        if (slot0.sqrtPriceX96.equals(BigInteger.ZERO)) {
            return Optional.empty();
        }
        BigDecimal sqrtPrice = new BigDecimal(slot0.sqrtPriceX96);
        BigDecimal numerator = sqrtPrice.multiply(sqrtPrice, PRICE_MATH_CONTEXT);
        BigDecimal denominator = new BigDecimal(BigInteger.ONE.shiftLeft(192));
        BigDecimal priceToken1PerToken0 = numerator.divide(denominator, 18, RoundingMode.HALF_UP);
        priceToken1PerToken0 = adjustForDecimals(priceToken1PerToken0, pairMetadata.token0Decimals, pairMetadata.token1Decimals);

        if (pairMetadata.token0.equalsIgnoreCase(baseToken)) {
            return Optional.of(priceToken1PerToken0.setScale(18, RoundingMode.HALF_UP));
        } else if (pairMetadata.token1.equalsIgnoreCase(baseToken)) {
            if (priceToken1PerToken0.compareTo(BigDecimal.ZERO) == 0) {
                return Optional.empty();
            }
            return Optional.of(BigDecimal.ONE.divide(priceToken1PerToken0, 18, RoundingMode.HALF_UP));
        }
        return Optional.empty();
    }

    /**
     * 查询 slot0 数据
     *
     * @param pairMetadata 池子信息
     * @param blockNumber  区块高度
     * @return slot0 数据
     */
    private Optional<Slot0Data> getSlot0(PairMetadata pairMetadata, BigInteger blockNumber) {
        if (pairMetadata.poolType == PoolType.V4) {
            return getSlot0ForV4(pairMetadata, blockNumber);
        }
        Function function = new Function(
                "slot0",
                Collections.emptyList(),
                Arrays.asList(
                        new TypeReference<Uint160>() {
                        },
                        new TypeReference<Int24>() {
                        },
                        new TypeReference<Uint16>() {
                        },
                        new TypeReference<Uint16>() {
                        },
                        new TypeReference<Uint16>() {
                        },
                        new TypeReference<Uint8>() {
                        },
                        new TypeReference<Bool>() {
                        }
                )
        );
        return executeSlot0Call(pairMetadata, pairMetadata.pairAddress, function, blockNumber);
    }

    /**
     * 查询 V4 池子的 slot0 数据
     */
    private Optional<Slot0Data> getSlot0ForV4(PairMetadata pairMetadata, BigInteger blockNumber) {
        byte[] poolIdBytes = decodePoolId(pairMetadata.pairAddress);
        if (poolIdBytes == null) {
            log.warn("Failed to decode poolId for V4 pool {}", pairMetadata.pairAddress);
            return Optional.empty();
        }
        String target = resolveV4Slot0Contract(pairMetadata.factoryAddress);
        if (target == null) {
            log.warn("Unsupported V4 manager {} for pool {}", pairMetadata.factoryAddress, pairMetadata.pairAddress);
            return Optional.empty();
        }
        Function function = new Function(
                "getSlot0",
                Collections.singletonList(new Bytes32(poolIdBytes)),
                Arrays.asList(
                        new TypeReference<Uint160>() {
                        },
                        new TypeReference<Int24>() {
                        },
                        new TypeReference<Uint24>() {
                        },
                        new TypeReference<Uint24>() {
                        }
                )
        );
        return executeSlot0Call(pairMetadata, target, function, blockNumber);
    }

    /**
     * 执行 slot0 查询并解析结果
     */
    private Optional<Slot0Data> executeSlot0Call(PairMetadata metadata, String contractAddress, Function function, BigInteger blockNumber) {
        if (contractAddress == null) {
            return Optional.empty();
        }
        String data = FunctionEncoder.encode(function);
        Transaction tx = Transaction.createEthCallTransaction(null, contractAddress, data);
        DefaultBlockParameter blockParameter = resolveBlockParameter(blockNumber);
        try {
            EthCall response = web3j.ethCall(tx, blockParameter).send();
            if (response.isReverted()) {
                log.warn("slot0 reverted for pool {} via contract {}", metadata.pairAddress, contractAddress);
                return Optional.empty();
            }
            List<Type> values = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
            if (values.size() < 2) {
                return Optional.empty();
            }
            BigInteger sqrtPriceX96 = ((Uint160) values.get(0)).getValue();
            int tick = ((Int24) values.get(1)).getValue().intValue();
            return Optional.of(new Slot0Data(sqrtPriceX96, tick));
        } catch (IOException e) {
            log.error("Failed to query slot0 for pool {} via contract {}", metadata.pairAddress, contractAddress, e);
            return Optional.empty();
        }
    }

    /**
     * 将池子 ID 转换为 32 字节数组
     */
    private byte[] decodePoolId(String poolId) {
        if (poolId == null) {
            return null;
        }
        byte[] raw = Numeric.hexStringToByteArray(poolId);
        if (raw.length == 32) {
            return raw;
        }
        if (raw.length > 32) {
            byte[] trimmed = new byte[32];
            System.arraycopy(raw, raw.length - 32, trimmed, 0, 32);
            return trimmed;
        }
        byte[] padded = new byte[32];
        System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length);
        return padded;
    }

    /**
     * 解析 V4 slot0 查询目标合约
     */
    private String resolveV4Slot0Contract(String managerAddress) {
        if (managerAddress == null) {
            return null;
        }
        String normalizedManager = normalize(managerAddress);
        if (normalizedManager == null) {
            return null;
        }
        if (normalizedManager.equals(normalize(DexConstants.PANCAKE_V4_FACTORY))) {
            return normalizedManager;
        }
        if (normalizedManager.equals(normalize(DexConstants.UNISWAP_V4_FACTORY))) {
            return normalize(DexConstants.UNISWAP_V4_STATE_VIEW);
        }
        return null;
    }

    /**
     * 根据精度调整价格
     *
     * @param price           原始价格
     * @param token0Decimals token0 精度
     * @param token1Decimals token1 精度
     * @return 调整后的价格
     */
    private BigDecimal adjustForDecimals(BigDecimal price, BigInteger token0Decimals, BigInteger token1Decimals) {
        int diff = token0Decimals.intValue() - token1Decimals.intValue();
        if (diff == 0) {
            return price;
        }
        BigDecimal factor = BigDecimal.TEN.pow(Math.abs(diff));
        if (diff > 0) {
            return price.multiply(factor, PRICE_MATH_CONTEXT);
        }
        return price.divide(factor, PRICE_MATH_CONTEXT);
    }

    /**
     * 根据 tick 计算价格
     *
     * @param tick     tick 值
     * @param metadata 池子信息
     * @return 价格
     */
    private BigDecimal priceFromTick(int tick, PairMetadata metadata) {
        BigDecimal result = BigDecimal.ONE;
        BigDecimal base = BigDecimal.valueOf(1.0001);
        int absTick = Math.abs(tick);
        while (absTick > 0) {
            if ((absTick & 1) == 1) {
                result = result.multiply(base, PRICE_MATH_CONTEXT);
            }
            base = base.multiply(base, PRICE_MATH_CONTEXT);
            absTick >>= 1;
        }
        if (tick < 0) {
            result = BigDecimal.ONE.divide(result, PRICE_MATH_CONTEXT);
        }
        result = adjustForDecimals(result, metadata.token0Decimals, metadata.token1Decimals);
        return result;
    }

    /**
     * 计算目标代币在给定 tick 区间内的价格范围
     *
     * @param metadata  池子信息
     * @param tickLower 下界 tick
     * @param tickUpper 上界 tick
     * @return 价格区间
     */
    public Optional<PriceRange> calculateTargetPriceRange(PairMetadata metadata, int tickLower, int tickUpper) {
        if (metadata == null || metadata.poolType != PoolType.V3) {
            return Optional.empty();
        }
        BigDecimal lowerRatio = priceFromTick(tickLower, metadata);
        BigDecimal upperRatio = priceFromTick(tickUpper, metadata);
        if (metadata.token0.equalsIgnoreCase(tokenAddress)) {
            BigDecimal lower = lowerRatio.setScale(18, RoundingMode.HALF_UP);
            BigDecimal upper = upperRatio.setScale(18, RoundingMode.HALF_UP);
            return Optional.of(new PriceRange(lower, upper));
        } else if (metadata.token1.equalsIgnoreCase(tokenAddress)) {
            if (lowerRatio.compareTo(BigDecimal.ZERO) == 0 || upperRatio.compareTo(BigDecimal.ZERO) == 0) {
                return Optional.empty();
            }
            BigDecimal lower = BigDecimal.ONE.divide(upperRatio, PRICE_MATH_CONTEXT).setScale(18, RoundingMode.HALF_UP);
            BigDecimal upper = BigDecimal.ONE.divide(lowerRatio, PRICE_MATH_CONTEXT).setScale(18, RoundingMode.HALF_UP);
            return Optional.of(new PriceRange(lower, upper));
        }
        return Optional.empty();
    }

    /**
     * 查找或创建配对信息
     *
     * @param tokenA 代币 A
     * @param tokenB 代币 B
     * @return 池子信息
     */
    public Optional<PairMetadata> findOrCreatePair(String tokenA, String tokenB) {
        return findOrCreatePairs(tokenA, tokenB).stream().findFirst();
    }

    /**
     * 查找或创建所有 V2 配对信息
     *
     * @param tokenA 代币 A
     * @param tokenB 代币 B
     * @return 池子信息列表
     */
    public List<PairMetadata> findOrCreatePairs(String tokenA, String tokenB) {
        String key = buildPairKey(tokenA, tokenB);
        Map<String, PairMetadata> cachedByAddress = pairCacheByTokens.get(key);
        List<PairMetadata> result = new ArrayList<>();
        Set<String> seenAddresses = new HashSet<>();
        if (cachedByAddress != null) {
            result.addAll(cachedByAddress.values());
            seenAddresses.addAll(cachedByAddress.keySet());
        }
        for (String factory : DexConstants.V2_FACTORIES) {
            Optional<PairMetadata> metadata = queryPair(factory, tokenA, tokenB);
            if (metadata.isPresent()) {
                PairMetadata pair = metadata.get();
                String normalizedAddress = normalize(pair.pairAddress);
                if (seenAddresses.add(normalizedAddress)) {
                    result.add(pair);
                }
                cachePairMetadata(pair, true);
            }
        }
        return result;
    }

    /**
     * 查找或创建所有可用的 V3 池子信息
     *
     * @param tokenA 代币 A
     * @param tokenB 代币 B
     * @return 池子信息列表
     */
    public List<PairMetadata> findOrCreateV3Pools(String tokenA, String tokenB) {
        List<PairMetadata> pools = new ArrayList<>();
        for (BigInteger fee : DexConstants.V3_FEE_TIERS) {
            String key = buildV3PoolKey(tokenA, tokenB, fee);
            Map<String, PairMetadata> cachedByAddress = v3PoolCache.get(key);
            Set<String> seenAddresses = new HashSet<>();
            if (cachedByAddress != null) {
                pools.addAll(cachedByAddress.values());
                seenAddresses.addAll(cachedByAddress.keySet());
            }
            for (String factory : DexConstants.V3_FACTORIES) {
                Optional<PairMetadata> metadata = queryV3Pool(factory, tokenA, tokenB, fee);
                if (!metadata.isPresent()) {
                    continue;
                }
                PairMetadata pairMetadata = metadata.get();
                String normalizedAddress = normalize(pairMetadata.pairAddress);
                if (seenAddresses.add(normalizedAddress)) {
                    pools.add(pairMetadata);
                }
                cachePairMetadata(pairMetadata, false);
            }
        }
        return pools;
    }

    /**
     * 查找或创建所有可用的 V4 池子信息
     *
     * <p>目前 V4 工厂缺乏稳定的 on-chain 查询接口，此处预留扩展点，
     * 以便将 LiquidityMonitorService 等组件捕获的池子元数据注入缓存后复用。</p>
     *
     * @param tokenA 代币 A
     * @param tokenB 代币 B
     * @return 池子信息列表
     */
    public List<PairMetadata> findOrCreateV4Pools(String tokenA, String tokenB) {
        String key = buildPairKey(tokenA, tokenB);
        Map<String, PairMetadata> cachedByAddress = v4PoolCache.get(key);
        if (cachedByAddress == null || cachedByAddress.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(cachedByAddress.values());
    }

    /**
     * 注册外部已知的 V4 池子元数据
     *
     * @param metadata 池子元数据
     */
    public void registerV4Pool(PairMetadata metadata) {
        if (metadata == null || metadata.pairAddress == null) {
            return;
        }
        PairMetadata normalized = metadata.withPoolType(PoolType.V4);
        cachePairMetadata(normalized, true);
        refreshPriceForPool(normalized);
    }

    /**
     * 查找或创建任意费率的 V3 池子
     *
     * @param tokenA 代币 A
     * @param tokenB 代币 B
     * @return 池子信息
     */
    public Optional<PairMetadata> findOrCreateV3Pool(String tokenA, String tokenB) {
        return findOrCreateV3Pools(tokenA, tokenB).stream().findFirst();
    }

    /**
     * 查询 V2 工厂获得配对
     *
     * @param factory 工厂地址
     * @param tokenA  代币 A
     * @param tokenB  代币 B
     * @return 池子信息
     */
    private Optional<PairMetadata> queryPair(String factory, String tokenA, String tokenB) {
        Function function = new Function(
                "getPair",
                Arrays.asList(new Address(normalize(tokenA)), new Address(normalize(tokenB))),
                Collections.singletonList(new TypeReference<Address>() {
                })
        );
        String encodedFunction = FunctionEncoder.encode(function);
        Transaction tx = Transaction.createEthCallTransaction(null, factory, encodedFunction);
        try {
            EthCall response = web3j.ethCall(tx, DefaultBlockParameterName.LATEST).send();
            if (response.isReverted()) {
                return Optional.empty();
            }
            List<Type> values = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
            if (values.isEmpty()) {
                return Optional.empty();
            }
            String pairAddress = values.get(0).getValue().toString();
            if (isZeroAddress(pairAddress)) {
                return Optional.empty();
            }
            return loadPairMetadata(pairAddress, true, PoolType.V2, null);
        } catch (IOException e) {
            log.error("Failed to query pair from factory {}", factory, e);
            return Optional.empty();
        }
    }

    /**
     * 加载池子元数据
     *
     * @param pairAddress 池子地址
     * @return 元数据
     */
    public Optional<PairMetadata> loadPairMetadata(String pairAddress) {
        return loadPairMetadata(pairAddress, true, PoolType.V2, null);
    }

    /**
     * 加载池子元数据
     *
     * @param pairAddress    池子地址
     * @param cacheByTokens  是否按 token 缓存
     * @return 元数据
     */
    public Optional<PairMetadata> loadPairMetadata(String pairAddress, boolean cacheByTokens) {
        return loadPairMetadata(pairAddress, cacheByTokens, PoolType.V2, null);
    }

    /**
     * 加载池子元数据
     *
     * @param pairAddress    池子地址
     * @param cacheByTokens  是否按 token 缓存
     * @param poolType       池子类型
     * @return 元数据
     */
    public Optional<PairMetadata> loadPairMetadata(String pairAddress, boolean cacheByTokens, PoolType poolType) {
        return loadPairMetadata(pairAddress, cacheByTokens, poolType, null);
    }

    /**
     * 加载池子元数据
     *
     * @param pairAddress    池子地址
     * @param cacheByTokens  是否按 token 缓存
     * @param poolType       池子类型
     * @param fee            费率
     * @return 元数据
     */
    public Optional<PairMetadata> loadPairMetadata(String pairAddress, boolean cacheByTokens, PoolType poolType, BigInteger fee) {
        String normalizedAddress = normalize(pairAddress);
        PairMetadata existing = pairCacheByAddress.get(normalizedAddress);
        if (existing != null) {
            PairMetadata updated = existing;
            if (poolType != null && existing.poolType != poolType) {
                updated = existing.withPoolType(poolType);
            }
            if (fee != null) {
                updated = updated.withFee(fee);
            }
            if (updated != existing) {
                cachePairMetadata(updated, cacheByTokens || poolType == PoolType.V2);
            } else if (cacheByTokens) {
                cachePairMetadata(existing, true);
            }
            return Optional.of(updated);
        }
        try {
            String token0 = callAddressFunction(pairAddress, "token0");
            String token1 = callAddressFunction(pairAddress, "token1");
            if (token0 == null || token1 == null) {
                return Optional.empty();
            }
            BigInteger token0Decimals = fetchDecimals(token0);
            BigInteger token1Decimals = fetchDecimals(token1);
            String token0Symbol = fetchSymbol(token0);
            String token1Symbol = fetchSymbol(token1);
            String factoryAddress = callAddressFunction(pairAddress, "factory");
            String swapName = resolveFactoryName(factoryAddress);
            PairMetadata metadata = new PairMetadata(pairAddress, token0, token1, token0Decimals, token1Decimals, token0Symbol, token1Symbol, poolType, fee, factoryAddress, swapName);
            if (metadata.poolType == PoolType.V3 && metadata.fee == null) {
                metadata = metadata.withFee(fetchFee(pairAddress));
            }
            cachePairMetadata(metadata, cacheByTokens || poolType == PoolType.V2);
            return Optional.of(metadata);
        } catch (IOException e) {
            log.error("Failed to load pair metadata for {}", pairAddress, e);
            return Optional.empty();
        }
    }

    /**
     * 缓存池子元数据
     *
     * @param metadata      元数据
     * @param cacheByTokens 是否按 token 缓存
     */
    private void cachePairMetadata(PairMetadata metadata, boolean cacheByTokens) {
        if (metadata == null) {
            return;
        }
        String normalizedAddress = normalize(metadata.pairAddress);
        pairCacheByAddress.put(normalizedAddress, metadata);
        if (cacheByTokens) {
            addPairToTokenCache(metadata.token0, metadata.token1, metadata);
            addPairToTokenCache(metadata.token1, metadata.token0, metadata);
        }
        if (metadata.poolType == PoolType.V3 && metadata.fee != null) {
            addV3PoolToCache(metadata.token0, metadata.token1, metadata.fee, metadata);
            addV3PoolToCache(metadata.token1, metadata.token0, metadata.fee, metadata);
        }
        if (metadata.poolType == PoolType.V4) {
            addV4PoolToCache(metadata.token0, metadata.token1, metadata);
            addV4PoolToCache(metadata.token1, metadata.token0, metadata);
        }
    }

    /**
     * 将池子加入 token 缓存
     *
     * @param tokenA    代币 A
     * @param tokenB    代币 B
     * @param metadata  池子信息
     */
    private void addPairToTokenCache(String tokenA, String tokenB, PairMetadata metadata) {
        String key = buildPairKey(tokenA, tokenB);
        pairCacheByTokens.compute(key, (k, existing) -> {
            Map<String, PairMetadata> updated = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
            String normalizedAddress = normalize(metadata.pairAddress);
            updated.put(normalizedAddress, metadata);
            return updated;
        });
    }

    /**
     * 将 V3 池子加入缓存
     *
     * @param tokenA    代币 A
     * @param tokenB    代币 B
     * @param fee       费率
     * @param metadata  池子信息
     */
    private void addV3PoolToCache(String tokenA, String tokenB, BigInteger fee, PairMetadata metadata) {
        if (fee == null) {
            return;
        }
        String key = buildV3PoolKey(tokenA, tokenB, fee);
        v3PoolCache.compute(key, (k, existing) -> {
            Map<String, PairMetadata> updated = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
            String normalizedAddress = normalize(metadata.pairAddress);
            updated.put(normalizedAddress, metadata);
            return updated;
        });
    }

    /**
     * 将 V4 池子加入缓存
     *
     * @param tokenA   代币 A
     * @param tokenB   代币 B
     * @param metadata 池子元数据
     */
    private void addV4PoolToCache(String tokenA, String tokenB, PairMetadata metadata) {
        String key = buildPairKey(tokenA, tokenB);
        v4PoolCache.compute(key, (k, existing) -> {
            Map<String, PairMetadata> updated = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
            String normalizedAddress = normalize(metadata.pairAddress);
            updated.put(normalizedAddress, metadata);
            return updated;
        });
    }

    /**
     * 调用返回地址的函数
     *
     * @param contractAddress 合约地址
     * @param functionName    函数名
     * @return 地址
     * @throws IOException 调用异常
     */
    private String callAddressFunction(String contractAddress, String functionName) throws IOException {
        Function function = new Function(
                functionName,
                Collections.emptyList(),
                Collections.singletonList(new TypeReference<Address>() {
                })
        );
        String encodedFunction = FunctionEncoder.encode(function);
        Transaction tx = Transaction.createEthCallTransaction(null, contractAddress, encodedFunction);
        EthCall response = web3j.ethCall(tx, DefaultBlockParameterName.LATEST).send();
        if (response.isReverted()) {
            return null;
        }
        List<Type> values = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
        if (values.isEmpty()) {
            return null;
        }
        return normalize(values.get(0).getValue().toString());
    }

    /**
     * 查询代币精度
     *
     * @param token 代币地址
     * @return 精度
     */
    private BigInteger fetchDecimals(String token) {
        String normalized = normalize(token);
        return decimalsCache.computeIfAbsent(normalized, key ->
                tokenInfoService.loadDecimals(token).orElse(BigInteger.valueOf(18)));
    }

    /**
     * 查询代币符号
     *
     * @param token 代币地址
     * @return 代币符号
     */
    private String fetchSymbol(String token) {
        if (token == null) {
            return null;
        }
        String normalized = normalize(token);
        if (isZeroAddress(normalized)) {
            symbolCache.putIfAbsent(normalized, "BNB");
            return "BNB";
        }
        return symbolCache.computeIfAbsent(normalized, key ->
                tokenInfoService.loadSymbol(token).orElse(normalized));
    }

    /**
     * 解析工厂名称
     *
     * @param factoryAddress 工厂地址
     * @return 交易所名称
     */
    private String resolveFactoryName(String factoryAddress) {
        if (factoryAddress == null) {
            return null;
        }
        return DexConstants.FACTORY_NAMES.get(factoryAddress.toLowerCase(Locale.ROOT));
    }

    /**
     * 查询池子费率
     *
     * @param poolAddress 池子地址
     * @return 费率
     */
    private BigInteger fetchFee(String poolAddress) {
        Function function = new Function(
                "fee",
                Collections.emptyList(),
                Collections.singletonList(new TypeReference<Uint24>() {
                })
        );
        String data = FunctionEncoder.encode(function);
        Transaction tx = Transaction.createEthCallTransaction(null, poolAddress, data);
        try {
            EthCall response = web3j.ethCall(tx, DefaultBlockParameterName.LATEST).send();
            if (response.isReverted()) {
                return null;
            }
            List<Type> values = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
            if (values.isEmpty()) {
                return null;
            }
            return ((Uint24) values.get(0)).getValue();
        } catch (IOException e) {
            log.error("Failed to fetch fee for pool {}", poolAddress, e);
            return null;
        }
    }

    /**
     * 查询池子持有的代币余额并转换为标准精度
     *
     * @param tokenAddress 代币地址
     * @param decimals     代币精度
     * @param holder       池子地址
     * @return 标准化余额
     */
    private Optional<BigDecimal> fetchTokenBalance(String tokenAddress, BigInteger decimals, String holder) {
        if (tokenAddress == null || holder == null) {
            return Optional.empty();
        }
        Optional<BigInteger> balanceOpt = tokenInfoService.loadBalance(tokenAddress, holder);
        if (!balanceOpt.isPresent()) {
            return Optional.empty();
        }
        int scale = decimals != null ? decimals.intValue() : 18;
        if (scale < 0) {
            scale = 0;
        }
        BigDecimal divisor = BigDecimal.TEN.pow(scale);
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            return Optional.empty();
        }
        BigDecimal normalized = new BigDecimal(balanceOpt.get())
                .divide(divisor, 18, RoundingMode.HALF_UP);
        return Optional.of(normalized);
    }

    /**
     * 查询 V3 池子的 tickSpacing
     *
     * @param metadata 池子元数据
     * @return tickSpacing
     */
    private Optional<Integer> fetchTickSpacing(PairMetadata metadata) {
        if (metadata == null || metadata.poolType != PoolType.V3) {
            return Optional.empty();
        }
        Function function = new Function(
                "tickSpacing",
                Collections.emptyList(),
                Collections.singletonList(new TypeReference<Int24>() {
                })
        );
        String data = FunctionEncoder.encode(function);
        Transaction tx = Transaction.createEthCallTransaction(null, metadata.pairAddress, data);
        try {
            EthCall response = web3j.ethCall(tx, DefaultBlockParameterName.LATEST).send();
            if (response.isReverted()) {
                return Optional.empty();
            }
            List<Type> values = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
            if (values.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(((Int24) values.get(0)).getValue().intValue());
        } catch (IOException e) {
            log.error("Failed to query tickSpacing for pool {}", metadata.pairAddress, e);
            return Optional.empty();
        }
    }

    /**
     * 查询最新区块高度
     *
     * @return 最新区块
     */
    private Optional<BigInteger> fetchLatestBlockNumber() {
        try {
            return Optional.ofNullable(web3j.ethBlockNumber().send().getBlockNumber());
        } catch (IOException e) {
            log.error("Failed to fetch latest block number", e);
            return Optional.empty();
        }
    }

    /**
     * 加载池子当前快照信息
     *
     * @param metadata 池子元数据
     * @return 池子快照
     */
    public Optional<PoolSnapshot> loadPoolSnapshot(PairMetadata metadata) {
        if (metadata == null || metadata.pairAddress == null) {
            return Optional.empty();
        }
        Optional<BigDecimal> token0BalanceOpt = fetchTokenBalance(metadata.token0, metadata.token0Decimals, metadata.pairAddress);
        Optional<BigDecimal> token1BalanceOpt = fetchTokenBalance(metadata.token1, metadata.token1Decimals, metadata.pairAddress);
        BigDecimal priceToken1PerToken0 = null;
        if (token0BalanceOpt.isPresent() && token1BalanceOpt.isPresent()
                && token0BalanceOpt.get().compareTo(BigDecimal.ZERO) > 0) {
            priceToken1PerToken0 = token1BalanceOpt.get()
                    .divide(token0BalanceOpt.get(), 18, RoundingMode.HALF_UP);
        }
        Integer currentTick = null;
        BigInteger sqrtPriceX96 = null;
        Integer tickSpacing = null;
        if (metadata.poolType == PoolType.V3) {
            Optional<BigInteger> blockNumberOpt = fetchLatestBlockNumber();
            if (blockNumberOpt.isPresent()) {
                Optional<Slot0Data> slot0Opt = getSlot0(metadata, blockNumberOpt.get());
                if (slot0Opt.isPresent()) {
                    Slot0Data slot0 = slot0Opt.get();
                    currentTick = slot0.tick;
                    sqrtPriceX96 = slot0.sqrtPriceX96;
                }
            }
            tickSpacing = fetchTickSpacing(metadata).orElse(null);
        }
        return Optional.of(new PoolSnapshot(
                token0BalanceOpt.orElse(null),
                token1BalanceOpt.orElse(null),
                priceToken1PerToken0,
                currentTick,
                tickSpacing,
                sqrtPriceX96));
    }

    /**
     * 构建配对键
     *
     * @param tokenA 代币 A
     * @param tokenB 代币 B
     * @return 键值
     */
    private String buildPairKey(String tokenA, String tokenB) {
        return normalize(tokenA) + "-" + normalize(tokenB);
    }

    /**
     * 构建 V3 池子缓存键
     *
     * @param tokenA 代币 A
     * @param tokenB 代币 B
     * @param fee    费率
     * @return 键值
     */
    private String buildV3PoolKey(String tokenA, String tokenB, BigInteger fee) {
        return buildPairKey(tokenA, tokenB) + ":" + fee.toString();
    }

    /**
     * 地址归一化（小写）
     *
     * @param address 地址
     * @return 归一化地址
     */
    private String normalize(String address) {
        return address == null ? null : address.toLowerCase();
    }

    /**
     * 获取代币精度
     *
     * @param token 代币地址
     * @return 精度
     */
    public BigInteger resolveTokenDecimals(String token) {
        return fetchDecimals(token);
    }

    /**
     * 获取代币符号
     *
     * @param token 代币地址
     * @return 符号
     */
    public String resolveTokenSymbol(String token) {
        return fetchSymbol(token);
    }

    /**
     * 查询 V3 工厂获得池子
     *
     * @param factory 工厂地址
     * @param tokenA  代币 A
     * @param tokenB  代币 B
     * @param fee     费率
     * @return 池子信息
     */
    private Optional<PairMetadata> queryV3Pool(String factory, String tokenA, String tokenB, BigInteger fee) {
        Function function = new Function(
                "getPool",
                Arrays.asList(new Address(normalize(tokenA)), new Address(normalize(tokenB)), new Uint24(fee)),
                Collections.singletonList(new TypeReference<Address>() {
                })
        );
        String encodedFunction = FunctionEncoder.encode(function);
        Transaction tx = Transaction.createEthCallTransaction(null, factory, encodedFunction);
        try {
            EthCall response = web3j.ethCall(tx, DefaultBlockParameterName.LATEST).send();
            if (response.isReverted()) {
                return Optional.empty();
            }
            List<Type> values = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
            if (values.isEmpty()) {
                return Optional.empty();
            }
            String poolAddress = values.get(0).getValue().toString();
            if (isZeroAddress(poolAddress)) {
                return Optional.empty();
            }
            return loadPairMetadata(poolAddress, false, PoolType.V3, fee);
        } catch (IOException e) {
            log.error("Failed to query V3 pool from factory {} with fee {}", factory, fee, e);
            return Optional.empty();
        }
    }

    /**
     * 判断地址是否为零地址
     *
     * @param address 地址
     * @return 是否为零地址
     */
    private boolean isZeroAddress(String address) {
        return address == null || Numeric.toBigInt(address).equals(BigInteger.ZERO);
    }

    /**
     * 池子快照数据
     */
    public static class PoolSnapshot {
        /** token0 数量（标准精度） */
        public final BigDecimal token0Amount;
        /** token1 数量（标准精度） */
        public final BigDecimal token1Amount;
        /** token1/token0 价格 */
        public final BigDecimal priceToken1PerToken0;
        /** 当前 tick */
        public final Integer currentTick;
        /** tickSpacing */
        public final Integer tickSpacing;
        /** 当前 sqrtPriceX96 */
        public final BigInteger sqrtPriceX96;

        public PoolSnapshot(BigDecimal token0Amount,
                            BigDecimal token1Amount,
                            BigDecimal priceToken1PerToken0,
                            Integer currentTick,
                            Integer tickSpacing,
                            BigInteger sqrtPriceX96) {
            this.token0Amount = token0Amount;
            this.token1Amount = token1Amount;
            this.priceToken1PerToken0 = priceToken1PerToken0;
            this.currentTick = currentTick;
            this.tickSpacing = tickSpacing;
            this.sqrtPriceX96 = sqrtPriceX96;
        }

        /**
         * 计算指定代币的价格
         *
         * @param baseToken 代币地址
         * @param metadata  池子元数据
         * @return 价格
         */
        public Optional<BigDecimal> priceForToken(String baseToken, PairMetadata metadata) {
            if (metadata == null || priceToken1PerToken0 == null || baseToken == null) {
                return Optional.empty();
            }
            if (metadata.token0.equalsIgnoreCase(baseToken)) {
                return Optional.of(priceToken1PerToken0);
            }
            if (metadata.token1.equalsIgnoreCase(baseToken) && priceToken1PerToken0.compareTo(BigDecimal.ZERO) > 0) {
                return Optional.of(BigDecimal.ONE.divide(priceToken1PerToken0, 18, RoundingMode.HALF_UP));
            }
            return Optional.empty();
        }
    }

    /**
     * 加权价格结构
     */
    private static class WeightedPrice {
        private final BigDecimal price;
        private final BigDecimal weight;

        private WeightedPrice(BigDecimal price, BigDecimal weight) {
            this.price = price;
            this.weight = weight;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public BigDecimal getWeight() {
            return weight;
        }
    }

    /**
     * 价格样本
     */
    private static class PriceSample {
        private final PairMetadata metadata;
        private final BigDecimal priceToken1PerToken0;
        private final BigDecimal priceToken0PerToken1;
        private final BigDecimal reserve0;
        private final BigDecimal reserve1;
        private final BigDecimal tvlInToken1;
        private final BigDecimal tvlInToken0;
        private final BigDecimal liquidityWeight;
        private final long updatedAt;

        private PriceSample(PairMetadata metadata,
                             BigDecimal priceToken1PerToken0,
                             BigDecimal priceToken0PerToken1,
                             BigDecimal reserve0,
                             BigDecimal reserve1,
                             BigDecimal tvlInToken1,
                             BigDecimal tvlInToken0,
                             BigDecimal liquidityWeight,
                             long updatedAt) {
            this.metadata = metadata;
            this.priceToken1PerToken0 = priceToken1PerToken0;
            this.priceToken0PerToken1 = priceToken0PerToken1;
            this.reserve0 = reserve0;
            this.reserve1 = reserve1;
            this.tvlInToken1 = tvlInToken1;
            this.tvlInToken0 = tvlInToken0;
            this.liquidityWeight = liquidityWeight;
            this.updatedAt = updatedAt;
        }

        private Optional<WeightedPrice> toWeightedPrice(String baseToken) {
            if (metadata == null || baseToken == null) {
                return Optional.empty();
            }
            if (metadata.token0 != null && metadata.token0.equalsIgnoreCase(baseToken)) {
                if (priceToken1PerToken0 == null || priceToken1PerToken0.compareTo(BigDecimal.ZERO) <= 0) {
                    return Optional.empty();
                }
                BigDecimal weight = firstPositive(tvlInToken1,
                        reserve0 != null && priceToken1PerToken0 != null
                                ? reserve0.multiply(priceToken1PerToken0, PRICE_MATH_CONTEXT)
                                : null,
                        liquidityWeight);
                if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
                    weight = BigDecimal.ONE;
                }
                return Optional.of(new WeightedPrice(priceToken1PerToken0, weight));
            }
            if (metadata.token1 != null && metadata.token1.equalsIgnoreCase(baseToken)) {
                if (priceToken0PerToken1 == null || priceToken0PerToken1.compareTo(BigDecimal.ZERO) <= 0) {
                    return Optional.empty();
                }
                BigDecimal weight = firstPositive(tvlInToken0,
                        reserve1 != null && priceToken0PerToken1 != null
                                ? reserve1.multiply(priceToken0PerToken1, PRICE_MATH_CONTEXT)
                                : null,
                        liquidityWeight);
                if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
                    weight = BigDecimal.ONE;
                }
                return Optional.of(new WeightedPrice(priceToken0PerToken1, weight));
            }
            return Optional.empty();
        }
    }

    /**
     * 储备数据结构
     */
    private static class Reserves {
        private final BigDecimal reserve0;
        private final BigDecimal reserve1;

        private Reserves(BigDecimal reserve0, BigDecimal reserve1) {
            this.reserve0 = reserve0;
            this.reserve1 = reserve1;
        }

        /**
         * 获取 token0 储备
         *
         * @return 储备
         */
        public BigDecimal getReserve0() {
            return reserve0;
        }

        /**
         * 获取 token1 储备
         *
         * @return 储备
         */
        public BigDecimal getReserve1() {
            return reserve1;
        }
    }

    /**
     * slot0 数据结构
     */
    private static class Slot0Data {
        /** sqrtPrice 值 */
        private final BigInteger sqrtPriceX96;
        /** 当前 tick */
        private final int tick;

        private Slot0Data(BigInteger sqrtPriceX96, int tick) {
            this.sqrtPriceX96 = sqrtPriceX96;
            this.tick = tick;
        }
    }

    /**
     * V3 价格区间
     */
    public static class PriceRange {
        public final BigDecimal lower;
        public final BigDecimal upper;

        public PriceRange(BigDecimal lower, BigDecimal upper) {
            this.lower = lower;
            this.upper = upper;
        }
    }

    /**
     * 池子类型
     */
    public enum PoolType {
        V2,
        V3,
        V4
    }

    /**
     * 池子元数据
     */
    public static class PairMetadata {
        /** 池子合约地址 */
        public final String pairAddress;
        /** token0 地址 */
        public final String token0;
        /** token1 地址 */
        public final String token1;
        /** token0 精度 */
        public final BigInteger token0Decimals;
        /** token1 精度 */
        public final BigInteger token1Decimals;
        /** token0 符号 */
        public final String token0Symbol;
        /** token1 符号 */
        public final String token1Symbol;
        /** 池子类型 */
        public final PoolType poolType;
        /** 费率 */
        public final BigInteger fee;
        /** 工厂地址 */
        public final String factoryAddress;
        /** 交易所名称 */
        public final String swapName;

        public PairMetadata(String pairAddress, String token0, String token1,
                             BigInteger token0Decimals, BigInteger token1Decimals,
                             String token0Symbol, String token1Symbol,
                             PoolType poolType, BigInteger fee,
                             String factoryAddress, String swapName) {
            this.pairAddress = pairAddress;
            this.token0 = token0;
            this.token1 = token1;
            this.token0Decimals = token0Decimals;
            this.token1Decimals = token1Decimals;
            this.token0Symbol = token0Symbol;
            this.token1Symbol = token1Symbol;
            this.poolType = poolType == null ? PoolType.V2 : poolType;
            this.fee = fee;
            this.factoryAddress = factoryAddress;
            this.swapName = swapName;
        }

        public PairMetadata withPoolType(PoolType poolType) {
            if (poolType == null || this.poolType == poolType) {
                return this;
            }
            return new PairMetadata(pairAddress, token0, token1, token0Decimals, token1Decimals,
                    token0Symbol, token1Symbol, poolType, fee, factoryAddress, swapName);
        }

        public PairMetadata withFee(BigInteger fee) {
            if (fee == null || (this.fee != null && this.fee.equals(fee))) {
                return this;
            }
            return new PairMetadata(pairAddress, token0, token1, token0Decimals, token1Decimals,
                    token0Symbol, token1Symbol, poolType, fee, factoryAddress, swapName);
        }

        public String getDisplayName() {
            String symbol0 = token0Symbol != null ? token0Symbol : token0;
            String symbol1 = token1Symbol != null ? token1Symbol : token1;
            return symbol0.toLowerCase(java.util.Locale.ROOT) + "-" + symbol1.toLowerCase(java.util.Locale.ROOT);
        }

        public String getSwapName() {
            if (swapName != null && !StringUtils.isEmpty(swapName)) {
                return swapName;
            }
            return factoryAddress != null ? factoryAddress : "unknown";
        }
    }
}
