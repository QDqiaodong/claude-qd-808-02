-- 饲料加工厂 · 配方批次与质检管理
-- ddl-auto=none：表结构与种子数据全部在这里建好（mysql/Dockerfile 在构建期执行本文件）
SET NAMES utf8mb4;

-- ========================= 建表 =========================
DROP TABLE IF EXISTS qc_recheck_item;
DROP TABLE IF EXISTS qc_recheck;
DROP TABLE IF EXISTS qc_item;
DROP TABLE IF EXISTS qc_sample;
DROP TABLE IF EXISTS pellet;
DROP TABLE IF EXISTS feeding_item;
DROP TABLE IF EXISTS batch;
DROP TABLE IF EXISTS recipe_item;
DROP TABLE IF EXISTS recipe;
DROP TABLE IF EXISTS material;

-- 原料与筒仓
CREATE TABLE material (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    material_code VARCHAR(32)   NOT NULL COMMENT '原料编码',
    name          VARCHAR(64)   NOT NULL COMMENT '原料名称',
    silo_code     VARCHAR(16)   NOT NULL COMMENT '筒仓编号',
    silo_name     VARCHAR(64)   NULL COMMENT '筒仓名称',
    category      VARCHAR(32)   NULL COMMENT '类别 能量饲料/蛋白饲料/矿物质/添加剂',
    stock_tons    DECIMAL(10,2) NULL COMMENT '库存吨',
    moisture_pct  DECIMAL(5,2)  NULL COMMENT '水分含量 %',
    unit_price    DECIMAL(10,2) NULL COMMENT '单价 元/吨',
    supplier      VARCHAR(128)  NULL COMMENT '供应商',
    safety_stock  DECIMAL(10,2) NULL COMMENT '安全库存吨',
    inbound_batch VARCHAR(32)   NULL COMMENT '入仓批次',
    max_ratio     DECIMAL(5,2)  NULL COMMENT '配方中单原料占比上限 %',
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_code (material_code),
    KEY idx_material_silo (silo_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='原料与筒仓';

-- 配方
CREATE TABLE recipe (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    recipe_code     VARCHAR(32)  NOT NULL COMMENT '配方编号',
    name            VARCHAR(64)  NOT NULL COMMENT '配方名称',
    species         VARCHAR(16)  NOT NULL COMMENT '适用畜种 SWINE/POULTRY/AQUA/RUMINANT',
    stage           VARCHAR(16)  NULL COMMENT '阶段 SUCKLING/GROWER/FINISHER/BROILER/LAYER',
    crude_protein   DECIMAL(5,2) NULL COMMENT '粗蛋白 %',
    metabolic_energy DECIMAL(6,2) NULL COMMENT '代谢能 MJ/kg',
    lysine          DECIMAL(5,2) NULL COMMENT '赖氨酸 %',
    status          VARCHAR(16)  NULL COMMENT 'DRAFT/RELEASED',
    cost_per_ton    DECIMAL(10,2) NULL COMMENT '吨成本 元/吨',
    PRIMARY KEY (id),
    UNIQUE KEY uk_recipe_code (recipe_code),
    KEY idx_recipe_species (species)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配方';

-- 配方明细（父配方用 @SortNatural 的 SortedSet 装载，按原料自然序）
CREATE TABLE recipe_item (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    recipe_id     BIGINT        NOT NULL COMMENT '所属配方',
    material_id   BIGINT        NOT NULL COMMENT '原料',
    ratio_pct     DECIMAL(6,2)  NULL COMMENT '占比 %',
    cost_subtotal DECIMAL(10,2) NULL COMMENT '成本小计 元/吨',
    locked        TINYINT(1)    NULL DEFAULT 0 COMMENT '是否锁定占比',
    sort_hint     INT           NULL COMMENT '原料自然序提示',
    PRIMARY KEY (id),
    KEY idx_recipe_item_recipe (recipe_id),
    CONSTRAINT fk_recipe_item_recipe FOREIGN KEY (recipe_id) REFERENCES recipe (id),
    CONSTRAINT fk_recipe_item_material FOREIGN KEY (material_id) REFERENCES material (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配方明细';

-- 生产批次
CREATE TABLE batch (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    batch_no     VARCHAR(32)   NOT NULL COMMENT '批次号',
    recipe_id    BIGINT        NOT NULL COMMENT '配方',
    planned_tons DECIMAL(10,2) NULL COMMENT '计划产量吨',
    actual_tons  DECIMAL(10,2) NULL COMMENT '实际产量吨',
    produce_date DATE          NULL COMMENT '生产日期',
    status       VARCHAR(16)   NULL COMMENT 'PLANNED/FEEDING/PELLETING/QC/RECHECK/DONE',
    operator_name VARCHAR(32)  NULL COMMENT '班组',
    remark       VARCHAR(255)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_no (batch_no),
    KEY idx_batch_recipe (recipe_id),
    CONSTRAINT fk_batch_recipe FOREIGN KEY (recipe_id) REFERENCES recipe (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生产批次';

-- 投料明细（父批次用 @SortComparator 的 SortedSet 装载，严格按投料顺序）
CREATE TABLE feeding_item (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    batch_id      BIGINT        NOT NULL COMMENT '所属批次',
    feed_seq      INT           NOT NULL COMMENT '投料顺序',
    port_no       VARCHAR(16)   NULL COMMENT '投料口编号',
    material_id   BIGINT        NOT NULL COMMENT '原料',
    planned_kg    DECIMAL(12,2) NULL COMMENT '计划投料量 kg',
    actual_kg     DECIMAL(12,2) NULL COMMENT '实际投料量 kg',
    deviation_rate DECIMAL(8,4) NULL COMMENT '误差率 %',
    status        VARCHAR(16)   NULL COMMENT 'PENDING/WEIGHING/DONE/ABNORMAL',
    abnormal_note VARCHAR(255)  NULL COMMENT '异常说明',
    weighed_at    DATETIME      NULL COMMENT '称重时间',
    PRIMARY KEY (id),
    KEY idx_feeding_batch (batch_id),
    CONSTRAINT fk_feeding_batch FOREIGN KEY (batch_id) REFERENCES batch (id),
    CONSTRAINT fk_feeding_material FOREIGN KEY (material_id) REFERENCES material (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='投料明细';

-- 制粒工段
CREATE TABLE pellet (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    batch_id       BIGINT        NOT NULL COMMENT '所属批次',
    die_code       VARCHAR(32)   NULL COMMENT '环模编号',
    tempering_temp DECIMAL(5,2)  NULL COMMENT '调质温度 ℃',
    steam_pressure DECIMAL(5,2)  NULL COMMENT '蒸汽压力 MPa',
    die_aperture   DECIMAL(5,2)  NULL COMMENT '环模孔径 mm',
    die_used_tons  DECIMAL(10,2) NULL COMMENT '环模累计产量吨',
    die_life_tons  DECIMAL(10,2) NULL COMMENT '环模寿命上限吨',
    hardness       DECIMAL(5,1)  NULL COMMENT '颗粒硬度 N',
    powder_rate    DECIMAL(5,2)  NULL COMMENT '粉化率 %',
    output_tons    DECIMAL(10,2) NULL COMMENT '本批制粒产量吨',
    status         VARCHAR(16)   NULL COMMENT 'IDLE/RUNNING/DONE/BLOCKED',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pellet_batch (batch_id),
    CONSTRAINT fk_pellet_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='制粒工段';

-- 质检抽样
CREATE TABLE qc_sample (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    sample_no   VARCHAR(32)  NOT NULL COMMENT '抽样单号',
    batch_id    BIGINT       NOT NULL COMMENT '抽样批次',
    sample_time DATETIME     NULL COMMENT '抽样时间',
    sampler     VARCHAR(32)  NULL COMMENT '抽样人',
    conclusion  VARCHAR(16)  NULL COMMENT 'PASS/DOWNGRADE/REJECT',
    disposition VARCHAR(64)  NULL COMMENT '处理去向',
    score       DECIMAL(5,2) NULL COMMENT '综合评分',
    remark      VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sample_no (sample_no),
    KEY idx_sample_batch (batch_id),
    CONSTRAINT fk_sample_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质检抽样';

-- 质检检验项（父抽样单用 @SortNatural 的 SortedSet 装载，按检验次序）
CREATE TABLE qc_item (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    sample_id      BIGINT        NOT NULL COMMENT '所属抽样单',
    item_code      VARCHAR(32)   NULL COMMENT 'MOISTURE/PROTEIN/MYCOTOXIN/ASH',
    item_name      VARCHAR(32)   NULL COMMENT '检验项名称',
    check_order    INT           NULL COMMENT '检验次序',
    unit           VARCHAR(16)   NULL COMMENT '单位',
    measured_value DECIMAL(10,3) NULL COMMENT '实测值',
    std_lower      DECIMAL(10,3) NULL COMMENT '标准下限',
    std_upper      DECIMAL(10,3) NULL COMMENT '标准上限',
    qualified      TINYINT(1)    NULL DEFAULT 1 COMMENT '是否合格',
    PRIMARY KEY (id),
    KEY idx_qc_item_sample (sample_id),
    CONSTRAINT fk_qc_item_sample FOREIGN KEY (sample_id) REFERENCES qc_sample (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质检检验项';

-- 退货复检单：仅初检结论为 REJECT 的批次可发起；一批次同一时刻只允许一张进行中（active_flag=1 唯一）
CREATE TABLE qc_recheck (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    recheck_no        VARCHAR(32)  NOT NULL COMMENT '复检单号',
    sample_id         BIGINT       NOT NULL COMMENT '初检抽样单（原记录只读）',
    batch_id          BIGINT       NOT NULL COMMENT '退货批次',
    initiator         VARCHAR(32)  NOT NULL COMMENT '发起人',
    reason            VARCHAR(255) NOT NULL COMMENT '复检原因',
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待复核/MEASURING 复检中/DONE 已完成',
    active_flag       TINYINT      NULL DEFAULT 1 COMMENT '进行中=1，完成=NULL（配合唯一索引收口）',
    reviewer          VARCHAR(32)  NULL COMMENT '复核员（必须不同于初检判定人）',
    final_conclusion  VARCHAR(16)  NULL COMMENT '最终结论 RELEASE/DOWNGRADE/REJECT',
    final_disposition VARCHAR(64)  NULL COMMENT '最终去向',
    reviewed_at       DATETIME     NULL COMMENT '复核提交时间',
    created_at        DATETIME     NULL COMMENT '发起时间',
    remark            VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recheck_no (recheck_no),
    UNIQUE KEY uk_recheck_active (batch_id, active_flag),
    KEY idx_recheck_sample (sample_id),
    CONSTRAINT fk_recheck_sample FOREIGN KEY (sample_id) REFERENCES qc_sample (id),
    CONSTRAINT fk_recheck_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退货复检单';

-- 复检检验项：复核员录入的新检验值，初检 qc_item 永不改写
CREATE TABLE qc_recheck_item (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    recheck_id     BIGINT        NOT NULL COMMENT '所属复检单',
    item_code      VARCHAR(32)   NULL COMMENT 'MOISTURE/PROTEIN/MYCOTOXIN/ASH',
    item_name      VARCHAR(32)   NULL COMMENT '检验项名称',
    check_order    INT           NULL COMMENT '检验次序',
    unit           VARCHAR(16)   NULL COMMENT '单位',
    measured_value DECIMAL(10,3) NULL COMMENT '复检实测值',
    std_lower      DECIMAL(10,3) NULL COMMENT '标准下限（快照自初检）',
    std_upper      DECIMAL(10,3) NULL COMMENT '标准上限（快照自初检）',
    qualified      TINYINT(1)    NULL DEFAULT 1 COMMENT '是否合格',
    PRIMARY KEY (id),
    KEY idx_recheck_item (recheck_id),
    CONSTRAINT fk_recheck_item FOREIGN KEY (recheck_id) REFERENCES qc_recheck (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='复检检验项';

-- ========================= 种子数据 =========================

-- 原料与筒仓（11 行）
INSERT INTO material (id, material_code, name, silo_code, silo_name, category, stock_tons, moisture_pct, unit_price, supplier, safety_stock, inbound_batch, max_ratio) VALUES
(1, 'M01', '玉米', 'SILO-01', '一号玉米仓', '能量饲料', 620.00, 14.20, 2680.00, '中粮贸易', 200.00, 'IN-260812A', 70.00),
(2, 'M02', '玉米(二等)', 'SILO-02', '二号玉米仓', '能量饲料', 380.50, 15.60, 2520.00, '松原粮贸', 200.00, 'IN-260820B', 70.00),
(3, 'M03', '豆粕', 'SILO-03', '豆粕仓', '蛋白饲料', 265.00, 12.40, 3480.00, '益海嘉里', 120.00, 'IN-260818A', 40.00),
(4, 'M04', '去皮豆粕', 'SILO-04', '去皮豆粕仓', '蛋白饲料', 132.40, 11.80, 3750.00, '益海嘉里', 120.00, 'IN-260902A', 40.00),
(5, 'M05', '麸皮', 'SILO-05', '麸皮仓', '能量饲料', 96.30, 13.50, 1980.00, '五得利面粉', 60.00, 'IN-260828A', 20.00),
(6, 'M06', '鱼粉', 'SILO-06', '鱼粉仓', '蛋白饲料', 42.80, 8.60, 9800.00, '大连远洋鱼粉', 30.00, 'IN-260730A', 8.00),
(7, 'M07', '预混料', 'SILO-07', '预混料仓', '添加剂', 28.60, 6.20, 12600.00, '新希望六和', 15.00, 'IN-260805A', 4.00),
(8, 'M08', '石粉', 'SILO-08', '石粉仓', '矿物质', 88.90, 0.50, 620.00, '灵寿矿业', 40.00, 'IN-260815A', 3.00),
(9, 'M09', '磷酸氢钙', 'SILO-09', '磷酸氢钙仓', '矿物质', 35.20, 1.20, 2950.00, '云天化', 20.00, 'IN-260822A', 3.00),
(10, 'M10', '豆油', 'SILO-10', '油脂仓', '能量饲料', 51.40, 0.30, 7600.00, '中储粮油脂', 25.00, 'IN-260905A', 5.00),
(11, 'M11', '小麦', 'SILO-11', '小麦仓', '能量饲料', 58.70, 12.90, 2760.00, '河北柏乡粮库', 80.00, 'IN-260825A', 60.00);

-- 配方（8 行）
INSERT INTO recipe (id, recipe_code, name, species, stage, crude_protein, metabolic_energy, lysine, status, cost_per_ton) VALUES
(1, 'F-1001', '乳猪教槽料', 'SWINE', 'SUCKLING', 21.50, 14.20, 1.35, 'RELEASED', 3931.34),
(2, 'F-1002', '中猪浓缩料', 'SWINE', 'GROWER', 17.80, 13.60, 1.05, 'RELEASED', 3381.45),
(3, 'F-1003', '育肥猪配合料', 'SWINE', 'FINISHER', 15.60, 13.20, 0.85, 'RELEASED', 3033.60),
(4, 'F-2001', '肉鸡前期料', 'POULTRY', 'BROILER', 21.00, 12.90, 1.15, 'RELEASED', 3903.81),
(5, 'F-2002', '蛋鸡产蛋料', 'POULTRY', 'LAYER', 16.50, 11.80, 0.78, 'RELEASED', 3124.65),
(6, 'F-3001', '草鱼膨化料', 'AQUA', 'FINGERLING', 30.00, 12.50, 1.60, 'RELEASED', 4005.60),
(7, 'F-3002', '对虾配合料', 'AQUA', 'SHRIMP', 38.00, 13.00, 1.95, 'RELEASED', 4005.40),
(8, 'F-4001', '奶牛泌乳料', 'RUMINANT', 'LACTATING', 18.20, 11.20, 0.68, 'RELEASED', 2961.65);

-- 配方明细（按原料自然序，合计 100%）
INSERT INTO recipe_item (id, recipe_id, material_id, ratio_pct, cost_subtotal, locked, sort_hint) VALUES
(1, 1, 1, 56.00, 1500.80, 0, 1),
(2, 1, 4, 28.00, 1050.00, 0, 4),
(3, 1, 6, 6.00, 588.00, 1, 6),
(4, 1, 7, 4.00, 504.00, 0, 7),
(5, 1, 8, 1.20, 7.44, 0, 8),
(6, 1, 9, 1.80, 53.10, 0, 9),
(7, 1, 10, 3.00, 228.00, 0, 10),
(8, 2, 1, 54.00, 1447.20, 0, 1),
(9, 2, 3, 24.00, 835.20, 0, 3),
(10, 2, 5, 12.00, 237.60, 0, 5),
(11, 2, 6, 3.00, 294.00, 1, 6),
(12, 2, 7, 3.50, 441.00, 0, 7),
(13, 2, 8, 1.00, 6.20, 0, 8),
(14, 2, 9, 1.50, 44.25, 0, 9),
(15, 2, 10, 1.00, 76.00, 0, 10),
(16, 3, 1, 56.00, 1500.80, 0, 1),
(17, 3, 3, 20.00, 696.00, 0, 3),
(18, 3, 5, 18.00, 356.40, 0, 5),
(19, 3, 7, 3.00, 378.00, 0, 7),
(20, 3, 8, 1.00, 6.20, 0, 8),
(21, 3, 9, 1.20, 35.40, 0, 9),
(22, 3, 10, 0.80, 60.80, 0, 10),
(23, 4, 1, 54.50, 1460.60, 0, 1),
(24, 4, 4, 30.00, 1125.00, 0, 4),
(25, 4, 6, 5.00, 490.00, 1, 6),
(26, 4, 7, 4.00, 504.00, 0, 7),
(27, 4, 8, 1.30, 8.06, 0, 8),
(28, 4, 9, 1.70, 50.15, 0, 9),
(29, 4, 10, 3.50, 266.00, 0, 10),
(30, 5, 1, 59.00, 1581.20, 0, 1),
(31, 5, 3, 22.00, 765.60, 0, 3),
(32, 5, 5, 10.00, 198.00, 0, 5),
(33, 5, 7, 3.50, 441.00, 0, 7),
(34, 5, 8, 3.00, 18.60, 0, 8),
(35, 5, 9, 1.50, 44.25, 0, 9),
(36, 5, 10, 1.00, 76.00, 0, 10),
(37, 6, 1, 38.00, 1018.40, 0, 1),
(38, 6, 3, 26.00, 904.80, 0, 3),
(39, 6, 4, 12.00, 450.00, 0, 4),
(40, 6, 5, 8.00, 158.40, 0, 5),
(41, 6, 6, 8.00, 784.00, 1, 6),
(42, 6, 7, 3.50, 441.00, 0, 7),
(43, 6, 9, 2.00, 59.00, 0, 9),
(44, 6, 10, 2.50, 190.00, 0, 10),
(45, 7, 1, 47.00, 1259.60, 0, 1),
(46, 7, 3, 12.00, 417.60, 0, 3),
(47, 7, 4, 26.00, 975.00, 0, 4),
(48, 7, 6, 8.00, 784.00, 1, 6),
(49, 7, 7, 4.00, 504.00, 0, 7),
(50, 7, 8, 1.00, 6.20, 0, 8),
(51, 7, 9, 2.00, 59.00, 0, 9),
(52, 8, 1, 58.50, 1567.80, 0, 1),
(53, 8, 3, 14.00, 487.20, 0, 3),
(54, 8, 5, 20.00, 396.00, 0, 5),
(55, 8, 7, 3.00, 378.00, 0, 7),
(56, 8, 8, 2.00, 12.40, 0, 8),
(57, 8, 9, 1.50, 44.25, 0, 9),
(58, 8, 10, 1.00, 76.00, 0, 10);

-- 生产批次（11 行，日期按 CURDATE() 相对生成）
INSERT INTO batch (id, batch_no, recipe_id, planned_tons, actual_tons, produce_date, status, operator_name, remark) VALUES
(1, 'FM-001', 3, 24.00, 23.60, DATE_SUB(CURDATE(), INTERVAL 6 DAY), 'DONE', '甲班', '育肥料常规订单'),
(2, 'FM-002', 1, 12.00, 11.80, DATE_SUB(CURDATE(), INTERVAL 6 DAY), 'DONE', '甲班', '乳猪料小批量'),
(3, 'FM-003', 4, 20.00, 19.90, DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'DONE', '乙班', '肉鸡料'),
(4, 'FM-004', 2, 18.00, 17.60, DATE_SUB(CURDATE(), INTERVAL 4 DAY), 'DONE', '乙班', '中猪料'),
(5, 'FM-005', 5, 22.00, 21.70, DATE_SUB(CURDATE(), INTERVAL 3 DAY), 'DONE', '丙班', '蛋鸡料'),
(6, 'FM-006', 6, 15.00, 14.80, DATE_SUB(CURDATE(), INTERVAL 2 DAY), 'DONE', '丙班', '草鱼膨化料'),
(7, 'FM-007', 7, 10.00, 9.60, DATE_SUB(CURDATE(), INTERVAL 2 DAY), 'DONE', '甲班', '对虾料'),
(8, 'FM-008', 8, 26.00, 25.40, DATE_SUB(CURDATE(), INTERVAL 1 DAY), 'DONE', '乙班', '奶牛料'),
(9, 'FM-009', 3, 28.00, 27.80, DATE_SUB(CURDATE(), INTERVAL 1 DAY), 'QC', '丙班', '育肥料待质检放行'),
(10, 'FM-010', 1, 14.00, NULL, DATE_SUB(CURDATE(), INTERVAL 0 DAY), 'FEEDING', '甲班', '乳猪料在产'),
(11, 'FM-011', 4, 16.00, NULL, DATE_SUB(CURDATE(), INTERVAL 0 DAY), 'PLANNED', '乙班', '待排产');

-- 投料明细（按投料口顺序，计划量 = 计划产量 × 占比）
INSERT INTO feeding_item (id, batch_id, feed_seq, port_no, material_id, planned_kg, actual_kg, deviation_rate, status, abnormal_note, weighed_at) VALUES
(1, 1, 1, 'P1', 1, 13440.00, 13587.84, 1.1000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 121 HOUR)),
(2, 1, 2, 'P2', 3, 4800.00, 4780.80, -0.4000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 122 HOUR)),
(3, 1, 3, 'P3', 5, 4320.00, 4376.16, 1.3000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 123 HOUR)),
(4, 1, 4, 'P4', 7, 720.00, 712.80, -1.0000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 124 HOUR)),
(5, 1, 5, 'P5', 8, 240.00, 241.68, 0.7000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 125 HOUR)),
(6, 1, 6, 'P6', 9, 288.00, 287.42, -0.2000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 126 HOUR)),
(7, 1, 7, 'P7', 10, 192.00, 193.15, 0.6000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 127 HOUR)),
(8, 2, 1, 'P1', 1, 6720.00, 6693.12, -0.4000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 121 HOUR)),
(9, 2, 2, 'P2', 4, 3360.00, 3403.68, 1.3000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 122 HOUR)),
(10, 2, 3, 'P3', 6, 720.00, 712.80, -1.0000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 123 HOUR)),
(11, 2, 4, 'P4', 7, 480.00, 483.36, 0.7000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 124 HOUR)),
(12, 2, 5, 'P5', 8, 144.00, 143.71, -0.2000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 125 HOUR)),
(13, 2, 6, 'P6', 9, 216.00, 217.30, 0.6000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 126 HOUR)),
(14, 2, 7, 'P7', 10, 360.00, 357.12, -0.8000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 127 HOUR)),
(15, 3, 1, 'P1', 1, 10900.00, 11041.70, 1.3000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 101 HOUR)),
(16, 3, 2, 'P2', 4, 6000.00, 5940.00, -1.0000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 102 HOUR)),
(17, 3, 3, 'P3', 6, 1000.00, 1007.00, 0.7000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 103 HOUR)),
(18, 3, 4, 'P4', 7, 800.00, 798.40, -0.2000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 104 HOUR)),
(19, 3, 5, 'P5', 8, 260.00, 261.56, 0.6000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 105 HOUR)),
(20, 3, 6, 'P6', 9, 340.00, 337.28, -0.8000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 106 HOUR)),
(21, 3, 7, 'P7', 10, 700.00, 707.70, 1.1000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 107 HOUR)),
(22, 4, 1, 'P1', 1, 9720.00, 9622.80, -1.0000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 81 HOUR)),
(23, 4, 2, 'P2', 3, 4320.00, 4350.24, 0.7000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 82 HOUR)),
(24, 4, 3, 'P3', 5, 2160.00, 2155.68, -0.2000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 83 HOUR)),
(25, 4, 4, 'P4', 6, 540.00, 543.24, 0.6000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 84 HOUR)),
(26, 4, 5, 'P5', 7, 630.00, 624.96, -0.8000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 85 HOUR)),
(27, 4, 6, 'P6', 8, 180.00, 181.98, 1.1000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 86 HOUR)),
(28, 4, 7, 'P7', 9, 270.00, 268.92, -0.4000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 87 HOUR)),
(29, 4, 8, 'P8', 10, 180.00, 182.34, 1.3000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 88 HOUR)),
(30, 5, 1, 'P1', 1, 12980.00, 13070.86, 0.7000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 61 HOUR)),
(31, 5, 2, 'P2', 3, 4840.00, 4830.32, -0.2000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 62 HOUR)),
(32, 5, 3, 'P3', 5, 2200.00, 2213.20, 0.6000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 63 HOUR)),
(33, 5, 4, 'P4', 7, 770.00, 763.84, -0.8000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 64 HOUR)),
(34, 5, 5, 'P5', 8, 660.00, 667.26, 1.1000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 65 HOUR)),
(35, 5, 6, 'P6', 9, 330.00, 328.68, -0.4000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 66 HOUR)),
(36, 5, 7, 'P7', 10, 220.00, 222.86, 1.3000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 67 HOUR)),
(37, 6, 1, 'P1', 1, 5700.00, 5688.60, -0.2000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 41 HOUR)),
(38, 6, 2, 'P2', 3, 3900.00, 3923.40, 0.6000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 42 HOUR)),
(39, 6, 3, 'P3', 4, 1800.00, 1785.60, -0.8000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 43 HOUR)),
(40, 6, 4, 'P4', 5, 1200.00, 1213.20, 1.1000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 44 HOUR)),
(41, 6, 5, 'P5', 6, 1200.00, 1195.20, -0.4000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 45 HOUR)),
(42, 6, 6, 'P6', 7, 525.00, 531.83, 1.3000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 46 HOUR)),
(43, 6, 7, 'P7', 9, 300.00, 297.00, -1.0000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 47 HOUR)),
(44, 6, 8, 'P8', 10, 375.00, 377.63, 0.7000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 48 HOUR)),
(45, 7, 1, 'P1', 1, 4700.00, 4728.20, 0.6000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 41 HOUR)),
(46, 7, 2, 'P2', 3, 1200.00, 1190.40, -0.8000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 42 HOUR)),
(47, 7, 3, 'P3', 4, 2600.00, 2628.60, 1.1000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 43 HOUR)),
(48, 7, 4, 'P4', 6, 800.00, 796.80, -0.4000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 44 HOUR)),
(49, 7, 5, 'P5', 7, 400.00, 405.20, 1.3000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 45 HOUR)),
(50, 7, 6, 'P6', 8, 100.00, 99.00, -1.0000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 46 HOUR)),
(51, 7, 7, 'P7', 9, 200.00, 201.40, 0.7000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 47 HOUR)),
(52, 8, 1, 'P1', 1, 15210.00, 15088.32, -0.8000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 21 HOUR)),
(53, 8, 2, 'P2', 3, 3640.00, 3680.04, 1.1000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 22 HOUR)),
(54, 8, 3, 'P3', 5, 5200.00, 5179.20, -0.4000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 23 HOUR)),
(55, 8, 4, 'P4', 7, 780.00, 790.14, 1.3000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 24 HOUR)),
(56, 8, 5, 'P5', 8, 520.00, 514.80, -1.0000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 25 HOUR)),
(57, 8, 6, 'P6', 9, 390.00, 392.73, 0.7000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 26 HOUR)),
(58, 8, 7, 'P7', 10, 260.00, 259.48, -0.2000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 27 HOUR)),
(59, 9, 1, 'P1', 1, 15680.00, 15852.48, 1.1000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 21 HOUR)),
(60, 9, 2, 'P2', 3, 5600.00, 5577.60, -0.4000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 22 HOUR)),
(61, 9, 3, 'P3', 5, 5040.00, 5183.64, 2.8500, 'ABNORMAL', '第 3 号投料口实际称重与计划偏差 +2.85%，超出 ±2% 阈值，待复核', DATE_SUB(NOW(), INTERVAL 3 HOUR)),
(62, 9, 4, 'P4', 7, 840.00, 831.60, -1.0000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 24 HOUR)),
(63, 9, 5, 'P5', 8, 280.00, 281.96, 0.7000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 25 HOUR)),
(64, 9, 6, 'P6', 9, 336.00, 335.33, -0.2000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 26 HOUR)),
(65, 9, 7, 'P7', 10, 224.00, 225.34, 0.6000, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 27 HOUR)),
(66, 10, 1, 'P1', 1, 7840.00, 7887.04, 0.60, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 34 MINUTE)),
(67, 10, 2, 'P2', 4, 3920.00, 3888.64, -0.80, 'DONE', NULL, DATE_SUB(NOW(), INTERVAL 28 MINUTE)),
(68, 10, 3, 'P3', 6, 840.00, NULL, NULL, 'PENDING', NULL, NULL),
(69, 10, 4, 'P4', 7, 560.00, NULL, NULL, 'PENDING', NULL, NULL),
(70, 10, 5, 'P5', 8, 168.00, NULL, NULL, 'PENDING', NULL, NULL),
(71, 10, 6, 'P6', 9, 252.00, NULL, NULL, 'PENDING', NULL, NULL),
(72, 10, 7, 'P7', 10, 420.00, NULL, NULL, 'PENDING', NULL, NULL),
(73, 11, 1, 'P1', 1, 8720.00, NULL, NULL, 'PENDING', NULL, NULL),
(74, 11, 2, 'P2', 4, 4800.00, NULL, NULL, 'PENDING', NULL, NULL),
(75, 11, 3, 'P3', 6, 800.00, NULL, NULL, 'PENDING', NULL, NULL),
(76, 11, 4, 'P4', 7, 640.00, NULL, NULL, 'PENDING', NULL, NULL),
(77, 11, 5, 'P5', 8, 208.00, NULL, NULL, 'PENDING', NULL, NULL),
(78, 11, 6, 'P6', 9, 272.00, NULL, NULL, 'PENDING', NULL, NULL),
(79, 11, 7, 'P7', 10, 560.00, NULL, NULL, 'PENDING', NULL, NULL);

