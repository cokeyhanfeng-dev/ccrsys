package com.ccr.admin.config;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.ccr.application.controller.CustomerController;
import com.ccr.application.controller.GroupQueryController;
import com.ccr.application.controller.OtherLoanImportController;
import com.ccr.admin.system.controller.FlowConfigController;
import com.ccr.admin.system.controller.ProductConfigController;
import com.ccr.admin.system.controller.RunLogController;
import com.ccr.admin.system.controller.RunLogFileController;
import com.ccr.commitment.controller.CommitmentController;
import com.ccr.commitment.controller.TrackingPolicyController;
import com.ccr.message.controller.NotificationLogController;
import com.ccr.message.controller.NotificationRuleController;
import com.ccr.resolution.controller.ResolutionController;
import com.ccr.rule.controller.RuleController;
import com.ccr.snapshot.controller.DatacenterController;
import com.ccr.snapshot.controller.SnapshotController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 敏感接口角色边界元数据回归测试。 */
class SensitiveEndpointAuthorizationTest {

    @Test
    void technicalDataEndpoints_requireAdmin() {
        assertRoles(DatacenterController.class, Set.of("admin"));
        assertRoles(SnapshotController.class, Set.of("admin"));
        // 运行日志监控(报错采集/日志文件查看):含 SQL 实参与服务器文件,仅 admin
        assertRoles(RunLogController.class, Set.of("admin"));
        assertRoles(RunLogFileController.class, Set.of("admin"));
    }

    @Test
    void customerDataEntryEndpoints_requireCustomerManagerOrAdmin() {
        // 2026-08-20(5e36369):Sa-Token 严格匹配,admin 原被拦报"无权限";改为与 ResolutionController 一致的多角色 OR 放行
        assertRoles(CustomerController.class, Set.of("customer_manager", "admin"));
        assertRoles(GroupQueryController.class, Set.of("customer_manager", "admin"));
        assertRoles(OtherLoanImportController.class, Set.of("customer_manager", "admin"));
    }

    @Test
    void resolutionCreation_hasNoExternalControllerEndpoint() {
        assertFalse(Arrays.stream(ResolutionController.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("create")));
        assertRoles(method(ResolutionController.class, "bind"),
                Set.of("contract_operator", "president", "admin"));
        assertRoles(method(ResolutionController.class, "check"),
                Set.of("contract_operator", "president", "admin"));
    }

    @Test
    void configurationMaintenanceAndReview_areSeparatedByRole() {
        assertRoles(method(RuleController.class, "createSet"), Set.of("admin"));
        assertRoles(method(RuleController.class, "publishSet"), Set.of("admin", "config_reviewer"));
        assertRoles(method(TrackingPolicyController.class, "create"), Set.of("admin"));
        assertRoles(method(TrackingPolicyController.class, "changePolicyStatus"),
                Set.of("admin", "config_reviewer"));
        assertRoles(method(NotificationRuleController.class, "create"), Set.of("admin"));
        assertRoles(method(NotificationRuleController.class, "changeStatus"),
                Set.of("admin", "config_reviewer"));
        assertRoles(method(FlowConfigController.class, "createLpr"), Set.of("admin"));
        assertRoles(method(FlowConfigController.class, "publishLpr"),
                Set.of("admin", "config_reviewer"));
        assertRoles(method(ProductConfigController.class, "createRoute"), Set.of("admin"));
        assertRoles(method(ProductConfigController.class, "publishRoute"),
                Set.of("admin", "config_reviewer"));
    }

    @Test
    void manualSideEffectEndpoints_requireAdmin() {
        assertRoles(method(NotificationLogController.class, "send"), Set.of("admin"));
        assertRoles(method(NotificationLogController.class, "process"), Set.of("admin"));
        assertRoles(method(CommitmentController.class, "createPlan"), Set.of("admin"));
        assertRoles(method(CommitmentController.class, "evaluatePlan"), Set.of("admin"));
    }

    private Method method(Class<?> type, String name) {
        Method[] methods = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .toArray(Method[]::new);
        assertEquals(1, methods.length, "应唯一定位方法:" + type.getSimpleName() + "." + name);
        return methods[0];
    }

    private void assertRoles(AnnotatedElement element, Set<String> expected) {
        SaCheckRole annotation = element.getAnnotation(SaCheckRole.class);
        assertNotNull(annotation, "敏感入口缺少 @SaCheckRole:" + element);
        Set<String> actual = Arrays.stream(annotation.value()).collect(Collectors.toSet());
        assertEquals(expected, actual);
        if (expected.size() > 1) {
            assertEquals(SaMode.OR, annotation.mode());
        }
    }
}
