/*
 * Copyright 2013 Tim Roes <tim.roes@inovex.de>.
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
package de.inovex.andsync.manager;

import de.inovex.andsync.cache.lucene.LuceneCache;
import java.util.concurrent.ScheduledFuture;
import com.mongodb.BasicDBObject;
import java.util.Map;
import java.util.Set;
import android.util.Log;
import com.mongodb.BasicDBList;
import de.inovex.andsync.cache.Cache;
import com.mongodb.DBObject;
import com.mongodb.DBRef;
import de.inovex.andsync.rest.RestClient;
import de.inovex.andsync.rest.RestClient.RestResponse;
import de.inovex.andsync.rest.RestException;
import de.inovex.andsync.util.Base64;
import de.inovex.andsync.util.BsonConverter;
import de.inovex.andsync.util.TimeUtil;
import de.inovex.jmom.FieldList;
import de.inovex.jmom.Storage;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.lucene.util.NamedThreadFactory;
import org.bson.types.ObjectId;
import static de.inovex.andsync.Constants.*;

/**
 *
 * @author Tim Roes <tim.roes@inovex.de>
 */
class RestStorageHandler implements Storage.DBHandler {

	private RepeatingRestClient mRest;
	private CallCollector mCallCollector;
	private Cache mCache;

	public RestStorageHandler(RestClient restClient, Cache cache) {
		assert restClient != null && cache != null;
		this.mCallCollector = new CallCollector();
		this.mRest = new RepeatingRestClient(restClient);
		this.mCache = cache;
	}

	public void onSave(final String collection, final DBObject dbo) {
		if (dbo.containsField("_id")) {
			// Object already has an id, update object
			// Put the object also in cache (see CacheStorageHandler.onSave() for explanation)
			mCache.put(collection, dbo);
			updateObject(collection, dbo);
		} else {
			// Object is new in storage
			// Create an id for the DBObject.
			dbo.put("_id", ObjectId.get());
			// Put the object also in cache (see CacheStorageHandler.onSave() for explanation)
			mCache.put(collection, dbo);
			newObject(collection, dbo);
		}
	}

	private void updateObject(final String collection, final DBObject dbo) {
		final byte[] data = BsonConverter.toByteArrayList(dbo);
		mRest.post(data, REST_OBJECT_PATH, collection);
	}

	private void newObject(final String collection, final DBObject dbo) {
		final byte[] data = BsonConverter.toByteArrayList(dbo);
		mRest.put(data, REST_OBJECT_PATH, collection);
	}

	public Collection<DBObject> onGet(final String collection, final FieldList fl) {

		RestResponse response;

		response = mRest.get(REST_OBJECT_PATH, collection);

		if (response.data == null) {
			return null;
		}

		final List<DBObject> objects = BsonConverter.fromBsonList(response.data);

		long beginCacheUpdate = TimeUtil.getTimestamp();

		// Save all retrieved documents in cache
		mCache.put(collection, objects);
		// Delete all objects from cache, that doesn't exist on the server anymore
		// -> Haven't been updated in this session (so update timestamp is older than beginCacheUpdate
		mCache.delete(collection, beginCacheUpdate);

		return objects;

	}

	public DBRef onCreateRef(String collection, DBObject dbo) {
		return new DBRef(null, collection, dbo.get("_id"));
	}

	public DBObject onFetchRef(DBRef dbref) {

		if (dbref == null || dbref.getId() == null) {
			return null;
		}

		DBObject dbobj = mCallCollector.callForId(dbref.getRef(), (ObjectId) dbref.getId());
		mCache.put(dbref.getRef(), dbobj);
		return dbobj;

	}

	public void onDelete(String collection, ObjectId oi) {
		mRest.delete(REST_OBJECT_PATH, collection, oi.toString());
	}

	private interface RestCall {

		RestResponse doCall() throws RestException;
	}

	/**
	 * Keeps retrying REST calls to the server if connection fails.
	 */
	private class RepeatingRestClient extends RestClient {

		/**
		 * The wrapped {@link RestClient} to use for communication.
		 */
		private RestClient mWrapped;
		/**
		 * The maximum delay in milliseconds after trying a call again.
		 */
		private final long MAX_RETRY_TIME = 60000;
		private final int MAX_RETRIES = 10;
		
		private int id = 1;

		public RepeatingRestClient(RestClient wrappedClient) {
			assert mWrapped != null;
			mWrapped = wrappedClient;
		}

		@Override
		public RestResponse get(final String... path) {
			return call(new RestCall() {
				public RestResponse doCall() throws RestException {
					return mWrapped.get(path);
				}
			});
		}

