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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    /** 按 token 组合缓存的 V2 配对信息 */
    private final Map<String, List<PairMetadata>> pairCacheByTokens = new ConcurrentHashMap<>();
    /** 按地址缓存的池子信息 */
    private final Map<String, PairMetadata> pairCacheByAddress = new ConcurrentHashMap<>();
    /** 按 token 与费率缓存的 V3 池子信息 */
    private final Map<String, PairMetadata> v3PoolCache = new ConcurrentHashMap<>();
    /** 按 token 与费率缓存的 V4 池子信息 */
    private final Map<String, PairMetadata> v4PoolCache = new ConcurrentHashMap<>();

    /** 价格计算精度 */
    private static final MathContext PRICE_MATH_CONTEXT = new MathContext(40, RoundingMode.HALF_UP);
    /** 零地址常量 */
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    /** 空的 32 字节参数占位 */
    private static final Bytes32 EMPTY_BYTES32 = new Bytes32(new byte[32]);

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
        Optional<BigDecimal> directBusd = getBestPriceForPair(tokenAddress, DexConstants.USDT_ADDRESS, blockNumber);
        if (directBusd.isPresent()) {
            return directBusd.map(value -> value.setScale(8, RoundingMode.HALF_UP));
        }

        Optional<BigDecimal> viaWbnb = combinePrices(
                getBestPriceForPair(tokenAddress, DexConstants.WBNB_ADDRESS, blockNumber),
                getBestPriceForPair(DexConstants.WBNB_ADDRESS, DexConstants.USDT_ADDRESS, blockNumber)
        );
        if (viaWbnb.isPresent()) {
            return viaWbnb.map(value -> value.setScale(8, RoundingMode.HALF_UP));
        }

        return Optional.empty();
    }

    /**
     * 获取指定交易对的最佳价格
     *
     * @param baseToken  基础代币
     * @param quoteToken 报价代币
     * @param blockNumber 区块高度
     * @return 最佳价格
     */
    private Optional<BigDecimal> getBestPriceForPair(String baseToken, String quoteToken, BigInteger blockNumber) {
        List<PairMetadata> v2Pairs = findOrCreatePairs(baseToken, quoteToken);
        Optional<BigDecimal> v2Price = getBestPriceFromPairs(v2Pairs, baseToken, blockNumber);
        if (v2Price.isPresent()) {
            return v2Price;
        }
        List<PairMetadata> v3Pools = findOrCreateV3Pools(baseToken, quoteToken);
        Optional<BigDecimal> v3Price = getBestPriceFromPairs(v3Pools, baseToken, blockNumber);
        if (v3Price.isPresent()) {
            return v3Price;
        }
        List<PairMetadata> v4Pools = findOrCreateV4Pools(baseToken, quoteToken);
        return getBestPriceFromPairs(v4Pools, baseToken, blockNumber);
    }

    /**
     * 从一组池子中选择最佳价格
     *
     * @param pairs       池子列表
     * @param baseToken   基础代币
     * @param blockNumber 区块高度
     * @return 最佳价格
     */
    private Optional<BigDecimal> getBestPriceFromPairs(List<PairMetadata> pairs, String baseToken, BigInteger blockNumber) {
        for (PairMetadata metadata : pairs) {
            Optional<BigDecimal> price = calculatePrice(metadata, baseToken, blockNumber);
            if (price.isPresent()) {
                return price.map(value -> value.setScale(18, RoundingMode.HALF_UP));
            }
        }
        return Optional.empty();
    }

    private Optional<BigDecimal> combinePrices(Optional<BigDecimal> first, Optional<BigDecimal> second) {
        if (first.isPresent() && second.isPresent()) {
            BigDecimal combined = first.get().multiply(second.get(), PRICE_MATH_CONTEXT);
            return Optional.of(combined.setScale(18, RoundingMode.HALF_UP));
        }
        return Optional.empty();
    }

    /**
     * 根据池子类型计算价格
     *
     * @param metadata   池子元数据
     * @param baseToken  基础代币
     * @param blockNumber 区块高度
     * @return 价格
     */
    private Optional<BigDecimal> calculatePrice(PairMetadata metadata, String baseToken, BigInteger blockNumber) {
        if (metadata == null) {
            return Optional.empty();
        }
        if (metadata.poolType == PoolType.V3 || metadata.poolType == PoolType.V4) {
            return calculateConcentratedLiquidityPrice(metadata, baseToken, blockNumber);
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
            if (baseReserve.compareTo(BigDecimal.ZERO) == 0) {
                return Optional.empty();
            }
            return Optional.of(quoteReserve.divide(baseReserve, 18, RoundingMode.HALF_UP));
        } else if (pairMetadata.token1.equalsIgnoreCase(baseToken)) {
            BigDecimal baseReserve = reserves.getReserve1()
                    .divide(BigDecimal.TEN.pow(pairMetadata.token1Decimals.intValue()), 18, RoundingMode.HALF_UP);
            BigDecimal quoteReserve = reserves.getReserve0()
                    .divide(BigDecimal.TEN.pow(pairMetadata.token0Decimals.intValue()), 18, RoundingMode.HALF_UP);
            if (baseReserve.compareTo(BigDecimal.ZERO) == 0) {
                return Optional.empty();
            }
            return Optional.of(quoteReserve.divide(baseReserve, 18, RoundingMode.HALF_UP));
        }
        return Optional.empty();
    }

    /**
     * 计算集中流动性池价格（V3/V4）
     *
     * @param pairMetadata 池子元数据
     * @param baseToken    基础代币
     * @param blockNumber  区块高度
     * @return 价格
     */
    private Optional<BigDecimal> calculateConcentratedLiquidityPrice(PairMetadata pairMetadata, String baseToken, BigInteger blockNumber) {
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
     * 查询集中流动性池的 slot0 数据
     *
     * @param pairMetadata 池子元数据
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
     * 根据代币精度调整价格
     *
     * @param price           原始价格
     * @param token0Decimals  token0 精度
     * @param token1Decimals  token1 精度
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
     * 根据 tick 计算价格比率
     *
     * @param tick      tick 值
     * @param metadata  池子元数据
     * @return 价格比率
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

    public Optional<PriceRange> calculateTargetPriceRange(PairMetadata metadata, int tickLower, int tickUpper) {
        if (metadata == null || (metadata.poolType != PoolType.V3 && metadata.poolType != PoolType.V4)) {
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
        List<PairMetadata> cached = pairCacheByTokens.get(key);
        List<PairMetadata> result = new ArrayList<>();
        Set<String> seenAddresses = ConcurrentHashMap.newKeySet();
        if (cached != null) {
            result.addAll(cached);
            cached.stream().map(meta -> normalize(meta.pairAddress)).forEach(seenAddresses::add);
        }
        for (String factory : DexConstants.V2_FACTORIES) {
            Optional<PairMetadata> metadata = queryPair(factory, tokenA, tokenB);
            if (metadata.isPresent()) {
                PairMetadata pair = metadata.get();
                String normalizedAddress = normalize(pair.pairAddress);
                if (seenAddresses.add(normalizedAddress)) {
                    result.add(pair);
                    cachePairMetadata(pair, true);
                }
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
            PairMetadata cached = v3PoolCache.get(key);
            if (cached != null) {
                pools.add(cached);
                continue;
            }
            for (String factory : DexConstants.V3_FACTORIES) {
                Optional<PairMetadata> metadata = queryV3Pool(factory, tokenA, tokenB, fee);
                if (metadata.isPresent()) {
                    PairMetadata pairMetadata = metadata.get();
                    v3PoolCache.put(key, pairMetadata);
                    v3PoolCache.put(buildV3PoolKey(tokenB, tokenA, fee), pairMetadata);
                    pools.add(pairMetadata);
                    break;
                }
            }
        }
        return pools;
    }

    /**
     * 查找或创建单个 V3 池子
     *
     * @param tokenA 代币 A
     * @param tokenB 代币 B
     * @return 池子信息
     */
    public Optional<PairMetadata> findOrCreateV3Pool(String tokenA, String tokenB) {
        return findOrCreateV3Pools(tokenA, tokenB).stream().findFirst();
    }

    /**
     * 查找或创建所有可用的 V4 池子信息
     *
     * @param tokenA 代币 A
     * @param tokenB 代币 B
     * @return 池子信息列表
     */
    public List<PairMetadata> findOrCreateV4Pools(String tokenA, String tokenB) {
        List<PairMetadata> pools = new ArrayList<>();
        for (BigInteger fee : DexConstants.V3_FEE_TIERS) {
            String key = buildV4PoolKey(tokenA, tokenB, fee);
            PairMetadata cached = v4PoolCache.get(key);
            if (cached != null) {
                pools.add(cached);
                continue;
            }
            for (String factory : DexConstants.V4_FACTORIES) {
                Optional<PairMetadata> metadata = queryV4Pool(factory, tokenA, tokenB, fee);
                if (metadata.isPresent()) {
                    PairMetadata pairMetadata = metadata.get();
                    v4PoolCache.put(key, pairMetadata);
                    v4PoolCache.put(buildV4PoolKey(tokenB, tokenA, fee), pairMetadata);
                    pools.add(pairMetadata);
                    break;
                }
            }
        }
        return pools;
    }

    /**
     * 查找或创建单个 V4 池子
     *
     * @param tokenA 代币 A
     * @param tokenB 代币 B
     * @return 池子信息
     */
    public Optional<PairMetadata> findOrCreateV4Pool(String tokenA, String tokenB) {
        return findOrCreateV4Pools(tokenA, tokenB).stream().findFirst();
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
     * @param cacheByTokens  是否按代币缓存
     * @return 元数据
     */
    public Optional<PairMetadata> loadPairMetadata(String pairAddress, boolean cacheByTokens) {
        return loadPairMetadata(pairAddress, cacheByTokens, PoolType.V2, null);
    }

    /**
     * 加载池子元数据
     *
     * @param pairAddress    池子地址
     * @param cacheByTokens  是否按代币缓存
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
     * @param cacheByTokens  是否按代币缓存
     * @param poolType       池子类型
     * @param fee            池子费率
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
            PairMetadata metadata = new PairMetadata(pairAddress, token0, token1, token0Decimals, token1Decimals, token0Symbol, token1Symbol, poolType, fee);
            if ((metadata.poolType == PoolType.V3 || metadata.poolType == PoolType.V4) && metadata.fee == null) {
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
     * 获取代币精度，若获取失败则返回默认 18
     *
     * @param tokenAddress 代币地址
     * @return 精度
     */
    public BigInteger getTokenDecimals(String tokenAddress) {
        if (tokenAddress == null) {
            return BigInteger.valueOf(18);
        }
        return fetchDecimals(tokenAddress);
    }

    /**
     * 获取代币符号，若获取失败则返回地址
     *
     * @param tokenAddress 代币地址
     * @return 代币符号或地址
     */
    public String getTokenSymbol(String tokenAddress) {
        if (tokenAddress == null) {
            return "unknown";
        }
        return fetchSymbol(tokenAddress);
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
    }

    /**
     * 将池子加入按代币缓存
     *
     * @param tokenA   代币 A
     * @param tokenB   代币 B
     * @param metadata 池子元数据
     */
    private void addPairToTokenCache(String tokenA, String tokenB, PairMetadata metadata) {
        String key = buildPairKey(tokenA, tokenB);
        pairCacheByTokens.compute(key, (k, list) -> {
            List<PairMetadata> updated = list == null ? new ArrayList<>() : new ArrayList<>(list);
            boolean replaced = false;
            String normalizedAddress = normalize(metadata.pairAddress);
            for (int i = 0; i < updated.size(); i++) {
                if (normalize(updated.get(i).pairAddress).equals(normalizedAddress)) {
                    updated.set(i, metadata);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                updated.add(metadata);
            }
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
        String normalized = normalize(token);
        return symbolCache.computeIfAbsent(normalized, key ->
                tokenInfoService.loadSymbol(token).orElse(normalized));
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
     * 构建 V4 池子缓存键
     *
     * @param tokenA 代币 A
     * @param tokenB 代币 B
     * @param fee    费率
     * @return 键值
     */
    private String buildV4PoolKey(String tokenA, String tokenB, BigInteger fee) {
        return "v4:" + buildPairKey(tokenA, tokenB) + ":" + fee.toString();
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
     * 查询 V4 工厂获得池子
     *
     * @param factory 工厂地址
     * @param tokenA  代币 A
     * @param tokenB  代币 B
     * @param fee     费率
     * @return 池子信息
     */
    private Optional<PairMetadata> queryV4Pool(String factory, String tokenA, String tokenB, BigInteger fee) {
        if (factory == null || isZeroAddress(factory)) {
            return Optional.empty();
        }
        Function function = new Function(
                "getPool",
                Arrays.asList(
                        new Address(normalize(tokenA)),
                        new Address(normalize(tokenB)),
                        new Uint24(fee),
                        new Address(ZERO_ADDRESS),
                        EMPTY_BYTES32
                ),
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
            return loadPairMetadata(poolAddress, false, PoolType.V4, fee);
        } catch (IOException e) {
            log.error("Failed to query V4 pool from factory {} with fee {}", factory, fee, e);
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
     * 储备数据结构
     */
    private static class Reserves {
        private final BigDecimal reserve0;
        private final BigDecimal reserve1;

        /**
         * 构造储备数据
         *
         * @param reserve0 token0 储备
         * @param reserve1 token1 储备
         */
        private Reserves(BigDecimal reserve0, BigDecimal reserve1) {
            this.reserve0 = reserve0;
            this.reserve1 = reserve1;
        }

        /**
         * 获取 token0 储备
         *
         * @return token0 储备
         */
        public BigDecimal getReserve0() {
            return reserve0;
        }

        /**
         * 获取 token1 储备
         *
         * @return token1 储备
         */
        public BigDecimal getReserve1() {
            return reserve1;
        }
    }

    private static class Slot0Data {
        private final BigInteger sqrtPriceX96;
        private final int tick;

        /**
         * 构造 slot0 数据
         *
         * @param sqrtPriceX96 当前 sqrtPriceX96
         * @param tick         当前 tick
         */
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

        /**
         * 构造价格区间
         *
         * @param lower 下限
         * @param upper 上限
         */
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
        public final String pairAddress;
        public final String token0;
        public final String token1;
        public final BigInteger token0Decimals;
        public final BigInteger token1Decimals;
        public final String token0Symbol;
        public final String token1Symbol;
        public final PoolType poolType;
        public final BigInteger fee;

        /**
         * 构造池子元数据
         *
         * @param pairAddress    池子地址
         * @param token0         token0 地址
         * @param token1         token1 地址
         * @param token0Decimals token0 精度
         * @param token1Decimals token1 精度
         * @param token0Symbol   token0 符号
         * @param token1Symbol   token1 符号
         * @param poolType       池子类型
         * @param fee            池子费率
         */
        public PairMetadata(String pairAddress, String token0, String token1,
                             BigInteger token0Decimals, BigInteger token1Decimals,
                             String token0Symbol, String token1Symbol,
                             PoolType poolType, BigInteger fee) {
            this.pairAddress = pairAddress;
            this.token0 = token0;
            this.token1 = token1;
            this.token0Decimals = token0Decimals;
            this.token1Decimals = token1Decimals;
            this.token0Symbol = token0Symbol;
            this.token1Symbol = token1Symbol;
            this.poolType = poolType == null ? PoolType.V2 : poolType;
            this.fee = fee;
        }

        /**
         * 生成新的池子元数据并替换类型
         *
         * @param poolType 新的池子类型
         * @return 更新后的元数据
         */
        public PairMetadata withPoolType(PoolType poolType) {
            if (poolType == null || this.poolType == poolType) {
                return this;
            }
            return new PairMetadata(pairAddress, token0, token1, token0Decimals, token1Decimals,
                    token0Symbol, token1Symbol, poolType, fee);
        }

        /**
         * 生成新的池子元数据并替换费率
         *
         * @param fee 新的费率
         * @return 更新后的元数据
         */
        public PairMetadata withFee(BigInteger fee) {
            if (fee == null || (this.fee != null && this.fee.equals(fee))) {
                return this;
            }
            return new PairMetadata(pairAddress, token0, token1, token0Decimals, token1Decimals,
                    token0Symbol, token1Symbol, poolType, fee);
        }

        /**
         * 获取显示名称
         *
         * @return 显示名称
         */
        public String getDisplayName() {
            String symbol0 = token0Symbol != null ? token0Symbol : token0;
            String symbol1 = token1Symbol != null ? token1Symbol : token1;
            return symbol0.toLowerCase(java.util.Locale.ROOT) + "-" + symbol1.toLowerCase(java.util.Locale.ROOT);
        }
    }
}
