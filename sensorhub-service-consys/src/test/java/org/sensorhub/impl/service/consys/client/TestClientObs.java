/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2023 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.client;

import static org.junit.Assert.assertFalse;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.ObsData;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.vast.swe.helper.GeoPosHelper;
import com.google.common.base.Strings;


public class TestClientObs extends TestClientBase
{
    TestClientSystems systemTests;
    TestClientDataStreams dsTests;
    
    
    @Before
    public void setup() throws IOException, SensorHubException
    {
        super.setup();
        //this.apiRootUrl = "http://localhost:8181/sensorhub/api";
        dsTests = new TestClientDataStreams(apiRootUrl);
        systemTests = new TestClientSystems(apiRootUrl);
    }
    
    
    @Test
    public void testAddObs() throws Exception
    {
        var sysId = systemTests.addSystem(1, true);
        
        var swe = new GeoPosHelper();
        var recordStruct = swe.createLocationVectorLLA()
            .name("pos")
            .build();

        var dsId = dsTests.addDataStream(sysId, 3, recordStruct, true);
        
        addObs(dsId, 0, 100, true);
    }
    
    
    protected void addObs(String dsId, int start, int count, boolean checkHead) throws Exception
    {
        var client = ConSysApiClient
            .newBuilder(apiRootUrl)
            .build();
        
        var dsInfo = client.getDatastreamSchema(dsId, ResourceFormat.SWE_JSON, ResourceFormat.JSON).get();
        var recordStruct = dsInfo.getRecordStructure();
        
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(3600);
        var allFutures = new ArrayList<CompletableFuture<String>>();
        for (int i = start; i < start+count; i++) {
            var ts = now.plusSeconds(i*1);
            var data = recordStruct.createDataBlock();
            data.setDoubleValue(0, ts.getEpochSecond()*1000);
            data.setDoubleValue(1, i);
            data.setDoubleValue(2, i+1);
            data.setDoubleValue(3, i+2);
            var obs = new ObsData.Builder()
                .withPhenomenonTime(ts)
                .withResult(data)
                .build();
            var f = client.pushObs(dsId, dsInfo, obs);
            allFutures.add(f);
        }
        
        // wait for all requests to be complete
        CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0])).join();
        
        if (checkHead) {
            for (var f: allFutures) {
                var id = f.get();
                assertFalse(Strings.isNullOrEmpty(id));
                checkObsIdExists(id);
            }
        }
    }
    
    
    protected void checkObsIdExists(String id) throws Exception
    {
        checkUriExists(apiRootUrl + "/" + OBS_COLLECTION + "/" + id);
    }
}