		@Override
		public RestResponse delete(final String... path) {
			return call(new RestCall() {
				public RestResponse doCall() throws RestException {
					return mWrapped.delete(path);
				}
			});
		}

		@Override
		public RestResponse put(final byte[] data, final String... path) {
			return call(new RestCall() {
				public RestResponse doCall() throws RestException {
					return mWrapped.put(data, path);
				}
			});
		}

		@Override
		public RestResponse post(final byte[] data, final String... path) {
			return call(new RestCall() {
				public RestResponse doCall() throws RestException {
					return mWrapped.post(data, path);
				}
			});
		}

		/**
		 * Retry the given call until it succeeds, or the maximum limit of tries has been exceeded.
		 * Double the delay every time a call fails, until the {@link #MAX_RETRY_TIME maximum
		 * time limit} has been reached.
		 * 
		 * @param call The call to repeat.
		 * @return The response from that call.
		 */
		private RestResponse call(RestCall call) {
			RestResponse response = null;
			long wait = 2000;
			int retry = 0;
			do {
				try {
					response = call.doCall();
				} catch (RestException ex) {
					Log.d(LOG_TAG, String.format("Could not make REST call. Trying again in %d ms. "
							+ "[Caused by %s]", wait, ex.getCause().getMessage()));
					try {
						Thread.sleep(wait);
						wait = Math.min(wait * 2, MAX_RETRY_TIME);
					} catch (InterruptedException ex1) {
						Logger.getLogger(RestStorageHandler.class.getName()).log(Level.SEVERE, null, ex1);
					}
				}
				retry++;
			} while ((response == null || response.code != 200) && retry < MAX_RETRIES);
			return response;
		}
	}

	/**
	 * Collects different calls to the REST interface and try to minimize the required HTTP connections,
	 * by bunching calls together.
	 */
	private class CallCollector {

		/**
		 * Limit of calls that get cached for one collection before sending a REST request.
		 */
		private final static int CALL_COLLECT_LIMIT = 100;
		/**
		 * Time limit after which a REST call will be made (even when less than {@link #CALL_COLLECT_LIMIT}
		 * calls are pending.
		 */
		private final static int CALL_COLLECT_TIME_LIMIT = 3;
		/**
		 * A list of {@link ObjectId} that are waiting to be fetched from server. Separated by their
		 * collection.
		 */
		private Map<String, Set<ObjectId>> mIdCalls = new ConcurrentHashMap<String, Set<ObjectId>>();
		/**
		 * The {@link DBObject DBObjects} returned from the server for each {@link ObjectId}.
		 */
		private Map<ObjectId, DBObject> mResults = new ConcurrentHashMap<ObjectId, DBObject>();
		/**
		 * The waiting locks for each call made. Each call holds a list of locks for all pending calls 
		 * to this {@link ObjectId}.
		 */
		private Map<ObjectId, Collection<Object>> mIdLocks = Collections.synchronizedMap(new HashMap<ObjectId, Collection<Object>>());
		/**
		 * This is a map containing a lock for each collection. This is used when the call for a collection
		 * is made and when modifying the {@link #mIdCalls pending calls} for that collection.
		 * So the pending call ids aren't modified while reading from the list to fetch them via REST.
		 */
		private Map<String, Object> mCollectionLocks = Collections.synchronizedMap(new HashMap<String, Object>());
		/**
		 * This lock is used when modifying the periodic check for pending calls.
		 * @see #mScheduled
		 * @see #mCheckForPendingCalls
		 * @see #mExecutor
		 */
		private final Object mSchedulerLock = new Object();
		/**
		 * This lock is used when modifying the {@link #mIdLocks id locks}.
		 */
		private final Object mLockCreationLock = new Object();
		/**
		 * This lock is used when modifying the {@link #mResults results list}.
		 */
		private final Object mResultsLock = new Object();
		private ScheduledFuture<?> mScheduled;
		private final ScheduledExecutorService mExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Periodic AndSync Thread "));
		private final Runnable mCheckForPendingCalls = new Runnable() {

			public void run() {
				// Check if any pending calls exist. If they do, do REST calls.
				for (String collection : mIdCalls.keySet()) {
					if (numPendingcalls(collection) > 0) {
						doCall(collection);
					}
				}

				// Check if any calls are left over, if so reschedule the periodic check, if not
				// don't do any further periodic check.
				synchronized (mSchedulerLock) {
					if (hasPendingCalls()) {
						mScheduled = mExecutor.schedule(mCheckForPendingCalls, CALL_COLLECT_TIME_LIMIT,
								TimeUnit.SECONDS);
					} else {
						mScheduled = null;
					}
				}

			}
		};

