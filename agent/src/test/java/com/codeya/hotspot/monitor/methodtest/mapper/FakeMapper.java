package com.codeya.hotspot.monitor.methodtest.mapper;

/** 模拟业务 Mapper 接口（MyBatis 动态代理的目标接口） */
public interface FakeMapper {

    String getPortalIds(String userId);
}
