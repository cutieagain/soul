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

package org.dromara.soul.client.springmvc.init;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.soul.client.common.utils.RegisterUtils;
import org.dromara.soul.client.springmvc.annotation.SoulSpringMvcClient;
import org.dromara.soul.client.springmvc.config.SoulSpringMvcConfig;
import org.dromara.soul.client.springmvc.dto.SpringMvcRegisterDTO;
import org.dromara.soul.client.springmvc.utils.ValidateUtils;
import org.dromara.soul.common.enums.RpcTypeEnum;
import org.dromara.soul.common.utils.GsonUtils;
import org.dromara.soul.common.utils.IpUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
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
 * The type Soul spring mvc client bean post processor.
 *
 * @author xiaoyu(Myth)
 */
@Slf4j
public class SpringMvcClientBeanPostProcessor implements BeanPostProcessor {

    // 线程池
    private final ThreadPoolExecutor executorService;

    // 注册的地址
    private final String url;

    // http的配置信息
    private final SoulSpringMvcConfig soulSpringMvcConfig;

    /**
     * Instantiates a new Soul client bean post processor.
     *
     * @param soulSpringMvcConfig the soul spring mvc config
     */
    // @SoulSpringMvcClient(path = "/order")
    // soulSpringMvcConfig 配置加载的地方 SoulSpringMvcClientConfiguration.soulHttpConfig 使用 @ConfigurationProperties(prefix = "soul.http")
    public SpringMvcClientBeanPostProcessor(final SoulSpringMvcConfig soulSpringMvcConfig) {
        // 检查是否配置正常 【contextPath 上下文路径； adminUrl 同步信息的admin地址； port http提供服务的端口】不能为空
        ValidateUtils.validate(soulSpringMvcConfig);
        this.soulSpringMvcConfig = soulSpringMvcConfig;
        // 注册的地址，需要修改为常量 cutie 20200120
        url = soulSpringMvcConfig.getAdminUrl() + "/soul-client/springmvc-register";
        // 线程池初始化 单线程的线程池，队列无限长 这个会不会注册很多个方法，会不会有性能问题？需要测试下 cutie 20200120
        executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    // 初始化结束后要做的操作
    @Override
    public Object postProcessAfterInitialization(@NonNull final Object bean, @NonNull final String beanName) throws BeansException {
        // 如果isFull是true，则提供所有服务的代理
        if (soulSpringMvcConfig.isFull()) {
            return bean;
        }
        // @Controller
        Controller controller = AnnotationUtils.findAnnotation(bean.getClass(), Controller.class);
        // @RestController
        RestController restController = AnnotationUtils.findAnnotation(bean.getClass(), RestController.class);
        // @RequestMapping("/order")
        RequestMapping requestMapping = AnnotationUtils.findAnnotation(bean.getClass(), RequestMapping.class);
        if (controller != null || restController != null || requestMapping != null) {
            // @SoulSpringMvcClient(path = "/order") Controller上的soul注解
            SoulSpringMvcClient clazzAnnotation = AnnotationUtils.findAnnotation(bean.getClass(), SoulSpringMvcClient.class);
            String prePath = "";
            if (Objects.nonNull(clazzAnnotation)) {
                if (clazzAnnotation.path().indexOf("*") > 1) {
                    String finalPrePath = prePath;
                    // 注册Controller
                    executorService.execute(() -> RegisterUtils.doRegister(buildJsonParams(clazzAnnotation, finalPrePath), url,
                            RpcTypeEnum.HTTP));
                    return bean;
                }
                prePath = clazzAnnotation.path();
            }
            final Method[] methods = ReflectionUtils.getUniqueDeclaredMethods(bean.getClass());
            for (Method method : methods) {
                // @SoulSpringMvcClient(path = "/findById", desc = "Find by id") Method上的soul注解
                SoulSpringMvcClient soulSpringMvcClient = AnnotationUtils.findAnnotation(method, SoulSpringMvcClient.class);
                if (Objects.nonNull(soulSpringMvcClient)) {
                    String finalPrePath = prePath;
                    // 注册Method
                    executorService.execute(() -> RegisterUtils.doRegister(buildJsonParams(soulSpringMvcClient, finalPrePath), url,
                            RpcTypeEnum.HTTP));
                }
            }
        }
        return bean;
    }

    // 参数构建的方法，Json字符串
    private String buildJsonParams(final SoulSpringMvcClient soulSpringMvcClient, final String prePath) {
        String contextPath = soulSpringMvcConfig.getContextPath();
        String appName = soulSpringMvcConfig.getAppName();
        Integer port = soulSpringMvcConfig.getPort();
        String path = contextPath + prePath + soulSpringMvcClient.path();
        String desc = soulSpringMvcClient.desc();
        String configHost = soulSpringMvcConfig.getHost();
        // 配置的host是否存在，不存在的话就使用IpUtils
        String host = StringUtils.isBlank(configHost) ? IpUtils.getHost() : configHost;
        String configRuleName = soulSpringMvcClient.ruleName();
        String ruleName = StringUtils.isBlank(configRuleName) ? path : configRuleName;
        SpringMvcRegisterDTO registerDTO = SpringMvcRegisterDTO.builder()
                .context(contextPath)
                .host(host)
                .port(port)
                .appName(appName)
                .path(path)
                .pathDesc(desc)
                .rpcType(soulSpringMvcClient.rpcType())
                .enabled(soulSpringMvcClient.enabled())
                .ruleName(ruleName)
                .registerMetaData(soulSpringMvcClient.registerMetaData())
                .build();
        // 原来是用的这个
//        return OkHttpTools.getInstance().getGson().toJson(registerDTO);
        // 改成这个会不会好一点？而且OkHttpTools中有声明Gson对象是否可以删除？ cutie 20200120
        return GsonUtils.getInstance().toJson(registerDTO);
    }
}


