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

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;

import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.cache.support.NullValue;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.core.convert.support.ConfigurableConversionService;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

/**
 * {@link org.springframework.cache.Cache} implementation suitable for Redis.
 *
 * @author Christoph Strobl
 * @since 2.0
 */
public class NRedisCache extends AbstractValueAdaptingCache {

	private static final NullValue NULL_VALUE;
	private static final byte[] BINARY_NULL_VALUE;

	private final String name;
	private final RedisCacheWriter cacheWriter;
	private final RedisCacheConfiguration cacheConfig;
	private final ConfigurableConversionService conversionService = new DefaultFormattingConversionService();

	static {

		Field field = ReflectionUtils.findField(NullValue.class, "INSTANCE");
		ReflectionUtils.makeAccessible(field);
		NULL_VALUE = NullValue.class.cast(ReflectionUtils.getField(field, null));

		BINARY_NULL_VALUE = new JdkSerializationRedisSerializer().serialize(NULL_VALUE);
	}

	/**
	 * Create new {@link NRedisCache}.
	 * 
	 * @param name must not be {@literal null}.
	 * @param cacheWriter must not be {@literal null}.
	 * @param cacheConfig must not be {@literal null}.
	 */
	public NRedisCache(String name, RedisCacheWriter cacheWriter, RedisCacheConfiguration cacheConfig) {

		super(cacheConfig.getAllowCacheNullValues());

		Assert.notNull(name, "Name must not be null!");
		Assert.notNull(cacheWriter, "CacheWriter must not be null!");
		Assert.notNull(cacheConfig, "CacheConfig must not be null!");

		this.name = name;
		this.cacheWriter = cacheWriter;
		this.cacheConfig = cacheConfig;

		conversionService.addConverter(String.class, byte[].class, source -> source.getBytes(Charset.forName("UTF8")));
	}

	@Override
	protected Object lookup(Object key) {

		byte[] value = cacheWriter.get(name, createAndConvertCacheKey(key));

		if (value == null) {
			return null;
		}

		return deserializeCacheValue(value);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public RedisCacheWriter getNativeCache() {
		return this.cacheWriter;
	}

	@Override
	public synchronized <T> T get(Object key, Callable<T> valueLoader) {

		ValueWrapper result = get(key);

		if (result != null) {
			return (T) result.get();
		}

		T value = valueFromLoader(key, valueLoader);
		put(key, value);
		return value;
	}

	@Override
	public void put(Object key, Object value) {

		Object cacheValue = preProcessCacheValue(value);

		if (!isAllowNullValues() && cacheValue == null) {

			throw new IllegalArgumentException(String.format(
					"Cache '%s' does not allow 'null' values. Avoid storing null via '@Cacheable(unless=\"#result == null\")' or configure RedisCache to allow 'null' via RedisCacheConfiguration.",
					name));
		}

		cacheWriter.put(name, createAndConvertCacheKey(key), serializeCacheValue(cacheValue), cacheConfig.getTimeout());
	}

	@Override
	public ValueWrapper putIfAbsent(Object key, Object value) {

		Object cacheValue = preProcessCacheValue(value);

		if (!isAllowNullValues() && cacheValue == null) {
			return get(key);
		}

		byte[] result = cacheWriter.putIfAbsent(name, createAndConvertCacheKey(key), serializeCacheValue(cacheValue),
				cacheConfig.getTimeout());

		if (result == null) {
			return null;
		}

		return new SimpleValueWrapper(fromStoreValue(deserializeCacheValue(result)));
	}

	@Override
	public void evict(Object key) {
		cacheWriter.remove(name, createAndConvertCacheKey(key));
	}

	@Override
	public void clear() {

		byte[] pattern = conversionService.convert(createCacheKey("*"), byte[].class);
		cacheWriter.clean(name, pattern);
	}

	protected Object preProcessCacheValue(Object value) {

		if (value != null) {
			return value;
		}

		return isAllowNullValues() ? NULL_VALUE : null;
	}

	protected byte[] serializeCacheKey(String cacheKey) {
		return cacheConfig.getKeySerializationPair().write(cacheKey).array();
	}

	protected byte[] serializeCacheValue(Object value) {

		if (isAllowNullValues() && value instanceof NullValue) {
			return BINARY_NULL_VALUE;
		}

		return cacheConfig.getValueSerializationPair().write(value).array();
	}

	protected Object deserializeCacheValue(byte[] value) {

		if (isAllowNullValues() && ObjectUtils.nullSafeEquals(value, BINARY_NULL_VALUE)) {
			return NULL_VALUE;
		}

		return cacheConfig.getValueSerializationPair().read(ByteBuffer.wrap(value));
	}

	private byte[] createAndConvertCacheKey(Object key) {
		return serializeCacheKey(createCacheKey(key));
	}

	private String createCacheKey(Object key) {

		String convertedKey = conversionService.convert(key, String.class);
		if (!cacheConfig.usePrefix()) {
			return convertedKey;
		}

		return prefixCacheKey(convertedKey);
	}

	private String prefixCacheKey(String key) {
		return cacheConfig.getDefaultPrefix().orElseGet(() -> name + "::") + key;
	}

	private <T> T valueFromLoader(Object key, Callable<T> valueLoader) {

		try {
			return valueLoader.call();
		} catch (Exception e) {
			throw new ValueRetrievalException(key, valueLoader, e);
		}
	}

}
