/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are Copyright (C) 2021 Sensia Software LLC.
 All Rights Reserved.

 Contributor(s):
 Nicolas Garay <nic.garay@botts-inc.com>

 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.tools;


import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.sensorhub.api.persistence.DataFilter;
import org.sensorhub.api.persistence.IDataFilter;
import org.sensorhub.api.persistence.IDataRecord;
import org.sensorhub.api.persistence.IRecordStoreInfo;
import org.sensorhub.impl.persistence.perst.BasicStorageConfig;
import org.sensorhub.impl.persistence.perst.BasicStorageImpl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

public class DbMerge
{

    private enum ArgType { INPUT, OUTPUT, NONE}

    private String outputDbPath;

    private List<String> inputDbPaths = new ArrayList<>();

    private DbMerge()
    {
    }

    boolean parseArgs(String[] args) {

        ArgType argType = ArgType.NONE;

        boolean error = false;

        for(String arg: args) {
            switch(arg) {
                case "-i":
                    argType = ArgType.INPUT;
                    continue;
                case "-o":
                    argType = ArgType.OUTPUT;
                    continue;
                default:
                    break;
            }

            if (argType == ArgType.INPUT) {

                inputDbPaths.add(arg);

            } else if (argType == ArgType.OUTPUT) {

                if (outputDbPath != null) {
                    error = true;
                    break;
                }

                outputDbPath = arg;
            }
        }

        if (inputDbPaths.size() == 0) {

            error = true;
        }

        return error;
    }

    public static void main(String[] args) throws Exception
    {
        DbMerge dbMerge = new DbMerge();

        if (dbMerge.parseArgs(args))
        {
            System.out.println("Usage: DbMerge -i input_path_1 ... -i input_path_n -o storage_path ");
            System.exit(1);
        }

        BasicStorageConfig outputDbConf = new BasicStorageConfig();
        outputDbConf.name = "TargetStorage";
        outputDbConf.autoStart = true;
        outputDbConf.memoryCacheSize = 1024;
        outputDbConf.storagePath = dbMerge.outputDbPath;
        BasicStorageImpl outputDb = new BasicStorageImpl();
        outputDb.init(outputDbConf);
        outputDb.start();

        boolean descriptionImported = false;

        for(String inputPath: dbMerge.inputDbPaths) {

            BasicStorageConfig inputDbConf = new BasicStorageConfig();
            inputDbConf.name = "SourceStorage";
            inputDbConf.autoStart = true;
            inputDbConf.memoryCacheSize = 1024;
            inputDbConf.storagePath = inputPath;
            BasicStorageImpl inputDb = new BasicStorageImpl();
            inputDb.init(inputDbConf);
            inputDb.start();

            if (!descriptionImported) {

                // export all SensorML descriptions
                for (AbstractProcess process: inputDb.getDataSourceDescriptionHistory(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY))
                {
                    outputDb.storeDataSourceDescription(process);
                    outputDb.commit();
                    System.out.println("Exported SensorML description " + process.getId());
                    descriptionImported = true;
                }
            }

            final double[] timePeriod = new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};

            for (Entry<String, ? extends IRecordStoreInfo> entry: inputDb.getRecordStores().entrySet())
            {
                String recordType = entry.getKey();
                IRecordStoreInfo recordInfo = entry.getValue();
                DataComponent recordStruct = recordInfo.getRecordDescription();
                DataEncoding recordEncoding = recordInfo.getRecommendedEncoding();
                if (!outputDb.getRecordStores().containsKey(recordType))
                {
                    outputDb.addRecordStore(recordType, recordStruct, recordEncoding);
                    outputDb.commit();
                    System.out.println("Added metadata for data store " + recordType);
                }

                System.out.println("Exporting records...");

                IDataFilter recordFilter = new DataFilter(recordType) {
                    @Override
                    public double[] getTimeStampRange() { return timePeriod; }
                };

                int recordCount = 0;
                Iterator<? extends IDataRecord> it = inputDb.getRecordIterator(recordFilter);
                while (it.hasNext())
                {
                    IDataRecord rec = it.next();

                    outputDb.storeRecord(rec.getKey(), rec.getData());

                    recordCount++;
                    if (recordCount % 100 == 0) {
                        System.out.print(recordCount + "\r");
                    }
                    if (recordCount % 100000 == 0) {
                        outputDb.commit();
                    }
                }

                outputDb.commit();

                System.out.println("Exported " + recordCount + " records");
            }

            inputDb.stop();
        }

        outputDb.stop();
    }
}
