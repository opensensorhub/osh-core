/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys;

import java.util.concurrent.Callable;
import org.sensorhub.api.common.IdEncoder;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.database.IProcedureDatabase;
import org.sensorhub.api.datastore.command.ICommandStore;
import org.sensorhub.api.datastore.command.ICommandStreamStore;
import org.sensorhub.api.datastore.deployment.IDeploymentStore;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.procedure.IProcedureStore;
import org.sensorhub.api.datastore.property.IPropertyStore;
import org.sensorhub.api.datastore.system.ISystemDescStore;
import org.sensorhub.impl.service.consys.deployment.DeploymentStoreWrapper;
import org.sensorhub.impl.service.consys.feature.FeatureIdEncoder;
import org.sensorhub.impl.service.consys.feature.FoiStoreWrapper;
import org.sensorhub.impl.service.consys.obs.DataStreamIdEncoder;
import org.sensorhub.impl.service.consys.obs.DataStreamStoreWrapper;
import org.sensorhub.impl.service.consys.obs.ObsStoreWrapper;
import org.sensorhub.impl.service.consys.procedure.ProcedureStoreWrapper;
import org.sensorhub.impl.service.consys.property.PropertyStoreWrapper;
import org.sensorhub.impl.service.consys.system.SystemStoreWrapper;
import org.sensorhub.impl.service.consys.task.CommandStoreWrapper;
import org.sensorhub.impl.service.consys.task.CommandStreamStoreWrapper;
import org.vast.util.Asserts;


public class ObsSystemDbWrapper implements IObsSystemDatabase, IProcedureDatabase
{
    static final String NOT_WRITABLE_MSG = "Database is not writable";
    
    final IObsSystemDatabase readDb;
    final IObsSystemDatabase writeDb;
    final IPropertyStore propertyStore;
    final IProcedureStore procedureStore;
    final ISystemDescStore systemStore;
    final IDeploymentStore deploymentStore;
    final IFoiStore foiStore;
    final IDataStreamStore dataStreamStore;
    final IObsStore obsStore;
    final ICommandStreamStore commandStreamStore;
    final ICommandStore commandStore;
    final IdEncoders idEncoders;
    final CurieResolver curieResolver;
    
    
    public ObsSystemDbWrapper(IObsSystemDatabase readDb, IObsSystemDatabase writeDb, IdEncoders idEncoders, CurieResolver curieResolver)
    {
        this.readDb = Asserts.checkNotNull(readDb);
        this.writeDb = Asserts.checkNotNull(writeDb);
        
        this.systemStore = new SystemStoreWrapper(
            readDb.getSystemDescStore(),
            writeDb != null ? writeDb.getSystemDescStore() : null);
        
        this.deploymentStore = new DeploymentStoreWrapper(
            readDb.getDeploymentStore(),
            writeDb != null ? writeDb.getDeploymentStore() : null);

        this.foiStore = new FoiStoreWrapper(
            readDb.getFoiStore(),
            writeDb != null ? writeDb.getFoiStore() : null);
        
        this.dataStreamStore = new DataStreamStoreWrapper(
            readDb.getDataStreamStore(),
            writeDb != null ? writeDb.getDataStreamStore() : null);
        
        this.obsStore = new ObsStoreWrapper(
            readDb.getObservationStore(),
            writeDb != null ? writeDb.getObservationStore() : null);

        this.commandStreamStore = new CommandStreamStoreWrapper(
            readDb.getCommandStreamStore(),
            writeDb != null ? writeDb.getCommandStreamStore() : null);

        this.commandStore = new CommandStoreWrapper(
            readDb.getCommandStore(),
            writeDb != null ? writeDb.getCommandStore() : null);
        
        if (readDb instanceof IProcedureDatabase)
        {
            this.procedureStore = new ProcedureStoreWrapper(
                ((IProcedureDatabase)readDb).getProcedureStore(),
                writeDb != null && writeDb instanceof IProcedureDatabase ?
                    ((IProcedureDatabase)writeDb).getProcedureStore() : null);
            
            this.propertyStore = new PropertyStoreWrapper(
                ((IProcedureDatabase)readDb).getPropertyStore(),
                writeDb != null && writeDb instanceof IProcedureDatabase ?
                    ((IProcedureDatabase)writeDb).getPropertyStore() : null);
        }
        else
        {
            this.procedureStore = null;
            this.propertyStore = null;
        }
        
        this.idEncoders = Asserts.checkNotNull(idEncoders);
        this.curieResolver = curieResolver;
    }


