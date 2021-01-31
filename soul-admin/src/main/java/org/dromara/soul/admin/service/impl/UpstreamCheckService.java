/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dromara.soul.admin.service.impl;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.dromara.soul.admin.entity.PluginDO;
import org.dromara.soul.admin.entity.SelectorDO;
import org.dromara.soul.admin.listener.DataChangedEvent;
import org.dromara.soul.admin.mapper.PluginMapper;
import org.dromara.soul.admin.mapper.SelectorConditionMapper;
import org.dromara.soul.admin.mapper.SelectorMapper;
import org.dromara.soul.admin.query.SelectorConditionQuery;
import org.dromara.soul.admin.transfer.ConditionTransfer;
import org.dromara.soul.common.concurrent.SoulThreadFactory;
import org.dromara.soul.common.dto.ConditionData;
import org.dromara.soul.common.dto.SelectorData;
import org.dromara.soul.common.dto.convert.DivideUpstream;
import org.dromara.soul.common.enums.ConfigGroupEnum;
import org.dromara.soul.common.enums.DataEventTypeEnum;
import org.dromara.soul.common.enums.PluginEnum;
import org.dromara.soul.common.utils.GsonUtils;
import org.dromara.soul.common.utils.UpstreamCheckUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * this is divide  http url upstream.
 *
 * @author xiaoyu
 */
@Slf4j
@Component
public class UpstreamCheckService {

    // 缓存被代理的服务信息
    private static final Map<String, List<DivideUpstream>> UPSTREAM_MAP = Maps.newConcurrentMap();

    // 是否配置ip端口探活，admin->代理服务
    @Value("${soul.upstream.check:true}")
    private boolean check;

    // 定时扫描的时间
    @Value("${soul.upstream.scheduledTime:10}")
    private int scheduledTime;

    // 选择器数据库操作
    private final SelectorMapper selectorMapper;

    // spring事件发布
    private final ApplicationEventPublisher eventPublisher;

    // 插件数据库操作
    private final PluginMapper pluginMapper;

    // 选择器条件数据库操作
    private final SelectorConditionMapper selectorConditionMapper;
    
    /**
     * Instantiates a new Upstream check service.
     *
     * @param selectorMapper            the selector mapper
     * @param eventPublisher            the event publisher
     * @param pluginMapper              the plugin mapper
     * @param selectorConditionMapper   the selectorCondition mapper
     */
    @Autowired(required = false)
    public UpstreamCheckService(final SelectorMapper selectorMapper, final ApplicationEventPublisher eventPublisher,
                                final PluginMapper pluginMapper, final SelectorConditionMapper selectorConditionMapper) {
        this.selectorMapper = selectorMapper;
        this.eventPublisher = eventPublisher;
        this.pluginMapper = pluginMapper;
        this.selectorConditionMapper = selectorConditionMapper;
    }
    
