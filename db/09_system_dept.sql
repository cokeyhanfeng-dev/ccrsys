-- ============================================================
-- 基础系统功能:机构表(数据权限控制基础,详设 §5.1.1/§5.4/§10.3.20)
-- 机构层级:总行(HEAD) → 部门(DEPT)/支行(BRANCH) → 网点(NETWORK)
-- 机构编码 org_code:真实宜兴农商行机构码(唯一、禁改),如 3202230000 总行 → 32022339xx 部门 → 32022330xx 支行/分理处
-- 数据权限:机构范围按 org_code 前缀匹配(LIKE '3202233050%' 覆盖支行及其下辖网点),不做递归查询
-- 2026-08-14:删除 dept_code 字母编码列,机构编码统一 org_code(部门归属/矩阵/指派/数仓全部对齐 org_code)
-- 2026-08-14:删除 ancestors 冗余祖先链(与 parent_id 重复),机构层级由 parent_id 表达、范围判定走 org_code 前缀
-- 2026-08-14:机构表由 12 家测试机构迁移为真实宜兴农商行 59 家(org_code=320223xxxx,来源数仓 dws.ccr_sys_dept1);
--           机构 id 全量重分配(总行 1000,其余按 org_code 升序 1001-1058);部门归属码同步:
--           公司金融部=3202233912/授信评审部=3202233943/零售金融=3202233991;支行系:城东支行=3202233050/营业部=3202233001
-- 2026-08-14:删重复机构 3 家(董事业办公室 3202233904/运营管理部 3202233932/网络金融部 3202233992)+ 投诉处理部 3202233998,59→55 家
-- ============================================================

USE `ccr_rate`;

CREATE TABLE IF NOT EXISTS `ccr_sys_dept` (
  `id`          BIGINT       NOT NULL,
  `tenant_id`   VARCHAR(20)  NOT NULL DEFAULT '000000',
  `org_code`    VARCHAR(32)  NOT NULL COMMENT '机构编码(层级前缀数字码,唯一,禁改):1000总行/1001xx部门/1002xx支行/支行码+两位为网点',
  `branch_code` VARCHAR(32)  NULL COMMENT '支行编码:BRANCH=自身org_code;NETWORK=所属支行org_code;DEPT/HEAD为空',
  `dept_name`   VARCHAR(64)  NOT NULL COMMENT '机构名称',
  `parent_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '父机构id',
  `org_type`    VARCHAR(16)  NOT NULL COMMENT 'HEAD总行/DEPT部门/BRANCH支行/NETWORK网点/GROUP集团管理机构',
  `manager`     VARCHAR(64)  NULL COMMENT '负责人',
  `status`      VARCHAR(8)   NOT NULL DEFAULT 'ENABLE',
  `sort_no`     INT          NOT NULL DEFAULT 1,
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NULL,
  `del_flag`    CHAR(1)      NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_org_code` (`org_code`),
  KEY `idx_parent` (`parent_id`),
  KEY `idx_branch_code` (`branch_code`)
) ENGINE=InnoDB COMMENT='机构表(数据权限控制,编码前缀匹配)';

