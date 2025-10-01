package com.example.monitor;

import java.util.List;

/**
 * DEX 常量配置
 */
public class DexConstants {
    /** PancakeSwap V2 工厂地址 */
    public static final String PANCAKE_V2_FACTORY = "0xBCfCcbde45cE874adCB698cC183deBcF17952812";
    /** PancakeSwap V3 工厂地址 */
    public static final String PANCAKE_V3_FACTORY = "0xAf1114e0Ed5D9652CDd0F8B0FFe91c6c4e1e0DaC";
    /** PancakeSwap V4 工厂地址（占位，若上线可替换真实地址） */
    public static final String PANCAKE_V4_FACTORY = "0x0000000000000000000000000000000000000000";

    /** Uniswap V2 工厂地址（BSC 部署） */
    public static final String UNISWAP_V2_FACTORY = "0x1F98431c8aD98523631AE4a59f267346ea31F984";
    /** Uniswap V3 工厂地址（BSC 部署占位） */
    public static final String UNISWAP_V3_FACTORY = "0x1F98431c8aD98523631AE4a59f267346ea31F984";
    /** Uniswap V4 工厂地址占位 */
    public static final String UNISWAP_V4_FACTORY = "0x0000000000000000000000000000000000000000";

    /** WBNB 代币地址 */
    public static final String WBNB_ADDRESS = "0xBB4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c";
    /** BUSD 代币地址 */
    public static final String BUSD_ADDRESS = "0xe9e7cea3dedca5984780bafc599bd69add087d56";

    /** PancakeSwap V2 工厂列表 */
    public static final List<String> V2_FACTORIES = List.of(
            PANCAKE_V2_FACTORY,
            UNISWAP_V2_FACTORY
    );

    /** PancakeSwap V3 工厂列表 */
    public static final List<String> V3_FACTORIES = List.of(
            PANCAKE_V3_FACTORY,
            UNISWAP_V3_FACTORY
    );

    /** PancakeSwap V4 工厂列表 */
    public static final List<String> V4_FACTORIES = List.of(
            PANCAKE_V4_FACTORY,
            UNISWAP_V4_FACTORY
    );

    private DexConstants() {
    }
}
