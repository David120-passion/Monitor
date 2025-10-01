package com.example.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint112;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;
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

    /** 缓存的 V2 配对信息 */
    private final Map<String, PairMetadata> pairCache = new ConcurrentHashMap<>();

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
    }

    /**
     * 获取指定区块的代币美元价格
     *
     * @param blockNumber 区块高度
     * @return 价格
     */
    public Optional<BigDecimal> getPriceAtBlock(BigInteger blockNumber) {
        Optional<PairMetadata> busdPair = findOrCreatePair(tokenAddress, DexConstants.BUSD_ADDRESS);
        if (busdPair.isPresent()) {
            return busdPair.flatMap(pair -> calculatePriceFromPair(pair, blockNumber));
        }
        Optional<PairMetadata> wbnbPair = findOrCreatePair(tokenAddress, DexConstants.WBNB_ADDRESS);
        Optional<PairMetadata> bnbBusdPair = findOrCreatePair(DexConstants.WBNB_ADDRESS, DexConstants.BUSD_ADDRESS);
        if (wbnbPair.isPresent() && bnbBusdPair.isPresent()) {
            Optional<BigDecimal> tokenWbnbPrice = calculatePriceFromPair(wbnbPair.get(), blockNumber);
            Optional<BigDecimal> wbnbBusdPrice = calculatePriceFromPair(bnbBusdPair.get(), blockNumber);
            if (tokenWbnbPrice.isPresent() && wbnbBusdPrice.isPresent()) {
                return Optional.of(tokenWbnbPrice.get().multiply(wbnbBusdPrice.get()))
                        .map(value -> value.setScale(8, RoundingMode.HALF_UP));
            }
        }
        return Optional.empty();
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
    private Optional<BigDecimal> calculatePriceFromPair(PairMetadata pairMetadata, BigInteger blockNumber) {
        Optional<Reserves> reservesOpt = getReserves(pairMetadata, blockNumber);
        if (reservesOpt.isEmpty()) {
            return Optional.empty();
        }
        Reserves reserves = reservesOpt.get();
        BigDecimal tokenReserve;
        BigDecimal quoteReserve;
        if (pairMetadata.token0.equalsIgnoreCase(tokenAddress)) {
            tokenReserve = reserves.reserve0.divide(BigDecimal.TEN.pow(tokenDecimals.intValue()), 18, RoundingMode.HALF_UP);
            quoteReserve = reserves.reserve1.divide(BigDecimal.TEN.pow(pairMetadata.token1Decimals.intValue()), 18, RoundingMode.HALF_UP);
        } else {
            tokenReserve = reserves.reserve1.divide(BigDecimal.TEN.pow(tokenDecimals.intValue()), 18, RoundingMode.HALF_UP);
            quoteReserve = reserves.reserve0.divide(BigDecimal.TEN.pow(pairMetadata.token0Decimals.intValue()), 18, RoundingMode.HALF_UP);
        }
        if (tokenReserve.compareTo(BigDecimal.ZERO) == 0) {
            return Optional.empty();
        }
        return Optional.of(quoteReserve.divide(tokenReserve, 18, RoundingMode.HALF_UP));
    }

    /**
     * 查找或创建配对信息
     *
     * @param tokenA 代币 A
     * @param tokenB 代币 B
     * @return 池子信息
     */
    public Optional<PairMetadata> findOrCreatePair(String tokenA, String tokenB) {
        String key = buildPairKey(tokenA, tokenB);
        if (pairCache.containsKey(key)) {
            return Optional.of(pairCache.get(key));
        }
        for (String factory : DexConstants.V2_FACTORIES) {
            Optional<PairMetadata> metadata = queryPair(factory, tokenA, tokenB);
            if (metadata.isPresent()) {
                pairCache.put(key, metadata.get());
                pairCache.put(buildPairKey(tokenB, tokenA), metadata.get());
                return metadata;
            }
        }
        return Optional.empty();
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
            return loadPairMetadata(pairAddress);
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
        try {
            String token0 = callAddressFunction(pairAddress, "token0");
            String token1 = callAddressFunction(pairAddress, "token1");
            if (token0 == null || token1 == null) {
                return Optional.empty();
            }
            BigInteger token0Decimals = fetchDecimals(token0);
            BigInteger token1Decimals = fetchDecimals(token1);
            PairMetadata metadata = new PairMetadata(pairAddress, token0, token1, token0Decimals, token1Decimals);
            pairCache.put(buildPairKey(token0, token1), metadata);
            pairCache.put(buildPairKey(token1, token0), metadata);
            return Optional.of(metadata);
        } catch (IOException e) {
            log.error("Failed to load pair metadata for {}", pairAddress, e);
            return Optional.empty();
        }
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
        TokenInfoService tokenInfoService = new TokenInfoService(web3j);
        return tokenInfoService.loadDecimals(token).orElse(BigInteger.valueOf(18));
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
     * 地址归一化（小写）
     *
     * @param address 地址
     * @return 归一化地址
     */
    private String normalize(String address) {
        return address == null ? null : address.toLowerCase();
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
    private record Reserves(BigDecimal reserve0, BigDecimal reserve1) {
    }

    /**
     * 池子元数据
     */
    public static class PairMetadata {
        /** 池子地址 */
        public final String pairAddress;
        /** token0 地址 */
        public final String token0;
        /** token1 地址 */
        public final String token1;
        /** token0 精度 */
        public final BigInteger token0Decimals;
        /** token1 精度 */
        public final BigInteger token1Decimals;

        /**
         * 构造函数
         */
        public PairMetadata(String pairAddress, String token0, String token1, BigInteger token0Decimals, BigInteger token1Decimals) {
            this.pairAddress = pairAddress;
            this.token0 = token0;
            this.token1 = token1;
            this.token0Decimals = token0Decimals;
            this.token1Decimals = token1Decimals;
        }
    }
}
