package org.springframework.data.redis.cache;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.springframework.cache.Cache;
import org.springframework.cache.transaction.AbstractTransactionSupportingCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.util.Assert;

/**
 * @author Christoph Strobl
 * @since 2017/05
 */
public class NRedisCacheManager extends AbstractTransactionSupportingCacheManager {

	final RedisCacheWriter cacheWriter;
	final RedisCacheConfiguration defaultCacheConfig;
	final Map<String, RedisCacheConfiguration> intialCacheConfiguration;

	public NRedisCacheManager(RedisCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration,
			String... initialCacheNames) {

		Assert.notNull(cacheWriter, "CacheWriter must not be null!");
		Assert.notNull(defaultCacheConfiguration, "DefaultCacheConfiguration must not be null!");

		this.cacheWriter = cacheWriter;
		this.defaultCacheConfig = defaultCacheConfiguration;
		this.intialCacheConfiguration = new LinkedHashMap<>(initialCacheNames.length, 1);

		for (String cacheName : initialCacheNames) {
			this.intialCacheConfiguration.put(cacheName, defaultCacheConfiguration);
		}
	}

	public NRedisCacheManager(RedisCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration,
			Map<String, RedisCacheConfiguration> initialCacheConfigurations) {

		Assert.notNull(cacheWriter, "CacheWriter must not be null!");
		Assert.notNull(defaultCacheConfiguration, "DefaultCacheConfiguration must not be null!");
		Assert.notNull(initialCacheConfigurations, "InitialCacheConfigurations must not be null!");

		this.cacheWriter = cacheWriter;
		this.defaultCacheConfig = defaultCacheConfiguration;
		this.intialCacheConfiguration = new LinkedHashMap<>(initialCacheConfigurations);
	}

	/**
	 * Create a new {@link NRedisCacheManager} with defaults applied.
	 * <dl>
	 * <dt>locking</dt>
	 * <dd>disabled</dd>
	 * <dt>cache configuration</dt>
	 * <dd>{@link RedisCacheConfiguration#defaultCacheConfig()}</dd>
	 * <dt>initial caches</dt>
	 * <dd>none</dd>
	 * <dt>transaction aware</dt>
	 * <dd>no</dd>
	 * </dl>
	 *
	 * @param connectionFactory must not be {@literal null}.
	 * @return
	 */
	public static NRedisCacheManager iAmFineWithTheDefaults(RedisConnectionFactory connectionFactory) {

		Assert.notNull(connectionFactory, "ConnectionFactory must not be null!");

		return new NRedisCacheManager(new DefaultRedisCacheWriter(connectionFactory),
				RedisCacheConfiguration.defaultCacheConfig());
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
		return createRedisCache(name, defaultCacheConfig);
	}

	NRedisCache createRedisCache(String name, RedisCacheConfiguration cacheConfig) {
		return new NRedisCache(name, cacheWriter, cacheConfig != null ? cacheConfig : defaultCacheConfig);
	}
}
