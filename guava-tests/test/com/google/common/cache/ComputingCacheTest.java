/*
 * Copyright (C) 2011 The Guava Authors
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

package com.google.common.cache;

import static com.google.common.cache.CacheBuilder.EMPTY_STATS;
import static com.google.common.cache.CustomConcurrentHashMapTest.SMALL_MAX_SIZE;
import static com.google.common.cache.TestingCacheLoaders.identityLoader;
import static org.junit.contrib.truth.Truth.ASSERT;

import com.google.common.cache.CacheBuilder.NullCache;
import com.google.common.cache.CustomConcurrentHashMap.Segment;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.testing.NullPointerTester;

import junit.framework.TestCase;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Charles Fry
 */
public class ComputingCacheTest extends TestCase {

  private static <K, V> ComputingCache<K, V> makeCache(
      CacheBuilder<K, V> builder, CacheLoader<? super K, V> loader) {
    return new ComputingCache<K, V>(builder, CacheBuilder.CACHE_STATS_COUNTER, loader);
  }

  private static <K, V> NullCache<K, V> makeNullCache(
      CacheBuilder<K, V> builder, CacheLoader<? super K, V> loader) {
    if (builder.nullRemovalCause == null) {
      builder.nullRemovalCause = RemovalCause.SIZE;
    }
    return new NullCache<K, V>(builder, CacheBuilder.CACHE_STATS_COUNTER, loader);
  }

  private CacheBuilder<Object, Object> createCacheBuilder() {
    return new CacheBuilder<Object, Object>();
  }

  // constructor tests