    /**
     * Setup selectors of divide plugin.
     */
    // 初始化
    @PostConstruct
    public void setup() {
        // 获取DIVIDE插件信息
        PluginDO pluginDO = pluginMapper.selectByName(PluginEnum.DIVIDE.getName());
        if (pluginDO != null) {
            // 获取divide插件的选择器列表
            List<SelectorDO> selectorDOList = selectorMapper.findByPluginId(pluginDO.getId());
            for (SelectorDO selectorDO : selectorDOList) {
                // 获取数据库保存的divide代理服务列表
                List<DivideUpstream> divideUpstreams = GsonUtils.getInstance().fromList(selectorDO.getHandle(), DivideUpstream.class);
                if (CollectionUtils.isNotEmpty(divideUpstreams)) {
                    // 被代理的服务信息缓存
                    UPSTREAM_MAP.put(selectorDO.getName(), divideUpstreams);
                }
            }
        }
        // 根据ip端口进行服务探活
        if (check) {
            new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors(), SoulThreadFactory.create("scheduled-upstream-task", false))
                    .scheduleWithFixedDelay(this::scheduled, 10, scheduledTime, TimeUnit.SECONDS);
        }
    }
    
    /**
     * Remove by key.
     *
     * @param selectorName the selector name
     */
    public static void removeByKey(final String selectorName) {
        UPSTREAM_MAP.remove(selectorName);
    }
    
    /**
     * Submit.
     *
     * @param selectorName   the selector name
     * @param divideUpstream the divide upstream
     */
    public void submit(final String selectorName, final DivideUpstream divideUpstream) {
        if (UPSTREAM_MAP.containsKey(selectorName)) {
            UPSTREAM_MAP.get(selectorName).add(divideUpstream);
        } else {
            UPSTREAM_MAP.put(selectorName, Lists.newArrayList(divideUpstream));
        }
    }

    /**
     * Replace.
     *
     * @param selectorName    the selector name
     * @param divideUpstreams the divide upstream list
     */
    public void replace(final String selectorName, final List<DivideUpstream> divideUpstreams) {
        UPSTREAM_MAP.put(selectorName, divideUpstreams);
    }

    // 一个一个遍历进行探活，定时
    private void scheduled() {
        if (UPSTREAM_MAP.size() > 0) {
            UPSTREAM_MAP.forEach(this::check);
        }
    }

    // 具体的探活操作
    private void check(final String selectorName, final List<DivideUpstream> upstreamList) {
        // 探活成功的服务提供方列表
        List<DivideUpstream> successList = Lists.newArrayListWithCapacity(upstreamList.size());
        // 默认保存的服务提供方列表
        for (DivideUpstream divideUpstream : upstreamList) {
            final boolean pass = UpstreamCheckUtils.checkUrl(divideUpstream.getUpstreamUrl());
            if (pass) {
                // 测试通过
                if (!divideUpstream.isStatus()) {
                    divideUpstream.setTimestamp(System.currentTimeMillis());
                    divideUpstream.setStatus(true);
                    log.info("UpstreamCacheManager check success the url: {}, host: {} ", divideUpstream.getUpstreamUrl(), divideUpstream.getUpstreamHost());
                }
                successList.add(divideUpstream);
            } else {
                // 测试不通过
                divideUpstream.setStatus(false);
                log.error("check the url={} is fail ", divideUpstream.getUpstreamUrl());
            }
        }
        // 长度相等，表示不用更新配置
        if (successList.size() == upstreamList.size()) {
            return;
        }
        if (successList.size() > 0) {
            // 长度大于0，更新配置
            UPSTREAM_MAP.put(selectorName, successList);
            updateSelectorHandler(selectorName, successList);
        } else {
            // 长度等于0，移除配置
            UPSTREAM_MAP.remove(selectorName);
            updateSelectorHandler(selectorName, null);
        }
    }

    private void updateSelectorHandler(final String selectorName, final List<DivideUpstream> upstreams) {
        // 选择器信息
        SelectorDO selectorDO = selectorMapper.selectByName(selectorName);
        if (Objects.nonNull(selectorDO)) {
            // 获取选择器条件列表
            List<ConditionData> conditionDataList = ConditionTransfer.INSTANCE.mapToSelectorDOS(
                    selectorConditionMapper.selectByQuery(new SelectorConditionQuery(selectorDO.getId())));
            // 获取插件信息
            PluginDO pluginDO = pluginMapper.selectById(selectorDO.getPluginId());
            // 服务提供列表转json
            String handler = CollectionUtils.isEmpty(upstreams) ? "" : GsonUtils.getInstance().toJson(upstreams);
            selectorDO.setHandle(handler);
            // 更新选择器
            selectorMapper.updateSelective(selectorDO);
            if (Objects.nonNull(pluginDO)) {
                // 插件更新通知发布
                SelectorData selectorData = SelectorDO.transFrom(selectorDO, pluginDO.getName(), conditionDataList);
                selectorData.setHandle(handler);
                // publish change event.
                eventPublisher.publishEvent(new DataChangedEvent(ConfigGroupEnum.SELECTOR, DataEventTypeEnum.UPDATE,
                                                                 Collections.singletonList(selectorData)));
            }
        }
    }
}
