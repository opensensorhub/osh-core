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

import static org.junit.Assert.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.sensorhub.api.database.IProcedureObsDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.api.obs.IDataStreamInfo;
import org.sensorhub.impl.procedure.wrapper.ProcedureWrapper;
import org.vast.sensorML.SMLHelper;
import org.vast.util.TimeExtent;
import net.opengis.sensorml.v20.AbstractProcess;


@Ignore
public abstract class AbstractTestObsDatabase<DbType extends IProcedureObsDatabase>
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
    
    
    protected long[] addProcedures(int... uidSuffixes)
    {
        try
        {
            long[] internalIDs = new long[uidSuffixes.length];

            for (int i = 0 ; i < uidSuffixes.length; i++)
            {
                AbstractProcess p = new SMLHelper().createPhysicalComponent()
                    .uniqueID(PROC_UID_PREFIX + uidSuffixes[i])
                    .name("Procedure #" + (char)(uidSuffixes[i]+65))
                    .build();
                var procWrapper = new ProcedureWrapper(p);
                internalIDs[i] = obsDb.getProcedureStore().add(procWrapper).getInternalID();
            }

            return internalIDs;
        }
        catch (DataStoreException e)
        {
            throw new IllegalStateException(e);
        }
    }
    
    
    @Test
    public void testSelectProcedureWithDataStreamFilterJoin()
    {
        fail("Not yet implemented");
    }
    
    
    @Test
    public void testSelectProcedureWithFoiFilterJoin()
    {
        fail("Not yet implemented");
    }
        
    
    @Test
    public void testSelectDatastreamWithProcedureFilterJoin()
    {
        int procUids[] = {13, 5, 25};
        long[] procIds = addProcedures(procUids);
        
        dataStreamTests.addSimpleDataStream(procIds[0], "out1", TimeExtent.beginAt(Instant.EPOCH));
        var dsId1 = dataStreamTests.addSimpleDataStream(procIds[0], "out2", TimeExtent.beginAt(Instant.EPOCH));
        
        dataStreamTests.addSimpleDataStream(procIds[1], "out1", TimeExtent.beginAt(Instant.EPOCH));
        dataStreamTests.addSimpleDataStream(procIds[1], "out2", TimeExtent.beginAt(Instant.EPOCH));
        
        dataStreamTests.addSimpleDataStream(procIds[2], "out1", TimeExtent.beginAt(Instant.EPOCH));
        var dsId2 = dataStreamTests.addSimpleDataStream(procIds[2], "out2", TimeExtent.beginAt(Instant.EPOCH));
        
        var filter = new DataStreamFilter.Builder()
            .withProcedures()
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
        fail("Not yet implemented");
    }
    
    
    @Test
    public void testSelectObsWithDataStreamFilterJoin()
    {
        fail("Not yet implemented");
    }
    
    
    @Test
    public void testSelectObsWithFoiFilterJoin()
    {
        fail("Not yet implemented");
    }

}