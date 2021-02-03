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

package org.dromara.soul.plugin.grpc.loadbalance;

import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * SubChannelCopy.
 *
 * @author zhanglei
 */
@EqualsAndHashCode
@Getter
public class SubChannelCopy {

    private final int weight;

    private final LoadBalancer.Subchannel channel;

    private final EquivalentAddressGroup addressGroup;

    private final ConnectivityStateInfo state;

    public SubChannelCopy(final LoadBalancer.Subchannel channel) {
        this.channel = channel;
        this.addressGroup = channel.getAddresses();
        this.weight = SubChannels.getWeight(channel);
        this.state = SubChannels.getStateInfo(channel);
    }
}
