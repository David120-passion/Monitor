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
import org.web3j.abi.datatypes.generated.Int24;
import org.web3j.abi.datatypes.generated.Uint112;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /** 价格计算精度 */
    private static final MathContext PRICE_MATH_CONTEXT = new MathContext(40, RoundingMode.HALF_UP);

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
        List<BigDecimal> prices = new ArrayList<>();

        prices.addAll(collectPricesFromPairs(findOrCreatePairs(baseToken, quoteToken), baseToken, blockNumber));
        prices.addAll(collectPricesFromPairs(findOrCreateV3Pools(baseToken, quoteToken), baseToken, blockNumber));
        prices.addAll(collectPricesFromPairs(findOrCreateV4Pools(baseToken, quoteToken), baseToken, blockNumber));

        if (prices.isEmpty()) {
            return Optional.empty();
        }

        Optional<BigDecimal> average = calculateMedianPrice(prices);

        return average;
    }

    /**
     * 计算价格中位数
     * @param prices
     * @return
     */
    private Optional<BigDecimal> calculateMedianPrice(List<BigDecimal> prices) {
        if (prices == null || prices.isEmpty()) {
            return Optional.empty();
        }

        // 过滤掉 null 或 ≤0 的价格
        List<BigDecimal> validPrices = prices.stream()
                .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                .sorted()
                .collect(Collectors.toList());

        if (validPrices.isEmpty()) {
            return Optional.empty();
        }

        int size = validPrices.size();
        if (size % 2 == 1) {
            // 奇数个，直接取中间值
            return Optional.of(validPrices.get(size / 2));
        } else {
            // 偶数个，取中间两个的平均值
            BigDecimal p1 = validPrices.get(size / 2 - 1);
            BigDecimal p2 = validPrices.get(size / 2);
            BigDecimal median = p1.add(p2).divide(BigDecimal.valueOf(2), 18, RoundingMode.HALF_UP);
            return Optional.of(median);
        }
    }

    /**
     * 遍历池子集合并收集价格
     *
     * @param pairs       池子列表
     * @param baseToken   基础代币
     * @param blockNumber 区块高度
     * @return 价格列表
     */
    private List<BigDecimal> collectPricesFromPairs(List<PairMetadata> pairs, String baseToken, BigInteger blockNumber) {
        if (pairs == null || pairs.isEmpty()) {
            return Collections.emptyList();
        }
        List<BigDecimal> prices = new ArrayList<>();
        for (PairMetadata metadata : pairs) {
            Optional<BigDecimal> price = calculatePrice(metadata, baseToken, blockNumber);
            price.ifPresent(value -> prices.add(value.setScale(18, RoundingMode.HALF_UP)));
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
            EthCall response = web3j.ethCall(tx, DefaultBlockParameter.valueOf(blockNumber)).send();
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
        String data = FunctionEncoder.encode(function);
        Transaction tx = Transaction.createEthCallTransaction(null, pairMetadata.pairAddress, data);
        try {
            EthCall response = web3j.ethCall(tx, DefaultBlockParameter.valueOf(blockNumber)).send();
            if (response.isReverted()) {
                log.warn("slot0 reverted for pool {}", pairMetadata.pairAddress);
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
            log.error("Failed to query slot0 for pool {}", pairMetadata.pairAddress, e);
            return Optional.empty();
        }
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
