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

import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

import org.springframework.data.redis.SettingsUtils;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.OxmSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.oxm.xstream.XStreamMarshaller;

/**
 * @author Christoph Strobl
 */
class CacheTestParams {

	private static Collection<RedisConnectionFactory> connectionFactories() {

		JedisConnectionFactory jedisConnectionFactory = new JedisConnectionFactory();
		jedisConnectionFactory.setPort(SettingsUtils.getPort());
		jedisConnectionFactory.setHostName(SettingsUtils.getHost());
		jedisConnectionFactory.afterPropertiesSet();

		LettuceConnectionFactory lettuceConnectionFactory = new LettuceConnectionFactory();
		lettuceConnectionFactory.setPort(SettingsUtils.getPort());
		lettuceConnectionFactory.setHostName(SettingsUtils.getHost());
		lettuceConnectionFactory.afterPropertiesSet();

		return Arrays.asList(new FixDamnedJunitParameterizedNameForConnectionFactory(jedisConnectionFactory),
				new FixDamnedJunitParameterizedNameForConnectionFactory(lettuceConnectionFactory));
	}

	static Collection<Object[]> justConnectionFactories() {
		return connectionFactories().stream().map(factory -> new Object[] { factory }).collect(Collectors.toList());
	}

	static Collection<Object[]> connectionFactoriesAndSerializers() {

		// XStream serializer
		XStreamMarshaller xstream = new XStreamMarshaller();
		xstream.afterPropertiesSet();

		OxmSerializer oxmSerializer = new OxmSerializer(xstream, xstream);
		GenericJackson2JsonRedisSerializer jackson2Serializer = new GenericJackson2JsonRedisSerializer();
		JdkSerializationRedisSerializer jdkSerializer = new JdkSerializationRedisSerializer();

		return connectionFactories()
				.stream().flatMap(factory -> Arrays
						.asList( //
								new Object[] { factory, new FixDamnedJunitParameterizedNameForRedisSerializer(jdkSerializer) }, //
								new Object[] { factory, new FixDamnedJunitParameterizedNameForRedisSerializer(jackson2Serializer) }, //
								new Object[] { factory, new FixDamnedJunitParameterizedNameForRedisSerializer(oxmSerializer) })
						.stream())
				.collect(Collectors.toList());
	}

	@RequiredArgsConstructor
	static class FixDamnedJunitParameterizedNameForConnectionFactory/* ¯\_(ツ)_/¯ */ implements RedisConnectionFactory {

		final @Delegate RedisConnectionFactory connectionFactory;

		@Override // Why Junit? Why?
		public String toString() {
			return connectionFactory.getClass().getSimpleName();
		}
	}

	@RequiredArgsConstructor
	static class FixDamnedJunitParameterizedNameForRedisSerializer/* ¯\_(ツ)_/¯ */ implements RedisSerializer {

		final @Delegate RedisSerializer serializer;

		@Override // Why Junit? Why?
		public String toString() {
			return serializer.getClass().getSimpleName();
		}
	}
}