-- ---------- 种子:真实机构层级(2026-08-14 由 12 家测试机构迁移为真实宜兴农商行 59 家,后删 4 家,现 55 家;来源数仓 dws.ccr_sys_dept1) ----------
-- 机构 id 全量重分配:总行 1000,其余按 org_code 升序 1001-1058;部门归属(矩阵 dept_code/指派/ccr_dept_vp)统一使用机构 org_code
INSERT INTO `ccr_sys_dept`
  (`id`,`org_code`,`branch_code`,`dept_name`,`parent_id`,`org_type`,`manager`,`status`,`sort_no`) VALUES
  (1000,'3202230000',NULL,'江苏宜兴农村商业银行总行',0,'HEAD',NULL,'ENABLE',00),
  (1001,'3202230019','3202230019','江苏宜兴农村商业银行官林支行',1000,'BRANCH',NULL,'ENABLE',19),
  (1002,'3202230034','3202230034','江苏宜兴农村商业银行周铁支行',1000,'BRANCH',NULL,'ENABLE',34),
  (1003,'3202230042','3202230042','江苏宜兴农村商业银行太滆支行',1000,'BRANCH',NULL,'ENABLE',42),
  (1004,'3202230044','3202230044','江苏宜兴农村商业银行川埠支行',1000,'BRANCH',NULL,'ENABLE',44),
  (1005,'3202230078','3202230078','江苏宜兴农村商业银行溧水支行',1000,'BRANCH',NULL,'ENABLE',78),
  (1006,'3202233001','3202233001','江苏宜兴农村商业银行营业部',1000,'BRANCH',NULL,'ENABLE',01),
  (1007,'3202233003','3202233003','江苏宜兴农村商业银行十里牌支行',1000,'BRANCH',NULL,'ENABLE',03),
  (1008,'3202233004','3202233056','江苏宜兴农村商业银行城东分理处',1028,'NETWORK',NULL,'ENABLE',04),
  (1009,'3202233005','3202233005','江苏宜兴农村商业银行新街支行',1000,'BRANCH',NULL,'ENABLE',05),
  (1010,'3202233006','3202233006','江苏宜兴农村商业银行张渚支行',1000,'BRANCH',NULL,'ENABLE',06),
  (1011,'3202233007','3202233007','江苏宜兴农村商业银行西渚支行',1000,'BRANCH',NULL,'ENABLE',07),
  (1012,'3202233009','3202233009','江苏宜兴农村商业银行大华支行',1000,'BRANCH',NULL,'ENABLE',09),
  (1013,'3202233013','3202233013','江苏宜兴农村商业银行徐舍支行',1000,'BRANCH',NULL,'ENABLE',13),
  (1014,'3202233020','3202233020','江苏宜兴农村商业银行杨巷支行',1000,'BRANCH',NULL,'ENABLE',20),
  (1015,'3202233022','3202233022','江苏宜兴农村商业银行新建支行',1000,'BRANCH',NULL,'ENABLE',22),
  (1016,'3202233025','3202233025','江苏宜兴农村商业银行丰义支行',1000,'BRANCH',NULL,'ENABLE',25),
  (1017,'3202233026','3202233026','江苏宜兴农村商业银行范道支行',1000,'BRANCH',NULL,'ENABLE',26),
  (1018,'3202233027','3202233027','江苏宜兴农村商业银行和桥支行',1000,'BRANCH',NULL,'ENABLE',27),
  (1019,'3202233028','3202233028','江苏宜兴农村商业银行高塍支行',1000,'BRANCH',NULL,'ENABLE',28),
  (1020,'3202233029','3202233029','江苏宜兴农村商业银行屺亭支行',1000,'BRANCH',NULL,'ENABLE',29),
  (1021,'3202233032','3202233032','江苏宜兴农村商业银行万石支行',1000,'BRANCH',NULL,'ENABLE',32),
  (1022,'3202233035','3202233035','江苏宜兴农村商业银行万桥支行',1000,'BRANCH',NULL,'ENABLE',35),
  (1023,'3202233039','3202233039','江苏宜兴农村商业银行新庄支行',1000,'BRANCH',NULL,'ENABLE',39),
  (1024,'3202233041','3202233041','江苏宜兴农村商业银行丁蜀支行',1000,'BRANCH',NULL,'ENABLE',41),
  (1025,'3202233045','3202233045','江苏宜兴农村商业银行湖㳇支行',1000,'BRANCH',NULL,'ENABLE',45),
  (1026,'3202233050','3202233050','江苏宜兴农村商业银行城东支行',1000,'BRANCH',NULL,'ENABLE',50),
  (1027,'3202233055','3202233050','江苏宜兴农村商业银行东虹分理处',1026,'NETWORK',NULL,'ENABLE',55),
  (1028,'3202233056','3202233056','江苏宜兴农村商业银行宜城支行',1000,'BRANCH',NULL,'ENABLE',56),
  (1029,'3202233060','3202233060','江苏宜兴农村商业银行阳都支行',1000,'BRANCH',NULL,'ENABLE',60),
  (1030,'3202233062','3202233062','江苏宜兴农村商业银行南郊支行',1000,'BRANCH',NULL,'ENABLE',62),
  (1031,'3202233065','3202233065','江苏宜兴农村商业银行环科园支行',1000,'BRANCH',NULL,'ENABLE',65),
  (1032,'3202233071','3202233071','江苏宜兴农村商业银行广汇支行',1000,'BRANCH',NULL,'ENABLE',71),
  (1033,'3202233077','3202233077','江苏宜兴农村商业银行高淳支行',1000,'BRANCH',NULL,'ENABLE',77),
  (1034,'3202233080',NULL,'江苏宜兴农村商业银行普惠金融部',1000,'DEPT',NULL,'ENABLE',80),
  (1036,'3202233905',NULL,'江苏宜兴农村商业银行党群工作部',1000,'DEPT',NULL,'ENABLE',05),
  (1037,'3202233906',NULL,'江苏宜兴农村商业银行办公室',1000,'DEPT',NULL,'ENABLE',06),
  (1038,'3202233908',NULL,'江苏宜兴农村商业银行纪委纪检监察室',1000,'DEPT',NULL,'ENABLE',08),
  (1039,'3202233911',NULL,'江苏宜兴农村商业银行信贷管理部',1000,'DEPT',NULL,'ENABLE',11),
  (1040,'3202233912',NULL,'江苏宜兴农村商业银行公司金融部',1000,'DEPT',NULL,'ENABLE',12),
  (1041,'3202233913',NULL,'江苏宜兴农村商业银行数字银行部',1000,'DEPT',NULL,'ENABLE',13),
  (1042,'3202233915',NULL,'江苏宜兴农村商业银行特殊资产管理部',1000,'DEPT',NULL,'ENABLE',15),
  (1043,'3202233921',NULL,'江苏宜兴农村商业银行董事会办公室',1000,'DEPT',NULL,'ENABLE',21),
  (1044,'3202233931',NULL,'江苏宜兴农村商业银行计划财务部',1000,'DEPT',NULL,'ENABLE',31),
  (1046,'3202233935',NULL,'江苏宜兴农村商业银行金融市场部',1000,'DEPT',NULL,'ENABLE',35),
  (1047,'3202233937',NULL,'江苏宜兴农村商业银行运营管理部',1000,'DEPT',NULL,'ENABLE',37),
  (1048,'3202233941',NULL,'江苏宜兴农村商业银行法律合规部',1000,'DEPT',NULL,'ENABLE',41),
  (1049,'3202233942',NULL,'江苏宜兴农村商业银行风险管理部',1000,'DEPT',NULL,'ENABLE',42),
  (1050,'3202233943',NULL,'江苏宜兴农村商业银行授信评审部',1000,'DEPT',NULL,'ENABLE',43),
  (1051,'3202233953',NULL,'江苏宜兴农村商业银行科技金融部',1000,'DEPT',NULL,'ENABLE',53),
  (1052,'3202233961',NULL,'江苏宜兴农村商业银行审计部',1000,'DEPT',NULL,'ENABLE',61),
  (1053,'3202233971',NULL,'江苏宜兴农村商业银行人力资源部',1000,'DEPT',NULL,'ENABLE',71),
  (1054,'3202233981',NULL,'江苏宜兴农村商业银行安全保卫部',1000,'DEPT',NULL,'ENABLE',81),
  (1055,'3202233984',NULL,'江苏宜兴农村商业银行网络金融部',1000,'DEPT',NULL,'ENABLE',84),
  (1056,'3202233991',NULL,'江苏宜兴农村商业银行零售金融',1000,'DEPT',NULL,'ENABLE',91)
