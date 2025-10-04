package com.example.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Collections;
import java.util.Optional;

/**
 * 代币基础信息服务
 */
public class TokenInfoService {
    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(TokenInfoService.class);
    /** Web3j 客户端实例 */
    private final Web3j web3j;

    /**
     * 构造函数
     *
     * @param web3j Web3j 客户端
     */
    public TokenInfoService(Web3j web3j) {
        this.web3j = web3j;
    }

    /**
     * 查询代币精度
     *
     * @param tokenAddress 代币合约地址
     * @return 精度数值
     */
    public Optional<BigInteger> loadDecimals(String tokenAddress) {
        Function function = new Function(
                "decimals",
                Collections.emptyList(),
                Collections.singletonList(new TypeReference<Uint8>() {
                })
        );
        Optional<Type> result = callSingleValueReturn(tokenAddress, function);
        if (result.isPresent()) {
            Uint8 value = (Uint8) result.get();
            return Optional.ofNullable(value.getValue());
        }
        return Optional.of(BigInteger.valueOf(18)); // 默认 18 位精度
    }

    /**
     * 查询代币符号
     *
     * @param tokenAddress 代币合约地址
     * @return 代币符号
     */
    public Optional<String> loadSymbol(String tokenAddress) {
        Function function = new Function(
                "symbol",
                Collections.emptyList(),
                Collections.singletonList(new TypeReference<Utf8String>() {
                })
        );
        return callSingleValueReturn(tokenAddress, function)
                .map(value -> (Utf8String) value)
                .map(Utf8String::getValue);
    }

    /**
     * 执行链上 call 并解析返回值
     *
     * @param contractAddress 合约地址
     * @param function        查询函数
     * @return 返回值
     */
    private Optional<Type> callSingleValueReturn(String contractAddress, Function function) {
        String encodedFunction = FunctionEncoder.encode(function);
        Transaction transaction = Transaction.createEthCallTransaction(null, contractAddress, encodedFunction);
        try {
            EthCall response = web3j.ethCall(transaction, DefaultBlockParameterName.LATEST).send();
            if (response.isReverted()) {
                log.warn("Contract call reverted: {}", response.getRevertReason());
                return Optional.empty();
            }
            String value = response.getValue();
            if (value == null || value.equals("0x")) {
                return Optional.empty();
            }
            return Optional.of(FunctionReturnDecoder.decode(value, function.getOutputParameters()).get(0));
        } catch (IOException e) {
            log.error("Failed to call contract {}", contractAddress, e);
            return Optional.empty();
        }
    }
}
