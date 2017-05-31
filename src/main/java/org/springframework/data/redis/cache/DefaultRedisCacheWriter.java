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

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.util.Assert;

/**
 * @author Christoph Strobl
 * @since 2.0
 */
class DefaultRedisCacheWriter implements RedisCacheWriter {

	private static final byte[] CLEAN_SCRIPT = "local keys = redis.call('KEYS', ARGV[1]); local keysCount = table.getn(keys); if(keysCount > 0) then for _, key in ipairs(keys) do redis.call('del', key); end; end; return keysCount;"
			.getBytes(Charset.forName("UTF-8"));

	private final RedisConnectionFactory connectionFactory;
	private final Duration sleepTime;

	/**
	 * @param connectionFactory must not be {@literal null}.
	 */
	DefaultRedisCacheWriter(RedisConnectionFactory connectionFactory) {
		this(connectionFactory, Duration.ZERO);
	}

	/**
	 * @param connectionFactory must not be {@literal null}.
	 * @param sleepTime sleep time between lock request attempts. Must not be {@literal null}. Use {@link Duration#ZERO}
	 *          to disable locking.
	 */
	DefaultRedisCacheWriter(RedisConnectionFactory connectionFactory, Duration sleepTime) {

		Assert.notNull(connectionFactory, "ConnectionFactory must not be null!");
		Assert.notNull(sleepTime, "SleepTime must not be null!");

		this.connectionFactory = connectionFactory;
		this.sleepTime = sleepTime;
	}

	public static DefaultRedisCacheWriter nonLockingRedisCacheWriter(RedisConnectionFactory connectionFactory) {
		return new DefaultRedisCacheWriter(connectionFactory);
	}

	public static DefaultRedisCacheWriter lockingRedisCacheWriter(RedisConnectionFactory connectionFactory) {
		return new DefaultRedisCacheWriter(connectionFactory, Duration.ofMillis(50));
	}

	@Override
	public void put(String name, byte[] key, byte[] value, Duration ttl) {

		Assert.notNull(name, "Name must not be null!");
		Assert.notNull(key, "Key must not be null!");
		Assert.notNull(value, "Value must not be null!");
		Assert.notNull(ttl, "Ttl must not be null!");

		execute(name, connection -> {

			if (shouldExpireWithin(ttl)) {
				connection.set(key, value, Expiration.from(ttl.toMillis(), TimeUnit.MILLISECONDS), SetOption.upsert());
			} else {
				connection.set(key, value);
			}

			return "OK";
		});
	}

	@Override
	public byte[] get(String name, byte[] key) {

		Assert.notNull(name, "Name must not be null!");
		Assert.notNull(key, "Key must not be null!");

		return execute(name, connection -> connection.get(key));
	}

	@Override
	public byte[] putIfAbsent(String name, byte[] key, byte[] value, Duration ttl) {

		Assert.notNull(name, "Name must not be null!");
		Assert.notNull(key, "Key must not be null!");
		Assert.notNull(value, "Value must not be null!");
		Assert.notNull(ttl, "Ttl must not be null!");

		return execute(name, connection -> {

			if (connection.setNX(key, value)) {

				if (shouldExpireWithin(ttl)) {
					connection.pExpire(key, ttl.toMillis());
				}
				return null;
			}

			return connection.get(key);
		});
	}

	@Override
	public void remove(String name, byte[] key) {

		Assert.notNull(name, "Name must not be null!");
		Assert.notNull(key, "Key must not be null!");

		execute(name, connection -> connection.del(key));
	}

	public void lock(String name) {
		execute(name, connection -> doLock(name, connection));
	}

	private Boolean doLock(String name, RedisConnection connection) {
		return connection.setNX(createCacheLockKey(name), new byte[] {});
	}

	public void unlock(String name) {
		executeWithoutLockCheck(connection -> doUnlock(name, connection));
	}

	private Long doUnlock(String name, RedisConnection connection) {
		return connection.del(createCacheLockKey(name));
	}

	public boolean isLoked(String name) {
		return executeWithoutLockCheck(connection -> doCheckLock(name, connection));
	}

	private boolean doCheckLock(String name, RedisConnection connection) {
		return connection.exists(createCacheLockKey(name));
	}

	@Override
	public void clean(String name, byte[] pattern) {

		Assert.notNull(name, "Name must not be null!");
		Assert.notNull(pattern, "Pattern must not be null!");

		execute(name, connection -> {

			if (isLockingCacheWriter()) {
				doLock(name, connection);
			}

			try {
				if (connection instanceof RedisClusterConnection) {

					byte[][] keys = connection.keys(pattern).stream().toArray(size -> new byte[size][]);
					connection.del(keys);
				} else {
					connection.eval(CLEAN_SCRIPT, ReturnType.INTEGER, 0, pattern);
				}
			} finally {

				if (isLockingCacheWriter()) {
					doUnlock(name, connection);
				}
			}

			return "OK";
		});
	}

	public <T> T execute(String name, ConnectionCallback<T> callback) {

		RedisConnection connection = connectionFactory.getConnection();
		try {

			checkAndPotentiallyWaitUntilUnlocked(name, connection);
			return callback.doWithConnection(connection);
		} finally {
			connection.close();
		}
	}

	private <T> T executeWithoutLockCheck(ConnectionCallback<T> callback) {

		RedisConnection connection = connectionFactory.getConnection();

		try {
			return callback.doWithConnection(connection);
		} finally {
			connection.close();
		}
	}

	public boolean isLockingCacheWriter() {
		return !sleepTime.isZero() && !sleepTime.isNegative();
	}

	private void checkAndPotentiallyWaitUntilUnlocked(String name, RedisConnection connection) {

		if (isLockingCacheWriter()) {

			long timeout = sleepTime.toMillis();

			while (doCheckLock(name, connection)) {
				try {
					Thread.sleep(timeout);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	private boolean shouldExpireWithin(Duration ttl) {
		return ttl != null && !ttl.isZero() && !ttl.isNegative();
	}

	byte[] createCacheLockKey(String name) {
		return (name + "~lock").getBytes(Charset.forName("UTF-8"));
	}

	interface ConnectionCallback<T> {
		T doWithConnection(RedisConnection connection);
	}
}
