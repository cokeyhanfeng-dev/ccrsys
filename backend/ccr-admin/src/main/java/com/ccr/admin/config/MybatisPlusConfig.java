package com.ccr.admin.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.ccr.common.datascope.CcrDataPermissionHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置:乐观锁、分页、公共字段自动填充
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁(version_no)
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 数据权限(§5.4 受限全局:仅 @DataScope 标注接口经 DataScopeContext 注入;先过滤再分页)
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(new CcrDataPermissionHandler()));
        // 分页
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 公共字段自动填充(附录 A.1)
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
                strictInsertFill(metaObject, "delFlag", String.class, "0");
                strictInsertFill(metaObject, "versionNo", Integer.class, 1);
                strictInsertFill(metaObject, "tenantId", String.class, "000000");
                strictInsertFill(metaObject, "businessNo", String.class, IdUtil.getSnowflakeNextIdStr());
                strictInsertFill(metaObject, "orgId", Long.class, currentOrgId());
                strictInsertFill(metaObject, "status", String.class, "ACTIVE");
                strictInsertFill(metaObject, "createBy", Long.class, currentUserId());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
                strictUpdateFill(metaObject, "updateBy", Long.class, currentUserId());
            }

            /** 当前登录用户(开发期 mock;未登录兜底 0,避免 NOT NULL 约束失败) */
            private Long currentUserId() {
                try {
                    return cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong();
                } catch (Exception e) {
                    return 0L;
                }
            }

            /** 当前用户机构(登录时写入 session;未登录兜底 0) */
            private Long currentOrgId() {
                try {
                    Object orgId = cn.dev33.satoken.stp.StpUtil.getSession().get("orgId");
                    return orgId == null ? 0L : Long.valueOf(orgId.toString());
                } catch (Exception e) {
                    return 0L;
                }
            }
        };
    }
}
