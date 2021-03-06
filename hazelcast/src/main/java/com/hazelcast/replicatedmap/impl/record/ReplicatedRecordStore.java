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

package com.hazelcast.replicatedmap.impl.record;

import com.hazelcast.replicatedmap.merge.ReplicatedMapMergePolicy;
import com.hazelcast.util.scheduler.ScheduledEntry;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * This interface describes a common record store for replicated maps and their actual records
 */
public interface ReplicatedRecordStore {

    String getName();

    Object remove(Object key);

    void evict(Object key);

    Object get(Object key);

    Object put(Object key, Object value);

    Object put(Object key, Object value, long ttl, TimeUnit timeUnit, boolean incrementHits);

    boolean containsKey(Object key);

    boolean containsValue(Object value);

    ReplicatedRecord getReplicatedRecord(Object key);

    Set keySet(boolean lazy);

    Collection values(boolean lazy);

    Collection values(Comparator comparator);

    Set entrySet(boolean lazy);

    int size();

    void clear();

    void reset();

    boolean isEmpty();

    Object unmarshall(Object key);

    Object marshall(Object key);

    void destroy();

    long getVersion();

    void setVersion(long version);

    Iterator<ReplicatedRecord> recordIterator();

    void putRecord(RecordMigrationInfo record);

    InternalReplicatedMapStorage getStorage();

    ScheduledEntry<Object, Object> cancelTtlEntry(Object key);

    boolean scheduleTtlEntry(long delayMillis, Object key, Object object);

    boolean isLoaded();

    void setLoaded(boolean loaded);

    boolean merge(Object key, ReplicatedMapEntryView entryView, ReplicatedMapMergePolicy policy);
}
