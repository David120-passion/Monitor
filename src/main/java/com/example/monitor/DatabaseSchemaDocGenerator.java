package com.example.monitor;

import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库表结构说明文档生成器
 * 使用 Apache POI 生成格式化的 Word 文档
 */
public class DatabaseSchemaDocGenerator {
    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaDocGenerator.class);
    
    private static class TableField {
        String name;
        String dataType;
        String nullable;
        String description;
        
        TableField(String name, String dataType, String nullable, String description) {
            this.name = name;
            this.dataType = dataType;
            this.nullable = nullable;
            this.description = description;
        }
    }
    
    private static class TableInfo {
        int number;
        String tableName;
        String title;
        String description;
        List<TableField> fields;
        
        TableInfo(int number, String tableName, String title, String description, List<TableField> fields) {
            this.number = number;
            this.tableName = tableName;
            this.title = title;
            this.description = description;
            this.fields = fields;
        }
    }
    
    public static void main(String[] args) {
        String outputPath = args.length > 0 ? args[0] : "docs/数据库表结构说明文档.docx";
        try {
            generateDocument(outputPath);
            log.info("Word 文档已生成：{}", outputPath);
        } catch (Exception e) {
            log.error("生成文档失败", e);
            System.exit(1);
        }
    }
    
    public static void generateDocument(String outputPath) throws IOException {
        XWPFDocument document = new XWPFDocument();
        
        // 添加标题
        addTitle(document);
        
        // 添加目录
        addTableOfContents(document);
        
        // 添加所有表结构说明
        List<TableInfo> tables = getAllTables();
        for (TableInfo table : tables) {
            addTableSection(document, table);
            document.createParagraph(); // 空行
        }
        
        // 添加数据归档说明
        addPageBreak(document);
        addArchiveSection(document);
        
        // 添加注意事项
        addPageBreak(document);
        addNotesSection(document);
        
        // 保存文档
        try (FileOutputStream out = new FileOutputStream(outputPath)) {
            document.write(out);
        }
        document.close();
    }
    
    private static void addTitle(XWPFDocument document) {
        // 标题
        XWPFParagraph titlePara = document.createParagraph();
        titlePara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun titleRun = titlePara.createRun();
        titleRun.setText("数据库表结构说明文档");
        titleRun.setBold(true);
        titleRun.setFontSize(22);
        titleRun.setColor("003366");
        titleRun.setFontFamily("微软雅黑");
        
        // 副标题
        XWPFParagraph subtitlePara = document.createParagraph();
        subtitlePara.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun subtitleRun = subtitlePara.createRun();
        subtitleRun.setText("BSC 代币监控系统");
        subtitleRun.setFontSize(14);
        subtitleRun.setColor("666666");
        subtitleRun.setFontFamily("微软雅黑");
        
        document.createParagraph(); // 空行
        
        // 说明
        XWPFParagraph introPara = document.createParagraph();
        XWPFRun introRun = introPara.createRun();
        introRun.setText("本文档详细说明了 BSC 代币监控系统中所有数据库表的结构和字段说明。");
        introRun.setFontSize(11);
        introRun.setFontFamily("微软雅黑");
        
        document.createParagraph(); // 空行
    }
    
    private static void addTableOfContents(XWPFDocument document) {
        XWPFParagraph tocPara = document.createParagraph();
        XWPFRun tocRun = tocPara.createRun();
        tocRun.setText("目录");
        tocRun.setBold(true);
        tocRun.setFontSize(16);
        tocRun.setColor("003366");
        tocRun.setFontFamily("微软雅黑");
        
        List<TableInfo> tables = getAllTables();
        for (TableInfo table : tables) {
            XWPFParagraph itemPara = document.createParagraph();
            itemPara.setIndentationLeft(360); // 缩进
            XWPFRun itemRun = itemPara.createRun();
            itemRun.setText(String.format("%d. %s - %s", table.number, table.tableName, table.title));
            itemRun.setFontSize(10);
            itemRun.setFontFamily("微软雅黑");
        }
        
        addPageBreak(document);
    }
    
    private static void addTableSection(XWPFDocument document, TableInfo table) {
        // 标题
        XWPFParagraph headingPara = document.createParagraph();
        XWPFRun headingRun = headingPara.createRun();
        headingRun.setText(String.format("%d. %s - %s", table.number, table.tableName, table.title));
        headingRun.setBold(true);
        headingRun.setFontSize(16);
        headingRun.setColor("003366");
        headingRun.setFontFamily("微软雅黑");
        
        // 说明
        XWPFParagraph descPara = document.createParagraph();
        XWPFRun descRun = descPara.createRun();
        descRun.setText("表说明：");
        descRun.setBold(true);
        descRun.setFontSize(11);
        descRun.setFontFamily("微软雅黑");
        
        XWPFRun descTextRun = descPara.createRun();
        descTextRun.setText(" " + table.description);
        descTextRun.setFontSize(11);
        descTextRun.setFontFamily("微软雅黑");
        
        document.createParagraph(); // 空行
        
        // 创建表格
        XWPFTable wordTable = document.createTable(table.fields.size() + 1, 4);
        wordTable.setWidth("100%");
        
        // 设置表头
        XWPFTableRow headerRow = wordTable.getRow(0);
        String[] headers = {"字段名", "数据类型", "是否为空", "说明"};
        for (int i = 0; i < headers.length; i++) {
            XWPFTableCell cell = headerRow.getCell(i);
            cell.setColor("4472C4"); // 蓝色背景
            XWPFParagraph para = cell.getParagraphs().get(0);
            para.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun run = para.createRun();
            run.setText(headers[i]);
            run.setBold(true);
            run.setColor("FFFFFF"); // 白色文字
            run.setFontSize(10);
            run.setFontFamily("微软雅黑");
        }
        
        // 填充数据
        for (int i = 0; i < table.fields.size(); i++) {
            TableField field = table.fields.get(i);
            XWPFTableRow row = wordTable.getRow(i + 1);
            
            // 字段名
            setCellText(row.getCell(0), field.name, 9);
            // 数据类型
            setCellText(row.getCell(1), field.dataType, 9);
            // 是否为空
            setCellText(row.getCell(2), field.nullable, 9);
            // 说明
            setCellText(row.getCell(3), field.description, 9);
        }
        
        // 设置列宽
        wordTable.getCTTbl().addNewTblGrid().addNewGridCol().setW(BigInteger.valueOf(2000));
        wordTable.getCTTbl().getTblGrid().addNewGridCol().setW(BigInteger.valueOf(1800));
        wordTable.getCTTbl().getTblGrid().addNewGridCol().setW(BigInteger.valueOf(1500));
        wordTable.getCTTbl().getTblGrid().addNewGridCol().setW(BigInteger.valueOf(5000));
    }
    
    private static void setCellText(XWPFTableCell cell, String text, int fontSize) {
        XWPFParagraph para = cell.getParagraphs().get(0);
        para.setAlignment(ParagraphAlignment.LEFT);
        XWPFRun run = para.createRun();
        run.setText(text);
        run.setFontSize(fontSize);
        run.setFontFamily("微软雅黑");
    }
    
    private static void addArchiveSection(XWPFDocument document) {
        XWPFParagraph headingPara = document.createParagraph();
        XWPFRun headingRun = headingPara.createRun();
        headingRun.setText("数据归档说明");
        headingRun.setBold(true);
        headingRun.setFontSize(16);
        headingRun.setColor("003366");
        headingRun.setFontFamily("微软雅黑");
        
        document.createParagraph();
        
        XWPFParagraph p1 = document.createParagraph();
        XWPFRun r1 = p1.createRun();
        r1.setText("系统在每次启动时会自动归档所有表的数据：");
        r1.setFontSize(11);
        r1.setFontFamily("微软雅黑");
        
        document.createParagraph();
        
        addBulletPoint(document, "归档表命名规则：", " {时间戳}-{原表名}");
        addBulletPoint(document, "  - 时间戳格式：", "yyyy-MM-dd_HH-mm（例如：2025-12-09_19-14）");
        addBulletPoint(document, "  - 示例：", "2025-12-09_19-14-pair_created_logs");
        
        document.createParagraph();
        
        addBulletPoint(document, "归档流程：", "");
        addBulletPoint(document, "  1. ", "创建归档表（复制原表结构）");
        addBulletPoint(document, "  2. ", "将原表数据复制到归档表");
        addBulletPoint(document, "  3. ", "清空原表");
        
        document.createParagraph();
        
        addBulletPoint(document, "归档条件：", "");
        addBulletPoint(document, "  - ", "表必须存在");
        addBulletPoint(document, "  - ", "表必须有数据（行数 > 0）");
    }
    
    private static void addNotesSection(XWPFDocument document) {
        XWPFParagraph headingPara = document.createParagraph();
        XWPFRun headingRun = headingPara.createRun();
        headingRun.setText("注意事项");
        headingRun.setBold(true);
        headingRun.setFontSize(16);
        headingRun.setColor("003366");
        headingRun.setFontFamily("微软雅黑");
        
        document.createParagraph();
        
        addNumberedPoint(document, "精度说明：", new String[]{
            "DECIMAL(38, 18)：用于已转换精度的金额（通常除以 10^18）",
            "DECIMAL(50, 0)：用于原始精度的金额（未转换）",
            "DECIMAL(65, 18)：用于价格区间等需要高精度的字段"
        });
        
        addNumberedPoint(document, "地址格式：", new String[]{
            "所有地址字段存储为小写十六进制字符串（带 0x 前缀）"
        });
        
        addNumberedPoint(document, "时间字段：", new String[]{
            "event_time：区块链事件发生的时间（区块时间戳）",
            "created_at：数据库记录创建的时间（服务器时间）"
        });
        
        addNumberedPoint(document, "手续费率：", new String[]{
            "以基点（basis points）表示，例如：100 = 0.01%，10000 = 1%"
        });
        
        addNumberedPoint(document, "V4 池子 ID：", new String[]{
            "V4 使用 poolId（bytes32）而不是地址来标识池子",
            "存储为十六进制字符串（带 0x 前缀）"
        });
    }
    
    private static void addBulletPoint(XWPFDocument document, String prefix, String text) {
        XWPFParagraph para = document.createParagraph();
        para.setIndentationLeft(360);
        XWPFRun run = para.createRun();
        run.setText("• " + prefix);
        if (prefix.contains("：") || prefix.contains("规则") || prefix.contains("流程") || prefix.contains("条件")) {
            run.setBold(true);
        }
        run.setFontSize(11);
        run.setFontFamily("微软雅黑");
        
        if (!text.isEmpty()) {
            XWPFRun textRun = para.createRun();
            textRun.setText(text);
            textRun.setFontSize(11);
            textRun.setFontFamily("微软雅黑");
        }
    }
    
    private static void addNumberedPoint(XWPFDocument document, String title, String[] items) {
        XWPFParagraph para = document.createParagraph();
        XWPFRun run = para.createRun();
        run.setText(title);
        run.setBold(true);
        run.setFontSize(11);
        run.setFontFamily("微软雅黑");
        
        for (String item : items) {
            XWPFParagraph itemPara = document.createParagraph();
            itemPara.setIndentationLeft(720);
            XWPFRun itemRun = itemPara.createRun();
            itemRun.setText("  " + item);
            itemRun.setFontSize(11);
            itemRun.setFontFamily("微软雅黑");
        }
        
        document.createParagraph();
    }
    
    private static void addPageBreak(XWPFDocument document) {
        XWPFParagraph para = document.createParagraph();
        XWPFRun run = para.createRun();
        run.addBreak(BreakType.PAGE);
    }
    
    private static List<TableInfo> getAllTables() {
        List<TableInfo> tables = new ArrayList<>();
        
        // 表1: pair_created_logs
        List<TableField> fields1 = new ArrayList<>();
        fields1.add(new TableField("id", "BIGINT", "NOT NULL", "主键，自增"));
        fields1.add(new TableField("pair_address", "VARCHAR(128)", "NOT NULL", "交易对合约地址"));
        fields1.add(new TableField("swap", "VARCHAR(128)", "NULL", "DEX 名称（如：PancakeSwap V2, Uniswap V2）"));
        fields1.add(new TableField("name", "VARCHAR(256)", "NULL", "交易对显示名称（如：usdt-night）"));
        fields1.add(new TableField("token0", "VARCHAR(128)", "NULL", "Token0 地址（地址较小的代币）"));
        fields1.add(new TableField("token1", "VARCHAR(128)", "NULL", "Token1 地址（地址较大的代币）"));
        fields1.add(new TableField("liquidity", "DECIMAL(50, 0)", "NULL", "初始流动性（原始精度，未转换）"));
        fields1.add(new TableField("event_time", "TIMESTAMP", "NULL", "事件发生时间（区块时间戳）"));
        fields1.add(new TableField("created_at", "TIMESTAMP", "NULL", "记录创建时间（默认当前时间）"));
        tables.add(new TableInfo(1, "pair_created_logs", "V2 交易对创建日志",
                "记录 Uniswap V2 / PancakeSwap V2 交易对创建事件。当新的交易对被创建时，会记录该事件。", fields1));
        
        // 表2: pool_created_logs
        List<TableField> fields2 = new ArrayList<>();
        fields2.add(new TableField("id", "BIGINT", "NOT NULL", "主键，自增"));
        fields2.add(new TableField("pool_address", "VARCHAR(128)", "NOT NULL", "池子合约地址"));
        fields2.add(new TableField("swap", "VARCHAR(128)", "NULL", "DEX 名称（如：PancakeSwap V3, Uniswap V3）"));
        fields2.add(new TableField("name", "VARCHAR(256)", "NULL", "池子显示名称（如：usdt-night）"));
        fields2.add(new TableField("token0", "VARCHAR(128)", "NULL", "Token0 地址（地址较小的代币）"));
        fields2.add(new TableField("token1", "VARCHAR(128)", "NULL", "Token1 地址（地址较大的代币）"));
        fields2.add(new TableField("fee", "INTEGER", "NULL", "手续费率（以基点表示，如 100 = 0.01%）"));
        fields2.add(new TableField("tick_spacing", "INTEGER", "NULL", "Tick 间距"));
        fields2.add(new TableField("event_time", "TIMESTAMP", "NULL", "事件发生时间（区块时间戳）"));
        fields2.add(new TableField("created_at", "TIMESTAMP", "NULL", "记录创建时间（默认当前时间）"));
        tables.add(new TableInfo(2, "pool_created_logs", "V3 池子创建日志",
                "记录 Uniswap V3 / PancakeSwap V3 池子创建事件。当新的 V3 池子被创建时，会记录该事件。", fields2));
        
        // 表3: v4_initialize_logs
        List<TableField> fields3 = new ArrayList<>();
        fields3.add(new TableField("id", "BIGINT", "NOT NULL", "主键，自增"));
        fields3.add(new TableField("pool_id", "VARCHAR(256)", "NOT NULL", "V4 池子 ID（bytes32，十六进制字符串）"));
        fields3.add(new TableField("swap", "VARCHAR(128)", "NULL", "DEX 名称（如：PancakeSwap V4, Uniswap V4）"));
        fields3.add(new TableField("name", "VARCHAR(256)", "NULL", "池子显示名称（如：usdt-night）"));
        fields3.add(new TableField("currency0", "VARCHAR(128)", "NULL", "Currency0 地址（地址较小的代币）"));
        fields3.add(new TableField("currency1", "VARCHAR(128)", "NULL", "Currency1 地址（地址较大的代币）"));
        fields3.add(new TableField("fee", "INTEGER", "NULL", "手续费率（以基点表示）"));
        fields3.add(new TableField("hooks", "VARCHAR(128)", "NULL", "Hooks 合约地址（V4 特性）"));
        fields3.add(new TableField("price_token1_per_token0", "DECIMAL(38, 18)", "NULL", "Token1 相对于 Token0 的价格"));
        fields3.add(new TableField("price_token0_per_token1", "DECIMAL(38, 18)", "NULL", "Token0 相对于 Token1 的价格"));
        fields3.add(new TableField("target_token_price", "DECIMAL(38, 18)", "NULL", "目标代币的价格（USD）"));
        fields3.add(new TableField("event_time", "TIMESTAMP", "NULL", "事件发生时间（区块时间戳）"));
        fields3.add(new TableField("created_at", "TIMESTAMP", "NULL", "记录创建时间（默认当前时间）"));
        tables.add(new TableInfo(3, "v4_initialize_logs", "V4 池子初始化日志",
                "记录 Uniswap V4 / PancakeSwap V4 池子初始化事件。当 V4 池子被初始化时，会记录该事件，包括初始价格等信息。", fields3));
        
        // 表4: v4_modify_liquidity_logs
        List<TableField> fields4 = new ArrayList<>();
        fields4.add(new TableField("id", "BIGINT", "NOT NULL", "主键，自增"));
        fields4.add(new TableField("pool_id", "VARCHAR(256)", "NOT NULL", "V4 池子 ID（bytes32，十六进制字符串）"));
        fields4.add(new TableField("action", "VARCHAR(64)", "NOT NULL", "操作类型（ADD, REMOVE, UPDATE）"));
        fields4.add(new TableField("swap", "VARCHAR(128)", "NULL", "DEX 名称（如：PancakeSwap V4, Uniswap V4）"));
        fields4.add(new TableField("name", "VARCHAR(256)", "NULL", "池子显示名称"));
        fields4.add(new TableField("sender", "VARCHAR(128)", "NULL", "操作发起者地址"));
        fields4.add(new TableField("fee", "INTEGER", "NULL", "手续费率（以基点表示）"));
        fields4.add(new TableField("liquidity_delta", "DECIMAL(50, 0)", "NULL", "流动性变化量（原始精度）"));
        fields4.add(new TableField("amount0_delta", "DECIMAL(38, 18)", "NULL", "Token0 数量变化（已转换精度）"));
        fields4.add(new TableField("amount1_delta", "DECIMAL(38, 18)", "NULL", "Token1 数量变化（已转换精度）"));
        fields4.add(new TableField("price_range_lower", "DECIMAL(65, 18)", "NULL", "价格区间下限"));
        fields4.add(new TableField("price_range_upper", "DECIMAL(65, 18)", "NULL", "价格区间上限"));
        fields4.add(new TableField("amount0_remaining", "DECIMAL(38, 18)", "NULL", "Token0 剩余数量"));
        fields4.add(new TableField("amount1_remaining", "DECIMAL(38, 18)", "NULL", "Token1 剩余数量"));
        fields4.add(new TableField("tvl_remaining", "DECIMAL(38, 18)", "NULL", "剩余总锁定价值（USD）"));
        fields4.add(new TableField("salt", "VARCHAR(128)", "NULL", "Salt 值（V4 特性）"));
        fields4.add(new TableField("event_time", "TIMESTAMP", "NULL", "事件发生时间（区块时间戳）"));
        fields4.add(new TableField("created_at", "TIMESTAMP", "NULL", "记录创建时间（默认当前时间）"));
        tables.add(new TableInfo(4, "v4_modify_liquidity_logs", "V4 流动性修改日志",
                "记录 Uniswap V4 / PancakeSwap V4 池子的流动性修改事件。包括添加、移除或更新流动性操作。", fields4));
        
        // 表5: burn_logs_v2
        List<TableField> fields5 = new ArrayList<>();
        fields5.add(new TableField("id", "BIGINT", "NOT NULL", "主键，自增"));
        fields5.add(new TableField("pair_address", "VARCHAR(128)", "NOT NULL", "交易对合约地址"));
        fields5.add(new TableField("name", "VARCHAR(256)", "NULL", "交易对显示名称"));
        fields5.add(new TableField("sender", "VARCHAR(128)", "NULL", "操作发起者地址"));
        fields5.add(new TableField("recipient", "VARCHAR(128)", "NULL", "接收者地址"));
        fields5.add(new TableField("token0", "VARCHAR(128)", "NULL", "Token0 地址"));
        fields5.add(new TableField("token1", "VARCHAR(128)", "NULL", "Token1 地址"));
        fields5.add(new TableField("amount0", "DECIMAL(38, 18)", "NULL", "Token0 销毁数量（已转换精度）"));
        fields5.add(new TableField("amount1", "DECIMAL(38, 18)", "NULL", "Token1 销毁数量（已转换精度）"));
        fields5.add(new TableField("event_time", "TIMESTAMP", "NULL", "事件发生时间（区块时间戳）"));
        fields5.add(new TableField("created_at", "TIMESTAMP", "NULL", "记录创建时间（默认当前时间）"));
        tables.add(new TableInfo(5, "burn_logs_v2", "V2 销毁日志",
                "记录 Uniswap V2 / PancakeSwap V2 交易对的流动性销毁事件。当用户移除流动性时触发。", fields5));
        
        // 表6: burn_logs_v3
        List<TableField> fields6 = new ArrayList<>();
        fields6.add(new TableField("id", "BIGINT", "NOT NULL", "主键，自增"));
        fields6.add(new TableField("pool_address", "VARCHAR(128)", "NOT NULL", "池子合约地址"));
        fields6.add(new TableField("name", "VARCHAR(256)", "NULL", "池子显示名称"));
        fields6.add(new TableField("owner", "VARCHAR(128)", "NULL", "流动性所有者地址"));
        fields6.add(new TableField("fee", "INTEGER", "NULL", "手续费率（以基点表示）"));
        fields6.add(new TableField("price_range_lower", "DECIMAL(65, 18)", "NULL", "价格区间下限"));
        fields6.add(new TableField("price_range_upper", "DECIMAL(65, 18)", "NULL", "价格区间上限"));
        fields6.add(new TableField("token0", "VARCHAR(128)", "NULL", "Token0 地址"));
        fields6.add(new TableField("token1", "VARCHAR(128)", "NULL", "Token1 地址"));
        fields6.add(new TableField("burned_liquidity", "DECIMAL(50, 0)", "NULL", "销毁的流动性数量（原始精度）"));
        fields6.add(new TableField("amount0", "DECIMAL(38, 18)", "NULL", "Token0 销毁数量（已转换精度）"));
        fields6.add(new TableField("amount1", "DECIMAL(38, 18)", "NULL", "Token1 销毁数量（已转换精度）"));
        fields6.add(new TableField("event_time", "TIMESTAMP", "NULL", "事件发生时间（区块时间戳）"));
        fields6.add(new TableField("created_at", "TIMESTAMP", "NULL", "记录创建时间（默认当前时间）"));
        tables.add(new TableInfo(6, "burn_logs_v3", "V3 销毁日志",
                "记录 Uniswap V3 / PancakeSwap V3 池子的流动性销毁事件。当用户移除流动性时触发。", fields6));
        
        // 表7: mint_logs_v2
        List<TableField> fields7 = new ArrayList<>();
        fields7.add(new TableField("id", "BIGINT", "NOT NULL", "主键，自增"));
        fields7.add(new TableField("pair_address", "VARCHAR(128)", "NOT NULL", "交易对合约地址"));
        fields7.add(new TableField("name", "VARCHAR(256)", "NULL", "交易对显示名称"));
        fields7.add(new TableField("sender", "VARCHAR(128)", "NULL", "操作发起者地址"));
        fields7.add(new TableField("token0", "VARCHAR(128)", "NULL", "Token0 地址"));
        fields7.add(new TableField("token1", "VARCHAR(128)", "NULL", "Token1 地址"));
        fields7.add(new TableField("amount0", "DECIMAL(38, 18)", "NULL", "Token0 铸造数量（已转换精度）"));
        fields7.add(new TableField("amount1", "DECIMAL(38, 18)", "NULL", "Token1 铸造数量（已转换精度）"));
        fields7.add(new TableField("event_time", "TIMESTAMP", "NULL", "事件发生时间（区块时间戳）"));
        fields7.add(new TableField("created_at", "TIMESTAMP", "NULL", "记录创建时间（默认当前时间）"));
        tables.add(new TableInfo(7, "mint_logs_v2", "V2 铸造日志",
                "记录 Uniswap V2 / PancakeSwap V2 交易对的流动性铸造事件。当用户添加流动性时触发。", fields7));
        
        // 表8: mint_logs_v3
        List<TableField> fields8 = new ArrayList<>();
        fields8.add(new TableField("id", "BIGINT", "NOT NULL", "主键，自增"));
        fields8.add(new TableField("pool_address", "VARCHAR(128)", "NOT NULL", "池子合约地址"));
        fields8.add(new TableField("name", "VARCHAR(256)", "NULL", "池子显示名称"));
        fields8.add(new TableField("sender", "VARCHAR(128)", "NULL", "操作发起者地址"));
        fields8.add(new TableField("owner", "VARCHAR(128)", "NULL", "流动性所有者地址"));
        fields8.add(new TableField("fee", "INTEGER", "NULL", "手续费率（以基点表示）"));
        fields8.add(new TableField("price_range_lower", "DECIMAL(65, 18)", "NULL", "价格区间下限"));
        fields8.add(new TableField("price_range_upper", "DECIMAL(65, 18)", "NULL", "价格区间上限"));
        fields8.add(new TableField("token0", "VARCHAR(128)", "NULL", "Token0 地址"));
        fields8.add(new TableField("token1", "VARCHAR(128)", "NULL", "Token1 地址"));
        fields8.add(new TableField("amount0", "DECIMAL(38, 18)", "NULL", "Token0 铸造数量（已转换精度）"));
        fields8.add(new TableField("amount1", "DECIMAL(38, 18)", "NULL", "Token1 铸造数量（已转换精度）"));
        fields8.add(new TableField("event_time", "TIMESTAMP", "NULL", "事件发生时间（区块时间戳）"));
        fields8.add(new TableField("created_at", "TIMESTAMP", "NULL", "记录创建时间（默认当前时间）"));
        tables.add(new TableInfo(8, "mint_logs_v3", "V3 铸造日志",
                "记录 Uniswap V3 / PancakeSwap V3 池子的流动性铸造事件。当用户添加流动性时触发。", fields8));
        
        // 表9: trade_logs
        List<TableField> fields9 = new ArrayList<>();
        fields9.add(new TableField("id", "BIGINT", "NOT NULL", "主键，自增"));
        fields9.add(new TableField("tx_hash", "VARCHAR(128)", "NOT NULL", "交易哈希"));
        fields9.add(new TableField("address", "VARCHAR(128)", "NOT NULL", "交易者地址"));
        fields9.add(new TableField("action", "VARCHAR(32)", "NOT NULL", "交易方向（BUY 或 SELL）"));
        fields9.add(new TableField("amount", "DECIMAL(38, 18)", "NULL", "本次交易数量（目标代币）"));
        fields9.add(new TableField("token_symbol", "VARCHAR(64)", "NULL", "代币符号"));
        fields9.add(new TableField("price", "DECIMAL(38, 18)", "NULL", "本次交易价格（USD）"));
        fields9.add(new TableField("notional_value", "DECIMAL(38, 18)", "NULL", "本次交易价值（USD）"));
        fields9.add(new TableField("total_buy_amount", "DECIMAL(38, 18)", "NULL", "累计买入数量（目标代币）"));
        fields9.add(new TableField("total_sell_amount", "DECIMAL(38, 18)", "NULL", "累计卖出数量（目标代币）"));
        fields9.add(new TableField("total_buy_value", "DECIMAL(38, 18)", "NULL", "累计买入价值（USD）"));
        fields9.add(new TableField("total_sell_value", "DECIMAL(38, 18)", "NULL", "累计卖出价值（USD）"));
        fields9.add(new TableField("net_amount", "DECIMAL(38, 18)", "NULL", "净持仓数量（买入 - 卖出）"));
        fields9.add(new TableField("net_value", "DECIMAL(38, 18)", "NULL", "净持仓价值（USD）"));
        fields9.add(new TableField("avg_buy_price", "DECIMAL(38, 18)", "NULL", "平均买入价格（USD）"));
        fields9.add(new TableField("avg_sell_price", "DECIMAL(38, 18)", "NULL", "平均卖出价格（USD）"));
        fields9.add(new TableField("trade_count", "INTEGER", "NULL", "交易次数"));
        fields9.add(new TableField("block_number", "BIGINT", "NULL", "区块号"));
        fields9.add(new TableField("event_time", "TIMESTAMP", "NULL", "事件发生时间（区块时间戳）"));
        fields9.add(new TableField("created_at", "TIMESTAMP", "NULL", "记录创建时间（默认当前时间）"));
        tables.add(new TableInfo(9, "trade_logs", "交易汇总日志",
                "记录目标代币的交易汇总信息。每次检测到买入或卖出交易时，会记录该交易的详细信息以及该地址的累计统计数据。", fields9));
        
        // 表10: pool_registration_logs
        List<TableField> fields10 = new ArrayList<>();
        fields10.add(new TableField("id", "BIGINT", "NOT NULL", "主键，自增"));
        fields10.add(new TableField("pair_address", "VARCHAR(128)", "NOT NULL", "池子/交易对地址"));
        fields10.add(new TableField("swap", "VARCHAR(128)", "NULL", "DEX 名称"));
        fields10.add(new TableField("name", "VARCHAR(256)", "NULL", "池子显示名称"));
        fields10.add(new TableField("fee", "INTEGER", "NULL", "手续费率（以基点表示）"));
        fields10.add(new TableField("amount0", "DECIMAL(38, 18)", "NULL", "Token0 余额（已转换精度）"));
        fields10.add(new TableField("amount1", "DECIMAL(38, 18)", "NULL", "Token1 余额（已转换精度）"));
        fields10.add(new TableField("price_range_lower", "DECIMAL(65, 18)", "NULL", "价格区间下限（V3/V4）"));
        fields10.add(new TableField("price_range_upper", "DECIMAL(65, 18)", "NULL", "价格区间上限（V3/V4）"));
        fields10.add(new TableField("tvl", "DECIMAL(38, 18)", "NULL", "总锁定价值（USD）"));
        fields10.add(new TableField("event_time", "TIMESTAMP", "NULL", "事件发生时间（区块时间戳）"));
        fields10.add(new TableField("created_at", "TIMESTAMP", "NULL", "记录创建时间（默认当前时间）"));
        tables.add(new TableInfo(10, "pool_registration_logs", "池子注册日志",
                "记录池子注册时的快照信息。当池子被注册到监控系统时，会记录池子的当前状态（余额、价格区间、TVL 等）。", fields10));
        
        // 表11: transfer_logs
        List<TableField> fields11 = new ArrayList<>();
        fields11.add(new TableField("id", "BIGINT", "NOT NULL", "主键，自增"));
        fields11.add(new TableField("tx_hash", "VARCHAR(128)", "NOT NULL", "交易哈希"));
        fields11.add(new TableField("token_address", "VARCHAR(128)", "NOT NULL", "代币合约地址"));
        fields11.add(new TableField("from_address", "VARCHAR(128)", "NOT NULL", "发送方地址"));
        fields11.add(new TableField("to_address", "VARCHAR(128)", "NOT NULL", "接收方地址"));
        fields11.add(new TableField("amount", "DECIMAL(38, 18)", "NOT NULL", "转账数量（已转换精度）"));
        fields11.add(new TableField("amount_raw", "DECIMAL(50, 0)", "NULL", "原始转账数量（未转换精度）"));
        fields11.add(new TableField("log_index", "INTEGER", "NULL", "日志索引（在交易中的位置）"));
        fields11.add(new TableField("block_number", "BIGINT", "NULL", "区块号"));
        fields11.add(new TableField("event_time", "TIMESTAMP", "NULL", "事件发生时间（区块时间戳）"));
        fields11.add(new TableField("created_at", "TIMESTAMP", "NULL", "记录创建时间（默认当前时间）"));
        tables.add(new TableInfo(11, "transfer_logs", "转账日志",
                "记录目标代币的所有 Transfer 事件。用于追踪代币的转账流向。", fields11));
        
        return tables;
    }
}

