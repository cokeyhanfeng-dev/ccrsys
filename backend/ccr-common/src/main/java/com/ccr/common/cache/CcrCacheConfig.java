package com.ccr.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 缓存基础设施(详设 §3.6)。
 * <p>key 用 String 序列化,value 用 Jackson JSON(复用 Spring Boot 已配置的 ObjectMapper,支持 LocalDateTime)。
 * 仅 {@code ccr.cache.enabled=true} 时创建模板与工具(Redis 未就绪可关开关直查库)。</p>
 */
@Configuration
@EnableConfigurationProperties(CcrCacheProperties.class)
public class CcrCacheConfig {

    @Bean
    public RedisTemplate<String, Object> ccrRedisTemplate(RedisConnectionFactory connectionFactory,
                                                          ObjectMapper objectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public CcrCacheUtil ccrCacheUtil(RedisTemplate<String, Object> ccrRedisTemplate, CcrCacheProperties properties) {
        return new CcrCacheUtil(ccrRedisTemplate, properties);
    }
}
