package com.ccr.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.admin.system.domain.*;
import com.ccr.admin.system.mapper.*;
import com.ccr.admin.system.dto.MenuSaveRequest;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MenuService {
    private final CcrSysMenuMapper menus;
    private final CcrSysRoleMapper roles;
    private final CcrSysUserMapper users;
    private final NodeAssigneeResolver assignees;
    private final JdbcTemplate jdbc;
    // 页面注册表限制到系统已有组件；变更目录不会改变业务接口和对象授权。
    public static final Set<String> PAGE_PATHS = Set.of("/overview", "/application/loan", "/application/deposit", "/special-asset",
        "/approval", "/commitment", "/history", "/resolution", "/datacenter", "/audit",
        "/system/user", "/system/role", "/system/menu", "/system/dept", "/system/flow",
        "/system/params", "/system/cache", "/system/run-log", "/system/online", "/system/notification");
    private static final Set<String> APPROVERS = Set.of("branch_manager", "dept_gm", "vice_president", "secretary", "committee_member", "president");

    public List<CcrSysMenu> list() {
        return menus.selectList(new LambdaQueryWrapper<CcrSysMenu>().orderByAsc(CcrSysMenu::getSortNo).orderByAsc(CcrSysMenu::getId));
    }

    /** 在事务内锁定管理员角色，串行化菜单树和角色授权更新，防止并发成环和悬挂引用。 */
    public void lockConfiguration() {
        jdbc.queryForList("SELECT id FROM ccr_sys_role WHERE role_code='admin' FOR UPDATE");
    }

    @Transactional
    public void save(Long id, MenuSaveRequest r) {
        lockConfiguration();
        List<CcrSysMenu> all = list();
        CcrSysMenu current = id == null ? new CcrSysMenu() : all.stream().filter(m -> m.getId().equals(id)).findFirst()
            .orElseThrow(() -> new ServiceException(404, "菜单不存在"));
        validateParent(id, r.parentId(), all);
        if ("C".equals(r.menuType()) && !PAGE_PATHS.contains(r.path() == null ? "" : r.path()))
            throw new ServiceException(400, "请选择已注册的系统页面");
        if ("C".equals(r.menuType()) && all.stream().anyMatch(m -> !Objects.equals(m.getId(), id) && Objects.equals(m.getPath(), r.path())))
            throw new ServiceException(400, "该页面已有菜单，请编辑原菜单");
        if ("C".equals(r.menuType()) && all.stream().anyMatch(m -> Objects.equals(m.getParentId(), id)))
            throw new ServiceException(400, "含子菜单的目录不能改为页面");
        if (id != null && Set.of(1L, 7L, 15L).contains(id) &&
            (!Objects.equals(current.getPath(), r.path()) || !"C".equals(r.menuType()) || (r.parentId() != 0L && id == 1L) ||
             !"ENABLE".equals(r.status()) || !"SHOW".equals(r.visible())))
            throw new ServiceException(400, "工作台、角色和菜单管理入口须保持启用和可见，工作台须位于根目录");
        if (id != null && containsProtected(id, all) && (!"ENABLE".equals(r.status()) || !"SHOW".equals(r.visible())))
            throw new ServiceException(400, "包含管理入口的目录不能隐藏或停用");
        current.setParentId(r.parentId()); current.setMenuName(r.menuName().trim()); current.setMenuType(r.menuType());
        current.setPath("C".equals(r.menuType()) ? r.path() : ""); current.setIcon(r.icon()); current.setSortNo(r.sortNo());
        current.setVisible(r.visible()); current.setStatus(r.status()); current.setUpdateTime(LocalDateTime.now());
        if (id == null) { current.setTenantId("000000"); current.setDelFlag("0"); current.setCreateTime(LocalDateTime.now()); menus.insert(current); }
        else menus.updateById(current);
    }

    public static void validateParent(Long id, Long parentId, List<CcrSysMenu> all) {
        Map<Long,CcrSysMenu> index = new HashMap<>(); all.forEach(m -> index.put(m.getId(),m));
        Set<Long> seen = new HashSet<>();
        for (Long p = parentId; p != null && p != 0L;) {
            if (Objects.equals(p,id) || !seen.add(p)) throw new ServiceException(400,"上级目录不能选择自己或子目录");
            CcrSysMenu parent = index.get(p);
            if (parent == null || !"M".equals(parent.getMenuType())) throw new ServiceException(400,"上级必须为有效目录");
            if (!"ENABLE".equals(parent.getStatus()) || !"SHOW".equals(parent.getVisible())) throw new ServiceException(400,"上级目录须启用且可见");
            p=parent.getParentId();
        }
    }

    private boolean containsProtected(Long id, List<CcrSysMenu> all) {
        return all.stream().anyMatch(m -> Objects.equals(m.getParentId(),id) &&
            (Set.of(1L,7L,15L).contains(m.getId()) || containsProtected(m.getId(),all)));
    }

    @Transactional
    public void delete(Long id) {
        lockConfiguration();
        if (menus.selectById(id)==null) throw new ServiceException(404,"菜单不存在");
        if (Set.of(1L,7L,15L).contains(id)) throw new ServiceException(400,"基础管理入口不能删除");
        if (list().stream().anyMatch(m -> Objects.equals(m.getParentId(),id))) throw new ServiceException(409,"请先删除或移动子菜单");
        if (roles.selectList(null).stream().anyMatch(r -> parseIds(r.getMenuIds()).contains(id)))
            throw new ServiceException(409,"菜单已分配给角色，请先取消授权");
        menus.deleteById(id);
    }

    public static Set<Long> parseIds(String value) {
        Set<Long> ids = new LinkedHashSet<>();
        if(value==null || value.isBlank()) return ids;
        try { for(String s:value.split(",")) if(!s.isBlank()) ids.add(Long.valueOf(s.trim())); }
        catch(NumberFormatException e) { throw new ServiceException(400,"菜单编号格式不正确"); }
        return ids;
    }

    public String validateGrants(String code, String value) {
        Set<Long> ids = parseIds(value);
        List<CcrSysMenu> all = list();
        for(Long id:ids) {
            CcrSysMenu m = all.stream().filter(x -> x.getId().equals(id)).findFirst().orElseThrow(() -> new ServiceException(400,"授权包含已删除菜单"));
            if("C".equals(m.getMenuType()) && !eligible(m.getPath(), Set.of(code)))
                throw new ServiceException(400,"该角色不具备页面的业务权限："+m.getMenuName());
        }
        String result=ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        if(result.length()>500) throw new ServiceException(400,"菜单授权数量超出当前存储限制");
        return result;
    }

    public List<CcrSysMenu> current(Long userId) {
        CcrSysUser user=users.selectById(userId);
        if(user==null || !"ENABLE".equals(user.getStatus())) throw new ServiceException(403,"账号不可用");
        Set<String> codes=new HashSet<>(); codes.add(user.getRoleCode());
        if(assignees.isUserInAssignees("SECRETARY",userId)) codes.add("secretary");
        if(assignees.isUserInAssignees("SIX_PEOPLE_GROUP",userId)) codes.add("committee_member");
        Set<String> enabled=new HashSet<>(); Set<Long> grants=new HashSet<>();
        for(CcrSysRole r:roles.selectList(null)) if(codes.contains(r.getRoleCode()) && "ENABLE".equals(r.getStatus())) {
            enabled.add(r.getRoleCode()); grants.addAll(parseIds(r.getMenuIds()));
        }
        return visibleMenus(list(),enabled,grants);
    }

    public static List<CcrSysMenu> visibleMenus(List<CcrSysMenu> all, Set<String> codes, Set<Long> grants) {
        Map<Long,CcrSysMenu> index=new HashMap<>(); all.forEach(m -> index.put(m.getId(),m));
        Set<Long> keep=new HashSet<>();
        for(CcrSysMenu m:all) {
            if(!"C".equals(m.getMenuType()) || !eligible(m.getPath(),codes) ||
               !(codes.contains("admin") || grants.contains(m.getId()) || "/overview".equals(m.getPath()))) continue;
            Set<Long> chain=new HashSet<>(); CcrSysMenu cursor=m; boolean valid=true;
            while(cursor!=null) {
                if(!chain.add(cursor.getId()) || !"ENABLE".equals(cursor.getStatus())) {valid=false;break;}
                Long p=cursor.getParentId(); if(p==null || p==0L) break;
                cursor=index.get(p); if(cursor==null) valid=false;
            }
            if(valid) keep.addAll(chain);
        }
        return all.stream().filter(m -> keep.contains(m.getId())).toList();
    }

    /** 页面授权取菜单配置与原业务角色边界的交集，目录授权不会扩散到所有子页面。 */
    public static boolean eligible(String path, Set<String> codes) {
        if(path==null || !PAGE_PATHS.contains(path)) return false;
        if(codes.contains("admin")) return true;
        if(codes.isEmpty()) return false;
        return switch(path) {
            case "/application/loan", "/application/deposit", "/special-asset" -> codes.contains("customer_manager");
            case "/approval" -> codes.stream().anyMatch(APPROVERS::contains);
            case "/resolution" -> codes.contains("resolution_query");
            case "/audit" -> codes.contains("auditor");
            case "/system/params" -> codes.contains("config_reviewer");
            case "/overview", "/commitment", "/history" -> true;
            default -> false;
        };
    }
}
