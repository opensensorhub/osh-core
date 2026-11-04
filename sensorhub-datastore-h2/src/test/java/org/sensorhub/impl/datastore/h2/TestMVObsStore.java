/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2;

import static org.junit.Assert.assertEquals;
import java.io.File;
import java.nio.file.Files;
import org.h2.mvstore.MVStore;
import org.junit.After;
import org.junit.Test;
import org.sensorhub.impl.datastore.AbstractTestObsStore;
import org.sensorhub.impl.datastore.h2.MVDatabaseConfig.IdProviderType;


public class TestMVObsStore extends AbstractTestObsStore<MVObsStoreImpl>
{
    private static String DB_FILE_PREFIX = "test-mvobs-";
    protected File dbFile;
    protected MVStore mvStore;
    
    
    protected MVObsStoreImpl initStore() throws Exception
    {
        dbFile = File.createTempFile(DB_FILE_PREFIX, ".dat");
        dbFile.deleteOnExit();
        return openMVStore();
    }
    
    
    protected void forceReadBackFromStorage() throws Exception
    {
        this.obsStore = openMVStore();
    }
    
    
    private MVObsStoreImpl openMVStore() throws Exception
    {
        if (mvStore != null)
        {
            mvStore.commit();
            mvStore.close();
            System.out.println("MVStore flushed to disk");
        }
        
        mvStore = H2Utils.openMVStore(dbFile.getAbsolutePath(), new MVStore.Builder()
                .autoCommitBufferSize(10)
                .cacheSize(1), 1000);
        
        return MVObsStoreImpl.open(mvStore, DATABASE_NUM, IdProviderType.SEQUENTIAL,
            MVDataStoreInfo.builder()
                .withName(OBS_DATASTORE_NAME)
                .build());
    }
    
    
    @After
    public void cleanup() throws Exception
    {
        if (mvStore != null)
            mvStore.close();
        
        if (dbFile != null) 
        {
            System.out.println("DB file size was " + dbFile.length()/1024 + "KB\n");
            
            Files.list(dbFile.toPath().getParent())
                 .filter(f -> f.getFileName().toString().startsWith(DB_FILE_PREFIX))
                 .forEach(f -> f.toFile().delete());
        }            
    }
    
    
    @Test
    public void testGetNumRecordsTwoDataStreams() throws Exception
    {
        super.testGetNumRecordsTwoDataStreams();
        
        // check that 2 series were created
        assertEquals(2, obsStore.obsSeriesMainIndex.size());
    }

}
