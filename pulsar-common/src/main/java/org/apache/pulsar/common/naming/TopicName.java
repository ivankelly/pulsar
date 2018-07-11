/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.common.naming;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.common.util.Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * Encapsulate the parsing of the completeTopicName name.
 */
public class TopicName implements ServiceUnitId {

    private static final Logger log = LoggerFactory.getLogger(TopicName.class);

    public static final String PUBLIC_TENANT = "public";
    public static final String DEFAULT_NAMESPACE = "default";

    private static final String PARTITIONED_TOPIC_SUFFIX = "-partition-";

    private final String completeTopicName;

    private final TopicDomain domain;
    private final String tenant;
    private final String cluster;
    private final String namespacePortion;
    private final String localName;

    private final NamespaceName namespaceName;

    private final int partitionIndex;

    private static final LoadingCache<String, TopicName> cache = CacheBuilder.newBuilder().maximumSize(100000)
            .expireAfterAccess(30, TimeUnit.MINUTES).build(new CacheLoader<String, TopicName>() {
                @Override
                public TopicName load(String name) throws Exception {
                    return new TopicName(name);
                }
            });

    public static TopicName get(String domain, NamespaceName namespaceName, String topic) {
        String name = domain + "://" + namespaceName.toString() + '/' + topic;
        return TopicName.get(name);
    }

    public static TopicName get(String domain, String tenant, String namespace, String topic) {
        String name = domain + "://" + tenant + '/' + namespace + '/' + topic;
        return TopicName.get(name);
    }

    public static TopicName get(String domain, String tenant, String cluster, String namespace,
            String topic) {
        String name = domain + "://" + tenant + '/' + cluster + '/' + namespace + '/' + topic;
        return TopicName.get(name);
    }

    public static TopicName get(String topic) {
        try {
            return cache.get(topic);
        } catch (ExecutionException e) {
            throw (RuntimeException) e.getCause();
        } catch (UncheckedExecutionException e) {
            throw (RuntimeException) e.getCause();
        }
    }

