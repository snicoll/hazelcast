/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.map.impl.mapstore;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapStoreConfig;
import com.hazelcast.config.MapStoreConfig.InitialLoadMode;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.MapStore;
import com.hazelcast.core.MapStoreAdapter;
import com.hazelcast.core.TransactionalMap;
import com.hazelcast.instance.GroupProperty;
import com.hazelcast.map.impl.MapService;
import com.hazelcast.map.impl.MapServiceContext;
import com.hazelcast.map.impl.mapstore.writebehind.WriteBehindStore;
import com.hazelcast.map.impl.recordstore.RecordStore;
import com.hazelcast.map.impl.mapstore.MapStoreTest.MapStoreWithStoreCount;
import com.hazelcast.map.impl.mapstore.MapStoreTest.SimpleMapStore;
import com.hazelcast.map.impl.mapstore.MapStoreTest.TestEventBasedMapStore;
import com.hazelcast.map.impl.mapstore.MapStoreTest.TestMapStore;
import com.hazelcast.map.impl.mapstore.writebehind.TestMapUsingMapStoreBuilder;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.test.AssertTask;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import com.hazelcast.transaction.TransactionContext;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author enesakar 1/21/13
 */
@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class MapStoreWriteBehindTest extends AbstractMapStoreTest {

    @Test(timeout = 120000)
    public void testOneMemberWriteBehindWithMaxIdle() throws Exception {
        final TestEventBasedMapStore testMapStore = new TestEventBasedMapStore();
        Config config = newConfig(testMapStore, 5, InitialLoadMode.EAGER);
        config.setProperty(GroupProperty.PARTITION_COUNT, "1");
        config.getMapConfig("default").setMaxIdleSeconds(10);
        HazelcastInstance h1 = createHazelcastInstance(config);
        final IMap map = h1.getMap("default");

        final int total = 10;

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(TestEventBasedMapStore.STORE_EVENTS.LOAD_ALL_KEYS, testMapStore.getEvents().poll());
            }
        });

        for (int i = 0; i < total; i++) {
            map.put(i, "value" + i);
        }

        sleepSeconds(11);
        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(0, map.size());
            }
        });
        assertEquals(total, testMapStore.getStore().size());
    }

    @Test(timeout = 120000)
    public void testOneMemberWriteBehindWithEvictions() throws Exception {
        final String mapName = "testOneMemberWriteBehindWithEvictions";
        final TestEventBasedMapStore testMapStore = new TestEventBasedMapStore();
        testMapStore.loadAllLatch = new CountDownLatch(1);
        final Config config = newConfig(testMapStore, 2, InitialLoadMode.EAGER);
        final HazelcastInstance node1 = createHazelcastInstance(config);
        final IMap map = node1.getMap(mapName);
        // check if load all called.
        assertTrue("map store loadAllKeys must be called", testMapStore.loadAllLatch.await(10, TimeUnit.SECONDS));
        // map population count.
        final int populationCount = 100;
        // latch for store & storeAll events.
        testMapStore.storeLatch = new CountDownLatch(populationCount);
        //populate map.
        for (int i = 0; i < populationCount; i++) {
            map.put(i, "value" + i);
        }
        //wait for all store ops.
        assertOpenEventually(testMapStore.storeLatch);
        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(0, writeBehindQueueSize(node1, mapName));
            }
        });

        // init before eviction.
        testMapStore.storeLatch = new CountDownLatch(populationCount);
        //evict.
        for (int i = 0; i < populationCount; i++) {
            map.evict(i);
        }
        //expect no store op.
        assertEquals(populationCount, testMapStore.storeLatch.getCount());
        //check store size
        assertEquals(populationCount, testMapStore.getStore().size());
        //check map size
        assertEquals(0, map.size());
        //re-populate map.
        for (int i = 0; i < populationCount; i++) {
            map.put(i, "value" + i);
        }
        //evict again.
        for (int i = 0; i < populationCount; i++) {
            map.evict(i);
        }
        //wait for all store ops.
        testMapStore.storeLatch.await(10, TimeUnit.SECONDS);
        //check store size
        assertEquals(populationCount, testMapStore.getStore().size());
        //check map size
        assertEquals(0, map.size());

        //re-populate map.
        for (int i = 0; i < populationCount; i++) {
            map.put(i, "value" + i);
        }
        testMapStore.deleteLatch = new CountDownLatch(populationCount);
        //clear map.
        for (int i = 0; i < populationCount; i++) {
            map.remove(i);
        }
        testMapStore.deleteLatch.await(10, TimeUnit.SECONDS);
        //check map size
        assertEquals(0, map.size());
    }

    private int writeBehindQueueSize(HazelcastInstance node, String mapName) {
        int size = 0;
        final NodeEngineImpl nodeEngine = getNode(node).getNodeEngine();
        MapService mapService = nodeEngine.getService(MapService.SERVICE_NAME);
        final MapServiceContext mapServiceContext = mapService.getMapServiceContext();
        final int partitionCount = nodeEngine.getPartitionService().getPartitionCount();
        for (int i = 0; i < partitionCount; i++) {
            final RecordStore recordStore = mapServiceContext.getExistingRecordStore(i, mapName);
            if (recordStore == null) {
                continue;
            }
            final MapDataStore<Data, Object> mapDataStore
                    = recordStore.getMapDataStore();
            size += ((WriteBehindStore) mapDataStore).getWriteBehindQueue().size();
        }
        return size;
    }


    @Test(timeout = 120000)
    public void testOneMemberWriteBehind() throws Exception {
        MapStoreTest.TestMapStore testMapStore = new TestMapStore(1, 1, 1);
        testMapStore.setLoadAllKeys(false);
        Config config = newConfig(testMapStore, 2);
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(3);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        testMapStore.insert("1", "value1");
        IMap map = h1.getMap("default");
        assertEquals(0, map.size());
        assertEquals("value1", map.get("1"));
        assertEquals("value1", map.put("1", "value2"));
        assertEquals("value2", map.get("1"));
        // store should have the old data as we will write-behind
        assertEquals("value1", testMapStore.getStore().get("1"));
        assertEquals(1, map.size());
        map.flush();
        assertTrue(map.evict("1"));
        assertEquals("value2", testMapStore.getStore().get("1"));
        assertEquals(0, map.size());
        assertEquals(1, testMapStore.getStore().size());
        assertEquals("value2", map.get("1"));
        assertEquals(1, map.size());
        map.remove("1");
        // store should have the old data as we will delete-behind
        assertEquals(1, testMapStore.getStore().size());
        assertEquals(0, map.size());
        testMapStore.assertAwait(12);
        assertEquals(0, testMapStore.getStore().size());
    }

    @Test(timeout = 120000)
    public void testWriteBehindUpdateSameKey() throws Exception {
        final TestMapStore testMapStore = new TestMapStore(2, 0, 0);
        testMapStore.setLoadAllKeys(false);
        Config config = newConfig(testMapStore, 5);
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        HazelcastInstance h2 = nodeFactory.newHazelcastInstance(config);
        IMap<Object, Object> map = h1.getMap("map");
        map.put("key", "value");
        Thread.sleep(2000);
        map.put("key", "value2");
        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals("value2", testMapStore.getStore().get("key"));
            }
        });
    }

    @Test(timeout = 120000)
    public void testOneMemberWriteBehindFlush() throws Exception {
        TestMapStore testMapStore = new TestMapStore(1, 1, 1);
        testMapStore.setLoadAllKeys(false);
        Config config = newConfig(testMapStore, 2);
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(3);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        IMap map = h1.getMap("default");
        assertEquals(0, map.size());
        assertEquals(null, map.put("1", "value1"));
        assertEquals("value1", map.get("1"));
        assertEquals(null, testMapStore.getStore().get("1"));
        assertEquals(1, map.size());
        map.flush();
        assertEquals("value1", testMapStore.getStore().get("1"));
    }

    @Test(timeout = 120000)
    public void testOneMemberWriteBehind2() throws Exception {
        final TestEventBasedMapStore testMapStore = new TestEventBasedMapStore();
        testMapStore.setLoadAllKeys(false);
        Config config = newConfig(testMapStore, 1, InitialLoadMode.EAGER);
        HazelcastInstance h1 = createHazelcastInstance(config);
        IMap map = h1.getMap("default");

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                Object event = testMapStore.getEvents().poll();
                assertEquals(TestEventBasedMapStore.STORE_EVENTS.LOAD_ALL_KEYS, event);
            }
        });

        map.put("1", "value1");

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(TestEventBasedMapStore.STORE_EVENTS.LOAD, testMapStore.getEvents().poll());
            }
        });

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(TestEventBasedMapStore.STORE_EVENTS.STORE, testMapStore.getEvents().poll());
            }
        });

        map.remove("1");

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(TestEventBasedMapStore.STORE_EVENTS.DELETE, testMapStore.getEvents().poll());
            }
        });

    }


    @Test(timeout = 120000)
    //issue#2747:when MapStore configured with write behind, distributed objects' destroy method does not work.
    public void testWriteBehindDestroy() throws InterruptedException {
        final int writeDelaySeconds = 3;
        String mapName = randomMapName();

        final MapStore<String, String> store = new SimpleMapStore<String, String>();

        Config config = newConfig(mapName, store, writeDelaySeconds);
        HazelcastInstance hzInstance = createHazelcastInstance(config);
        IMap<String, String> map = hzInstance.getMap(mapName);

        map.put("key", "value");

        map.destroy();

        sleepSeconds(2 * writeDelaySeconds);

        assertNotEquals("value", store.load("key"));
    }

    @Test(timeout = 120000)
    public void testKeysWithPredicateShouldLoadMapStore() throws InterruptedException {
        TestEventBasedMapStore testMapStore = new TestEventBasedMapStore()
                .insert("key1", 17)
                .insert("key2", 23)
                .insert("key3", 47);

        HazelcastInstance instance = createHazelcastInstance(newConfig(testMapStore, 0));
        final IMap map = instance.getMap("default");

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                Set result = map.keySet();
                assertTrue(result.contains("key1"));
                assertTrue(result.contains("key2"));
                assertTrue(result.contains("key3"));
            }
        });

    }

    @Test(timeout = 120000)
    public void testIssue1085WriteBehindBackup() throws InterruptedException {
        Config config = getConfig();
        String name = "testIssue1085WriteBehindBackup";
        MapConfig writeBehindBackup = config.getMapConfig(name);
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setWriteDelaySeconds(5);
        int size = 1000;
        MapStoreWithStoreCount mapStore = new MapStoreWithStoreCount(size, 120);
        mapStoreConfig.setImplementation(mapStore);
        writeBehindBackup.setMapStoreConfig(mapStoreConfig);
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(3);
        HazelcastInstance instance = factory.newHazelcastInstance(config);
        HazelcastInstance instance2 = factory.newHazelcastInstance(config);
        final IMap map = instance.getMap(name);
        for (int i = 0; i < size; i++) {
            map.put(i, i);
        }
        instance2.getLifecycleService().shutdown();
        mapStore.awaitStores();
    }

    @Test(timeout = 120000)
    public void testIssue1085WriteBehindBackupWithLongRunnigMapStore() throws InterruptedException {
        final String name = randomMapName("testIssue1085WriteBehindBackup");
        final int expectedStoreCount = 3;
        final int nodeCount = 3;
        Config config = getConfig();
        config.setProperty(GroupProperty.MAP_REPLICA_SCHEDULED_TASK_DELAY_SECONDS, "30");
        MapConfig writeBehindBackupConfig = config.getMapConfig(name);
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setWriteDelaySeconds(5);
        final MapStoreWithStoreCount mapStore = new MapStoreWithStoreCount(expectedStoreCount, 300, 50);
        mapStoreConfig.setImplementation(mapStore);
        writeBehindBackupConfig.setMapStoreConfig(mapStoreConfig);
        // create nodes.
        final TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(nodeCount);
        HazelcastInstance node1 = factory.newHazelcastInstance(config);
        HazelcastInstance node2 = factory.newHazelcastInstance(config);
        HazelcastInstance node3 = factory.newHazelcastInstance(config);
        // create corresponding keys.
        final String keyOwnedByNode1 = generateKeyOwnedBy(node1);
        final String keyOwnedByNode2 = generateKeyOwnedBy(node2);
        final String keyOwnedByNode3 = generateKeyOwnedBy(node3);
        // put one key value pair per node.
        final IMap map = node1.getMap(name);
        map.put(keyOwnedByNode1, 1);
        map.put(keyOwnedByNode2, 2);
        map.put(keyOwnedByNode3, 3);
        // shutdown node2.
        node2.getLifecycleService().shutdown();
        // wait store ops. finish.
        mapStore.awaitStores();
        // we should see at least expected store count.
        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                int storeOperationCount = mapStore.count.intValue();
                assertTrue("expected : " + expectedStoreCount
                        + ", actual : " + storeOperationCount, expectedStoreCount <= storeOperationCount);
            }
        });
    }


    @Test(timeout = 120000)
    public void testMapDelete_whenLoadFails() throws Exception {
        final FailingLoadMapStore mapStore = new FailingLoadMapStore();
        final IMap<Object, Object> map = TestMapUsingMapStoreBuilder.create()
                .withMapStore(mapStore)
                .withNodeCount(1)
                .withNodeFactory(createHazelcastInstanceFactory(1))
                .build();

        try {
            map.delete(1);
        } catch (IllegalStateException e) {
            fail();
        }
    }

    @Test(timeout = 120000, expected = IllegalStateException.class)
    public void testMapRemove_whenMapStoreLoadFails() throws Exception {
        final FailingLoadMapStore mapStore = new FailingLoadMapStore();
        final IMap<Object, Object> map = TestMapUsingMapStoreBuilder.create()
                .withMapStore(mapStore)
                .withNodeCount(1)
                .withNodeFactory(createHazelcastInstanceFactory(1))
                .build();

        map.remove(1);
    }

    @Test(timeout = 120000)
    public void testIssue1085WriteBehindBackupTransactional() throws InterruptedException {
        final String name = randomMapName();
        final int size = 1000;
        MapStoreTest.MapStoreWithStoreCount mapStore = new MapStoreTest.MapStoreWithStoreCount(size, 120);
        Config config = newConfig(name, mapStore, 5);

        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(3);
        HazelcastInstance instance = factory.newHazelcastInstance(config);
        HazelcastInstance instance2 = factory.newHazelcastInstance(config);

        TransactionContext context = instance.newTransactionContext();
        context.beginTransaction();
        TransactionalMap<Object, Object> tmap = context.getMap(name);
        for (int i = 0; i < size; i++) {
            tmap.put(i, i);
        }
        context.commitTransaction();
        instance2.getLifecycleService().shutdown();
        mapStore.awaitStores();
    }

    @Test(timeout = 120000)
    public void testWriteBehindSameSecondSameKey() throws Exception {
        final TestMapStore testMapStore = new TestMapStore(100, 0, 0); // In some cases 2 store operation may happened
        testMapStore.setLoadAllKeys(false);
        Config config = newConfig(testMapStore, 2);
        HazelcastInstance h1 = createHazelcastInstance(config);
        IMap<Object, Object> map = h1.getMap("testWriteBehindSameSecondSameKey");
        final int size1 = 20;
        final int size2 = 10;

        for (int i = 0; i < size1; i++) {
            map.put("key", "value" + i);
        }
        for (int i = 0; i < size2; i++) {
            map.put("key" + i, "value" + i);
        }


        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals("value" + (size1 - 1), testMapStore.getStore().get("key"));
            }
        });
        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals("value" + (size2 - 1), testMapStore.getStore().get("key" + (size2 - 1)));
            }
        });


    }

    @Test(timeout = 120000)
    public void testWriteBehindWriteRemoveOrderOfSameKey() throws Exception {
        final String mapName = randomMapName("_testWriteBehindWriteRemoveOrderOfSameKey_");
        final int iterationCount = 5;
        final int delaySeconds = 1;
        final int putOps = 3;
        final int removeOps = 2;
        final int expectedStoreSizeEventually = 1;
        final RecordingMapStore store = new RecordingMapStore(iterationCount * putOps, iterationCount * removeOps);
        final Config config = newConfig(mapName, store, delaySeconds);
        final HazelcastInstance node = createHazelcastInstance(config);
        final IMap<String, String> map = node.getMap(mapName);

        String key = "key";

        for (int i = 0; i < iterationCount; i++) {
            String value = "value" + i;

            map.put(key, value);
            map.remove(key);

            map.put(key, value);
            map.remove(key);

            map.put(key, value);
        }

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                assertEquals(expectedStoreSizeEventually, store.getStore().size());
            }
        });

        assertEquals("value" + (iterationCount - 1), map.get(key));
    }

    @Test(timeout = 120000)
    public void mapStore_setOnIMapDoesNotRemoveKeyFromWriteBehindDeleteQueue() throws Exception {
        MapStoreConfig mapStoreConfig = new MapStoreConfig()
                .setEnabled(true)
                .setImplementation(new SimpleMapStore<String, String>())
                .setWriteDelaySeconds(Integer.MAX_VALUE);

        Config config = getConfig();
        config.getMapConfig("map").setMapStoreConfig(mapStoreConfig);

        HazelcastInstance instance = createHazelcastInstance(config);
        IMap<String, String> map = instance.getMap("map");
        map.put("foo", "bar");
        map.remove("foo");
        map.set("foo", "bar");

        assertEquals("bar", map.get("foo"));
    }

    @Test(timeout = 120000)
    public void testDelete_thenPutIfAbsent_withWriteBehindEnabled() throws Exception {
        TestMapStore testMapStore = new TestMapStore(1, 1, 1);
        Config config = newConfig(testMapStore, 100);
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(1);
        HazelcastInstance instance = nodeFactory.newHazelcastInstance(config);
        IMap map = instance.getMap("default");
        map.put(1, 1);
        map.delete(1);
        final Object putIfAbsent = map.putIfAbsent(1, 2);

        assertNull(putIfAbsent);
    }

    public static class RecordingMapStore implements MapStore<String, String> {

        private static final boolean DEBUG = false;

        private final CountDownLatch expectedStore;
        private final CountDownLatch expectedRemove;

        private final ConcurrentHashMap<String, String> store;

        public RecordingMapStore(int expectedStore, int expectedRemove) {
            this.expectedStore = new CountDownLatch(expectedStore);
            this.expectedRemove = new CountDownLatch(expectedRemove);
            this.store = new ConcurrentHashMap<String, String>();
        }

        public ConcurrentHashMap<String, String> getStore() {
            return store;
        }

        @Override
        public String load(String key) {
            log("load(" + key + ") called.");
            return store.get(key);
        }

        @Override
        public Map<String, String> loadAll(Collection<String> keys) {
            if (DEBUG) {
                List<String> keysList = new ArrayList<String>(keys);
                Collections.sort(keysList);
                log("loadAll(" + keysList + ") called.");
            }
            Map<String, String> result = new HashMap<String, String>();
            for (String key : keys) {
                String value = store.get(key);
                if (value != null) {
                    result.put(key, value);
                }
            }
            return result;
        }

        @Override
        public Set<String> loadAllKeys() {
            log("loadAllKeys() called.");
            Set<String> result = new HashSet<String>(store.keySet());
            log("loadAllKeys result = " + result);
            return result;
        }

        @Override
        public void store(String key, String value) {
            log("store(" + key + ") called.");
            String valuePrev = store.put(key, value);
            expectedStore.countDown();
            if (valuePrev != null) {
                log("- Unexpected Update (operations reordered?): " + key);
            }
        }

        @Override
        public void storeAll(Map<String, String> map) {
            if (DEBUG) {
                TreeSet<String> setSorted = new TreeSet<String>(map.keySet());
                log("storeAll(" + setSorted + ") called.");
            }
            store.putAll(map);
            final int size = map.keySet().size();
            for (int i = 0; i < size; i++) {
                expectedStore.countDown();
            }
        }

        @Override
        public void delete(String key) {
            log("delete(" + key + ") called.");
            String valuePrev = store.remove(key);
            expectedRemove.countDown();
            if (valuePrev == null) {
                log("- Unnecessary delete (operations reordered?): " + key);
            }
        }

        @Override
        public void deleteAll(Collection<String> keys) {
            if (DEBUG) {
                List<String> keysList = new ArrayList<String>(keys);
                Collections.sort(keysList);
                log("deleteAll(" + keysList + ") called.");
            }
            for (String key : keys) {
                String valuePrev = store.remove(key);
                expectedRemove.countDown();
                if (valuePrev == null) {
                    log("- Unnecessary delete (operations reordered?): " + key);
                }
            }
        }

        public void awaitStores() {
            assertOpenEventually(expectedStore);
        }

        public void awaitRemoves() {
            assertOpenEventually(expectedRemove);
        }

        private void log(String msg) {
            if (DEBUG) {
                System.out.println(msg);
            }
        }

    }


    public static class FailAwareMapStore implements MapStore {

        final Map db = new ConcurrentHashMap();

        final AtomicLong deletes = new AtomicLong();
        final AtomicLong deleteAlls = new AtomicLong();
        final AtomicLong stores = new AtomicLong();
        final AtomicLong storeAlls = new AtomicLong();
        final AtomicLong loads = new AtomicLong();
        final AtomicLong loadAlls = new AtomicLong();
        final AtomicLong loadAllKeys = new AtomicLong();
        final AtomicBoolean storeFail = new AtomicBoolean(false);
        final AtomicBoolean loadFail = new AtomicBoolean(false);
        final List<BlockingQueue> listeners = new CopyOnWriteArrayList<BlockingQueue>();

        public void addListener(BlockingQueue obj) {
            listeners.add(obj);
        }

        public void notifyListeners() {
            for (BlockingQueue listener : listeners) {
                listener.offer(new Object());
            }
        }

        public void delete(Object key) {
            try {
                if (storeFail.get()) {
                    throw new RuntimeException();
                } else {
                    db.remove(key);
                }
            } finally {
                deletes.incrementAndGet();
                notifyListeners();
            }
        }

        public void setFail(boolean shouldFail, boolean loadFail) {
            this.storeFail.set(shouldFail);
            this.loadFail.set(loadFail);
        }

        public int dbSize() {
            return db.size();
        }

        public boolean dbContainsKey(Object key) {
            return db.containsKey(key);
        }

        public Object dbGet(Object key) {
            return db.get(key);
        }

        public void store(Object key, Object value) {
            try {
                if (storeFail.get()) {
                    throw new RuntimeException();
                } else {
                    db.put(key, value);
                }
            } finally {
                stores.incrementAndGet();
                notifyListeners();
            }
        }

        public Set loadAllKeys() {
            try {
                return db.keySet();
            } finally {
                loadAllKeys.incrementAndGet();
            }
        }

        public Object load(Object key) {
            try {
                if (loadFail.get()) {
                    throw new RuntimeException();
                } else {
                    return db.get(key);
                }
            } finally {
                loads.incrementAndGet();
            }
        }

        public void storeAll(Map map) {
            try {
                if (storeFail.get()) {
                    throw new RuntimeException();
                } else {
                    db.putAll(map);
                }
            } finally {
                storeAlls.incrementAndGet();
                notifyListeners();
            }
        }

        public Map loadAll(Collection keys) {
            try {
                if (loadFail.get()) {
                    throw new RuntimeException();
                } else {
                    Map results = new HashMap();
                    for (Object key : keys) {
                        Object value = db.get(key);
                        if (value != null) {
                            results.put(key, value);
                        }
                    }
                    return results;
                }
            } finally {
                loadAlls.incrementAndGet();
                notifyListeners();
            }
        }

        public void deleteAll(Collection keys) {
            try {
                if (storeFail.get()) {
                    throw new RuntimeException();
                } else {
                    for (Object key : keys) {
                        db.remove(key);
                    }
                }
            } finally {
                deleteAlls.incrementAndGet();
                notifyListeners();
            }
        }
    }


    class FailingLoadMapStore extends MapStoreAdapter {
        @Override
        public Object load(Object key) {
            throw new IllegalStateException();
        }
    }

}