-- 制粒工段（每批次一条）
INSERT INTO pellet (id, batch_id, die_code, tempering_temp, steam_pressure, die_aperture, die_used_tons, die_life_tons, hardness, powder_rate, output_tons, status) VALUES
(1, 1, 'HM-201', 82.50, 0.34, 3.5, 1286.40, 1500.00, 42.6, 6.80, 23.60, 'DONE'),
(2, 2, 'HM-202', 86.20, 0.42, 4.0, 1452.30, 1500.00, 48.1, 5.40, 11.80, 'DONE'),
(3, 3, 'HM-203', 78.40, 0.28, 3.0, 926.70, 1200.00, 38.4, 7.90, 19.90, 'DONE'),
(4, 4, 'HM-201', 81.00, 0.32, 3.5, 1310.20, 1500.00, 41.2, 7.10, 17.60, 'DONE'),
(5, 5, 'HM-204', 75.60, 0.26, 2.8, 688.50, 1000.00, 35.8, 8.60, 21.70, 'DONE'),
(6, 6, 'HM-203', 84.30, 0.38, 3.2, 941.50, 1200.00, 44.0, 6.20, 14.80, 'DONE'),
(7, 7, 'HM-205', 88.10, 0.45, 2.5, 486.20, 800.00, 52.3, 4.70, 9.60, 'DONE'),
(8, 8, 'HM-204', 77.20, 0.30, 3.0, 714.80, 1000.00, 37.5, 8.10, 25.40, 'DONE'),
(9, 9, 'HM-201', 83.60, 0.36, 3.5, 1338.90, 1500.00, 43.7, 6.50, 27.80, 'DONE'),
(10, 10, 'HM-202', 85.00, 0.40, 4.5, 1466.10, 1500.00, 46.9, 5.90, NULL, 'IDLE'),
(11, 11, 'HM-205', 80.00, 0.31, 3.0, 502.40, 800.00, 40.1, 7.30, NULL, 'IDLE');

