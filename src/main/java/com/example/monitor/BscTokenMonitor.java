package com.example.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.math.BigInteger;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * 程序入口
 */
public class BscTokenMonitor {
    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(BscTokenMonitor.class);
    /** 默认 BSC RPC 节点（免费） */
    private static final String DEFAULT_RPC_ENDPOINT = "https://bsc-mainnet.nodereal.io/v1/9613675572f24988819d24233504b290";

    /**
     * 程序入口函数
     *
     * @param args 命令行参数
     * @throws InterruptedException 中断异常
     */
    public static void main(String[] args) throws InterruptedException {
        String tokenAddress = "0xf3d5b4c34ed623478cc5141861776e6cf7ae3a1e";
        Web3j web3j = Web3j.build(new HttpService(DEFAULT_RPC_ENDPOINT));
        TokenInfoService tokenInfoService = new TokenInfoService(web3j);
        BigInteger decimals = tokenInfoService.loadDecimals(tokenAddress).orElse(BigInteger.valueOf(18));
        String symbol = tokenInfoService.loadSymbol(tokenAddress).orElse("TOKEN");
        Optional<BigInteger> creationBlockOpt = tokenInfoService.findCreationBlock(tokenAddress);
        if (creationBlockOpt.isPresent()) {
            log.info("Detected token creation block={} for token={}", creationBlockOpt.get(), tokenAddress);
        } else {
            log.warn("Unable to determine creation block for token={}, defaulting to earliest block", tokenAddress);
        }
        log.info("Starting monitor for token={} decimals={}", symbol, decimals);

        DatabaseLogService databaseLogService = new DatabaseLogService("jdbc:h2:./monitor-logs");
        DexPriceService priceService = new DexPriceService(web3j, tokenAddress, decimals, symbol);
        TradeAnalysisService tradeAnalysisService = new TradeAnalysisService(web3j, tokenAddress, decimals, symbol,
                priceService, databaseLogService);
        LiquidityMonitorService liquidityMonitorService = new LiquidityMonitorService(web3j, tokenAddress, priceService,
                tradeAnalysisService, creationBlockOpt.orElse(null), databaseLogService);
        liquidityMonitorService.registerInitialPairs();
        log.info("v2 v3池子初始化完成");
        liquidityMonitorService.start();
        log.info("流动性监控服务已启动");
        tradeAnalysisService.start();
        log.info("交易分析服务已启动");
        priceService.initializePriceSampler();
        log.info("价格采样器已初始化");

        log.info("Monitor started. Press Ctrl+C to exit.");
        CountDownLatch latch = new CountDownLatch(1);
        latch.await();
    }
}

/**

 */
