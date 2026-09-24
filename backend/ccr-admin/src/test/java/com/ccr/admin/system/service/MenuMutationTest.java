package com.ccr.admin.system.service;
import com.ccr.admin.system.domain.*;
import com.ccr.admin.system.mapper.*;
import com.ccr.admin.system.dto.MenuSaveRequest;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MenuMutationTest {
    CcrSysMenuMapper mapper; CcrSysRoleMapper roles; MenuService service;
    @BeforeEach void setup() {
        mapper=mock(CcrSysMenuMapper.class);roles=mock(CcrSysRoleMapper.class);
        service=new MenuService(mapper,roles,mock(CcrSysUserMapper.class),mock(NodeAssigneeResolver.class),mock(JdbcTemplate.class));
        when(mapper.selectList(any())).thenReturn(new ArrayList<>());
        when(roles.selectList(any())).thenReturn(new ArrayList<>());
    }
    CcrSysMenu menu(long id,long parent,String path) {
        var m=new CcrSysMenu();m.setId(id);m.setParentId(parent);m.setPath(path);m.setMenuType(path.isEmpty()?"M":"C");m.setStatus("ENABLE");m.setVisible("SHOW");return m;
    }
    MenuSaveRequest request(long parent,String path) {return new MenuSaveRequest(parent,"测试菜单","C",path,"Menu",1,"SHOW","ENABLE");}
    @Test void createAndRepeatRejectDuplicateRoute() {
        service.save(null,request(0,"/history"));verify(mapper).insert(any(CcrSysMenu.class));
        when(mapper.selectList(any())).thenReturn(List.of(menu(5,0,"/history")));
        assertThrows(ServiceException.class,()->service.save(null,request(0,"/history")));
        verify(mapper,times(1)).insert(any(CcrSysMenu.class));
    }
    @Test void protectedEntryCannotBeDisabledOrRedirected() {
        when(mapper.selectList(any())).thenReturn(List.of(menu(15,0,"/system/menu")));
        assertThrows(ServiceException.class,()->service.save(15L,request(0,"/history")));
        assertThrows(ServiceException.class,()->service.save(15L,new MenuSaveRequest(0L,"菜单","C","/system/menu","Menu",1,"SHOW","DISABLE")));
        verify(mapper,never()).updateById(any(CcrSysMenu.class));
    }
    @Test void referencedMenusAndParentsCannotBeDeleted() {
        when(mapper.selectById(5L)).thenReturn(menu(5,0,"/history"));
        var role=new CcrSysRole();role.setMenuIds("5");when(roles.selectList(any())).thenReturn(List.of(role));
        assertThrows(ServiceException.class,()->service.delete(5L));
        when(mapper.selectById(100L)).thenReturn(menu(100,0,""));
        when(mapper.selectList(any())).thenReturn(List.of(menu(5,100,"/history")));
        assertThrows(ServiceException.class,()->service.delete(100L));
    }
    @Test void unknownRouteAndOutOfScopeGrantAreRejected() {
        assertThrows(ServiceException.class,()->service.save(null,request(0,"https://example.com")));
        when(mapper.selectList(any())).thenReturn(List.of(menu(6,0,"/system/user")));
        assertThrows(ServiceException.class,()->service.validateGrants("customer_manager","6"));
        assertThrows(ServiceException.class,()->service.validateGrants("admin","999"));
        assertEquals("6",service.validateGrants("admin","6,6"));
    }
}
