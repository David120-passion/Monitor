package com.example.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class Test {
    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(TradeAnalysisService.class);
    /** 时间格式 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    /** 目标时区（UTC+8） */
    private static final ZoneId TARGET_ZONE = ZoneId.of("Asia/Shanghai");

    /** Transfer 事件定义 */
    private static final Event TRANSFER_EVENT = new Event("Transfer",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Uint256.class)
            ));
    /** Transfer 事件签名 */
    private static final String TRANSFER_EVENT_SIGNATURE = EventEncoder.encode(TRANSFER_EVENT)
            .toLowerCase(Locale.ROOT);

    /** V2 Swap 事件签名
     *     pancakeSwap
     *     event Swap(
     *         address indexed sender,
     *         uint amount0In,
     *         uint amount1In,
     *         uint amount0Out,
     *         uint amount1Out,
     *         address indexed to
     *     );
     *
     * uniswap
     *     event Swap(
     *         address indexed sender,
     *         uint amount0In,
     *         uint amount1In,
     *         uint amount0Out,
     *         uint amount1Out,
     *         address indexed to
     *     );
     *
     * */
    private static final Event SWAP_EVENT_V2 = new Event("Swap",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Uint256.class),
                    TypeReference.create(Address.class, true)
            ));
    private static final String SWAP_EVENT_SIGNATURE_V2 = EventEncoder.encode(SWAP_EVENT_V2);

    /** V3
     *
     * pankcakeSwap 事件签名
     *     event Swap(
     *         address indexed sender,
     *         address indexed recipient,
     *         int256 amount0,
     *         int256 amount1,
     *         uint160 sqrtPriceX96,
     *         uint128 liquidity,
     *         int24 tick,
     *         uint128 protocolFeesToken0,
     *         uint128 protocolFeesToken1
     *     );
     *
     *     uniswapSwap 事件签名
     *       event Swap(
     *         address indexed sender,
     *         address indexed recipient,
     *         int256 amount0,
     *         int256 amount1,
     *         uint160 sqrtPriceX96,
     *         uint128 liquidity,
     *         int24 tick
     *     );
     *
     * */

    private static final Event PANCAKE_EVENT_V3 = new Event("Swap",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Int256.class),
                    TypeReference.create(Int256.class),
                    TypeReference.create(Uint160.class),
                    TypeReference.create(Uint128.class),
                    TypeReference.create(Int24.class),
                    TypeReference.create(Uint128.class),
                    TypeReference.create(Uint128.class)
            ));
    private static final Event UNISWAP_EVENT_V3 = new Event("Swap",
            Arrays.asList(
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Int256.class),
                    TypeReference.create(Int256.class),
                    TypeReference.create(Uint160.class),
                    TypeReference.create(Uint128.class),
                    TypeReference.create(Int24.class)
            ));
    private static final String PANCAKE_EVENT_SIGNATURE_V3 = EventEncoder.encode(PANCAKE_EVENT_V3);
    private static final String UNISWAP_EVENT_SIGNATURE_V3 = EventEncoder.encode(UNISWAP_EVENT_V3);
    /**
     * V4 Swap 事件签名
     * pancakeSwap
     *     event Swap(
     *         PoolId indexed id,
     *         address indexed sender,
     *         int128 amount0,
     *         int128 amount1,
     *         uint160 sqrtPriceX96,
     *         uint128 liquidity,
     *         int24 tick,
     *         uint24 fee,
     *         uint16 protocolFee
     *     );
     * uniswap
     *    event Swap(
     *         PoolId indexed id,
     *         address indexed sender,
     *         int128 amount0,
     *         int128 amount1,
     *         uint160 sqrtPriceX96,
     *         uint128 liquidity,
     *         int24 tick,
     *         uint24 fee
     *     );
     */
    private static final Event PANCAKESWAP_EVENT_V4 = new Event("Swap",
            Arrays.asList(
                    TypeReference.create(Bytes32.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Int128.class),
                    TypeReference.create(Int128.class),
                    TypeReference.create(Uint160.class),
                    TypeReference.create(Uint128.class),
                    TypeReference.create(Int24.class),
                    TypeReference.create(Uint24.class),
                    TypeReference.create(Uint16.class)
            ));
    private static final Event UNISWAP_EVENT_V4 = new Event("Swap",
            Arrays.asList(
                    TypeReference.create(Bytes32.class, true),
                    TypeReference.create(Address.class, true),
                    TypeReference.create(Int128.class),
                    TypeReference.create(Int128.class),
                    TypeReference.create(Uint160.class),
                    TypeReference.create(Uint128.class),
                    TypeReference.create(Int24.class),
                    TypeReference.create(Int24.class)
            ));
    private static final String PANCAKESWAP_EVENT_SIGNATURE_V4 = EventEncoder.encode(PANCAKESWAP_EVENT_V4);
    private static final String UNISWAP_EVENT_SIGNATURE_V4 = EventEncoder.encode(UNISWAP_EVENT_V4);


    private static final String SWAP_EVENT_SIGNATURE_V2_NORMALIZED = SWAP_EVENT_SIGNATURE_V2.toLowerCase(Locale.ROOT);
    private static final String UNISWAP_EVENT_SIGNATURE_V3_NORMALIZED = UNISWAP_EVENT_SIGNATURE_V3.toLowerCase(Locale.ROOT);
    private static final String PANCAKE_EVENT_SIGNATURE_V3_NORMALIZED = PANCAKE_EVENT_SIGNATURE_V3.toLowerCase(Locale.ROOT);
    private static final String UNISWAP_EVENT_SIGNATURE_V4_NORMALIZED = UNISWAP_EVENT_SIGNATURE_V4.toLowerCase(Locale.ROOT);
    private static final String PANCAKESWAP_EVENT_SIGNATURE_V4_NORMALIZED = PANCAKESWAP_EVENT_SIGNATURE_V4.toLowerCase(Locale.ROOT);

    /** 支持的 Swap 事件签名集合 */
    private static final Set<String> SUPPORTED_SWAP_SIGNATURES = new HashSet<>(Arrays.asList(
            SWAP_EVENT_SIGNATURE_V2_NORMALIZED,
            UNISWAP_EVENT_SIGNATURE_V3_NORMALIZED,
            PANCAKE_EVENT_SIGNATURE_V3_NORMALIZED,
            UNISWAP_EVENT_SIGNATURE_V4_NORMALIZED,
            PANCAKESWAP_EVENT_SIGNATURE_V4_NORMALIZED
    ));

    public static void main(String[] args) {
        SUPPORTED_SWAP_SIGNATURES.forEach(System.out::println);
    }
}
