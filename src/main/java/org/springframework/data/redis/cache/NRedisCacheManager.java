package org.springframework.data.redis.cache;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.springframework.cache.Cache;
import org.springframework.cache.support.AbstractCacheManager;
import org.springframework.util.Assert;

/**
 * @author Christoph Strobl
 * @since 2017/05
 */
public class NRedisCacheManager extends AbstractCacheManager {

	final DefaultRedisCacheWriter cacheWriter;
	final RedisCacheConfiguration defaultCacheOptions;
	final Map<String, RedisCacheConfiguration> intialCacheConfiguration;

	public NRedisCacheManager(DefaultRedisCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration,
							  String... initialCacheNames) {

		Assert.notNull(cacheWriter, "CacheWriter must not be null!");
		Assert.notNull(defaultCacheConfiguration, "DefaultCacheConfiguration must not be null!");

		this.cacheWriter = cacheWriter;
		this.defaultCacheOptions = defaultCacheConfiguration;
		this.intialCacheConfiguration = new LinkedHashMap<>(initialCacheNames.length, 1);

		for (String cacheName : initialCacheNames) {
			this.intialCacheConfiguration.put(cacheName, defaultCacheConfiguration);
		}
	}

	public NRedisCacheManager(DefaultRedisCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration,
							  Map<String, RedisCacheConfiguration> initialCacheConfigurations) {

		Assert.notNull(cacheWriter, "CacheWriter must not be null!");
		Assert.notNull(defaultCacheConfiguration, "DefaultCacheConfiguration must not be null!");
		Assert.notNull(initialCacheConfigurations, "InitialCacheConfigurations must not be null!");

		this.cacheWriter = cacheWriter;
		this.defaultCacheOptions = defaultCacheConfiguration;
		this.intialCacheConfiguration = new LinkedHashMap<>(initialCacheConfigurations);
	}

	@Override
	protected Collection<NRedisCache> loadCaches() {

		List<NRedisCache> caches = new LinkedList<>();
		for (Map.Entry<String, RedisCacheConfiguration> entry : intialCacheConfiguration.entrySet()) {
			caches.add(createRedisCache(entry.getKey(), entry.getValue()));
		}
		return caches;
	}

	@Override
	protected Cache getMissingCache(String name) {
		return createRedisCache(name, defaultCacheOptions);
	}

	NRedisCache createRedisCache(String name, RedisCacheConfiguration cacheConfig) {
		return new NRedisCache(name, cacheWriter, cacheConfig);
	}
}
