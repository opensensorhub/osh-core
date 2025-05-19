/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.database.registry;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.database.IDatabase;
import org.sensorhub.api.database.IDatabaseRegistry;
import org.sensorhub.api.database.IFeatureDatabase;
import org.sensorhub.api.database.IFederatedDatabase;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.database.IProcedureDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.util.Asserts;
import com.google.common.collect.Collections2;


/**
 * <p>
 * Default implementation of the database registry allowing to register
 * several {@link IObsSystemDatabase} instances. In this implementation, a given
 * system can only be associated to a single database instance.
 * </p>
 *
 * @author Alex Robin
 * @date Sep 18, 2019
 */
public class DefaultDatabaseRegistry implements IDatabaseRegistry
{
    static final Logger log = LoggerFactory.getLogger(DefaultDatabaseRegistry.class);
    static final Integer DEFAULT_DB_ID = Integer.valueOf(0);
    static final int MAX_NUM_DB = 100;
    static final BigInteger MAX_NUM_DB_BIGINT = BigInteger.valueOf(MAX_NUM_DB);
    static final String END_PREFIX_CHAR = "\n";
    static final String DB_NUM_ERROR_MSG = "Database number must be > 0 and < " + MAX_NUM_DB;
    
    ISensorHub hub;
    Map<Integer, IDatabase> databases;
    IFederatedDatabase federatedDb;
    Collection<IDatabase> allDatabases;
    Collection<IObsSystemDatabase> obsSystemDatabases;
    Collection<IProcedureDatabase> procedureDatabases;
    Collection<IFeatureDatabase> featureDatabases;
    
    
    public DefaultDatabaseRegistry(ISensorHub hub)
    {
        this.hub = hub;
        this.databases = new ConcurrentSkipListMap<>();
        this.federatedDb = new FederatedDatabase(this);
        
        this.allDatabases = Collections.unmodifiableCollection(databases.values());
        
        this.obsSystemDatabases = Collections2.transform(
            Collections2.filter(allDatabases, db -> db instanceof IObsSystemDatabase),
            db -> (IObsSystemDatabase)db);
        
        this.procedureDatabases = Collections2.transform(
            Collections2.filter(allDatabases, db -> db instanceof IProcedureDatabase),
            db -> (IProcedureDatabase)db);
        
        this.featureDatabases = Collections2.transform(
            Collections2.filter(allDatabases, db -> db instanceof IFeatureDatabase),
            db -> (IFeatureDatabase)db);
    }
    
    
    @Override
    public synchronized void register(IDatabase db)
    {
        Asserts.checkNotNull(db, IDatabase.class);
        checkDbNum(db.getDatabaseNum());
        
        log.info("Registering database {} with ID {}", db.getClass().getSimpleName(), db.getDatabaseNum());
        
        int dbNum = db.getDatabaseNum();
        
        // add to Id->DB instance map only if not already present
        if (databases.putIfAbsent(dbNum, db) != null)
            throw new IllegalStateException("An obs system database with number " + dbNum + " was already registered");
    }
    
    
    @Override
    public synchronized void unregister(IDatabase db)
    {
        Asserts.checkNotNull(db, IDatabase.class);
        databases.remove(db.getDatabaseNum(), db);
    }
    
    
    @Override
    public Collection<IDatabase> getAllDatabases()
    {
        return allDatabases;
    }
    
    
    void checkDbNum(Integer dbNum)
    {
        Asserts.checkArgument(dbNum != null && dbNum < MAX_NUM_DB, DB_NUM_ERROR_MSG);
    }


    /*
     * Obs System Databases
     */
    
    @Override
    public IObsSystemDatabase getObsDatabaseByNum(int dbNum)
    {
        var db = databases.get(dbNum);
        
        if (db instanceof IObsSystemDatabase)
            return (IObsSystemDatabase)db;
        else
            return null;
    }
    
    
    @Override
    public IObsSystemDatabase getObsDatabaseByModuleID(String moduleID)
    {
        Asserts.checkNotNullOrBlank(moduleID, "moduleID");
        var m = hub.getModuleRegistry().getLoadedModuleById(moduleID);
        if (m == null || !(m instanceof IObsSystemDatabase))
            throw new IllegalArgumentException("Cannot find obs system database module with ID " + moduleID);
        return (IObsSystemDatabase)m;
    }


    @Override
    public Collection<IObsSystemDatabase> getObsSystemDatabases()
    {
        return obsSystemDatabases;
    }
    
    
    /*
     * Procedure Databases
     */
    
    @Override
    public IProcedureDatabase getProcedureDatabaseByNum(int dbNum)
    {
        var db = databases.get(dbNum);
        
        if (db instanceof IProcedureDatabase)
            return (IProcedureDatabase)db;
        else
            return null;
    }


    @Override
    public Collection<IProcedureDatabase> getProcedureDatabases()
    {
        return procedureDatabases;
    }


    /*
     * Feature Databases
     */
    
    @Override
    public Collection<IFeatureDatabase> getFeatureDatabases()
    {
        return featureDatabases;
    }


    @Override
    public IFeatureDatabase getFeatureDatabaseByNum(int dbNum)
    {
        var db = databases.get(dbNum);
        
        if (db instanceof IFeatureDatabase)
            return (IFeatureDatabase)db;
        else
            return null;
    }
    
    
    /*
     * Federated database
     */
    
    @Override
    public IFederatedDatabase getFederatedDatabase()
    {
        return federatedDb;
    }
}
