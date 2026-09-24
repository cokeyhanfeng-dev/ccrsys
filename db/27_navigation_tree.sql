-- 菜单树与角色导航授权；保留原有业务权限检查。仅限 ccr_rate。
USE ccr_rate;
DROP PROCEDURE IF EXISTS migrate_navigation_tree;
DELIMITER $$
CREATE PROCEDURE migrate_navigation_tree()
BEGIN
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ccr_sys_menu' AND column_name='menu_type') THEN
  ALTER TABLE ccr_sys_menu ADD COLUMN menu_type CHAR(1) NOT NULL DEFAULT 'C' COMMENT 'M目录/C页面',
    ADD COLUMN icon VARCHAR(64) NOT NULL DEFAULT 'Menu',
    ADD COLUMN visible VARCHAR(8) NOT NULL DEFAULT 'SHOW' COMMENT 'SHOW/HIDE';
 END IF;
 START TRANSACTION;
 -- 菜单管理入口为受保护记录，其首次写入与初始化分组在同一事务完成。
 IF NOT EXISTS (SELECT 1 FROM ccr_sys_menu WHERE id=15) THEN
  INSERT INTO ccr_sys_menu(id,parent_id,menu_name,path,sort_no,menu_type) VALUES
    (100,0,'利率申请','',2,'M'),(101,0,'系统管理','',20,'M'),(102,0,'运行管理','',30,'M');
  INSERT INTO ccr_sys_menu(id,parent_id,menu_name,path,sort_no,menu_type) VALUES
    (15,101,'菜单管理','/system/menu',3,'C'),(16,102,'在线用户','/system/online',1,'C'),
    (17,102,'消息投递记录','/system/notification',2,'C'),(18,102,'缓存配置','/system/cache',3,'C'),
    (19,102,'运行监控','/system/run-log',4,'C');
  INSERT INTO ccr_sys_menu(id,parent_id,menu_name,path,sort_no,menu_type)
    SELECT 14,0,'决议书查询','/resolution',14,'C' WHERE NOT EXISTS (SELECT 1 FROM ccr_sys_menu WHERE id=14);
  UPDATE ccr_sys_menu SET parent_id=100 WHERE id IN (2,3);
  UPDATE ccr_sys_menu SET parent_id=101 WHERE id IN (6,7,8,9,13);
  UPDATE ccr_sys_menu SET menu_name='角色管理' WHERE id=7;
  UPDATE ccr_sys_menu SET parent_id=102 WHERE id=11;
  UPDATE ccr_sys_menu SET icon=CASE path
    WHEN '/overview' THEN 'HomeFilled'
    WHEN '/application/loan' THEN 'EditPen'
    WHEN '/application/deposit' THEN 'Coin'
    WHEN '/approval' THEN 'Stamp'
    WHEN '/commitment' THEN 'Timer'
    WHEN '/history' THEN 'Document'
    WHEN '/resolution' THEN 'DocumentCopy'
    WHEN '/datacenter' THEN 'DataAnalysis'
    WHEN '/audit' THEN 'View'
    WHEN '/system/user' THEN 'User'
    WHEN '/system/role' THEN 'Key'
    WHEN '/system/dept' THEN 'OfficeBuilding'
    WHEN '/system/flow' THEN 'Share'
    WHEN '/system/params' THEN 'Setting'
    WHEN '/system/cache' THEN 'Odometer'
    WHEN '/system/run-log' THEN 'Monitor'
    WHEN '/system/online' THEN 'Connection'
    WHEN '/system/notification' THEN 'Bell'
    ELSE icon END;
  UPDATE ccr_sys_menu SET icon='FolderOpened' WHERE menu_type='M';
  -- 旧种子曾包含非管理员数据中心，清理不可用授权；补齐原侧栏所有角色可见的贡献度跟踪和历史。
  UPDATE ccr_sys_role SET menu_ids=TRIM(BOTH ',' FROM REPLACE(CONCAT(',',COALESCE(menu_ids,''),','),',11,',',')) WHERE role_code<>'admin';
  UPDATE ccr_sys_role SET menu_ids=CONCAT_WS(',',NULLIF(menu_ids,''),'4') WHERE FIND_IN_SET('4',COALESCE(menu_ids,''))=0;
  UPDATE ccr_sys_role SET menu_ids=CONCAT_WS(',',NULLIF(menu_ids,''),'5') WHERE FIND_IN_SET('5',COALESCE(menu_ids,''))=0;
  UPDATE ccr_sys_role SET menu_ids=CONCAT_WS(',',NULLIF(menu_ids,''),'10') WHERE role_code='secretary' AND FIND_IN_SET('10',COALESCE(menu_ids,''))=0;
 END IF;
 COMMIT;
END$$
DELIMITER ;
CALL migrate_navigation_tree();
DROP PROCEDURE migrate_navigation_tree;
