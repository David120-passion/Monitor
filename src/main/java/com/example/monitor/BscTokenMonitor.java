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
        String tokenAddress = "0xc12eFb9e4A1A753e7f6523482C569793C2271dbB";
        Web3j web3j = Web3j.build(new HttpService(DEFAULT_RPC_ENDPOINT));
        TokenInfoService tokenInfoService = new TokenInfoService(web3j);
        BigInteger decimals = tokenInfoService.loadDecimals(tokenAddress).orElse(BigInteger.valueOf(18));
        String symbol = tokenInfoService.loadSymbol(tokenAddress).orElse("TOKEN");
//        Optional<BigInteger> creationBlockOpt = tokenInfoService.findCreationBlock(tokenAddress);
//        if (creationBlockOpt.isPresent()) {
//            log.info("Detected token creation block={} for token={}", creationBlockOpt.get(), tokenAddress);
//        } else {
//            log.warn("Unable to determine creation block for token={}, defaulting to earliest block", tokenAddress);
//        }
        log.info("Starting monitor for token={} decimals={}", symbol, decimals);
//
//        // MySQL 连接信息: 127.0.0.1:13306, 用户名: root, 密码: cljslrl0620
        DatabaseLogService databaseLogService = new DatabaseLogService("127.0.0.1", 13306, "monitor", "root", "cljslrl0620");
        DexPriceService priceService = new DexPriceService(web3j, tokenAddress, decimals, symbol);

        //todo 测试扫描下找到为什么没有这笔交易
        TradeAnalysisService tradeAnalysisService = new TradeAnalysisService(web3j, tokenAddress, decimals, symbol,
                priceService, databaseLogService);


        LiquidityMonitorService liquidityMonitorService = new LiquidityMonitorService(web3j, tokenAddress, priceService,
                tradeAnalysisService, new BigInteger("67355057"), databaseLogService);
        liquidityMonitorService.registerInitialPairs();
        log.info("v2 v3池子初始化完成");
        liquidityMonitorService.start();
        log.info("流动性监控服务已启动");
//        tradeAnalysisService.start();
        log.info("交易分析服务已启动");
//        priceService.initializePriceSampler();
        log.info("价格采样器已初始化");

        log.info("Monitor started. Press Ctrl+C to exit.");

        // 添加关闭钩子，确保程序退出时优雅关闭数据库服务
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down database service...");
            databaseLogService.shutdown();
            log.info("Database service shutdown complete.");
        }));

        CountDownLatch latch = new CountDownLatch(1);
        latch.await();
    }
}

/**

 */
