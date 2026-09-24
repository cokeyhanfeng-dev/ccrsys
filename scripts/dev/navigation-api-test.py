#!/usr/bin/env python3
"""仅对 ccrsys-test 回环接口执行菜单与角色真实读写回归；最后恢复配置并注销虚构账号。"""
import concurrent.futures
import json
import os
import secrets
import subprocess
import urllib.request
import urllib.error
from pathlib import Path

BASE = 'http://127.0.0.1:18080'
checks = []
users = []
created_dirs = []
created_role = None
admin_token = ''
user_tokens = []
original_history = None
original_roles = {}


def call(method, path, payload=None, token=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = token
    req = urllib.request.Request(BASE + path, data=None if payload is None else json.dumps(payload).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            result = json.load(response)
    except urllib.error.HTTPError as e:
        result = json.load(e)
    if expected is not None:
        assert result.get('code') == expected, f'{method} {path}: expected {expected}, got {result.get("code")}: {result.get("msg")}'
    return result.get('data') if expected is not None else result


def check(label, condition=True):
    assert condition, label
    checks.append(label)
    print('PASS ' + label, flush=True)


def admin(method, path, payload=None, expected=200):
    return call(method, path, payload, admin_token, expected)


def menu_payload(m, **changes):
    value = {k: m[k] for k in ['parentId', 'menuName', 'menuType', 'path', 'icon', 'sortNo', 'visible', 'status']}
    return {**value, **changes}


def visible(token):
    return call('GET', '/auth/menus', token=token)


def paths(token):
    return {m['path'] for m in visible(token) if m['menuType'] == 'C'}


def new_user(role):
    password = 'Nav!Aa7' + secrets.token_hex(12)
    name = 'navtest_' + secrets.token_hex(6)
    user = admin('POST', '/system/users', {'username': name, 'password': password, 'nickName': '导航自动回归虚构账号', 'roleCode': role, 'orgId': '1000'})
    users.append(user['id'])
    login = call('POST', '/auth/login', {'username': name, 'password': password})
    token = login['token']
    user_tokens.append(token)
    return token


def new_dir(name, parent='0'):
    body = {'parentId': parent, 'menuName': name, 'menuType': 'M', 'path': '', 'icon': 'FolderOpened', 'sortNo': 99, 'visible': 'SHOW', 'status': 'ENABLE'}
    admin('POST', '/system/menus', body)
    row = next(m for m in admin('GET', '/system/menus') if m['menuName'] == name)
    created_dirs.append(row)
    return row


def save_role(role, ids=None, **changes):
    body = {k: role.get(k) for k in ['roleCode', 'roleName', 'menuIds', 'status', 'remark']}
    if ids is not None:
        body['menuIds'] = ','.join(map(str, ids))
    admin('PUT', '/system/roles/' + str(role['id']), {**body, **changes})


try:
    admin_token = call('POST', '/auth/login', {'username': 'admin', 'password': os.environ.get('CCR_SMOKE_PASSWORD', 'Yxnsh@1a3s')})['token']
    menu_rows = admin('GET', '/system/menus')
    original_history = next(m for m in menu_rows if m['path'] == '/history')
    role_rows = admin('GET', '/system/roles')
    by_code = {r['roleCode']: r for r in role_rows}
    check('真实登录、菜单表新字段及角色列表可读取', len(menu_rows) >= 22 and all('menuType' in m and 'visible' in m for m in menu_rows))
    check('初始化二级目录和管理入口齐全', {'/system/menu', '/system/role', '/system/online'} <= paths(admin_token))
    check('匿名导航接口拒绝', call('GET', '/auth/menus', expected=None)['code'] == 401)

    cm = new_user('customer_manager')
    secretary = new_user('secretary')
    reviewer = new_user('config_reviewer')
    check('客户经理具有申请入口且无系统管理入口', {'/application/loan', '/application/deposit'} <= paths(cm) and '/system/user' not in paths(cm))
    check('秘书有审批入口', '/approval' in paths(secretary))
    check('配置复核人仅保留参数管理入口', '/system/params' in paths(reviewer) and '/system/user' not in paths(reviewer))
    check('迁移保留原有各角色公共历史入口', all('/history' in paths(token) for token in [cm, secretary, reviewer]))
    for method, path, payload in [('GET', '/system/menus', None), ('POST', '/system/menus', {}), ('PUT', '/system/menus/1', {}), ('DELETE', '/system/menus/1', None), ('GET', '/system/roles', None), ('POST', '/system/roles', {}), ('PUT', '/system/roles/2004', {}), ('DELETE', '/system/roles/2004', None)]:
        call(method, path, payload, cm, 403)
    check('非管理员 8 类配置接口全部拒绝')

    admin('POST', '/system/menus', {}, 400)
    admin('POST', '/system/menus', menu_payload(original_history, path='/not-registered'), 400)
    admin('POST', '/system/menus', menu_payload(original_history), 400)
    check('必填参数、未知页面和重复路由拒绝')

    suffix = secrets.token_hex(4)
    a = new_dir('导航回归A_' + suffix)
    b = new_dir('导航回归B_' + suffix)
    check('新增目录返回大编号字符串', isinstance(a['id'], str) and int(a['id']) > 2**53)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        jobs = [pool.submit(admin, 'PUT', '/system/menus/' + a['id'], menu_payload(a, parentId=b['id']), None), pool.submit(admin, 'PUT', '/system/menus/' + b['id'], menu_payload(b, parentId=a['id']), None)]
        codes = sorted(job.result()['code'] for job in jobs)
    check('并发互设父目录只有一项成功，另一项拒绝', codes == [200, 400])
    for row in [a, b]:
        admin('PUT', '/system/menus/' + row['id'], menu_payload(row, parentId='0'))
    admin('PUT', '/system/menus/' + b['id'], menu_payload(b, parentId=a['id']))
    admin('PUT', '/system/menus/' + original_history['id'], menu_payload(original_history, parentId=b['id'], menuName='导航回归历史', sortNo=88))
    admin('PUT', '/system/menus/' + original_history['id'], menu_payload(original_history, parentId=b['id'], menuName='导航回归历史', sortNo=88))
    result = visible(cm)
    check('三级目录、名称排序保存及重复更新持久化', {a['id'], b['id'], original_history['id']} <= {m['id'] for m in result} and next(m for m in result if m['id'] == original_history['id'])['sortNo'] == 88)
    before_migration = admin('GET', '/system/menus')
    before_roles = admin('GET', '/system/roles')
    project = Path(__file__).resolve().parents[2]
    for relative in ['db/incr/20260923_001_navigation_tree.sql', 'db/27_navigation_tree.sql']:
        migrated = subprocess.run(['docker', 'compose', '-f', str(project / 'compose.test.yml'), 'exec', '-T', '-e', 'MYSQL_PWD=root123', 'mysql', 'mysql', '-uroot', '--default-character-set=utf8mb4'], input=(project / relative).read_text(), text=True, capture_output=True)
        assert migrated.returncode == 0, '重复执行迁移失败：' + migrated.stderr[-500:]
    check('全量及增量迁移重复执行保留已有目录修改和角色配置', admin('GET', '/system/menus') == before_migration and admin('GET', '/system/roles') == before_roles)
    admin('PUT', '/system/menus/' + a['id'], menu_payload(a, parentId=b['id']), 400)
    admin('DELETE', '/system/menus/' + a['id'], expected=409)
    admin('DELETE', '/system/menus/' + original_history['id'], expected=409)
    check('循环、含子节点删除、已授权菜单删除均拒绝')
    admin('PUT', '/system/menus/' + a['id'], menu_payload(a, visible='HIDE'))
    check('隐藏目录保留授权且返回隐藏标记', next(m for m in visible(cm) if m['id'] == a['id'])['visible'] == 'HIDE')
    admin('PUT', '/system/menus/' + a['id'], menu_payload(a, status='DISABLE'))
    check('停用祖先阻断子页面', '/history' not in paths(cm))
    admin('PUT', '/system/menus/' + a['id'], menu_payload(a))

    for mid in ['1', '7', '15']:
        m = next(m for m in menu_rows if m['id'] == mid)
        admin('PUT', '/system/menus/' + mid, menu_payload(m, visible='HIDE'), 400)
        admin('DELETE', '/system/menus/' + mid, expected=400)
    check('三个基础入口隐藏和删除均拒绝')
    admin('PUT', '/system/roles/' + by_code['admin']['id'], {**by_code['admin'], 'status': 'DISABLE'}, 400)
    admin('DELETE', '/system/roles/' + by_code['secretary']['id'], expected=400)
    check('管理员停用和内置角色删除拒绝')

    role = by_code['customer_manager']
    original_roles[role['id']] = role
    ids = [s for s in role['menuIds'].split(',') if s != '2']
    save_role(role, ids)
    check('已登录用户撤销菜单后真实接口立即生效', '/application/loan' not in paths(cm) and '/application/deposit' in paths(cm))
    admin('PUT', '/system/roles/' + role['id'], {**role, 'menuIds': '6'}, 400)
    admin('PUT', '/system/roles/' + role['id'], {**role, 'menuIds': '999999999'}, 400)
    check('超出业务角色范围和不存在的菜单授权拒绝')
    save_role(role)
    check('恢复授权后旧会话重新获得入口', '/application/loan' in paths(cm))

    created_role = admin('POST', '/system/roles', {'roleCode': 'nav_' + suffix, 'roleName': '导航临时角色', 'menuIds': '5', 'status': 'ENABLE'})
    custom = new_user(created_role['roleCode'])
    check('自定义角色仅有基础工作台和授权历史页', paths(custom) == {'/overview', '/history'})
    save_role(created_role, status='DISABLE')
    check('停用角色取消导航授权', not paths(custom))
    save_role(created_role)
    admin('DELETE', '/system/roles/' + created_role['id'], expected=409)
    check('绑定用户的角色删除拒绝')
    print('真实接口场景执行完成', flush=True)
finally:
    errors = []
    def cleanup(label, action):
        try:
            action()
        except Exception:
            errors.append(label)
    if admin_token:
        if original_history:
            cleanup('恢复历史菜单', lambda: admin('PUT', '/system/menus/' + original_history['id'], menu_payload(original_history)))
        for role in original_roles.values():
            cleanup('恢复业务角色授权', lambda role=role: save_role(role))
        for token in user_tokens:
            cleanup('注销测试会话', lambda token=token: call('POST', '/auth/logout', token=token))
        for uid in users:
            cleanup('删除虚构账号', lambda uid=uid: admin('DELETE', '/system/users/' + str(uid)))
        if created_role:
            cleanup('删除临时角色', lambda: admin('DELETE', '/system/roles/' + created_role['id']))
        for row in created_dirs:
            cleanup('解除测试目录父子关系', lambda row=row: admin('PUT', '/system/menus/' + row['id'], menu_payload(row, parentId='0')))
        for row in created_dirs:
            cleanup('删除测试目录', lambda row=row: admin('DELETE', '/system/menus/' + row['id']))
        cleanup('注销管理员测试会话', lambda: call('POST', '/auth/logout', token=admin_token))
    if errors:
        raise RuntimeError('回归清理未完成：' + '、'.join(errors))
print(f'真实菜单回归通过：{len(checks)} 项；配置已恢复，虚构账号/角色/目录已逻辑删除，测试会话已注销。')
