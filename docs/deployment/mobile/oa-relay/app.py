"""OA 中转示例：ticket 换 CCR 移动 token，再重定向至移动入口。"""
import os
from urllib.parse import urlencode, urlsplit
import requests
from flask import Flask, abort, redirect, request

app = Flask(__name__)
BACKEND = os.environ['CCR_BACKEND'].rstrip('/')
ENTRY = os.environ['CCR_MOBILE_ENTRY']
for address in (BACKEND, ENTRY):
    parsed = urlsplit(address)
    if parsed.scheme not in ('http', 'https') or not parsed.netloc or parsed.username or parsed.query or parsed.fragment:
        raise ValueError('CCR 地址必须为固定 HTTP(S) 地址，不含凭证、查询串或片段')

@app.after_request
def private_response(response):
    response.headers['Cache-Control'] = 'no-store'
    response.headers['Referrer-Policy'] = 'no-referrer'
    return response

@app.get('/oa/approval/identify')
def identify():
    tickets = request.args.getlist('ticket')
    if len(tickets) != 1 or not tickets[0].strip() or len(tickets[0]) > 4096:
        abort(400, description='OA ticket 缺失或无效')
    try:
        response = requests.post(BACKEND + '/mobile/oa/login', json={'ticket': tickets[0]},
                                 timeout=(3, 15), allow_redirects=False)
        if response.status_code != 200:
            abort(502, description='审批登录服务暂不可用')
        result = response.json()
    except (requests.RequestException, ValueError):
        abort(502, description='审批登录服务暂不可用')
    if not isinstance(result, dict):
        abort(502, description='审批登录响应异常')
    code = result.get('code')
    if code == 403:
        abort(403, description='当前账号暂无移动审批权限，请联系管理员')
    if code == 401:
        abort(401, description='OA 身份已失效，请从 OA 工作台重新打开')
    if code != 200:
        abort(502, description='审批登录失败，请稍后重试')
    data = result.get('data')
    token = data.get('token') if isinstance(data, dict) else None
    if not isinstance(token, str) or not token or len(token) > 4096 or any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in token):
        abort(502, description='审批系统未返回有效令牌')
    return redirect(ENTRY + '?' + urlencode({'token': token, 'source': 'oa'}), code=302)
