/*
 * Copyright 2015-2017 the original author or authors.
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
package org.springframework.data.redis.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.keyvalue.core.AbstractKeyValueAdapter;
import org.springframework.data.keyvalue.core.KeyValueAdapter;
import org.springframework.data.keyvalue.core.mapping.KeyValuePersistentProperty;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.PartialUpdate.PropertyUpdate;
import org.springframework.data.redis.core.PartialUpdate.UpdateCommand;
import org.springframework.data.redis.core.convert.CustomConversions;
import org.springframework.data.redis.core.convert.GeoIndexedPropertyValue;
import org.springframework.data.redis.core.convert.KeyspaceConfiguration;
import org.springframework.data.redis.core.convert.MappingRedisConverter;
import org.springframework.data.redis.core.convert.PathIndexResolver;
import org.springframework.data.redis.core.convert.RedisConverter;
import org.springframework.data.redis.core.convert.RedisCustomConversions;
import org.springframework.data.redis.core.convert.RedisData;
import org.springframework.data.redis.core.convert.ReferenceResolverImpl;
import org.springframework.data.redis.core.mapping.RedisMappingContext;
import org.springframework.data.redis.core.mapping.RedisPersistentEntity;
import org.springframework.data.redis.core.mapping.RedisPersistentProperty;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.util.ByteUtils;
import org.springframework.data.util.CloseableIterator;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

/**
 * Redis specific {@link KeyValueAdapter} implementation. Uses binary codec to read/write data from/to Redis. Objects
 * are stored in a Redis Hash using the value of {@link RedisHash}, the {@link KeyspaceConfiguration} or just
 * {@link Class#getName()} as a prefix. <br />
 * <strong>Example</strong>
 *
 * <pre>
 * <code>
 * &#64;RedisHash("persons")
 * class Person {
 *   &#64;Id String id;
 *   String name;
 * }
 *
 *
 *         prefix              ID
 *           |                 |
 *           V                 V
 * hgetall persons:5d67b7e1-8640-4475-beeb-c666fab4c0e5
 * 1) id
 * 2) 5d67b7e1-8640-4475-beeb-c666fab4c0e5
 * 3) name
 * 4) Rand al'Thor
 * </code>
 * </pre>
 *
 * <br />
 * The {@link KeyValueAdapter} is <strong>not</strong> intended to store simple types such as {@link String} values.
 * Please use {@link RedisTemplate} for this purpose.
 *
 * @author Christoph Strobl
 * @author Mark Paluch
 * @since 1.7
 */
