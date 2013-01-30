/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.factory.impl;

import org.terracotta.toolkit.ToolkitObjectType;
import org.terracotta.toolkit.builder.ToolkitCacheConfigBuilder;
import org.terracotta.toolkit.config.Configuration;
import org.terracotta.toolkit.internal.store.ConfigFieldsInternal;
import org.terracotta.toolkit.store.ToolkitConfigFields;

import com.google.common.base.Preconditions;
import com.terracotta.toolkit.collections.servermap.api.ServerMapLocalStoreFactory;
import com.terracotta.toolkit.config.ConfigUtil;
import com.terracotta.toolkit.config.UnclusteredConfiguration;
import com.terracotta.toolkit.config.cache.InternalCacheConfigurationType;
import com.terracotta.toolkit.object.ToolkitObjectStripe;
import com.terracotta.toolkit.search.SearchFactory;
import com.terracotta.toolkit.type.DistributedToolkitTypeFactory;

import java.io.Serializable;
import java.util.Set;


/**
 * An implementation of {@link DistributedToolkitTypeFactory} for ClusteredMap's
 */
public class ToolkitCacheDistributedTypeFactory<K extends Serializable, V extends Serializable>
    extends BaseDistributedToolkitTypeFactory<K, V> {

  public ToolkitCacheDistributedTypeFactory(final SearchFactory searchBuilderFactory,
                                            final ServerMapLocalStoreFactory serverMapLocalStoreFactory) {
    super(searchBuilderFactory, serverMapLocalStoreFactory);
  }

  @Override
  protected Set<InternalCacheConfigurationType> getAllSupportedConfigs() {
    return InternalCacheConfigurationType.getConfigsFor(ToolkitObjectType.CACHE);
  }

  protected void validateNewConfiguration(final Configuration configuration) {
    // options specific for cache
    Preconditions.checkArgument(configuration.hasField(ToolkitConfigFields.EVICTION_ENABLED_FIELD_NAME),
        "Configuration property '%s' is not set for cache", ToolkitConfigFields.EVICTION_ENABLED_FIELD_NAME);
    Preconditions.checkArgument(configuration.hasField(ToolkitConfigFields.MAX_TOTAL_COUNT_FIELD_NAME),
        "Configuration property '%s' is not set for cache", ToolkitConfigFields.MAX_TOTAL_COUNT_FIELD_NAME);
  }

  @Override
  protected Configuration getDefaultConfiguration() {
    final ToolkitCacheConfigBuilder builder = new ToolkitCacheConfigBuilder();
    // populate defaults
    builder.concurrency(ToolkitConfigFields.DEFAULT_CONCURRENCY);
    builder.consistency(ToolkitConfigFields.Consistency.valueOf(ToolkitConfigFields.DEFAULT_CONSISTENCY));
    builder.localCacheEnabled(ToolkitConfigFields.DEFAULT_LOCAL_CACHE_ENABLED);
    builder.offheapEnabled(ToolkitConfigFields.DEFAULT_OFFHEAP_ENABLED);
    builder.maxBytesLocalHeap(ToolkitConfigFields.DEFAULT_MAX_BYTES_LOCAL_HEAP);
    builder.maxBytesLocalOffheap(ToolkitConfigFields.DEFAULT_MAX_BYTES_LOCAL_OFFHEAP);
    builder.maxCountLocalHeap(ToolkitConfigFields.DEFAULT_MAX_COUNT_LOCAL_HEAP);
    builder.compressionEnabled(ToolkitConfigFields.DEFAULT_COMPRESSION_ENABLED);
    builder.copyOnReadEnabled(ToolkitConfigFields.DEFAULT_COPY_ON_READ_ENABLED);
    builder.maxTotalCount(ToolkitConfigFields.DEFAULT_MAX_TOTAL_COUNT);
    builder.evictionEnabled(ToolkitConfigFields.DEFAULT_EVICTION_ENABLED);
    builder.maxTTISeconds(ToolkitConfigFields.DEFAULT_MAX_TTI_SECONDS);
    builder.maxTTLSeconds(ToolkitConfigFields.DEFAULT_MAX_TTL_SECONDS);
    builder.pinnedInLocalMemory(ToolkitConfigFields.DEFAULT_PINNED_IN_LOCAL_MEMORY);
    builder.configField(ConfigFieldsInternal.LOCAL_STORE_MANAGER_NAME_NAME,
        ConfigFieldsInternal.DEFAULT_LOCAL_STORE_MANAGER_NAME);
    return builder.build();
  }

  protected UnclusteredConfiguration[] distributeConfigAmongStripes(final Configuration config, int numberStripes) {
    final UnclusteredConfiguration[] configurations = super.distributeConfigAmongStripes(config, numberStripes);

    // continue distributing
    final int overallConcurrency = config.getInt(ToolkitConfigFields.CONCURRENCY_FIELD_NAME);
    final int overallMaxTotalCount = config.getInt(ToolkitConfigFields.MAX_TOTAL_COUNT_FIELD_NAME);
    // divide maxTotalCount using overallConcurrency in case its smaller than numberStripes
    int divisor = overallConcurrency < numberStripes ? overallConcurrency : numberStripes;
    int[] maxTotalCounts = ConfigUtil.distributeInStripes(overallMaxTotalCount, divisor);
    if (maxTotalCounts.length != divisor) { throw new AssertionError(); }

    for (int i = 0; i < configurations.length; i++) {
      if (overallMaxTotalCount < 0) {
        configurations[i].setInt(ToolkitConfigFields.MAX_TOTAL_COUNT_FIELD_NAME, -1);
      } else if (i < maxTotalCounts.length) {
        configurations[i].setInt(ToolkitConfigFields.MAX_TOTAL_COUNT_FIELD_NAME, maxTotalCounts[i]);
      } else {
        // use 0 in case numberStripes more than overallConcurrency for non-participating stripes
        configurations[i].setInt(ToolkitConfigFields.MAX_TOTAL_COUNT_FIELD_NAME, 0);
      }
    }
    return configurations;
  }

  @Override
  protected void validateExistingClusterWideConfigs(final ToolkitObjectStripe[] stripeObjects, final Configuration newConfig) {
    int concurrency = 0;
    int maxCount = 0;
    for (ToolkitObjectStripe stripeObject : stripeObjects) {
      final Configuration oldConfig = stripeObject.getConfiguration();
      for (InternalCacheConfigurationType configType : InternalCacheConfigurationType
          .getClusterWideConfigsFor(ToolkitObjectType.CACHE)) {
        final Object existingValue = getAndValidateExistingValue(oldConfig, configType);
        switch (configType) {
          case CONCURRENCY:
            concurrency += ((Integer)existingValue);
            break;
          case MAX_TOTAL_COUNT:
            maxCount += ((Integer)existingValue);
            break;
          default:
            configType.validateExistingMatchesValueFromConfig(existingValue, newConfig);
        }
      }
    }

    InternalCacheConfigurationType.CONCURRENCY.validateExistingMatchesValueFromConfig(concurrency, newConfig);
    InternalCacheConfigurationType.MAX_TOTAL_COUNT.validateExistingMatchesValueFromConfig(
        maxCount < 0 ? -1 : maxCount, newConfig);
  }

  @Override
  protected Serializable getExistingValueOrException(final InternalCacheConfigurationType configType,
                                                     final ToolkitObjectStripe[] stripeObjects) {
    // cache-specific configuration parameters
    switch (configType) {
      case MAX_TOTAL_COUNT:
        int maxTotalCount = 0;
        for (ToolkitObjectStripe stripeObject : stripeObjects) {
          Object existingValue = getAndValidateExistingValue(stripeObject.getConfiguration(), configType);
          maxTotalCount += (Integer)existingValue;
        }
        return maxTotalCount < 0 ? -1 : maxTotalCount;
      default:
        return super.getExistingValueOrException(configType, stripeObjects);
    }
  }
}