		private boolean hasPendingCalls() {
			for (Set<ObjectId> pending : mIdCalls.values()) {
				if (pending.size() > 0) {
					return true;
				}
			}
			return false;
		}

		private int numPendingcalls(String collection) {
			Set<ObjectId> calls = mIdCalls.get(collection);
			return calls != null ? mIdCalls.get(collection).size() : 0;
		}

		public DBObject callForId(String collection, ObjectId id) {

			// Wait till a current call for that collection has been finished,
			// before adding this call to the queue.
			synchronized (getCollectionLock(collection)) {
				// Get the queue (a Set, since we only need to fetch each object one time)
				// or create a new queue for that collection.
				Set<ObjectId> idSet = mIdCalls.get(collection);
				if (idSet == null) {
					idSet = Collections.newSetFromMap(new ConcurrentHashMap<ObjectId, Boolean>());
					mIdCalls.put(collection, idSet);
				}
				// Add the id of the object we want to get to the queue for its collection.
				idSet.add(id);
			}

			synchronized (mSchedulerLock) {
				if (mScheduled == null) {
					mScheduled = mExecutor.schedule(mCheckForPendingCalls,
							CALL_COLLECT_TIME_LIMIT, TimeUnit.SECONDS);
				} else {
					if (mScheduled.cancel(true)) {
						mScheduled = mExecutor.schedule(mCheckForPendingCalls, CALL_COLLECT_TIME_LIMIT, TimeUnit.SECONDS);
					}
				}
			}

			Object lockForId = newLock(id);
			synchronized (lockForId) {
				// Call if we have enough requests collected
				if (numPendingcalls(collection) >= CALL_COLLECT_LIMIT) {
					doCall(collection);
				}

				while (!mResults.containsKey(id)) {
					try {
						lockForId.wait(10000);
					} catch (InterruptedException ex) {
						// Do nothing
						//Log.w("ANDSYNC", String.format("Thread got interrupted %s", Thread.currentThread().getId()));
					}
				}

				boolean removeObject = false;
				synchronized (mLockCreationLock) {
					mIdLocks.get(id).remove(lockForId);
					if (mIdLocks.get(id) != null && mIdLocks.get(id).isEmpty()) {
						mIdLocks.remove(id);
						removeObject = true;
					}
					synchronized (mResultsLock) {
						DBObject dbo = (removeObject) ? mResults.remove(id) : mResults.get(id);
						return dbo;
					}
				}

			}

		}

		private synchronized Object getCollectionLock(String collection) {
			Object lock = mCollectionLocks.get(collection);
			if (lock == null) {
				lock = new Object();
				mCollectionLocks.put(collection, lock);
			}
			return lock;
		}

		private Object newLock(ObjectId id) {
			synchronized (mLockCreationLock) {
				Collection<Object> locksForId = mIdLocks.get(id);
				if (locksForId == null) {
					locksForId = new ConcurrentLinkedQueue<Object>();
					mIdLocks.put(id, locksForId);
				}
				Object newLock = new Object();
				locksForId.add(newLock);
				return newLock;
			}
		}

		private void doCall(final String collection) {

			Runnable r = new Runnable() {

				public void run() {

					synchronized (getCollectionLock(collection)) {

						if (numPendingcalls(collection) == 0) {
							return;
						}

						Set<ObjectId> requestedIds = mIdCalls.get(collection);

						// Build list of all ids to fetch
						BasicDBList keyList = new BasicDBList();
						for (ObjectId id : requestedIds) {
							keyList.add(id);
						}

						RestResponse response = mRest.get(REST_OBJECT_PATH, collection,
								Base64.encode(BsonConverter.toByteArray(keyList), 0));

						if (response.code != 200) {
							Log.w(LOG_TAG, String.format("Server returned error code %d.", response.code));
							return;
						}

						BasicDBObject objects = (BasicDBObject) BsonConverter.fromBson(response.data);

						if (objects == null) {
							Log.w(LOG_TAG, "Received object list from server wasn't a valid BSON object.");
							return;
						}

						for (Object o : objects.values()) {

							if (!(o instanceof DBObject)) {
								Log.w(LOG_TAG, "Received object from server wasn't a DBObject.");
								continue;
							}

							DBObject dbo = (DBObject) o;
							ObjectId id = (ObjectId) dbo.get(MONGO_ID);

							synchronized (mResultsLock) {
								mResults.put(id, dbo);
							}
							requestedIds.remove(id);

							Collection<Object> locksForId = mIdLocks.get(id);
							if (locksForId != null) {
								for (Object lock : locksForId) {
									synchronized (lock) {
										lock.notifyAll();
									}
								}
							}

						}


					}
				}
			};

			new Thread(r).start();

		}
	}
}
