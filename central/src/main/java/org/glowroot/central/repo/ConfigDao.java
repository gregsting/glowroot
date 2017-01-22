/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.central.repo;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.glowroot.central.repo.AgentDao.AgentConfigUpdate;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AdvancedConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;

import static com.google.common.base.Preconditions.checkNotNull;

// TODO agent config records never expire for abandoned agent rollup ids
public class ConfigDao {

    private static final String WITH_LCS =
            "with compaction = { 'class' : 'LeveledCompactionStrategy' }";

    private final Session session;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;
    private final PreparedStatement readUpdatePS;
    private final PreparedStatement markUpdatedPS;

    private final LoadingCache<String, Optional<AgentConfig>> agentConfigCache =
            CacheBuilder.newBuilder().build(new AgentConfigCacheLoader());

    public ConfigDao(Session session) {
        this.session = session;

        session.execute("create table if not exists config (agent_rollup_id varchar, config blob,"
                + " config_update boolean, config_update_token uuid, primary key"
                + " (agent_rollup_id)) " + WITH_LCS);
        // secondary index is needed for Cassandra 2.x (to avoid error on readUpdatePS)
        session.execute("create index if not exists config_update_idx on config (config_update)");

        insertPS = session.prepare("insert into config (agent_rollup_id, config, config_update,"
                + " config_update_token) values (?, ?, ?, ?)");
        readPS = session.prepare("select config from config where agent_rollup_id = ?");

        readUpdatePS = session.prepare("select config, config_update_token from config"
                + " where agent_rollup_id = ? and config_update = true allow filtering");
        markUpdatedPS = session.prepare("update config set config_update = false,"
                + " config_update_token = null where agent_rollup_id = ?"
                + " if config_update_token = ?");
    }

    public AgentConfig store(String agentId, @Nullable String agentRollupId,
            AgentConfig agentConfig) throws Exception {
        AgentConfig existingAgentConfig = read(agentId);
        AgentConfig updatedAgentConfig;
        if (existingAgentConfig == null) {
            updatedAgentConfig = agentConfig;
        } else {
            // sync list of plugin properties, central property values win
            Map<String, PluginConfig> existingPluginConfigs = Maps.newHashMap();
            for (PluginConfig existingPluginConfig : existingAgentConfig.getPluginConfigList()) {
                existingPluginConfigs.put(existingPluginConfig.getId(), existingPluginConfig);
            }
            List<PluginConfig> pluginConfigs = Lists.newArrayList();
            for (PluginConfig agentPluginConfig : agentConfig.getPluginConfigList()) {
                PluginConfig existingPluginConfig =
                        existingPluginConfigs.get(agentPluginConfig.getId());
                if (existingPluginConfig == null) {
                    pluginConfigs.add(agentPluginConfig);
                    continue;
                }
                Map<String, PluginProperty> existingProperties = Maps.newHashMap();
                for (PluginProperty existingProperty : existingPluginConfig.getPropertyList()) {
                    existingProperties.put(existingProperty.getName(), existingProperty);
                }
                List<PluginProperty> properties = Lists.newArrayList();
                for (PluginProperty agentProperty : agentPluginConfig.getPropertyList()) {
                    PluginProperty existingProperty =
                            existingProperties.get(agentProperty.getName());
                    if (existingProperty == null) {
                        properties.add(agentProperty);
                        continue;
                    }
                    // overlay existing property value
                    properties.add(agentProperty.toBuilder()
                            .setValue(existingProperty.getValue())
                            .build());
                }
                pluginConfigs.add(PluginConfig.newBuilder()
                        .setId(agentPluginConfig.getId())
                        .setName(agentPluginConfig.getName())
                        .addAllProperty(properties)
                        .build());
            }
            updatedAgentConfig = existingAgentConfig.toBuilder()
                    .clearPluginConfig()
                    .addAllPluginConfig(pluginConfigs)
                    .build();
        }
        if (existingAgentConfig == null || !updatedAgentConfig.equals(agentConfig)) {
            BoundStatement boundStatement = insertPS.bind();
            int i = 0;
            boundStatement.setString(i++, agentId);
            boundStatement.setBytes(i++, ByteBuffer.wrap(updatedAgentConfig.toByteArray()));
            // setting config_update to false as this method is only called by collectInit(), and
            // agent will not consider collectInit() to be successful until it receives updated
            // agent config
            boundStatement.setBool(i++, false);
            boundStatement.setToNull(i++);
            session.execute(boundStatement);
            agentConfigCache.invalidate(agentId);
        }
        if (agentRollupId != null) {
            List<String> agentRollupIds = AgentDao.getAgentRollupIds(agentRollupId);
            for (String loopAgentRollupId : agentRollupIds) {
                if (read(loopAgentRollupId) != null) {
                    continue;
                }
                // there is no config for rollup yet
                // so insert initial config propagating ui config and advanced config properties
                // that pertain to rollups
                BoundStatement boundStatement = insertPS.bind();
                int i = 0;
                boundStatement.setString(i++, loopAgentRollupId);
                AdvancedConfig advancedConfig = updatedAgentConfig.getAdvancedConfig();
                boundStatement.setBytes(i++, ByteBuffer.wrap(AgentConfig.newBuilder()
                        .setUiConfig(updatedAgentConfig.getUiConfig())
                        .setAdvancedConfig(AdvancedConfig.newBuilder()
                                .setMaxAggregateQueriesPerType(
                                        advancedConfig.getMaxAggregateQueriesPerType())
                                .setMaxAggregateServiceCallsPerType(
                                        advancedConfig.getMaxAggregateServiceCallsPerType()))
                        .build()
                        .toByteArray()));
                boundStatement.setBool(i++, false);
                boundStatement.setToNull(i++);
                session.execute(boundStatement);
                agentConfigCache.invalidate(loopAgentRollupId);
            }
        }
        return updatedAgentConfig;
    }

