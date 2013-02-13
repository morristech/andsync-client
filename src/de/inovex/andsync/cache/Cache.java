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
package de.inovex.andsync.cache;

import com.mongodb.DBObject;
import java.util.Collection;
import java.util.List;
import org.bson.types.ObjectId;

/**
 *
 * @author Tim Roes <tim.roes@inovex.de>
 */
public interface Cache {
	
	/**
	 * Returns all {@link DBObject DBObjects} from a specific collection.
	 * 
	 * @param collection The name of the collection.
	 * @return A {@link Collection} of all objects from that collection.
	 */
	public Collection<DBObject> getAll(String collection);
	
	/**
	 * Returns a single {@link DBObject} for the given {@link ObjectId} or {@code null}, if the
	 * cache doesn't have that document.
	 * 
	 * @param id The id of the requested object.
	 * @return The requested object or {@code null}.
	 */
	public DBObject getById(ObjectId id);
	
	/**
	 * Caches a {@link DBObject}.
	 * 
	 * @param collection The name of the collection that this object was or will be stored on the
	 *		server. This should be the fully qualified class name of the object's class.
	 * @param dbo The object to cache.
	 */
	public void put(String collection, DBObject dbo);
	
	/**
	 * Caches a list of {@link DBObject DBObjects}.
	 * 
	 * @param collection The name of the collection that this object was or will be stored on the
	 *		server. This should be the fully qualified class name of the obejct's class.
	 * @param dbos A list of objects to cache.
	 */
	public void put(String collection, List<DBObject> dbos);
	
	/**
	 * Deletes an {@link DBObject} from cache.
	 * 
	 * @param collection The name of the collection that this object was stored on the
	 *		server. This should be the fully qualified class name of the obejct's class.
	 * @param id The id of the object to delete.
	 */
	public void delete(String collection, ObjectId id);
	
	/**
	 * Deletes all {@link DBObject} from cache, that hasn't been updated since {@code timestamp}.
	 * The cache itself is responsible to save updating timestamps for each object.
	 * 
	 * @param collection The collection that should be cleaned.
	 * @param timestamp A UNIX timestamp indicating the oldest entry the cache shouldn't delete in
	 *		that collection.
	 */
	public void delete(String collection, long timestamp);
	
	/**
	 * Commits all changes made at the cache. This should be called, whenever a consistent 
	 * cache state has been reached, so the cache knows, it can commit its changes now.
	 */
	public void commit();
	
}