    public static boolean isValid(String topic) {
        try {
            get(topic);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private TopicName(String completeTopicName) {
        try {
            // The topic name can be in two different forms, one is fully qualified topic name,
            // the other one is short topic name
            if (!completeTopicName.contains("://")) {
                // The short topic name can be:
                // - <topic>
                // - <property>/<namespace>/<topic>
                String[] parts = StringUtils.split(completeTopicName, '/');
                if (parts.length == 3) {
                    completeTopicName = TopicDomain.persistent.name() + "://" + completeTopicName;
                } else if (parts.length == 1) {
                    completeTopicName = TopicDomain.persistent.name() + "://" + PUBLIC_TENANT + "/" + DEFAULT_NAMESPACE + "/" + parts[0];
                } else {
                    throw new IllegalArgumentException(
                        "Invalid short topic name '" + completeTopicName + "', it should be in the format of "
                        + "<tenant>/<namespace>/<topic> or <topic>");
                }
            }
            this.completeTopicName = completeTopicName;

            // The fully qualified topic name can be in two different forms:
            // new:    persistent://tenant/namespace/topic
            // legacy: persistent://tenant/cluster/namespace/topic

            List<String> parts = Splitter.on("://").limit(2).splitToList(completeTopicName);
            this.domain = TopicDomain.getEnum(parts.get(0));

            String rest = parts.get(1);

            // The rest of the name can be in different forms:
            // new:    tenant/namespace/<localName>
            // legacy: tenant/cluster/namespace/<localName>
            // Examples of localName:
            // 1. some/name/xyz//
            // 2. /xyz-123/feeder-2


            parts = Splitter.on("/").limit(4).splitToList(rest);
            if (parts.size() == 3) {
                // New topic name without cluster name
                this.tenant = parts.get(0);
                this.cluster = null;
                this.namespacePortion = parts.get(1);
                this.localName = parts.get(2);
                this.partitionIndex = getPartitionIndex(completeTopicName);
                this.namespaceName = NamespaceName.get(tenant, namespacePortion);
            } else if (parts.size() == 4) {
                // Legacy topic name that includes cluster name
                this.tenant = parts.get(0);
                this.cluster = parts.get(1);
                this.namespacePortion = parts.get(2);
                this.localName = parts.get(3);
                this.partitionIndex = getPartitionIndex(completeTopicName);
                this.namespaceName = NamespaceName.get(tenant, cluster, namespacePortion);
            } else {
                throw new IllegalArgumentException("Invalid topic name: " + completeTopicName);
            }


            if (localName == null || localName.isEmpty()) {
                throw new IllegalArgumentException("Invalid topic name: " + completeTopicName);
            }
        } catch (NullPointerException e) {
            throw new IllegalArgumentException("Invalid topic name: " + completeTopicName, e);
        }

    }

    public boolean isPersistent() {
        return TopicDomain.persistent == domain;
    }

    /**
     * Extract the namespace portion out of a completeTopicName name.
     *
     * Works both with old & new convention.
     *
     * @return the namespace
     */
    public String getNamespace() {
        return namespaceName.toString();
    }

    /**
     * Get the namespace object that this completeTopicName belongs to
     *
     * @return namespace object
     */
    @Override
    public NamespaceName getNamespaceObject() {
        return namespaceName;
    }

    public TopicDomain getDomain() {
        return domain;
    }

    public String getTenant() {
        return tenant;
    }

    @Deprecated
    public String getCluster() {
        return cluster;
    }

    public String getNamespacePortion() {
        return namespacePortion;
    }

    public String getLocalName() {
        return localName;
    }

    public String getEncodedLocalName() {
        return Codec.encode(localName);
    }

    public TopicName getPartition(int index) {
        if (index == -1 || this.toString().contains(PARTITIONED_TOPIC_SUFFIX)) {
            return this;
        }
        String partitionName = this.toString() + PARTITIONED_TOPIC_SUFFIX + index;
        return get(partitionName);
    }

    /**
     * @return partition index of the completeTopicName. It returns -1 if the completeTopicName (topic) is not partitioned.
     */
    public int getPartitionIndex() {
        return partitionIndex;
    }

    public boolean isPartitioned() {
        return partitionIndex != -1;
    }

    /**
     * For partitions in a topic, return the base partitioned topic name
     * Eg:
     * <ul>
     *  <li><code>persistent://prop/cluster/ns/my-topic-partition-1</code> --> <code>persistent://prop/cluster/ns/my-topic</code>
     *  <li><code>persistent://prop/cluster/ns/my-topic</code> --> <code>persistent://prop/cluster/ns/my-topic</code>
     * </ul>
     */
    public String getPartitionedTopicName() {
        if (isPartitioned()) {
            return completeTopicName.substring(0, completeTopicName.lastIndexOf("-partition-"));
        } else {
            return completeTopicName;
        }
    }

    /**
     * @return partition index of the completeTopicName. It returns -1 if the completeTopicName (topic) is not partitioned.
     */
    public static int getPartitionIndex(String topic) {
        int partitionIndex = -1;
        if (topic.contains(PARTITIONED_TOPIC_SUFFIX)) {
            try {
                partitionIndex = Integer.parseInt(topic.substring(topic.lastIndexOf('-') + 1));
            } catch (NumberFormatException nfe) {
                log.warn("Could not get the partition index from the topic {}", topic);
            }
        }

        return partitionIndex;
    }

    /**
     * Returns the http rest path for use in the admin web service
     * Eg:
     *
     *   * "persistent/my-tenant/my-namespace/my-topic"
     *   * "non-persistent/my-tenant/my-namespace/my-topic"
     *
     * @return topic rest path
     */
    public String getRestPath() {
        if (isV2()) {
            return String.format("%s/%s/%s/%s", domain, tenant, namespacePortion, getEncodedLocalName());
        } else {
            return String.format("%s/%s/%s/%s/%s", domain, tenant, cluster, namespacePortion, getEncodedLocalName());
        }
    }

    /**
     * Returns the name of the persistence resource associated with the completeTopicName.
     *
     * @return the relative path to be used in persistence
     */
    public String getPersistenceNamingEncoding() {
        // The convention is: domain://tenant/namespace/topic
        // We want to persist in the order: tenant/namespace/domain/topic

        // For legacy naming scheme, the convention is: domain://tenant/cluster/namespace/topic
        // We want to persist in the order: tenant/cluster/namespace/domain/topic
        if (isV2()) {
            return String.format("%s/%s/%s/%s", tenant, namespacePortion, domain, getEncodedLocalName());
        } else {
            return String.format("%s/%s/%s/%s/%s", tenant, cluster, namespacePortion, domain, getEncodedLocalName());
        }
    }

    /**
     * Get a string suitable for completeTopicName lookup
     * <p>
     * Example:
     * <p>
     * persistent://tenant/cluster/namespace/completeTopicName -> persistent/tenant/cluster/namespace/completeTopicName
     *
     * @return
     */
    public String getLookupName() {
        if (isV2()) {
            return String.format("%s/%s/%s/%s", domain, tenant, namespacePortion, getEncodedLocalName());
        } else {
            return String.format("%s/%s/%s/%s/%s", domain, tenant, cluster, namespacePortion, getEncodedLocalName());
        }
    }

    public boolean isGlobal() {
        return cluster == null || Constants.GLOBAL_CLUSTER.equalsIgnoreCase(cluster);
    }

    public String getSchemaName() {
        return getTenant()
            + "/" + getNamespacePortion()
            + "/" + getLocalName();
    }

    /**
     * Get the full name for the topic.
     * E.g. persistent://public/default/topic1
     */
    public String getFullName() {
        if (isV2()) {
            return String.format("%s://%s/%s/%s", domain, tenant, namespacePortion, getEncodedLocalName());
        } else {
            return String.format("%s://%s/%s/%s/%s", domain, tenant, cluster, namespacePortion, getEncodedLocalName());
        }
    }

    @Override
    public String toString() {
        return completeTopicName;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TopicName) {
            TopicName other = (TopicName) obj;
            return Objects.equal(completeTopicName, other.completeTopicName);
        }

        return false;
    }

    @Override
    public int hashCode() {
        return completeTopicName.hashCode();
    }

    @Override
    public boolean includes(TopicName otherTopicName) {
        return this.equals(otherTopicName);
    }

    /**
     * Returns true if this a V2 topic name prop/ns/topic-name
     * @return true if V2
     */
    public boolean isV2() {
        return cluster == null;
    }
}
