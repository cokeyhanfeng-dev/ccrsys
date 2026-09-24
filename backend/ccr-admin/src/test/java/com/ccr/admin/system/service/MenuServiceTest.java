package com.ccr.admin.system.service;

import com.ccr.admin.system.domain.CcrSysMenu;
import com.ccr.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MenuServiceTest {
    private CcrSysMenu menu(long id,long parent,String type,String path) {
        CcrSysMenu m=new CcrSysMenu();m.setId(id);m.setParentId(parent);m.setMenuType(type);m.setPath(path);
        m.setStatus("ENABLE");m.setVisible("SHOW");return m;
    }
    @Test void secretaryGetsGrantedApprovalWithAncestors() {
        var all=List.of(menu(100,0,"M",""),menu(10,100,"C","/approval"));
        assertEquals(2,MenuService.visibleMenus(all,Set.of("secretary"),Set.of(10L)).size());
        assertTrue(MenuService.visibleMenus(all,Set.of("customer_manager"),Set.of(10L)).isEmpty());
    }
    @Test void grantingDirectoryDoesNotGrantChildrenAndMissingGrantDeniesPage() {
        var all=List.of(menu(100,0,"M",""),menu(10,100,"C","/approval"));
        assertTrue(MenuService.visibleMenus(all,Set.of("secretary"),Set.of(100L)).isEmpty());
        assertTrue(MenuService.visibleMenus(all,Set.of("secretary"),Set.of()).isEmpty());
    }
    @Test void disabledAncestorDeniesButHiddenPageRemainsAuthorized() {
        var parent=menu(100,0,"M","");var child=menu(10,100,"C","/approval");child.setVisible("HIDE");
        assertEquals(2,MenuService.visibleMenus(List.of(parent,child),Set.of("admin"),Set.of()).size());
        parent.setStatus("DISABLE");assertTrue(MenuService.visibleMenus(List.of(parent,child),Set.of("admin"),Set.of()).isEmpty());
    }
    @Test void missingParentAndCyclesFailClosed() {
        var all=List.of(menu(100,101,"M",""),menu(101,100,"M",""),menu(10,100,"C","/approval"));
        assertTrue(MenuService.visibleMenus(all,Set.of("admin"),Set.of()).isEmpty());
        assertTrue(MenuService.visibleMenus(List.of(menu(10,999,"C","/approval")),Set.of("admin"),Set.of()).isEmpty());
    }
    @Test void invalidParentsAndDescendantsAreRejected() {
        var all=List.of(menu(100,0,"M",""),menu(101,100,"M",""),menu(10,101,"C","/approval"));
        assertThrows(ServiceException.class,()->MenuService.validateParent(100L,101L,all));
        assertThrows(ServiceException.class,()->MenuService.validateParent(100L,100L,all));
        assertThrows(ServiceException.class,()->MenuService.validateParent(102L,10L,all));
        assertThrows(ServiceException.class,()->MenuService.validateParent(102L,999L,all));
        assertDoesNotThrow(()->MenuService.validateParent(102L,101L,all));
    }
    @Test void adminAndBusinessBoundariesArePreserved() {
        assertTrue(MenuService.eligible("/system/menu",Set.of("admin")));
        assertFalse(MenuService.eligible("/system/menu",Set.of("secretary")));
        assertFalse(MenuService.eligible("/datacenter",Set.of("customer_manager")));
        assertTrue(MenuService.eligible("/system/params",Set.of("config_reviewer")));
        assertFalse(MenuService.eligible("/system/user",Set.of("config_reviewer")));
        assertFalse(MenuService.eligible("/unknown",Set.of("admin")));
    }
    @Test void parsingPreservesLongIdsDeduplicatesAndRejectsMalformedInput() {
        assertEquals(Set.of(90071992547409931L,1L),MenuService.parseIds("1,90071992547409931,1"));
        assertThrows(ServiceException.class,()->MenuService.parseIds("1,x"));
        assertTrue(MenuService.parseIds("").isEmpty());
    }
    @Test void specialAssetRequiresCustomerManagerAndMenuGrant() {
        var row=menu(1001,0,"C","/special-asset");
        assertEquals(1,MenuService.visibleMenus(List.of(row),Set.of("customer_manager"),Set.of(1001L)).size());
        assertTrue(MenuService.visibleMenus(List.of(row),Set.of("customer_manager"),Set.of()).isEmpty());
        assertTrue(MenuService.visibleMenus(List.of(row),Set.of("branch_manager"),Set.of(1001L)).isEmpty());
        assertTrue(MenuService.eligible("/special-asset",Set.of("admin")));
    }
}
