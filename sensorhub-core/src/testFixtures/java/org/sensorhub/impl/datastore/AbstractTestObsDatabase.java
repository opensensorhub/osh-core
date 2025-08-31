/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore;

import net.opengis.sensorml.v20.AbstractProcess;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.impl.datastore.AbstractTestDataStreamStore;
import org.sensorhub.impl.system.wrapper.SystemWrapper;
import org.vast.sensorML.SMLHelper;
import org.vast.util.TimeExtent;

import java.time.Instant;
import java.util.LinkedHashMap;


@Ignore
public abstract class AbstractTestObsDatabase<DbType extends IObsSystemDatabase>
{
    protected static String PROC_UID_PREFIX = "urn:osh:test:sensor:";
    protected static String FOI_UID_PREFIX = "urn:osh:test:foi:";
    protected DbType obsDb;
    protected AbstractTestDataStreamStore<IDataStreamStore> dataStreamTests;
    

    protected abstract DbType initDatabase() throws Exception;
    protected abstract void forceReadBackFromStorage() throws Exception;

    
    @Before
    public void init() throws Exception
    {
        this.obsDb = initDatabase();
        AbstractTestDataStreamStore.DATABASE_NUM = obsDb.getDatabaseNum();
        
        this.dataStreamTests = new AbstractTestDataStreamStore<IDataStreamStore>() {
            @Override
            protected IDataStreamStore initStore() throws Exception
            {
                return obsDb.getDataStreamStore();
            }

            @Override
            protected void forceReadBackFromStorage() throws Exception
            {   
            }
        };
        this.dataStreamTests.init();
    }
    
    
    protected BigId[] addSystems(int... uidSuffixes)
    {
        try
        {
            var internalIDs = new BigId[uidSuffixes.length];

            for (int i = 0 ; i < uidSuffixes.length; i++)
            {
                AbstractProcess p = new SMLHelper().createPhysicalComponent()
                    .uniqueID(PROC_UID_PREFIX + uidSuffixes[i])
                    .name("System #" + (char)(uidSuffixes[i]+65))
                    .build();
                var procWrapper = new SystemWrapper(p);
                internalIDs[i] = obsDb.getSystemDescStore().add(procWrapper).getInternalID();
            }

            return internalIDs;
        }
        catch (DataStoreException e)
        {
            throw new IllegalStateException(e);
        }
    }
    
    
    @Test
    public void testSelectSystemWithDataStreamFilterJoin()
    {
        // TODO Not yet implemented
    }
    
    
    @Test
    public void testSelectSystemWithFoiFilterJoin()
    {
        // TODO Not yet implemented
    }
        
    
    @Test
    public void testSelectDatastreamWithSystemFilterJoin() throws Exception
    {
        int procUids[] = {13, 5, 25};
        var procIds = addSystems(procUids);
        
        dataStreamTests.addSimpleDataStream(procIds[0], "out1", TimeExtent.beginAt(Instant.EPOCH));
        var dsId1 = dataStreamTests.addSimpleDataStream(procIds[0], "out2", TimeExtent.beginAt(Instant.EPOCH));
        
        dataStreamTests.addSimpleDataStream(procIds[1], "out1", TimeExtent.beginAt(Instant.EPOCH));
        dataStreamTests.addSimpleDataStream(procIds[1], "out2", TimeExtent.beginAt(Instant.EPOCH));
        
        dataStreamTests.addSimpleDataStream(procIds[2], "out1", TimeExtent.beginAt(Instant.EPOCH));
        var dsId2 = dataStreamTests.addSimpleDataStream(procIds[2], "out2", TimeExtent.beginAt(Instant.EPOCH));
        
        var filter = new DataStreamFilter.Builder()
            .withSystems()
                .withUniqueIDs(PROC_UID_PREFIX+procUids[0],
                               PROC_UID_PREFIX+procUids[2])
                .done()
            .withOutputNames("out2")
            .build();
        var results = obsDb.getDataStreamStore().selectEntries(filter);

        var expectedResults = new LinkedHashMap<DataStreamKey, IDataStreamInfo>();
        expectedResults.put(dsId1, dataStreamTests.allDataStreams.get(dsId1));
        expectedResults.put(dsId2, dataStreamTests.allDataStreams.get(dsId2));
        dataStreamTests.checkSelectedEntries(results, expectedResults, filter);
    }
    
    
    @Test
    public void testSelectDatastreamWithObsFilterJoin()
    {
        // TODO Not yet implemented
    }
    
    
    @Test
    public void testSelectObsWithDataStreamFilterJoin()
    {
        // TODO Not yet implemented
    }
    
    
    @Test
    public void testSelectObsWithFoiFilterJoin()
    {
        // TODO Not yet implemented
    }

}