ON DUPLICATE KEY UPDATE
  org_code=VALUES(org_code), branch_code=VALUES(branch_code), dept_name=VALUES(dept_name),
  parent_id=VALUES(parent_id), org_type=VALUES(org_type), manager=VALUES(manager), sort_no=VALUES(sort_no);

-- ---------- 种子:用户机构对齐(2026-08-14 按真实机构 org_code JOIN 定位新 id) ----------
UPDATE ccr_sys_user u JOIN ccr_sys_dept d ON d.org_code='3202230000' SET u.org_id=d.id WHERE u.username IN ('admin','president','reviewer','auditor','contractor','vicepresident_gsb','vicepresident_sxsb');
-- (早期 mock 账号 zhangsan/lisi/deptgm/committee2-6 已清理(2026-08-17),对应机构对齐 UPDATE 随之移除;
--   注:wangwu 测试行长此前已删除)
UPDATE ccr_sys_user u JOIN ccr_sys_dept d ON d.org_code='3202233943' SET u.org_id=d.id WHERE u.username IN ('deptgm_sxsb');
UPDATE ccr_sys_user u JOIN ccr_sys_dept d ON d.org_code='3202233991' SET u.org_id=d.id WHERE u.username IN ('deptgm_lsb');
DROP PROCEDURE IF EXISTS `ccr_drop_dept_ancestors`;
DELIMITER $$
CREATE PROCEDURE `ccr_drop_dept_ancestors`()
BEGIN
  IF EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ccr_sys_dept' AND COLUMN_NAME = 'ancestors') THEN
    ALTER TABLE `ccr_sys_dept` DROP COLUMN `ancestors`;
  END IF;
END$$
DELIMITER ;
CALL `ccr_drop_dept_ancestors`();
DROP PROCEDURE `ccr_drop_dept_ancestors`;
