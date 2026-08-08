package com.ccr.common.cache;

import com.ccr.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** CacheItem 精确/前缀匹配与编码解析(§3.6) */
class CacheItemTest {

    @Test
    void matchExactKey() {
        assertEquals(CacheItem.LPR_EFFECTIVE, CacheItem.match("ccr:cfg:lpr:effective"));
        assertEquals(CacheItem.MATRIX_EFFECTIVE, CacheItem.match("ccr:cfg:matrix:effective"));
    }

    @Test
    void matchPrefixKey() {
        assertEquals(CacheItem.RATE_LIMIT, CacheItem.match("ccr:cfg:rate-limit:LOAN:PUB_LOAN_01"));
        assertEquals(CacheItem.RATE_LIMIT, CacheItem.match("ccr:cfg:rate-limit:DEPOSIT:XXX"));
    }

    @Test
    void matchNoHit() {
        assertNull(CacheItem.match("ccr:cfg:v"));
        assertNull(CacheItem.match("ccr:other"));
    }

    @Test
    void fromCode() {
        assertEquals(CacheItem.RATE_LIMIT, CacheItem.fromCode("rate-limit"));
        assertThrows(ServiceException.class, () -> CacheItem.fromCode("unknown"));
    }
}
