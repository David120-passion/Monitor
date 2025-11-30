# BSC Token Monitor

该项目提供了一个使用 Java 编写的示例程序，可以在 Binance Smart Chain (BSC) 上监控指定 ERC-20 代币的转账以及主流 DEX 的流动性池变动。默认使用官方的免费 RPC 节点，后续可以替换为自定义的付费节点。

## 功能概览

- 监听指定代币合约的 `Transfer` 事件，实时输出转出地址、转入地址、交易价格、交易时间以及地址累计买入/卖出数据。
- 自动识别 PancakeSwap/Uniswap 系列主流池子，监听新增池子和移除池子的相关事件，记录池子代币数量与时间戳。
- 通过链上储备数据估算代币在交易发生时刻的价格，并附带可用于分析交易趋势的额外指标（如 24 小时成交额、净流入等）。
- 所有关键函数与全局变量均包含中文注释，便于阅读与二次开发。

## 快速开始

1. 安装 [Maven](https://maven.apache.org/) 和 JDK 1.8。
2. 编译项目：
   ```bash
   mvn clean package
   ```
3. 运行可执行 Jar（在 `target` 目录下生成）：
   ```bash
   java -jar target/bsc-monitor-1.0-SNAPSHOT-jar-with-dependencies.jar <TokenContractAddress>
   ```
   将 `<TokenContractAddress>` 替换为要监控的代币合约地址（0x 开头）。

## 配置

- 默认 RPC 节点为 `https://bsc-dataseed.binance.org/`，可在 `BscTokenMonitor` 中调整。
- 程序会自动识别 PancakeSwap 与 Uniswap（V2/V3/V4）工厂地址，可在 `DexConstants` 中根据需要增删。

## 输出字段说明

所有输出字段的详细解释可见 `docs/output_fields.txt`。

## 注意事项

- 部分免费 RPC 节点存在速率限制，若出现限流可切换至自建或付费节点。
- 监控价格依赖链上流动性池的储备，若代币流动性不足或没有对应池子，价格可能无法获取。
- 程序以示例为主，实际生产部署时建议增加缓存、错误重试、持久化等功能。

