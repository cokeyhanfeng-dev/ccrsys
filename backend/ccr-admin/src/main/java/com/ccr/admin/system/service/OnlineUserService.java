package com.ccr.admin.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.ccr.admin.system.dto.OnlineUserQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 有效登录会话清单，仅统计本地仍启用且未删除的账号。 */
@Service
@RequiredArgsConstructor
public class OnlineUserService {
    private final OnlineSessionReader sessions;
    private final JdbcTemplate jdbc;

    public record Row(String userId, String username, String nickName, String orgName, String roleName,
                      String client, LocalDateTime loginTime, LocalDateTime lastAccessTime, String loginIp) {}
    public record UserRow(String userId, String username, String nickName, String orgName, String roleName,
                          List<String> clients, int sessionCount, LocalDateTime lastLoginTime,
                          LocalDateTime lastAccessTime, List<Row> sessions) {}
    public record Result(long total, long userCount, long sessionCount,
                         long pcUserCount, long mobileUserCount, long pcSessionCount, long mobileSessionCount,
                         List<UserRow> records, LocalDateTime queriedAt) {}
    record Profile(long id, String username, String nickName, Long orgId, String orgName, String roleName) {}

    public Result list(OnlineUserQuery query) {
        StpUtil.checkRole("admin");
        List<OnlineSessionReader.Session> active = sessions.read();
        List<Long> ids = active.stream().map(OnlineSessionReader.Session::userId).distinct().toList();
        Map<Long, Profile> profiles = new HashMap<>();
        for (int start = 0; start < ids.size(); start += 500) {
            List<Long> batch = ids.subList(start, Math.min(start + 500, ids.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            List<Profile> rows = jdbc.query("""
                    SELECT u.id, u.username, u.nick_name, u.org_id, d.dept_name,
                           COALESCE(r.role_name, u.role_code) role_name
                    FROM ccr_sys_user u
                    LEFT JOIN ccr_sys_dept d ON d.id = u.org_id AND d.del_flag = '0'
                    LEFT JOIN ccr_sys_role r ON r.role_code = u.role_code AND r.del_flag = '0'
                    WHERE u.del_flag = '0' AND u.status = 'ENABLE' AND u.id IN (
                    """ + placeholders + ")", (rs, rowNum) -> new Profile(rs.getLong("id"), rs.getString("username"),
                    rs.getString("nick_name"), rs.getObject("org_id", Long.class), rs.getString("dept_name"),
                    rs.getString("role_name")), batch.toArray());
            profiles.putAll(rows.stream().collect(Collectors.toMap(Profile::id, Function.identity())));
        }
        String keyword = query.getKeyword() == null ? "" : query.getKeyword().trim().toLowerCase(Locale.ROOT);
        List<Row> matched = new ArrayList<>();
        for (var session : active) {
            Profile user = profiles.get(session.userId());
            if (user == null || query.getOrgId() != null && !query.getOrgId().equals(user.orgId())) continue;
            if (!keyword.isEmpty() && !contains(user.username(), keyword) && !contains(user.nickName(), keyword)) continue;
            matched.add(new Row(Long.toString(user.id()), user.username(), user.nickName(), user.orgName(), user.roleName(),
                    session.client(), date(session.loginTime()), date(session.lastAccessTime()), session.loginIp()));
        }
        matched.sort(Comparator.comparing(Row::lastAccessTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Row::userId).thenComparing(Row::client)
                .thenComparing(Row::loginTime, Comparator.nullsLast(Comparator.reverseOrder())));
        // 两类终端概况使用姓名/机构条件，覆盖所有分页，避免第一页仅有电脑用户时误判移动端无人。
        long pcSessions = matched.stream().filter(row -> "PC".equals(row.client())).count();
        long mobileSessions = matched.stream().filter(row -> "MOBILE".equals(row.client())).count();
        long pcUsers = matched.stream().filter(row -> "PC".equals(row.client())).map(Row::userId).distinct().count();
        long mobileUsers = matched.stream().filter(row -> "MOBILE".equals(row.client())).map(Row::userId).distinct().count();
        Map<String, List<Row>> byUser = new LinkedHashMap<>();
        for (Row row : matched) {
            if (query.getClient() != null && !query.getClient().isEmpty() && !query.getClient().equals(row.client())) continue;
            byUser.computeIfAbsent(row.userId(), ignored -> new ArrayList<>()).add(row);
        }
        List<UserRow> users = new ArrayList<>();
        long sessionCount = 0;
        for (List<Row> userSessions : byUser.values()) {
            Row first = userSessions.get(0);
            sessionCount += userSessions.size();
            LocalDateTime latestLogin = userSessions.stream().map(Row::loginTime).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            users.add(new UserRow(first.userId(), first.username(), first.nickName(), first.orgName(), first.roleName(),
                    userSessions.stream().map(Row::client).distinct().sorted().toList(), userSessions.size(),
                    latestLogin, first.lastAccessTime(), List.copyOf(userSessions)));
        }
        long offset = (long) (query.getPageNum() - 1) * query.getPageSize();
        int from = (int) Math.min(offset, users.size());
        int to = Math.min(from + query.getPageSize(), users.size());
        return new Result(users.size(), users.size(), sessionCount, pcUsers, mobileUsers, pcSessions, mobileSessions,
                List.copyOf(users.subList(from, to)), LocalDateTime.now());
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }
    private static LocalDateTime date(Long millis) {
        return millis == null ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }
}
