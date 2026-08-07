package com.ccr.common.core.assignee;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 节点审批人解析器单元测试(§5.5.1)
 * 覆盖:表不存在容错、无配置=不限制、PERSON 命中与代理替换、DEPT 机构归属判定、ROLE 兜底
 */
@ExtendWith(MockitoExtension.class)
class NodeAssigneeResolverTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private NodeAssigneeResolver resolver;

    /** 指派配置行 */
    private Map<String, Object> config(String type, String code) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("assignee_type", type);
        row.put("assignee_code", code);
        row.put("relation", "OR");
        return row;
    }

    /** loadConfigs 打桩(queryForList(String, Object...)) */
    private void stubConfigs(List<Map<String, Object>> configs) {
        when(jdbcTemplate.queryForList(anyString(), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class))).thenReturn(configs);
    }

    /** 用户查询打桩(query(String, RowMapper, Object...)):按 SQL 与入参模拟 ccr_sys_user 结果 */
    private void stubUserQueries(Map<String, NodeAssigneeResolver.AssigneeUser> byUsername,
                                 Map<String, List<NodeAssigneeResolver.AssigneeUser>> byRole,
                                 List<NodeAssigneeResolver.AssigneeUser> deptUsers) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    Object param = inv.getArgument(2);
                    if (sql.contains("JOIN ccr_sys_dept")) {
                        return new ArrayList<>(deptUsers);
                    }
                    if (sql.contains("role_code")) {
                        return new ArrayList<>(byRole.getOrDefault(String.valueOf(param), List.of()));
                    }
                    NodeAssigneeResolver.AssigneeUser user = byUsername.get(String.valueOf(param));
                    return user == null ? List.of() : List.of(user);
                });
    }

    private NodeAssigneeResolver.AssigneeUser user(long id, String username, String nickName) {
        return new NodeAssigneeResolver.AssigneeUser(id, username, nickName);
    }

    @Test
    void resolve_tableMissing_returnsEmpty() {
        // 未执行 03f 的环境:DataAccessException 容错,按未配置放行
        when(jdbcTemplate.queryForList(anyString(), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class)))
                .thenThrow(new BadSqlGrammarException("", "", new SQLException("Table doesn't exist")));

        NodeAssigneeResolver.ResolveResult result = resolver.resolve("BRANCH_MANAGER", 1001L);
        assertEquals(NodeAssigneeResolver.LEVEL_NONE, result.getHitLevel());
        assertTrue(result.getUsers().isEmpty());
        assertTrue(resolver.resolveUserIds("BRANCH_MANAGER", 1001L).isEmpty());
    }

    @Test
    void resolve_noConfig_returnsNone() {
        stubConfigs(List.of());

        NodeAssigneeResolver.ResolveResult result = resolver.resolve("BRANCH_MANAGER", null);
        assertEquals(NodeAssigneeResolver.LEVEL_NONE, result.getHitLevel());
        assertTrue(result.getUsers().isEmpty());
    }

    @Test
    void resolve_personHit_withDelegateReplacement() throws SQLException {
        Map<String, Object> person = config("PERSON", "zhangsan");
        person.put("delegate_to", "lisi");
        person.put("delegate_valid_from", LocalDateTime.now().minusDays(1));
        person.put("delegate_valid_to", LocalDateTime.now().plusDays(1));
        stubConfigs(List.of(person));
        stubUserQueries(Map.of(
                        "zhangsan", user(1001L, "zhangsan", "张三"),
                        "lisi", user(1002L, "lisi", "李四")),
                Map.of(), List.of());

        NodeAssigneeResolver.ResolveResult result = resolver.resolve("BRANCH_MANAGER", null);
        assertEquals("PERSON", result.getHitLevel());
        assertEquals(1, result.getUsers().size());
        // 代理有效期内:处理人替换为代理人,delegatedFrom 记原处理人工号
        assertEquals(1002L, result.getUsers().get(0).getUserId());
        assertEquals("zhangsan", result.getUsers().get(0).getDelegatedFrom());
    }

    @Test
    void resolve_personDelegateExpired_keepsOriginal() throws SQLException {
        Map<String, Object> person = config("PERSON", "zhangsan");
        person.put("delegate_to", "lisi");
        person.put("delegate_valid_from", LocalDateTime.now().minusDays(10));
        person.put("delegate_valid_to", LocalDateTime.now().minusDays(1));
        stubConfigs(List.of(person));
        stubUserQueries(Map.of("zhangsan", user(1001L, "zhangsan", "张三")), Map.of(), List.of());

        NodeAssigneeResolver.ResolveResult result = resolver.resolve("BRANCH_MANAGER", null);
        assertEquals("PERSON", result.getHitLevel());
        assertEquals(1001L, result.getUsers().get(0).getUserId());
    }

    @Test
    void resolve_deptHit_whenApplicantOrgUnderConfig() throws SQLException {
        stubConfigs(List.of(config("DEPT", "100201"), config("ROLE", "branch_manager")));
        // 申请人机构 10020101(网点) 归属配置机构 100201(支行)
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object.class)))
                .thenReturn(List.of("10020101"));
        NodeAssigneeResolver.AssigneeUser branchUser = user(1001L, "wangwu", "王五");
        stubUserQueries(Map.of(), Map.of("branch_manager", List.of(branchUser)), List.of(branchUser));

        NodeAssigneeResolver.ResolveResult result = resolver.resolve("BRANCH_MANAGER", 2001L);
        assertEquals("DEPT", result.getHitLevel());
        assertEquals(List.of(1001L), result.userIds());
    }

    @Test
    void resolve_deptNotMatched_fallsThroughToRole() throws SQLException {
        stubConfigs(List.of(config("DEPT", "100201"), config("ROLE", "branch_manager")));
        // 申请人机构 10020201 不归属配置机构 100201 → DEPT 不命中,ROLE 兜底
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object.class)))
                .thenReturn(List.of("10020201"));
        NodeAssigneeResolver.AssigneeUser branchUser = user(1001L, "wangwu", "王五");
        stubUserQueries(Map.of(), Map.of("branch_manager", List.of(branchUser)), List.of());

        NodeAssigneeResolver.ResolveResult result = resolver.resolve("BRANCH_MANAGER", 2001L);
        assertEquals("ROLE", result.getHitLevel());
        assertEquals(List.of(1001L), result.userIds());
    }
}
