package com.ccr.admin.mobile;

import com.ccr.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** 服务端向固定有度服务验票，账号只从验票响应获取，禁止接收前端自报账号。 */
@Service
public class YouduIdentityService {
    private final YouduProperties properties;
    private final ObjectMapper mapper;
    public YouduIdentityService(YouduProperties properties, ObjectMapper mapper) {
        this.properties=properties; this.mapper=mapper;
    }
    public String verify(String token) {
        if (!properties.isEnabled()) throw new ServiceException(503,"有度免密登录尚未开通，请联系管理员");
        if(token==null||token.isBlank()||token.length()>4096) throw new ServiceException(401,"有度身份凭证无效，请重新打开应用");
        String template=properties.getVerifyUrl();
        if(template==null||template.indexOf("{token}")<0||template.indexOf("{token}")!=template.lastIndexOf("{token}"))
            throw new ServiceException(503,"有度验票服务配置不完整");
        HttpURLConnection connection=null;
        try {
            URI uri=URI.create(template.replace("{token}",URLEncoder.encode(token, StandardCharsets.UTF_8)));
            if(!java.util.Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)
                throw new ServiceException(503,"有度验票服务配置无效");
            connection=(HttpURLConnection)uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Math.max(1,Math.min(properties.getConnectTimeoutMillis(),10000)));
            connection.setReadTimeout(Math.max(1,Math.min(properties.getReadTimeoutMillis(),15000)));
            connection.setRequestProperty("Accept","application/json");
            connection.setUseCaches(false);
            if(connection.getResponseCode()!=200) throw new ServiceException(503,"有度验票服务暂不可用，请稍后重试");
            try(var stream=connection.getInputStream()) {
                byte[] bytes=stream.readNBytes(65537);
                if(bytes.length>65536) throw new ServiceException(503,"有度验票响应异常");
                return verifiedAccount(bytes);
            }
        } catch(ServiceException e) { throw e;
        } catch(Exception e) {
            // 异常原因可能包含完整 URL/token，禁止记录或向客户端透传。
            throw new ServiceException(503,"有度身份验证失败，请稍后重试");
        } finally {if(connection!=null)connection.disconnect();}
    }
    String verifiedAccount(byte[] bytes) {
        try {
            var root=mapper.readTree(bytes);
            var code=root.path("status").path("code");
            var account=root.path("userInfo").path("account");
            if(!code.isIntegralNumber()||!code.canConvertToInt()||code.intValue()!=properties.getSuccessCode()
                    ||!account.isTextual()||account.asText().isBlank()||account.asText().length()>100)
                throw new ServiceException(401,"有度身份凭证无效或已过期，请重新打开应用");
            return account.asText().trim();
        } catch(ServiceException e) {throw e;} catch(Exception e) {throw new ServiceException(503,"有度验票响应异常");}
    }
}
