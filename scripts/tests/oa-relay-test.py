"""仅使用 Flask 测试客户端与模拟 CCR 响应，不连接生产。"""
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch, Mock
from urllib.parse import urlsplit, parse_qs
os.environ['CCR_BACKEND'] = 'http://ccr.example.invalid:8080'
os.environ['CCR_MOBILE_ENTRY'] = 'http://mobile.example.invalid:8090/mobile/'
path = Path(__file__).resolve().parents[2] / 'docs/deployment/mobile/oa-relay/app.py'
spec = importlib.util.spec_from_file_location('relay', path)
relay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relay)

class RelayTest(unittest.TestCase):
    def setUp(self):
        self.client = relay.app.test_client()
    def reply(self, value, status=200):
        return Mock(status_code=status, json=Mock(return_value=value))
    def test_redirect_uses_ccr_token_and_fixed_mobile_entry(self):
        with patch.object(relay.requests, 'post', return_value=self.reply({'code':200,'data':{'token':'fixture+a&b'}})) as post:
            response = self.client.get('/oa/approval/identify?ticket=oa%2Bfixture')
            self.assertEqual(response.status_code,302)
            target = urlsplit(response.headers['Location'])
            self.assertEqual(target.netloc,'mobile.example.invalid:8090')
            self.assertEqual(parse_qs(target.query),{'token':['fixture+a&b'],'source':['oa']})
            self.assertEqual(post.call_args.kwargs['json'],{'ticket':'oa+fixture'})
            self.assertFalse(post.call_args.kwargs['allow_redirects'])
            self.assertEqual(response.headers['Cache-Control'],'no-store')
            self.assertEqual(response.headers['Referrer-Policy'],'no-referrer')
    def test_bad_ticket_never_contacts_backend(self):
        with patch.object(relay.requests,'post') as post:
            for query in ['', '?ticket=', '?ticket=a&ticket=b']:
                self.assertEqual(self.client.get('/oa/approval/identify'+query).status_code,400)
            post.assert_not_called()
    def test_rejected_or_malformed_response_never_redirects(self):
        for payload,expected in [({'code':401},401),({'code':403},403),({'code':500},502),([],502),({'code':200,'data':{}},502),({'code':200,'data':{'token':'a\nb'}},502)]:
            with self.subTest(payload=payload), patch.object(relay.requests,'post',return_value=self.reply(payload)):
                response=self.client.get('/oa/approval/identify?ticket=test')
                self.assertEqual(response.status_code,expected)
                self.assertNotIn('Location',response.headers)
    def test_transport_error_and_upstream_redirect_fail_closed(self):
        for response in [self.reply({},302), self.reply({},500), Mock(status_code=200,json=Mock(side_effect=ValueError()))]:
            with patch.object(relay.requests,'post',return_value=response):
                self.assertEqual(self.client.get('/oa/approval/identify?ticket=test').status_code,502)
        with patch.object(relay.requests,'post',side_effect=relay.requests.Timeout()):
            self.assertEqual(self.client.get('/oa/approval/identify?ticket=test').status_code,502)
if __name__=='__main__': unittest.main()
