package com.ccr.admin.mobile;

import com.ccr.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class YouduTransportTest {
    @Test void verifiesEncodedTokenAndRefusesRedirectOrExpiredIdentity() throws Exception {
        var query=new AtomicReference<String>();var body=new AtomicReference<>("{\"status\":{\"code\":0},\"userInfo\":{\"account\":\"approver\"}}");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/verify",ex->{query.set(ex.getRequestURI().getRawQuery());byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,bytes.length);try(var out=ex.getResponseBody()){out.write(bytes);}});
        server.createContext("/redirect",ex->{ex.getResponseHeaders().add("Location","/verify");ex.sendResponseHeaders(302,-1);ex.close();});server.start();
        try {
            var p=new YouduProperties();p.setEnabled(true);String base="http://127.0.0.1:"+server.getAddress().getPort();p.setVerifyUrl(base+"/verify?token={token}");
            var service=new YouduIdentityService(p,new ObjectMapper());
            assertEquals("approver",service.verify("a+b&c=#"));assertEquals("token=a%2Bb%26c%3D%23",query.get());
            body.set("{\"status\":{\"code\":401},\"userInfo\":{\"account\":\"approver\"}}");assertThrows(ServiceException.class,()->service.verify("expired"));
            p.setVerifyUrl(base+"/redirect?token={token}");assertThrows(ServiceException.class,()->service.verify("do-not-follow"));
            p.setVerifyUrl(base+"/verify?token={token}");body.set("x".repeat(65537));assertThrows(ServiceException.class,()->service.verify("oversized"));
        }finally{server.stop(0);}
    }
}