public class RedisKeyValueAdapter extends AbstractKeyValueAdapter
		implements InitializingBean, ApplicationContextAware, ApplicationListener<RedisKeyspaceEvent> {

	private RedisOperations<?, ?> redisOps;
	private RedisConverter converter;
	private RedisMessageListenerContainer messageListenerContainer;
	private final AtomicReference<KeyExpirationEventMessageListener> expirationListener = new AtomicReference<KeyExpirationEventMessageListener>(
			null);
	private ApplicationEventPublisher eventPublisher;

	private EnableKeyspaceEvents enableKeyspaceEvents = EnableKeyspaceEvents.OFF;
	private String keyspaceNotificationsConfigParameter = null;

	/**
	 * Creates new {@link RedisKeyValueAdapter} with default {@link RedisMappingContext} and default
	 * {@link RedisCustomConversions}.
	 *
	 * @param redisOps must not be {@literal null}.
	 */
	public RedisKeyValueAdapter(RedisOperations<?, ?> redisOps) {
		this(redisOps, new RedisMappingContext());
	}

	/**
	 * Creates new {@link RedisKeyValueAdapter} with default {@link RedisCustomConversions}.
	 *
	 * @param redisOps must not be {@literal null}.
	 * @param mappingContext must not be {@literal null}.
	 */
	public RedisKeyValueAdapter(RedisOperations<?, ?> redisOps, RedisMappingContext mappingContext) {
		this(redisOps, mappingContext, new RedisCustomConversions());
	}

	/**
	 * Creates new {@link RedisKeyValueAdapter}.
	 *
	 * @param redisOps must not be {@literal null}.
	 * @param mappingContext must not be {@literal null}.
	 * @param customConversions can be {@literal null}.
	 * @deprecated since 2.0, use
	 *             {@link #RedisKeyValueAdapter(RedisOperations, RedisMappingContext, org.springframework.data.convert.CustomConversions)}.
	 */
	@Deprecated
	public RedisKeyValueAdapter(RedisOperations<?, ?> redisOps, RedisMappingContext mappingContext,
			CustomConversions customConversions) {
		this(redisOps, mappingContext, (org.springframework.data.convert.CustomConversions) customConversions);
	}

	/**
	 * Creates new {@link RedisKeyValueAdapter}.
	 *
	 * @param redisOps must not be {@literal null}.
	 * @param mappingContext must not be {@literal null}.
	 * @param customConversions can be {@literal null}.
	 * @since 2.0
	 */
	public RedisKeyValueAdapter(RedisOperations<?, ?> redisOps, RedisMappingContext mappingContext,
			org.springframework.data.convert.CustomConversions customConversions) {

		super(new RedisQueryEngine());

		Assert.notNull(redisOps, "RedisOperations must not be null!");
		Assert.notNull(mappingContext, "RedisMappingContext must not be null!");

		MappingRedisConverter mappingConverter = new MappingRedisConverter(mappingContext,
				new PathIndexResolver(mappingContext), new ReferenceResolverImpl(redisOps));
		mappingConverter.setCustomConversions(customConversions == null ? new RedisCustomConversions() : customConversions);
		mappingConverter.afterPropertiesSet();

		this.converter = mappingConverter;
		this.redisOps = redisOps;
		initMessageListenerContainer();
	}

	/**
	 * Creates new {@link RedisKeyValueAdapter} with specific {@link RedisConverter}.
	 *
	 * @param redisOps must not be {@literal null}.
	 * @param redisConverter must not be {@literal null}.
	 */
	public RedisKeyValueAdapter(RedisOperations<?, ?> redisOps, RedisConverter redisConverter) {

		super(new RedisQueryEngine());

		Assert.notNull(redisOps, "RedisOperations must not be null!");

		this.converter = redisConverter;
		this.redisOps = redisOps;
		initMessageListenerContainer();
	}

	/**
	 * Default constructor.
	 */
	protected RedisKeyValueAdapter() {}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#put(java.io.Serializable, java.lang.Object, java.io.Serializable)
	 */
	public Object put(final Serializable id, final Object item, final Serializable keyspace) {

		final RedisData rdo = item instanceof RedisData ? (RedisData) item : new RedisData();
		if (!(item instanceof RedisData)) {
			converter.write(item, rdo);
		}

		if (ObjectUtils.nullSafeEquals(EnableKeyspaceEvents.ON_DEMAND, enableKeyspaceEvents)
				&& this.expirationListener.get() == null) {

			if (rdo.getTimeToLive() != null && rdo.getTimeToLive().longValue() > 0) {
				initKeyExpirationListener();
			}
		}

		if (rdo.getId() == null) {

			rdo.setId(converter.getConversionService().convert(id, String.class));

			if (!(item instanceof RedisData)) {
				KeyValuePersistentProperty idProperty = converter.getMappingContext()
						.getRequiredPersistentEntity(item.getClass()).getRequiredIdProperty();
				converter.getMappingContext().getRequiredPersistentEntity(item.getClass()).getPropertyAccessor(item)
						.setProperty(idProperty, id);
			}
		}

		redisOps.execute(new RedisCallback<Object>() {

			@Override
			public Object doInRedis(RedisConnection connection) throws DataAccessException {

				byte[] key = toBytes(rdo.getId());
				byte[] objectKey = createKey(rdo.getKeyspace(), rdo.getId());

				boolean isNew = connection.del(objectKey) == 0;

				connection.hMSet(objectKey, rdo.getBucket().rawMap());

				if (rdo.getTimeToLive() != null && rdo.getTimeToLive().longValue() > 0) {

					connection.expire(objectKey, rdo.getTimeToLive().longValue());

					// add phantom key so values can be restored
					byte[] phantomKey = ByteUtils.concat(objectKey, toBytes(":phantom"));
					connection.del(phantomKey);
					connection.hMSet(phantomKey, rdo.getBucket().rawMap());
					connection.expire(phantomKey, rdo.getTimeToLive().longValue() + 300);
				}

				connection.sAdd(toBytes(rdo.getKeyspace()), key);

				IndexWriter indexWriter = new IndexWriter(connection, converter);
				if (isNew) {
					indexWriter.createIndexes(key, rdo.getIndexedData());
				} else {
					indexWriter.deleteAndUpdateIndexes(key, rdo.getIndexedData());
				}
				return null;
			}
		});

		return item;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#contains(java.io.Serializable, java.io.Serializable)
	 */
	public boolean contains(final Serializable id, final Serializable keyspace) {

		Boolean exists = redisOps.execute(new RedisCallback<Boolean>() {

			@Override
			public Boolean doInRedis(RedisConnection connection) throws DataAccessException {
				return connection.sIsMember(toBytes(keyspace), toBytes(id));
			}
		});

		return exists != null ? exists.booleanValue() : false;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#get(java.io.Serializable, java.io.Serializable)
	 */
	public Object get(Serializable id, Serializable keyspace) {
		return get(id, keyspace, Object.class);
	}

	/**
	 * @param id
	 * @param keyspace
	 * @param type
	 * @return
	 */
	public <T> T get(Serializable id, Serializable keyspace, Class<T> type) {

		String stringId = asString(id);
		String stringKeyspace = asString(keyspace);

		final byte[] binId = createKey(stringKeyspace, stringId);

		Map<byte[], byte[]> raw = redisOps.execute(new RedisCallback<Map<byte[], byte[]>>() {

			@Override
			public Map<byte[], byte[]> doInRedis(RedisConnection connection) throws DataAccessException {
				return connection.hGetAll(binId);
			}
		});

		RedisData data = new RedisData(raw);
		data.setId(stringId);
		data.setKeyspace(stringKeyspace);

		return readBackTimeToLiveIfSet(binId, converter.read(type, data));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#delete(java.io.Serializable, java.io.Serializable)
	 */
	public Object delete(final Serializable id, final Serializable keyspace) {
		return delete(id, keyspace, Object.class);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.AbstractKeyValueAdapter#delete(java.io.Serializable, java.io.Serializable, java.lang.Class)
	 */
	public <T> T delete(final Serializable id, final Serializable keyspace, final Class<T> type) {

		final byte[] binId = toBytes(id);
		final byte[] binKeyspace = toBytes(keyspace);

		T o = get(id, keyspace, type);

		if (o != null) {

			final byte[] keyToDelete = createKey(asString(keyspace), asString(id));

			redisOps.execute(new RedisCallback<Void>() {

				@Override
				public Void doInRedis(RedisConnection connection) throws DataAccessException {

					connection.del(keyToDelete);
					connection.sRem(binKeyspace, binId);

					new IndexWriter(connection, converter).removeKeyFromIndexes(asString(keyspace), binId);
					return null;
				}
			});

		}
		return o;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#getAllOf(java.io.Serializable)
	 */
	public List<?> getAllOf(final Serializable keyspace) {
		return getAllOf(keyspace, -1, -1);
	}

	public List<?> getAllOf(final Serializable keyspace, long offset, int rows) {

		final byte[] binKeyspace = toBytes(keyspace);

		Set<byte[]> ids = redisOps.execute(new RedisCallback<Set<byte[]>>() {

			@Override
			public Set<byte[]> doInRedis(RedisConnection connection) throws DataAccessException {
				return connection.sMembers(binKeyspace);
			}
		});

		List<Object> result = new ArrayList<Object>();

		List<byte[]> keys = new ArrayList<byte[]>(ids);

		if (keys.isEmpty() || keys.size() < offset) {
			return Collections.emptyList();
		}

		offset = Math.max(0, offset);
		if (offset >= 0 && rows > 0) {
			keys = keys.subList((int) offset, Math.min((int) offset + rows, keys.size()));
		}

		for (byte[] key : keys) {
			result.add(get(key, keyspace));
		}
		return result;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#deleteAllOf(java.io.Serializable)
	 */
	public void deleteAllOf(final Serializable keyspace) {

		redisOps.execute(new RedisCallback<Void>() {

			@Override
			public Void doInRedis(RedisConnection connection) throws DataAccessException {

				connection.del(toBytes(keyspace));
				new IndexWriter(connection, converter).removeAllIndexes(asString(keyspace));
				return null;
			}
		});
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#entries(java.io.Serializable)
	 */
	public CloseableIterator<Entry<Serializable, Object>> entries(Serializable keyspace) {
		throw new UnsupportedOperationException("Not yet implemented");
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#count(java.io.Serializable)
	 */
	public long count(final Serializable keyspace) {

		Long count = redisOps.execute(new RedisCallback<Long>() {

			@Override
			public Long doInRedis(RedisConnection connection) throws DataAccessException {
				return connection.sCard(toBytes(keyspace));
			}
		});

		return count != null ? count.longValue() : 0;
	}

	public void update(final PartialUpdate<?> update) {

		final RedisPersistentEntity<?> entity = this.converter.getMappingContext()
				.getRequiredPersistentEntity(update.getTarget());

		final String keyspace = entity.getKeySpace();
		final Object id = update.getId();

		final byte[] redisKey = createKey(keyspace, converter.getConversionService().convert(id, String.class));

		final RedisData rdo = new RedisData();
		this.converter.write(update, rdo);

		redisOps.execute(new RedisCallback<Void>() {

			@Override
			public Void doInRedis(RedisConnection connection) throws DataAccessException {

				RedisUpdateObject redisUpdateObject = new RedisUpdateObject(redisKey, keyspace, id);

				for (PropertyUpdate pUpdate : update.getPropertyUpdates()) {

					String propertyPath = pUpdate.getPropertyPath();

					if (UpdateCommand.DEL.equals(pUpdate.getCmd()) || pUpdate.getValue() instanceof Collection
							|| pUpdate.getValue() instanceof Map
							|| (pUpdate.getValue() != null && pUpdate.getValue().getClass().isArray()) || (pUpdate.getValue() != null
									&& !converter.getConversionService().canConvert(pUpdate.getValue().getClass(), byte[].class))) {

						redisUpdateObject = fetchDeletePathsFromHashAndUpdateIndex(redisUpdateObject, propertyPath, connection);
					}
				}

				if (!redisUpdateObject.fieldsToRemove.isEmpty()) {
					connection.hDel(redisKey,
							redisUpdateObject.fieldsToRemove.toArray(new byte[redisUpdateObject.fieldsToRemove.size()][]));
				}

				for (RedisUpdateObject.Index index : redisUpdateObject.indexesToUpdate) {

					if (ObjectUtils.nullSafeEquals(DataType.ZSET, index.type)) {
						connection.zRem(index.key, toBytes(redisUpdateObject.targetId));
					} else {
						connection.sRem(index.key, toBytes(redisUpdateObject.targetId));
					}
				}

				if (!rdo.getBucket().isEmpty()) {
					if (rdo.getBucket().size() > 1
							|| (rdo.getBucket().size() == 1 && !rdo.getBucket().asMap().containsKey("_class"))) {
						connection.hMSet(redisKey, rdo.getBucket().rawMap());
					}
				}

				if (update.isRefreshTtl()) {

					if (rdo.getTimeToLive() != null && rdo.getTimeToLive().longValue() > 0) {

						connection.expire(redisKey, rdo.getTimeToLive().longValue());

						// add phantom key so values can be restored
						byte[] phantomKey = ByteUtils.concat(redisKey, toBytes(":phantom"));
						connection.hMSet(phantomKey, rdo.getBucket().rawMap());
						connection.expire(phantomKey, rdo.getTimeToLive().longValue() + 300);

					} else {

						connection.persist(redisKey);
						connection.persist(ByteUtils.concat(redisKey, toBytes(":phantom")));
					}
				}

				new IndexWriter(connection, converter).updateIndexes(toBytes(id), rdo.getIndexedData());
				return null;
			}

		});
	}

	private RedisUpdateObject fetchDeletePathsFromHashAndUpdateIndex(RedisUpdateObject redisUpdateObject, String path,
			RedisConnection connection) {

		redisUpdateObject.addFieldToRemove(toBytes(path));
		byte[] value = connection.hGet(redisUpdateObject.targetKey, toBytes(path));

		if (value != null && value.length > 0) {

			byte[] existingValueIndexKey = value != null
					? ByteUtils.concatAll(toBytes(redisUpdateObject.keyspace), toBytes((":" + path)), toBytes(":"), value) : null;

			if (connection.exists(existingValueIndexKey)) {
				redisUpdateObject.addIndexToUpdate(new RedisUpdateObject.Index(existingValueIndexKey, DataType.SET));
			}

			return redisUpdateObject;
		}

		Set<byte[]> existingFields = connection.hKeys(redisUpdateObject.targetKey);

		for (byte[] field : existingFields) {

			if (asString(field).startsWith(path + ".")) {

				redisUpdateObject.addFieldToRemove(field);
				value = connection.hGet(redisUpdateObject.targetKey, toBytes(field));

				if (value != null) {

					byte[] existingValueIndexKey = value != null
							? ByteUtils.concatAll(toBytes(redisUpdateObject.keyspace), toBytes(":"), field, toBytes(":"), value)
							: null;

					if (connection.exists(existingValueIndexKey)) {
						redisUpdateObject.addIndexToUpdate(new RedisUpdateObject.Index(existingValueIndexKey, DataType.SET));
					}
				}
			}
		}

		String pathToUse = GeoIndexedPropertyValue.geoIndexName(path);
		byte[] existingGeoIndexKey = ByteUtils.concatAll(toBytes(redisUpdateObject.keyspace), toBytes(":"),
				toBytes(pathToUse));

		if (connection.zRank(existingGeoIndexKey, toBytes(redisUpdateObject.targetId)) != null) {
			redisUpdateObject.addIndexToUpdate(new RedisUpdateObject.Index(existingGeoIndexKey, DataType.ZSET));
		}

		return redisUpdateObject;
	}

	/**
	 * Execute {@link RedisCallback} via underlying {@link RedisOperations}.
	 *
	 * @param callback must not be {@literal null}.
	 * @see RedisOperations#execute(RedisCallback)
	 * @return
	 */
	public <T> T execute(RedisCallback<T> callback) {
		return redisOps.execute(callback);
	}

	/**
	 * Get the {@link RedisConverter} in use.
	 *
	 * @return never {@literal null}.
	 */
	public RedisConverter getConverter() {
		return this.converter;
	}

	public void clear() {
		// nothing to do
	}

	private String asString(Serializable value) {
		return value instanceof String ? (String) value
				: getConverter().getConversionService().convert(value, String.class);
	}

	public byte[] createKey(String keyspace, String id) {
		return toBytes(keyspace + ":" + id);
	}

	/**
	 * Convert given source to binary representation using the underlying {@link ConversionService}.
	 *
	 * @param source
	 * @return
	 * @throws ConverterNotFoundException
	 */
	public byte[] toBytes(Object source) {

		if (source instanceof byte[]) {
			return (byte[]) source;
		}

		return converter.getConversionService().convert(source, byte[].class);
	}

	/**
	 * Read back and set {@link TimeToLive} for the property.
	 *
	 * @param key
	 * @param target
	 * @return
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private <T> T readBackTimeToLiveIfSet(final byte[] key, T target) {

		if (target == null || key == null) {
			return target;
		}

		RedisPersistentEntity<?> entity = this.converter.getMappingContext().getRequiredPersistentEntity(target.getClass());
		if (entity.hasExplictTimeToLiveProperty()) {

			RedisPersistentProperty ttlProperty = entity.getExplicitTimeToLiveProperty();
			if (ttlProperty == null) {
				return target;
			}

			TimeToLive ttl = ttlProperty.findAnnotation(TimeToLive.class);

			Long timeout = redisOps.execute((RedisCallback<Long>) connection -> {

				if (ObjectUtils.nullSafeEquals(TimeUnit.SECONDS, ttl.unit())) {
					return connection.ttl(key);
				}

				return connection.pTtl(key, ttl.unit());
			});

			if (timeout != null || !ttlProperty.getType().isPrimitive()) {
				entity.getPropertyAccessor(target).setProperty(ttlProperty,
						converter.getConversionService().convert(timeout, ttlProperty.getType()));
			}
		}

		return target;
	}

	/**
	 * Configure usage of {@link KeyExpirationEventMessageListener}.
	 *
	 * @param enableKeyspaceEvents
	 * @since 1.8
	 */
	public void setEnableKeyspaceEvents(EnableKeyspaceEvents enableKeyspaceEvents) {
		this.enableKeyspaceEvents = enableKeyspaceEvents;
	}

	/**
	 * Configure the {@literal notify-keyspace-events} property if not already set. Use an empty {@link String} or
	 * {@literal null} to retain existing server settings.
	 *
	 * @param keyspaceNotificationsConfigParameter can be {@literal null}.
	 * @since 1.8
	 */
	public void setKeyspaceNotificationsConfigParameter(String keyspaceNotificationsConfigParameter) {
		this.keyspaceNotificationsConfigParameter = keyspaceNotificationsConfigParameter;
	}

	/**
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 * @since 1.8
	 */
	@Override
	public void afterPropertiesSet() {

		if (ObjectUtils.nullSafeEquals(EnableKeyspaceEvents.ON_STARTUP, this.enableKeyspaceEvents)) {
			initKeyExpirationListener();
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.DisposableBean#destroy()
	 */
	public void destroy() throws Exception {

		if (this.expirationListener.get() != null) {
			this.expirationListener.get().destroy();
		}

		if (this.messageListenerContainer != null) {
			this.messageListenerContainer.destroy();
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.context.ApplicationListener#onApplicationEvent(org.springframework.context.ApplicationEvent)
	 */
	@Override
	public void onApplicationEvent(RedisKeyspaceEvent event) {
		// just a customization hook
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.context.ApplicationContextAware#setApplicationContext(org.springframework.context.ApplicationContext)
	 */
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.eventPublisher = applicationContext;
	}

	private void initMessageListenerContainer() {

		this.messageListenerContainer = new RedisMessageListenerContainer();
		this.messageListenerContainer.setConnectionFactory(((RedisTemplate<?, ?>) redisOps).getConnectionFactory());
		this.messageListenerContainer.afterPropertiesSet();
		this.messageListenerContainer.start();
	}

	private void initKeyExpirationListener() {

		if (this.expirationListener.get() == null) {

			MappingExpirationListener listener = new MappingExpirationListener(this.messageListenerContainer, this.redisOps,
					this.converter);
			listener.setKeyspaceNotificationsConfigParameter(keyspaceNotificationsConfigParameter);

			if (this.eventPublisher != null) {
				listener.setApplicationEventPublisher(this.eventPublisher);
			}

			if (this.expirationListener.compareAndSet(null, listener)) {
				listener.init();
			}
		}
	}

	/**
	 * {@link MessageListener} implementation used to capture Redis keyspace notifications. Tries to read a previously
	 * created phantom key {@code keyspace:id:phantom} to provide the expired object as part of the published
	 * {@link RedisKeyExpiredEvent}.
	 *
	 * @author Christoph Strobl
	 * @since 1.7
	 */
	static class MappingExpirationListener extends KeyExpirationEventMessageListener {

		private final RedisOperations<?, ?> ops;
		private final RedisConverter converter;

		/**
		 * Creates new {@link MappingExpirationListener}.
		 *
		 * @param listenerContainer
		 * @param ops
		 * @param converter
		 */
		public MappingExpirationListener(RedisMessageListenerContainer listenerContainer, RedisOperations<?, ?> ops,
				RedisConverter converter) {

			super(listenerContainer);
			this.ops = ops;
			this.converter = converter;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.listener.KeyspaceEventMessageListener#onMessage(org.springframework.data.redis.connection.Message, byte[])
		 */
		@Override
		public void onMessage(Message message, byte[] pattern) {

			if (!isKeyExpirationMessage(message)) {
				return;
			}

			byte[] key = message.getBody();

			final byte[] phantomKey = ByteUtils.concat(key,
					converter.getConversionService().convert(":phantom", byte[].class));

			Map<byte[], byte[]> hash = ops.execute(new RedisCallback<Map<byte[], byte[]>>() {

				@Override
				public Map<byte[], byte[]> doInRedis(RedisConnection connection) throws DataAccessException {

					Map<byte[], byte[]> hash = connection.hGetAll(phantomKey);

					if (!org.springframework.util.CollectionUtils.isEmpty(hash)) {
						connection.del(phantomKey);
					}

					return hash;
				}
			});

			Object value = converter.read(Object.class, new RedisData(hash));

			String channel = !ObjectUtils.isEmpty(message.getChannel())
					? converter.getConversionService().convert(message.getChannel(), String.class) : null;

			final RedisKeyExpiredEvent event = new RedisKeyExpiredEvent(channel, key, value);

			ops.execute(new RedisCallback<Void>() {
				@Override
				public Void doInRedis(RedisConnection connection) throws DataAccessException {

					connection.sRem(converter.getConversionService().convert(event.getKeyspace(), byte[].class), event.getId());
					new IndexWriter(connection, converter).removeKeyFromIndexes(event.getKeyspace(), event.getId());
					return null;
				}
			});

			publishEvent(event);
		}

		private boolean isKeyExpirationMessage(Message message) {

			if (message == null || message.getChannel() == null || message.getBody() == null) {
				return false;
			}

			byte[][] args = ByteUtils.split(message.getBody(), ':');
			if (args.length != 2) {
				return false;
			}

			return true;
		}
	}

	/**
	 * @author Christoph Strobl
	 * @since 1.8
	 */
	public static enum EnableKeyspaceEvents {

		/**
		 * Initializes the {@link KeyExpirationEventMessageListener} on startup.
		 */
		ON_STARTUP,

		/**
		 * Initializes the {@link KeyExpirationEventMessageListener} on first insert having expiration time set.
		 */
		ON_DEMAND,

		/**
		 * Turn {@link KeyExpirationEventMessageListener} usage off. No expiration events will be received.
		 */
		OFF
	}

	/**
	 * Container holding update information like fields to remove from the Redis Hash.
	 *
	 * @author Christoph Strobl
	 */
	private static class RedisUpdateObject {

		private final String keyspace;
		private final Object targetId;
		private final byte[] targetKey;

		private Set<byte[]> fieldsToRemove = new LinkedHashSet<byte[]>();
		private Set<Index> indexesToUpdate = new LinkedHashSet<Index>();

		RedisUpdateObject(byte[] targetKey, String keyspace, Object targetId) {

			this.targetKey = targetKey;
			this.keyspace = keyspace;
			this.targetId = targetId;
		}

		void addFieldToRemove(byte[] field) {
			fieldsToRemove.add(field);
		}

		void addIndexToUpdate(Index index) {
			indexesToUpdate.add(index);
		}

		static class Index {
			final DataType type;
			final byte[] key;

			public Index(byte[] key, DataType type) {
				this.key = key;
				this.type = type;
			}

		}
	}
}