-- 质检抽样（9 行）
INSERT INTO qc_sample (id, sample_no, batch_id, sample_time, sampler, conclusion, disposition, score, remark) VALUES
(1, 'QC-001', 1, DATE_SUB(NOW(), INTERVAL 55 HOUR), '赵质检', 'PASS', '成品仓入库', 96.50, '各项达标'),
(2, 'QC-002', 2, DATE_SUB(NOW(), INTERVAL 50 HOUR), '赵质检', 'PASS', '成品仓入库', 95.20, '乳猪料色泽正常'),
(3, 'QC-003', 3, DATE_SUB(NOW(), INTERVAL 45 HOUR), '孙质检', 'DOWNGRADE', '降级销售', 82.40, '水分 13.90% 超上限，降级处理'),
(4, 'QC-004', 4, DATE_SUB(NOW(), INTERVAL 40 HOUR), '孙质检', 'PASS', '成品仓入库', 94.80, NULL),
(5, 'QC-005', 5, DATE_SUB(NOW(), INTERVAL 35 HOUR), '周质检', 'PASS', '成品仓入库', 93.10, '霉菌毒素接近上限，持续关注'),
(6, 'QC-006', 6, DATE_SUB(NOW(), INTERVAL 30 HOUR), '周质检', 'REJECT', '回机处理', 61.00, '霉菌毒素超标，整批退货'),
(7, 'QC-007', 7, DATE_SUB(NOW(), INTERVAL 25 HOUR), '赵质检', 'PASS', '成品仓入库', 95.60, NULL),
(8, 'QC-008', 8, DATE_SUB(NOW(), INTERVAL 20 HOUR), '孙质检', 'PASS', '成品仓入库', 94.10, NULL),
(9, 'QC-009', 9, DATE_SUB(NOW(), INTERVAL 15 HOUR), '周质检', NULL, NULL, 58.00, '霉菌毒素超标，待判定');