    void insert(String agentRollupId, AgentConfig agentConfig) {
        BoundStatement boundStatement = insertPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentRollupId);
        boundStatement.setBytes(i++, ByteBuffer.wrap(agentConfig.toByteArray()));
        boundStatement.setBool(i++, true);
        boundStatement.setUUID(i++, UUIDs.random());
        session.execute(boundStatement);
        agentConfigCache.invalidate(agentRollupId);
    }

    @Nullable
    AgentConfig read(String agentRollupId) throws Exception {
        return agentConfigCache.get(agentRollupId).orNull();
    }

    // does not apply to agent rollups
    public @Nullable AgentConfigUpdate readForUpdate(String agentId)
            throws InvalidProtocolBufferException {
        BoundStatement boundStatement = readUpdatePS.bind();
        boundStatement.setString(0, agentId);
        ResultSet results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            // no pending config update for this agent (or agent has been manually deleted)
            return null;
        }
        ByteBuffer bytes = checkNotNull(row.getBytes(0));
        UUID configUpdateToken = checkNotNull(row.getUUID(1));
        return ImmutableAgentConfigUpdate.builder()
                .config(AgentConfig.parseFrom(ByteString.copyFrom(bytes)))
                .configUpdateToken(configUpdateToken)
                .build();
    }

    // does not apply to agent rollups
    public void markUpdated(String agentId, UUID configUpdateToken) {
        BoundStatement boundStatement = markUpdatedPS.bind();
        int i = 0;
        boundStatement.setString(i++, agentId);
        boundStatement.setUUID(i++, configUpdateToken);
        session.execute(boundStatement);
    }

    private class AgentConfigCacheLoader extends CacheLoader<String, Optional<AgentConfig>> {
        @Override
        public Optional<AgentConfig> load(String agentRollupId) throws Exception {
            BoundStatement boundStatement = readPS.bind();
            boundStatement.setString(0, agentRollupId);
            ResultSet results = session.execute(boundStatement);
            Row row = results.one();
            if (row == null) {
                // agent must have been manually deleted
                return Optional.absent();
            }
            ByteBuffer bytes = checkNotNull(row.getBytes(0));
            return Optional.of(AgentConfig.parseFrom(ByteString.copyFrom(bytes)));
        }
    }
}
