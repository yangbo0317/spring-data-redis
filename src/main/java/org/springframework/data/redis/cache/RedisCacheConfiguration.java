/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.cache;

import java.time.Duration;
import java.util.Optional;

import org.springframework.cache.Cache;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.Assert;

/**
 * {@link RedisCacheConfiguration} helps customizing {@link NRedisCache} behaviour such as caching {@literal null}
 * values, cache key prefixes and binary serialization. <br />
 * Start with {@link RedisCacheConfiguration#defaultCacheConfig()} and customize
 *
 * @author Christoph Strobl
 * @since 2.0
 */
public class RedisCacheConfiguration {

	private final Duration timeout;
	private final Boolean cacheNullValues;
	private final String defaultPrefix;
	private final Boolean usePrefix;

	private final SerializationPair<String> keySerializationPair;
	private final SerializationPair<?> valueSerializationPair;

	private RedisCacheConfiguration(Duration ttl, Boolean cacheNullValues, Boolean usePrefix, String defaultPrefix,
			SerializationPair<String> keySerializationPair, SerializationPair<?> valueSerializationPair) {

		this.timeout = ttl;
		this.cacheNullValues = cacheNullValues;
		this.usePrefix = usePrefix;
		this.defaultPrefix = defaultPrefix;
		this.keySerializationPair = keySerializationPair;
		this.valueSerializationPair = valueSerializationPair;
	}

	/**
	 * Default {@link RedisCacheConfiguration} using the following:
	 * <dl>
	 * <dt>key expiration</dt>
	 * <dd>eternal</dd>
	 * <dt>cache null values</dt>
	 * <dd>yes</dd>
	 * <dt>prefix cache keys</dt>
	 * <dd>yes</dd>
	 * <dt>default prefix</dt>
	 * <dd>[the actual cache name]</dd>
	 * <dt>key serializer</dt>
	 * <dd>StringRedisSerializer.class</dd>
	 * <dt>value serializer</dt>
	 * <dd>JdkSerializationRedisSerializer.class</dd>
	 * </dl>
	 * 
	 * @return new {@link RedisCacheConfiguration}.
	 */
	public static RedisCacheConfiguration defaultCacheConfig() {
		return new RedisCacheConfiguration(Duration.ZERO, true, true, null,
				SerializationPair.fromSerializer(new StringRedisSerializer()),
				SerializationPair.fromSerializer(new JdkSerializationRedisSerializer()));
	}

	/**
	 * Set the timeout to apply for cache entries. Use {@link Duration#ZERO} to have an eternal cache.
	 *
	 * @param timeout must not be {@literal null}.
	 * @return new {@link RedisCacheConfiguration}.
	 */
	public RedisCacheConfiguration entryTimeout(Duration timeout) {

		Assert.notNull(timeout, "Timeout must not be null!");
		return new RedisCacheConfiguration(timeout, cacheNullValues, usePrefix, defaultPrefix, keySerializationPair,
				valueSerializationPair);
	}

	/**
	 * Use the given prefix instead of the default one.
	 *
	 * @param prefix must not be {@literal null}.
	 * @return new {@link RedisCacheConfiguration}.
	 */
	public RedisCacheConfiguration prefixKeysWith(String prefix) {

		Assert.notNull(prefix, "Prefix must not be null!");
		return new RedisCacheConfiguration(timeout, cacheNullValues, true, prefix, keySerializationPair,
				valueSerializationPair);
	}

	/**
	 * Disable caching {@literal null} values. <br />
	 * <strong>NOTE</strong> any {@link org.springframework.cache.Cache#put(Object, Object)} operation involving
	 * {@literal null} value will be silently aborted. Nothing will be written to Redis, nothing will be removed. An
	 * already existing key will still be there afterwards with the very same value as before.
	 *
	 * @return new {@link RedisCacheConfiguration}.
	 */
	public RedisCacheConfiguration disableCachingNullValues() {
		return new RedisCacheConfiguration(timeout, false, usePrefix, defaultPrefix, keySerializationPair,
				valueSerializationPair);
	}

	/**
	 * Disable using cache key prefixes. <br />
	 * <strong>NOTE</strong>: {@link Cache#clear()} might result in unintended removal of {@literal key}s in Redis. Make
	 * sure to use a dedicated Redis instance when disabling prefixes.
	 *
	 * @return new {@link RedisCacheConfiguration}.
	 */
	public RedisCacheConfiguration disableCachePrefix() {
		return new RedisCacheConfiguration(timeout, cacheNullValues, false, defaultPrefix, keySerializationPair,
				valueSerializationPair);
	}

	/**
	 * Define the {@link SerializationPair} used for de-/serializing cache keys.
	 *
	 * @param keySerializationPair must not be {@literal null}.
	 * @return new {@link RedisCacheConfiguration}.
	 */
	public RedisCacheConfiguration serializeKeysWith(SerializationPair<String> keySerializationPair) {

		Assert.notNull(keySerializationPair, "KeySerializationPair must not be null!");
		return new RedisCacheConfiguration(timeout, cacheNullValues, usePrefix, defaultPrefix, keySerializationPair,
				valueSerializationPair);
	}

	/**
	 * Define the {@link SerializationPair} used for de-/serializing cache values.
	 *
	 * @param valueSerializationPair must not be {@literal null}.
	 * @return new {@link RedisCacheConfiguration}.
	 */
	public RedisCacheConfiguration serializeValuesWith(SerializationPair<?> valueSerializationPair) {

		Assert.notNull(valueSerializationPair, "ValueSerializationPair must not be null!");
		return new RedisCacheConfiguration(timeout, cacheNullValues, usePrefix, defaultPrefix, keySerializationPair,
				valueSerializationPair);
	}

	Optional<String> getDefaultPrefix() {
		return Optional.ofNullable(defaultPrefix);
	}

	boolean usePrefix() {
		return usePrefix != null ? usePrefix.booleanValue() : false;
	}

	boolean getAllowCacheNullValues() {
		return cacheNullValues != null ? cacheNullValues.booleanValue() : true;
	}

	SerializationPair<String> getKeySerializationPair() {
		return keySerializationPair;
	}

	SerializationPair getValueSerializationPair() {
		return valueSerializationPair;
	}

	Duration getTimeout() {
		return timeout != null ? timeout : Duration.ZERO;
	}

}
