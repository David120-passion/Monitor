package com.example.monitor;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * DEX 常量配置
 */
public class DexConstants {
    /** PancakeSwap V2 工厂地址 */
    public static final String PANCAKE_V2_FACTORY = "0xcA143Ce32Fe78f1f7019d7d551a6402fC5350c73";
    /** PancakeSwap V3 工厂地址 */
    public static final String PANCAKE_V3_FACTORY = "0x0BFbCF9fa4f9C56B0F40a671Ad40E0805A091865";
    /** PancakeSwap V4 工厂地址（占位，若上线可替换真实地址） */
    public static final String PANCAKE_V4_FACTORY = "0xa0ffb9c1ce1fe56963b0321b32e7a0302114058b";

    /** Uniswap V2 工厂地址（BSC 部署） */
    public static final String UNISWAP_V2_FACTORY = "0x8909Dc15e40173Ff4699343b6eB8132c65e18eC6";
    /** Uniswap V3 工厂地址（BSC 部署占位） */
    public static final String UNISWAP_V3_FACTORY = "0xdB1d10011AD0Ff90774D0C6Bb92e5C5c8b4461F7";
    /** Uniswap V4 工厂地址占位 */
    public static final String UNISWAP_V4_FACTORY = "0x28e2ea090877bf75740558f6bfb36a5ffee9e9df";
    //正式
    /** WBNB 代币地址 */
    public static final String WBNB_ADDRESS = "0xBB4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c";
    /** BUSD 代币地址 */
    public static final String USDT_ADDRESS = "0x55d398326f99059ff775485246999027b3197955";

    /** PancakeSwap V2 工厂列表 */
    public static final List<String> V2_FACTORIES = Collections.unmodifiableList(Arrays.asList(
            PANCAKE_V2_FACTORY,
            UNISWAP_V2_FACTORY
    ));

    /** PancakeSwap V3 工厂列表 */
    public static final List<String> V3_FACTORIES = Collections.unmodifiableList(Arrays.asList(
            PANCAKE_V3_FACTORY,
            UNISWAP_V3_FACTORY
    ));

    /** V3 可用费率列表（以 1e-6 计） */
    public static final List<BigInteger> V3_FEE_TIERS = Collections.unmodifiableList(Arrays.asList(
            BigInteger.valueOf(100),      // 0.01%
            BigInteger.valueOf(500),      // 0.05%
            BigInteger.valueOf(2500),     // 0.25%
            BigInteger.valueOf(10000)     // 1%
    ));

    /** PancakeSwap V4 工厂列表 */
    public static final List<String> V4_FACTORIES = Collections.unmodifiableList(Arrays.asList(
            PANCAKE_V4_FACTORY,
            UNISWAP_V4_FACTORY
    ));

    private DexConstants() {
    }
}