    public IObsSystemDatabase getReadDb()
    {
        return readDb;
    }


    public IObsSystemDatabase getWriteDb()
    {
        return writeDb;
    }


    @Override
    public Integer getDatabaseNum()
    {
        Asserts.checkState(writeDb != null, NOT_WRITABLE_MSG);
        return writeDb.getDatabaseNum();
    }


    @Override
    public <T> T executeTransaction(Callable<T> transaction) throws Exception
    {
        Asserts.checkState(writeDb != null, NOT_WRITABLE_MSG);
        return writeDb.executeTransaction(transaction);
    }


    @Override
    public void commit()
    {
        Asserts.checkState(writeDb != null, NOT_WRITABLE_MSG);
        writeDb.commit();
    }


    @Override
    public boolean isOpen()
    {
        return writeDb != null ? writeDb.isOpen() : true;
    }


    @Override
    public boolean isReadOnly()
    {
        return writeDb == null || writeDb.isReadOnly();
    }


    @Override
    public IProcedureStore getProcedureStore()
    {
        return procedureStore;
    }


    @Override
    public IPropertyStore getPropertyStore()
    {
        return propertyStore;
    }


    @Override
    public ISystemDescStore getSystemDescStore()
    {
        return systemStore;
    }


    @Override
    public IDeploymentStore getDeploymentStore()
    {
        return deploymentStore;
    }


    @Override
    public IFoiStore getFoiStore()
    {
        return foiStore;
    }
    
    
    @Override
    public IDataStreamStore getDataStreamStore()
    {
        return dataStreamStore;
    }


    @Override
    public IObsStore getObservationStore()
    {
        return obsStore;
    }


    @Override
    public ICommandStreamStore getCommandStreamStore()
    {
        return commandStreamStore;
    }


    @Override
    public ICommandStore getCommandStore()
    {
        return commandStore;
    }
    
    
    public IdEncoders getIdEncoders()
    {
        return idEncoders;
    }
    
    
    public IdEncoder getProcedureIdEncoder()
    {
        return new FeatureIdEncoder(
            idEncoders.getProcedureIdEncoder(), 
            curieResolver,
            procedureStore);
    }
    
    
    public IdEncoder getSystemIdEncoder()
    {
        return new FeatureIdEncoder(
            idEncoders.getSystemIdEncoder(), 
            curieResolver,
            systemStore);
    }
    
    
    public IdEncoder getDeploymentIdEncoder()
    {
        return new FeatureIdEncoder(
            idEncoders.getDeploymentIdEncoder(), 
            curieResolver,
            deploymentStore);
    }
    
    
    public IdEncoder getFoiIdEncoder()
    {
        return new FeatureIdEncoder(
            idEncoders.getSystemIdEncoder(), 
            curieResolver,
            foiStore);
    }
    
    
    public IdEncoder getDataStreamIdEncoder()
    {
        return new DataStreamIdEncoder(
            idEncoders.getDataStreamIdEncoder(),
            curieResolver,
            dataStreamStore);
    }
    
    
    public IdEncoder getObsIdEncoder()
    {
        return idEncoders.getObsIdEncoder();
    }
    
    
    public IdEncoder getCommandStreamIdEncoder()
    {
        return idEncoders.getCommandStreamIdEncoder();
    }
    
    
    public IdEncoder getCommandIdEncoder()
    {
        return idEncoders.getCommandIdEncoder();
    }
    
    
    public IdEncoder getPropertyIdEncoder()
    {
        return idEncoders.getPropertyIdEncoder();
    }
    
    
    public IdEncoder getFeatureIdEncoder()
    {
        return idEncoders.getFeatureIdEncoder();
    }
    
    
    public CurieResolver getCurieResolver()
    {
        return curieResolver;
    }

}
