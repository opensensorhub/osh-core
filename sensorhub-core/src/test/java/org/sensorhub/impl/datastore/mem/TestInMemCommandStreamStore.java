    /***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.mem;

import static org.junit.Assert.assertEquals;
import java.time.Instant;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.Test;
import org.sensorhub.api.command.CommandStreamInfo;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.command.CommandStreamFilter;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.datastore.command.ICommandStreamStore.CommandStreamInfoField;
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.impl.datastore.AbstractTestCommandStreamStore;
import org.vast.data.TextEncodingImpl;
import org.vast.swe.SWEHelper;
import org.vast.util.TimeExtent;


public class TestInMemCommandStreamStore extends AbstractTestCommandStreamStore<InMemoryCommandStreamStore>
{
       
    
    protected InMemoryCommandStreamStore initStore() throws Exception
    {
        needValidTimeAdjustment = false;
        return (InMemoryCommandStreamStore)new InMemoryCommandStore(DATABASE_NUM).getCommandStreams();
    }
    
    
    protected void forceReadBackFromStorage()
    {
    }
    
    
    protected void testAddAndSelectLatestValidTime_ExpectedResults()
    {
        addToExpectedResults(2, 4, 7);
    }
    
    
    protected void testAddAndSelectByTimeRange_ExpectedResults(int testCaseIdx)
    {
        switch (testCaseIdx)
        {
            case 1: addToExpectedResults(1, 3, 5, 7); break;
            case 2: addToExpectedResults(1, 7); break;
            case 3: addToExpectedResults(7); break;
        }        
    }
    
    
    @Override
    protected void testAddAndSelectByOutputName_ExpectedResults()
    {
        addToExpectedResults(1, 3, 5);
    }
    
    
    protected void testAddAndSelectBySystemID_ExpectedResults()
    {
        addToExpectedResults(3, 4, 5);
    }
    
    
    protected void testAddAndSelectByKeywords_ExpectedResults(int testCaseIdx)
    {
        switch (testCaseIdx)
        {
            case 1: addToExpectedResults(4, 5); break;
            case 2: addToExpectedResults(1, 4, 5); break;
            case 3: addToExpectedResults(2, 4, 5); break;
            case 4: addToExpectedResults(4); break;
        }        
    }
    
    
    @Test
    public void testLatestVersionEntryWithUnsortedResults() throws Exception
    {
        var sysId = new FeatureId(BigId.fromLong(DATABASE_NUM, 1), "urn:osh:test:sys1");
        var rec = new SWEHelper().createRecord().name("in1").addField("f1", new SWEHelper().createQuantity()).build();
        var oldInfo = new CommandStreamInfo.Builder()
            .withName("v1").withSystem(sysId).withRecordDescription(rec).withRecordEncoding(new TextEncodingImpl())
            .withValidTime(TimeExtent.beginAt(Instant.parse("2020-01-01T00:00:00Z")))
            .build();
        var newInfo = new CommandStreamInfo.Builder()
            .withName("v2").withSystem(sysId).withRecordDescription(rec).withRecordEncoding(new TextEncodingImpl())
            .withValidTime(TimeExtent.beginAt(Instant.parse("2024-01-01T00:00:00Z")))
            .build();
        
        // store that returns versions oldest first
        var store = new InMemoryCommandStreamStore(new InMemoryCommandStore(DATABASE_NUM)) {
            @Override
            public Stream<Entry<CommandStreamKey, ICommandStreamInfo>> selectEntries(CommandStreamFilter filter, Set<CommandStreamInfoField> fields)
            {
                return Stream.of(
                    new SimpleEntry<>(new CommandStreamKey(DATABASE_NUM, 1), oldInfo),
                    new SimpleEntry<>(new CommandStreamKey(DATABASE_NUM, 2), newInfo));
            }
        };
        
        var e = store.getLatestVersionEntry(sysId.getUniqueID(), "in1");
        assertEquals("v2", e.getValue().getName());
    }
}
