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

package org.dromara.soul.client.springcloud.init;

import lombok.extern.slf4j.Slf4j;
import org.dromara.soul.client.common.utils.OkHttpTools;
import org.dromara.soul.client.common.utils.RegisterUtils;
import org.dromara.soul.client.springcloud.annotation.SoulSpringCloudClient;
import org.dromara.soul.client.springcloud.config.SoulSpringCloudConfig;
import org.dromara.soul.client.springcloud.dto.SpringCloudRegisterDTO;
import org.dromara.soul.client.springcloud.utils.ValidateUtils;
import org.dromara.soul.common.enums.RpcTypeEnum;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The type Soul client bean post processor.
 *
 * @author xiaoyu(Myth)
 */
// 加载springcloud使用的bean
@Slf4j
public class SpringCloudClientBeanPostProcessor implements BeanPostProcessor {

    private final ThreadPoolExecutor executorService;

    private final String url;

    private final SoulSpringCloudConfig config;

    private final Environment env;

    /**
     * Instantiates a new Soul client bean post processor.
     *
     * @param config the soul spring cloud config
     * @param env    the env
     */
    // 初始化配置获取
    public SpringCloudClientBeanPostProcessor(final SoulSpringCloudConfig config, final Environment env) {
        ValidateUtils.validate(config, env);
        this.config = config;
        this.env = env;
        // 注册地址
        this.url = config.getAdminUrl() + "/soul-client/springcloud-register";
        executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    // 注册
    @Override
    public Object postProcessAfterInitialization(@NonNull final Object bean, @NonNull final String beanName) throws BeansException {
        // 如果是全部代理
        if (config.isFull()) {
            return bean;
        }
        // 请求的controller注解获取
        Controller controller = AnnotationUtils.findAnnotation(bean.getClass(), Controller.class);
        RestController restController = AnnotationUtils.findAnnotation(bean.getClass(), RestController.class);
        RequestMapping requestMapping = AnnotationUtils.findAnnotation(bean.getClass(), RequestMapping.class);
        // 有一个就获取一个
        if (controller != null || restController != null || requestMapping != null) {
            String prePath = "";
            // 找到controller上的注解
            SoulSpringCloudClient clazzAnnotation = AnnotationUtils.findAnnotation(bean.getClass(), SoulSpringCloudClient.class);
            // 如果存在注解则代理
            if (Objects.nonNull(clazzAnnotation)) {
                // 如果是全部都代理
                if (clazzAnnotation.path().indexOf("*") > 1) {
                    // 路径都进行注册
                    String finalPrePath = prePath;
                    executorService.execute(() -> RegisterUtils.doRegister(buildJsonParams(clazzAnnotation, finalPrePath), url,
                            RpcTypeEnum.SPRING_CLOUD));
                    return bean;
                }
                // 不然就注册注解的路径
                prePath = clazzAnnotation.path();
            }
            // 需要代理的方法数组
            final Method[] methods = ReflectionUtils.getUniqueDeclaredMethods(bean.getClass());
            for (Method method : methods) {
                // 找到方法代理
                SoulSpringCloudClient soulSpringCloudClient = AnnotationUtils.findAnnotation(method, SoulSpringCloudClient.class);
                // 存在方法代理
                if (Objects.nonNull(soulSpringCloudClient)) {
                    String finalPrePath = prePath;
                    // 进行注册
                    executorService.execute(() -> RegisterUtils.doRegister(buildJsonParams(soulSpringCloudClient, finalPrePath), url,
                            RpcTypeEnum.SPRING_CLOUD));
                }
            }
        }
        return bean;
    }

    // 封装注册dto
    private String buildJsonParams(final SoulSpringCloudClient soulSpringCloudClient, final String prePath) {
        String contextPath = config.getContextPath();
        String appName = env.getProperty("spring.application.name");
        String path = contextPath + prePath + soulSpringCloudClient.path();
        String desc = soulSpringCloudClient.desc();
        String configRuleName = soulSpringCloudClient.ruleName();
        String ruleName = ("".equals(configRuleName)) ? path : configRuleName;
        SpringCloudRegisterDTO registerDTO = SpringCloudRegisterDTO.builder()
                .context(contextPath)
                .appName(appName)
                .path(path)
                .pathDesc(desc)
                .rpcType(soulSpringCloudClient.rpcType())
                .enabled(soulSpringCloudClient.enabled())
                .ruleName(ruleName)
                .build();
        return OkHttpTools.getInstance().getGson().toJson(registerDTO);
    }
}


