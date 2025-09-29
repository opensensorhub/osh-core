/***************************** BEGIN COPYRIGHT BLOCK **************************

Copyright (C) 2024 Delta Air Lines, Inc. All Rights Reserved.

Notice: All information contained herein is, and remains the property of
Delta Air Lines, Inc. The intellectual and technical concepts contained herein
are proprietary to Delta Air Lines, Inc. and may be covered by U.S. and Foreign
Patents, patents in process, and are protected by trade secret or copyright law.
Dissemination, reproduction or modification of this material is strictly
forbidden unless prior written permission is obtained from Delta Air Lines, Inc.

******************************* END COPYRIGHT BLOCK ***************************/

package org.vast.swe.test;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import org.junit.Test;
import org.vast.data.DataBlockMixed;
import org.vast.data.DataBlockProxy;
import org.vast.data.IDataAccessor;
import org.vast.swe.SWEHelper;
import net.opengis.swe.v20.Count;
import net.opengis.swe.v20.DataRecord;


public class TestDataAccessor
{
    interface RecordAccessor1 extends IDataAccessor
    {
        @SweMapping(path="time")
        public Instant getTimeStamp();
        
        @SweMapping(path="temp")
        public double getTemperature();
        
        @SweMapping(path="press")
        public double getPressure();
        
        @SweMapping(path="status")
        public String getStatus();
        
        static DataRecord getSchema()
        {
            var swe = new SWEHelper();
            return swe.createRecord()
                .addField("time", swe.createTime())
                .addField("temp", swe.createQuantity())
                .addField("press", swe.createQuantity())
                .addField("status", swe.createCategory())
                .build();
        }
    }
    
    
    interface RecordAccessor2 extends IDataAccessor
    {
        @SweMapping(path="type")
        public String getRecordType();
                
        @SweMapping(path="array")
        public Collection<RecordAccessor1> getRecords();
        
        @SweMapping(path="array")
        public RecordAccessor1 addRecord();
        
        static DataRecord getSchema()
        {
            var swe = new SWEHelper();
            Count sizeComp;
            return swe.createRecord()
                .addField("type", swe.createCategory())
                .addField("count", sizeComp = swe.createCount()
                    .id("ARRAY_SIZE")
                    .build())
                .addField("array", swe.createArray()
                    .withSizeComponent(sizeComp)
                    .withElement("rec", RecordAccessor1.getSchema()))
                .build();
        }
    }
    
    
    interface RecordAccessor3 extends IDataAccessor
    {
        @SweMapping(path="type")
        public String getRecordType();
                
        @SweMapping(path="array")
        public Collection<String> getTokens();
        
        @SweMapping(path="array")
        public void addToken();
        
        static DataRecord getSchema()
        {
            var swe = new SWEHelper();
            Count sizeComp;
            return swe.createRecord()
                .addField("type", swe.createCategory())
                .addField("count", sizeComp = swe.createCount()
                    .id("ARRAY_SIZE")
                    .build())
                .addField("array", swe.createArray()
                    .withSizeComponent(sizeComp)
                    .withElement("token", swe.createText()))
                .build();
        }
    }
    
    
    @Test
    public void testReadRecordOfScalars()
    {
        var rec = RecordAccessor1.getSchema();
        var accessor = DataBlockProxy.generate(rec, RecordAccessor1.class);
        
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var time = now.toEpochMilli() / 1000.;
        var temp = 25.6;
        var press = 1015.23;
        var status = "OFFLINE";
        
        int i = 0;
        var dblk = rec.createDataBlock();
        dblk.setDoubleValue(i++, time);
        dblk.setDoubleValue(i++, temp);
        dblk.setDoubleValue(i++, press);
        dblk.setStringValue(i++, status);
        
        accessor.wrap(dblk);
        assertEquals(now, accessor.getTimeStamp());
        assertEquals(temp, accessor.getTemperature(), 1e-8);
        assertEquals(press, accessor.getPressure(), 1e-8);
        assertEquals(status, accessor.getStatus());
        
        System.out.println(accessor.toString());
    }
    
    
    @Test
    public void testReadArrayOfRecords()
    {
        var rec = RecordAccessor2.getSchema();
        var accessor = DataBlockProxy.generate(rec, RecordAccessor2.class);
        
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String type = "wx";
        Instant[] times = {now.plusSeconds(10), now.plusSeconds(20), now.plusSeconds(30), now.plusSeconds(40)};
        double[] temps = {23.5, -4.6, 78.9, 17.25};
        double[] press = {1015.2, 1016, 1029.3, 986.5};
        String[] status = {"ON", "OFF", "OFFLINE", "ERROR"};
        
        int i = 0;
        var dblk = rec.createDataBlock();
        dblk.setStringValue(i++, type);
        dblk.setIntValue(i++, times.length);
        ((DataBlockMixed)dblk).getUnderlyingObject()[2].resize(times.length*4);
        for (var r = 0; r < times.length; r++)
        {
            dblk.setDoubleValue(i++, times[r].toEpochMilli() / 1000.0);
            dblk.setDoubleValue(i++, temps[r]);
            dblk.setDoubleValue(i++, press[r]);
            dblk.setStringValue(i++, status[r]);
        }
        
        accessor.wrap(dblk);
        
        assertEquals(type, accessor.getRecordType());
        assertEquals(times.length, accessor.getRecords().size());
        int r = 0;
        for (var elt: accessor.getRecords())
        {
            assertEquals(times[r], elt.getTimeStamp());
            assertEquals(temps[r], elt.getTemperature(), 1e-8);
            assertEquals(press[r], elt.getPressure(), 1e-8);
            assertEquals(status[r], elt.getStatus());
            r++;
            
            System.out.println(elt.toString());
        }
        
    }
    
    
    @Test
    public void testReadArrayOfScalars()
    {
        var rec = RecordAccessor3.getSchema();
        var accessor = DataBlockProxy.generate(rec, RecordAccessor3.class);
        
        String type = "wpts";
        String[] codes = {"LCH", "LFT", "AWDAD", "VOODO"};
        
        int i = 0;
        var dblk = rec.createDataBlock();
        dblk.setStringValue(i++, type);
        dblk.setIntValue(i++, codes.length);
        ((DataBlockMixed)dblk).getUnderlyingObject()[2].resize(codes.length);
        for (var r = 0; r < codes.length; r++) {
            dblk.setStringValue(i++, codes[r]);
        }
        
        accessor.wrap(dblk);
        
        assertEquals(type, accessor.getRecordType());
        assertEquals(codes.length, accessor.getTokens().size());
        int r = 0;
        for (var elt: accessor.getTokens())
        {
            assertEquals(codes[r], elt);
            r++;
            
            System.out.println(elt.toString());
        }
        
    }

}