-- 质检检验项（每单 4 项：水分 / 粗蛋白 / 霉菌毒素 / 灰分）
INSERT INTO qc_item (id, sample_id, item_code, item_name, check_order, unit, measured_value, std_lower, std_upper, qualified) VALUES
(1, 1, 'MOISTURE', '水分', 1, '%', 12.100, 10.000, 13.500, 1),
(2, 1, 'PROTEIN', '粗蛋白', 2, '%', 15.200, 14.800, 17.100, 1),
(3, 1, 'MYCOTOXIN', '霉菌毒素', 3, 'ppb', 22.000, 0.000, 50.000, 1),
(4, 1, 'ASH', '灰分', 4, '%', 6.200, 4.000, 9.000, 1),
(5, 2, 'MOISTURE', '水分', 1, '%', 12.800, 10.000, 13.500, 1),
(6, 2, 'PROTEIN', '粗蛋白', 2, '%', 21.000, 20.700, 23.000, 1),
(7, 2, 'MYCOTOXIN', '霉菌毒素', 3, 'ppb', 31.000, 0.000, 50.000, 1),
(8, 2, 'ASH', '灰分', 4, '%', 5.800, 4.000, 9.000, 1),
(9, 3, 'MOISTURE', '水分', 1, '%', 13.900, 10.000, 13.500, 0),
(10, 3, 'PROTEIN', '粗蛋白', 2, '%', 20.800, 20.200, 22.500, 1),
(11, 3, 'MYCOTOXIN', '霉菌毒素', 3, 'ppb', 26.000, 0.000, 50.000, 1),
(12, 3, 'ASH', '灰分', 4, '%', 6.500, 4.000, 9.000, 1),
(13, 4, 'MOISTURE', '水分', 1, '%', 12.400, 10.000, 13.500, 1),
(14, 4, 'PROTEIN', '粗蛋白', 2, '%', 17.200, 17.000, 19.300, 1),
(15, 4, 'MYCOTOXIN', '霉菌毒素', 3, 'ppb', 18.000, 0.000, 50.000, 1),
(16, 4, 'ASH', '灰分', 4, '%', 6.000, 4.000, 9.000, 1),
(17, 5, 'MOISTURE', '水分', 1, '%', 12.000, 10.000, 13.500, 1),
(18, 5, 'PROTEIN', '粗蛋白', 2, '%', 16.000, 15.700, 18.000, 1),
(19, 5, 'MYCOTOXIN', '霉菌毒素', 3, 'ppb', 44.000, 0.000, 50.000, 1),
(20, 5, 'ASH', '灰分', 4, '%', 7.100, 4.000, 9.000, 1),
(21, 6, 'MOISTURE', '水分', 1, '%', 11.600, 10.000, 13.500, 1),
(22, 6, 'PROTEIN', '粗蛋白', 2, '%', 29.400, 29.200, 31.500, 1),
(23, 6, 'MYCOTOXIN', '霉菌毒素', 3, 'ppb', 58.000, 0.000, 50.000, 0),
(24, 6, 'ASH', '灰分', 4, '%', 6.900, 4.000, 9.000, 1),
(25, 7, 'MOISTURE', '水分', 1, '%', 11.200, 10.000, 13.500, 1),
(26, 7, 'PROTEIN', '粗蛋白', 2, '%', 37.400, 37.200, 39.500, 1),
(27, 7, 'MYCOTOXIN', '霉菌毒素', 3, 'ppb', 27.000, 0.000, 50.000, 1),
(28, 7, 'ASH', '灰分', 4, '%', 8.400, 4.000, 9.000, 1),
(29, 8, 'MOISTURE', '水分', 1, '%', 12.600, 10.000, 13.500, 1),
(30, 8, 'PROTEIN', '粗蛋白', 2, '%', 17.900, 17.400, 19.700, 1),
(31, 8, 'MYCOTOXIN', '霉菌毒素', 3, 'ppb', 35.000, 0.000, 50.000, 1),
(32, 8, 'ASH', '灰分', 4, '%', 6.700, 4.000, 9.000, 1),
(33, 9, 'MOISTURE', '水分', 1, '%', 12.200, 10.000, 13.500, 1),
(34, 9, 'PROTEIN', '粗蛋白', 2, '%', 15.000, 14.800, 17.100, 1),
(35, 9, 'MYCOTOXIN', '霉菌毒素', 3, 'ppb', 62.000, 0.000, 50.000, 0),
(36, 9, 'ASH', '灰分', 4, '%', 6.400, 4.000, 9.000, 1);


-- 校验：配方占比合计应为 100%
-- SELECT recipe_id, SUM(ratio_pct) FROM recipe_item GROUP BY recipe_id;
