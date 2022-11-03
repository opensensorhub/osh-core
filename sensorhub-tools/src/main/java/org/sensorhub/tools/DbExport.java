/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are Copyright (C) 2014 Sensia Software LLC.
 All Rights Reserved.
 
 Contributor(s): 
    Alexandre Robin <alex.robin@sensiasoftware.com>
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.tools;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map.Entry;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.TextEncoding;
import org.sensorhub.api.persistence.DataFilter;
import org.sensorhub.api.persistence.IDataFilter;
import org.sensorhub.api.persistence.IDataRecord;
import org.sensorhub.api.persistence.IRecordStoreInfo;
import org.sensorhub.impl.persistence.perst.BasicStorageConfig;
import org.sensorhub.impl.persistence.perst.BasicStorageImpl;
import org.vast.cdm.common.DataStreamWriter;
import org.vast.sensorML.SMLUtils;
import org.vast.swe.SWEUtils;
import org.vast.swe.SWEHelper;
import org.vast.xml.DOMHelper;
import org.vast.xml.XMLImplFinder;
import org.w3c.dom.Element;


public class DbExport
{
    
    private DbExport()
    {        
    }
    
    
    public static void main(String[] args) throws Exception
    {
        if (args.length < 1)
        {
            System.out.println("Usage: DbExport storage_path");
            System.exit(1);
        }
        
        // open storage
        String dbPath = args[0];
        BasicStorageConfig dbConf = new BasicStorageConfig();
        dbConf.name = "StorageToExport";
        dbConf.autoStart = true;
        dbConf.memoryCacheSize = 1024;
        dbConf.storagePath = dbPath;
        BasicStorageImpl db = new BasicStorageImpl();
        db.init(dbConf);
        db.start();
        
        // write metadata file
        String outputName = new File(dbPath).getName();
        File metadataFile = new File(outputName + ".export.metadata");
        if (metadataFile.exists())
        {
            System.err.println("DB export file already exist: " + metadataFile);
            System.exit(1);
        }
        
        try (OutputStream metadataOs = new BufferedOutputStream(new FileOutputStream(metadataFile)))
        {            
            // prepare XML output
            DOMHelper dom = new DOMHelper("db_export");
            SMLUtils smlUtils = new SMLUtils(SMLUtils.V2_0);
            SWEUtils sweUtils = new SWEUtils(SWEUtils.V2_0);
            XMLImplFinder.setStaxOutputFactory(new com.ctc.wstx.stax.WstxOutputFactory());
            
            // export all SensorML descriptions
            for (AbstractProcess process: db.getDataSourceDescriptionHistory(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY))
            {
                Element smlElt = dom.addElement('+' + DbConstants.SECTION_SENSORML);
                smlElt.appendChild(smlUtils.writeProcess(dom, process));
                System.out.println("Exported SensorML description " + process.getId());
            }
            
            // export all datastores
            final double[] timePeriod = new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
                        
            for (Entry<String, ? extends IRecordStoreInfo> entry: db.getRecordStores().entrySet())
            {
                String recordType = entry.getKey();
                IRecordStoreInfo recordInfo = entry.getValue();
                DataComponent recordStruct = recordInfo.getRecordDescription();
                DataEncoding recordEncoding = recordInfo.getRecommendedEncoding();
                
                Element dataStoreElt = dom.addElement('+' + DbConstants.SECTION_DATASTORE);
                dom.setAttributeValue(dataStoreElt, "name", recordType);
                
                // add data description
                Element recordStructElt = dom.addElement(dataStoreElt, DbConstants.SECTION_RECORD_STRUCTURE);
                recordStructElt.appendChild(sweUtils.writeComponent(dom, recordStruct, false));
                Element recordEncElt = dom.addElement(dataStoreElt, DbConstants.SECTION_RECORD_ENCODING);
                recordEncElt.appendChild(sweUtils.writeEncoding(dom, recordEncoding));
                System.out.println("Exported metadata for data store " + recordType);
                System.out.println("Exporting records...");
                
                // prepare filter
                IDataFilter recordFilter = new DataFilter(recordType) {
                    @Override
                    public double[] getTimeStampRange() { return timePeriod; }
                };
                
                // write records to separate binary file
                File dataFile = new File(outputName + '.' + recordType + ".export.data");
                if (dataFile.exists())
                {
                    System.err.println("DB export file already exist: " + dataFile);
                    System.exit(1);
                }
                
                DataStreamWriter recordWriter = null;
                try (OutputStream recordOs = new BufferedOutputStream(new FileOutputStream(dataFile)))
                {
                    DataOutputStream dos = new DataOutputStream(recordOs);
                    
                    // prepare record writer
                    recordWriter = SWEHelper.createDataWriter(recordEncoding);
                    recordWriter.setDataComponents(recordStruct);
                    recordWriter.setOutput(dos);
                    
                    // write all records
                    int errorCount = 0;
                    int recordCount = 0;
                    Iterator<? extends IDataRecord> it = db.getRecordIterator(recordFilter);
                    while (it.hasNext())
                    {
                        IDataRecord rec = it.next();
                        
                        dos.writeDouble(rec.getKey().timeStamp);
                        if (rec.getKey().producerID == null)
                            dos.writeUTF(DbConstants.KEY_NULL_PRODUCER);
                        else
                            dos.writeUTF(rec.getKey().producerID);
                        recordWriter.write(rec.getData());
                        recordWriter.flush();
                        if (recordEncoding instanceof TextEncoding)
                            dos.write('\n');
                        dos.flush();
                        
                        recordCount++;
                        if (recordCount % 100 == 0)
                            System.out.print(recordCount + "\r");
                    }
                    
                    System.out.println("Exported " + recordCount + " records (" + errorCount + " errors)");
                }
            }
            
            // write out the whole metadata file
            dom.serialize(dom.getRootElement(), metadataOs, true);
        }
        finally
        {
            db.stop();
        }
    }
}
