package com.ccr.approval.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 审批链路由工具类单元测试(§7.2 用户拍板口径)
 * 覆盖:贷款/存款全链、routeCode 截断、不在链上回退全链、下一节点
 */
class RouteChainsTest {

    // ---------- fullChain ----------

    @Test
    void fullChain_贷款返回四级链() {
        List<String> chain = RouteChains.fullChain("LOAN");

        assertEquals(4, chain.size());
        assertEquals(RouteChains.BRANCH_MANAGER, chain.get(0));
        assertEquals(RouteChains.DEPT_GENERAL_MANAGER, chain.get(1));
        assertEquals(RouteChains.VICE_PRESIDENT, chain.get(2));
        assertEquals(RouteChains.SIX_PEOPLE_GROUP, chain.get(3));
    }

    @Test
    void fullChain_存款返回两级链() {
        List<String> chain = RouteChains.fullChain("DEPOSIT");

        assertEquals(2, chain.size());
        assertEquals(RouteChains.BRANCH_MANAGER, chain.get(0));
        assertEquals(RouteChains.SIX_PEOPLE_GROUP, chain.get(1));
    }

    @Test
    void fullChain_保证金同存款链() {
        // 非DEPOSIT一律走贷款链
        List<String> chain = RouteChains.fullChain("MARGIN");

        assertEquals(4, chain.size());
    }

    @Test
    void fullChain_保证全链不可变() {
        List<String> chain = RouteChains.fullChain("LOAN");

        // 返回的是不可变List,修改会抛异常(防止外部篡改静态链)
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> chain.add("X"));
    }

    // ---------- of(截断链) ----------

    @Test
    void of_贷款截断至支行行长() {
        List<String> chain = RouteChains.of("LOAN", "BRANCH_MANAGER");

        assertEquals(1, chain.size());
        assertEquals(RouteChains.BRANCH_MANAGER, chain.get(0));
    }

    @Test
    void of_贷款截断至部门总经理() {
        List<String> chain = RouteChains.of("LOAN", "DEPT_GENERAL_MANAGER");

        assertEquals(2, chain.size());
        assertEquals(RouteChains.BRANCH_MANAGER, chain.get(0));
        assertEquals(RouteChains.DEPT_GENERAL_MANAGER, chain.get(1));
    }

    @Test
    void of_贷款截断至分管副行长() {
        List<String> chain = RouteChains.of("LOAN", "VICE_PRESIDENT");

        assertEquals(3, chain.size());
    }

    @Test
    void of_贷款截断至六人小组_全链() {
        List<String> chain = RouteChains.of("LOAN", "SIX_PEOPLE_GROUP");

        assertEquals(4, chain.size());
    }

    @Test
    void of_存款截断至支行行长() {
        List<String> chain = RouteChains.of("DEPOSIT", "BRANCH_MANAGER");

        assertEquals(1, chain.size());
    }

    @Test
    void of_存款截断至六人小组_全链() {
        List<String> chain = RouteChains.of("DEPOSIT", "SIX_PEOPLE_GROUP");

        assertEquals(2, chain.size());
    }

    @Test
    void of_routeCode为空返回全链() {
        List<String> chain = RouteChains.of("LOAN", null);

        assertEquals(4, chain.size());
    }

    @Test
    void of_routeCode空白返回全链() {
        List<String> chain = RouteChains.of("LOAN", "  ");

        assertEquals(4, chain.size());
    }

    @Test
    void of_routeCode不在链上返回全链() {
        List<String> chain = RouteChains.of("LOAN", "PRESIDENT");

        // PRESIDENT不在贷款审批链中(贷款链终点是SIX_PEOPLE_GROUP)→返回全链
        assertEquals(4, chain.size());
    }

    @Test
    void of_存款routeCode不在链上返回全链() {
        List<String> chain = RouteChains.of("DEPOSIT", "DEPT_GENERAL_MANAGER");

        // 存款链无部门总经理→返回全链(2节点)
        assertEquals(2, chain.size());
    }

    // ---------- nextNode ----------

    @Test
    void nextNode_支行行长_下一节点为部门总经理() {
        assertEquals(RouteChains.DEPT_GENERAL_MANAGER, RouteChains.nextNode("BRANCH_MANAGER"));
    }

    @Test
    void nextNode_部门总经理_下一节点为分管副行长() {
        assertEquals(RouteChains.VICE_PRESIDENT, RouteChains.nextNode("DEPT_GENERAL_MANAGER"));
    }

    @Test
    void nextNode_分管副行长_下一节点为六人小组() {
        assertEquals(RouteChains.SIX_PEOPLE_GROUP, RouteChains.nextNode("VICE_PRESIDENT"));
    }

    @Test
    void nextNode_六人小组为终点_返回null() {
        assertNull(RouteChains.nextNode("SIX_PEOPLE_GROUP"));
    }

    @Test
    void nextNode_未知节点返回null() {
        assertNull(RouteChains.nextNode("UNKNOWN_NODE"));
    }

    @Test
    void nextNode_null返回null() {
        assertNull(RouteChains.nextNode(null));
    }
}
