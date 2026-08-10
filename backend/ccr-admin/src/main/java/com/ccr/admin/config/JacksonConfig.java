package com.ccr.admin.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局配置:Long/Long 包装类型序列化为字符串。
 * 雪花主键为 19 位 Long,超出 JS Number.MAX_SAFE_INTEGER(2^53),
 * 前端 JSON.parse 会丢精度导致"申请不存在"类错误;统一转字符串输出,前端按字符串处理即可。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}