  public void testComputingFunction() {
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object from) {
        return new Object();
      }
    };
    ComputingCache<Object, Object> cache = makeCache(createCacheBuilder(), loader);
    assertSame(loader, cache.map.loader);
  }

  // null parameters test

  public void testNullParameters() throws Exception {
    NullPointerTester tester = new NullPointerTester();
    CacheLoader<Object, Object> loader = identityLoader();
    tester.testAllPublicInstanceMethods(makeCache(createCacheBuilder(), loader));
  }

  // stats tests

  public void testStats() {
    CacheBuilder<Object, Object> builder = createCacheBuilder()
        .concurrencyLevel(1)
        .maximumSize(2);
    ComputingCache<Object, Object> cache = makeCache(builder, identityLoader());
    assertEquals(EMPTY_STATS, cache.stats());

    Object one = new Object();
    cache.getUnchecked(one);
    CacheStats stats = cache.stats();
    assertEquals(1, stats.requestCount());
    assertEquals(0, stats.hitCount());
    assertEquals(0.0, stats.hitRate());
    assertEquals(1, stats.missCount());
    assertEquals(1.0, stats.missRate());
    assertEquals(1, stats.loadCount());
    long totalLoadTime = stats.totalLoadTime();
    assertTrue(totalLoadTime > 0);
    assertTrue(stats.averageLoadPenalty() > 0.0);
    assertEquals(0, stats.evictionCount());

    cache.getUnchecked(one);
    stats = cache.stats();
    assertEquals(2, stats.requestCount());
    assertEquals(1, stats.hitCount());
    assertEquals(1.0/2, stats.hitRate());
    assertEquals(1, stats.missCount());
    assertEquals(1.0/2, stats.missRate());
    assertEquals(1, stats.loadCount());
    assertEquals(0, stats.evictionCount());

    Object two = new Object();
    cache.getUnchecked(two);
    stats = cache.stats();
    assertEquals(3, stats.requestCount());
    assertEquals(1, stats.hitCount());
    assertEquals(1.0/3, stats.hitRate());
    assertEquals(2, stats.missCount());
    assertEquals(2.0/3, stats.missRate());
    assertEquals(2, stats.loadCount());
    assertTrue(stats.totalLoadTime() > totalLoadTime);
    totalLoadTime = stats.totalLoadTime();
    assertTrue(stats.averageLoadPenalty() > 0.0);
    assertEquals(0, stats.evictionCount());

    Object three = new Object();
    cache.getUnchecked(three);
    stats = cache.stats();
    assertEquals(4, stats.requestCount());
    assertEquals(1, stats.hitCount());
    assertEquals(1.0/4, stats.hitRate());
    assertEquals(3, stats.missCount());
    assertEquals(3.0/4, stats.missRate());
    assertEquals(3, stats.loadCount());
    assertTrue(stats.totalLoadTime() > totalLoadTime);
    totalLoadTime = stats.totalLoadTime();
    assertTrue(stats.averageLoadPenalty() > 0.0);
    assertEquals(1, stats.evictionCount());
  }

  public void testStatsNoops() {
    CacheBuilder<Object, Object> builder = createCacheBuilder()
        .concurrencyLevel(1);
    ComputingCache<Object, Object> cache = makeCache(builder, identityLoader());
    ConcurrentMap<Object, Object> map = cache.map; // mofidiable map view
    assertEquals(EMPTY_STATS, cache.stats());

    Object one = new Object();
    assertNull(map.put(one, one));
    assertSame(one, map.get(one));
    assertTrue(map.containsKey(one));
    assertTrue(map.containsValue(one));
    Object two = new Object();
    assertSame(one, map.replace(one, two));
    assertTrue(map.containsKey(one));
    assertFalse(map.containsValue(one));
    Object three = new Object();
    assertTrue(map.replace(one, two, three));
    assertTrue(map.remove(one, three));
    assertFalse(map.containsKey(one));
    assertFalse(map.containsValue(one));
    assertNull(map.putIfAbsent(two, three));
    assertSame(three, map.remove(two));
    assertNull(map.put(three, one));
    assertNull(map.put(one, two));

    Set<Map.Entry<Object, Object>> entries = map.entrySet();
    ASSERT.that(entries).hasContentsAnyOrder(
        Maps.immutableEntry(three, one), Maps.immutableEntry(one, two));
    Set<Object> keys = map.keySet();
    ASSERT.that(keys).hasContentsAnyOrder(one, three);
    Collection<Object> values = map.values();
    ASSERT.that(values).hasContentsAnyOrder(one, two);

    map.clear();

    assertEquals(EMPTY_STATS, cache.stats());
  }

  public void testNullStats() {
    CacheBuilder<Object, Object> builder = createCacheBuilder();
    NullCache<Object, Object> cache = makeNullCache(builder, identityLoader());
    assertEquals(EMPTY_STATS, cache.stats());

    Object one = new Object();
    cache.getUnchecked(one);
    CacheStats stats = cache.stats();
    assertEquals(1, stats.requestCount());
    assertEquals(0, stats.hitCount());
    assertEquals(0.0, stats.hitRate());
    assertEquals(1, stats.missCount());
    assertEquals(1.0, stats.missRate());
    assertEquals(1, stats.loadCount());
    long totalLoadTime = stats.totalLoadTime();
    assertTrue(totalLoadTime > 0);
    assertTrue(stats.averageLoadPenalty() > 0.0);
    assertEquals(1, stats.evictionCount());

    cache.getUnchecked(one);
    stats = cache.stats();
    assertEquals(2, stats.requestCount());
    assertEquals(0, stats.hitCount());
    assertEquals(0.0, stats.hitRate());
    assertEquals(2, stats.missCount());
    assertEquals(1.0, stats.missRate());
    assertEquals(2, stats.loadCount());
    assertTrue(stats.totalLoadTime() > totalLoadTime);
    totalLoadTime = stats.totalLoadTime();
    assertEquals(2, stats.evictionCount());
  }

  // asMap tests

  public void testAsMap() {
    CacheBuilder<Object, Object> builder = createCacheBuilder();
    ComputingCache<Object, Object> cache = makeCache(builder, identityLoader());
    assertEquals(EMPTY_STATS, cache.stats());

    Object one = new Object();
    Object two = new Object();
    Object three = new Object();

    ConcurrentMap<Object, Object> map = cache.asMap();
    try {
      map.put(one, two);
    } catch (UnsupportedOperationException expected) {}
    try {
      Map<Object, Object> newMap = ImmutableMap.of(one, two);
      map.putAll(newMap);
    } catch (UnsupportedOperationException expected) {}
    try {
      map.putIfAbsent(one, two);
    } catch (UnsupportedOperationException expected) {}
    try {
      map.replace(one, two);
    } catch (UnsupportedOperationException expected) {}
    try {
      map.replace(one, two, three);
    } catch (UnsupportedOperationException expected) {}

    assertTrue(map.isEmpty());
    assertEquals(0, map.size());

    cache.getUnchecked(one);
    assertEquals(1, map.size());
    assertSame(one, map.get(one));
    assertTrue(map.containsKey(one));
    assertTrue(map.containsValue(one));
    assertSame(one, map.remove(one));
    assertEquals(0, map.size());

    cache.getUnchecked(one);
    assertEquals(1, map.size());
    assertFalse(map.remove(one, two));
    assertTrue(map.remove(one, one));
    assertEquals(0, map.size());

    cache.getUnchecked(one);
    Map<Object, Object> newMap = ImmutableMap.of(one, one);
    assertEquals(newMap, map);
    assertEquals(newMap.entrySet(), map.entrySet());
    assertEquals(newMap.keySet(), map.keySet());
    Set<Object> expectedValues = ImmutableSet.of(one);
    Set<Object> actualValues = ImmutableSet.copyOf(map.values());
    assertEquals(expectedValues, actualValues);
  }

  /**
   * Lookups on the map view shouldn't impact the recency queue.
   */
  public void testAsMapRecency() {
    CacheBuilder<Object, Object> builder = createCacheBuilder()
        .concurrencyLevel(1)
        .maximumSize(SMALL_MAX_SIZE);
    ComputingCache<Object, Object> cache = makeCache(builder, identityLoader());
    Segment<Object, Object> segment = cache.map.segments[0];
    ConcurrentMap<Object, Object> map = cache.asMap();

    Object one = new Object();
    assertSame(one, cache.getUnchecked(one));
    assertTrue(segment.recencyQueue.isEmpty());
    assertSame(one, map.get(one));
    assertTrue(segment.recencyQueue.isEmpty());
    assertSame(one, cache.getUnchecked(one));
    assertFalse(segment.recencyQueue.isEmpty());
  }

  public void testRecursiveComputation() throws InterruptedException {
    final AtomicReference<Cache<Integer, String>> cacheRef =
        new AtomicReference<Cache<Integer, String>>();
    CacheLoader<Integer, String> recursiveLoader = new CacheLoader<Integer, String>() {
      @Override
      public String load(Integer key) {
        if (key > 0) {
          return key + ", " + cacheRef.get().getUnchecked(key - 1);
        } else {
          return "0";
        }
      }
    };

    Cache<Integer, String> recursiveCache = new CacheBuilder<Integer, String>()
        .weakKeys()
        .weakValues()
        .build(recursiveLoader);
    cacheRef.set(recursiveCache);
    assertEquals("3, 2, 1, 0", recursiveCache.getUnchecked(3));

    recursiveLoader = new CacheLoader<Integer, String>() {
      @Override
      public String load(Integer key) {
        return cacheRef.get().getUnchecked(key);
      }
    };

    recursiveCache = new CacheBuilder<Integer, String>()
        .weakKeys()
        .weakValues()
        .build(recursiveLoader);
    cacheRef.set(recursiveCache);

    // tells the test when the compution has completed
    final CountDownLatch doneSignal = new CountDownLatch(1);

    Thread thread = new Thread() {
      @Override
      public void run() {
        try {
          cacheRef.get().getUnchecked(3);
        } finally {
          doneSignal.countDown();
        }
      }
    };
    thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
      @Override
      public void uncaughtException(Thread t, Throwable e) {}
    });
    thread.start();

    boolean done = doneSignal.await(1, TimeUnit.SECONDS);
    if (!done) {
      StringBuilder builder = new StringBuilder();
      for (StackTraceElement trace : thread.getStackTrace()) {
        builder.append("\tat ").append(trace).append('\n');
      }
      fail(builder.toString());
    }
  }
}